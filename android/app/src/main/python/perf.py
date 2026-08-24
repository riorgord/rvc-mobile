# -*- coding: utf-8 -*-
"""轻量性能探针:每次合成自动吐出各阶段耗时(加载/等待/计算分解)。

用法:
  import perf
  perf.begin()                      # 合成开始(重置)
  with perf.T("bert"):              # 阶段计时(累计到当前段)
      ...
  perf.gsv_init_done(graph, dt)     # context 加载耗时
  perf.gsv_begin_run(graph)         # 单次 run 开始(内部拆三段)
  perf.gsv_run_done()               # 单次 run 结束 → 累计 by_graph
  perf.ar_step()                    # AR 每步: 读上一次 run 三段, 累计到段
  perf.report(files_dir)            # 打印 + 写 perf_report.json

开销: time.perf_counter_ns ~50ns/次,对 62ms 级步长零影响。
"""
import json
import os
import time

REPORT = {
    "g2p_first_load": None,        # 秒: gsv_g2p 模块首载(jieba/词典)
    "split": None,                 # 秒: 切分阶段
    "g2p_calls": 0,                # 切分阶段 text_to_phones 调用次数
    "gsv": {"init": [],            # [(graph, 秒)] 每次 context 重建
            "by_graph": {}},       # {graph: {runs, ffi_s, alloc_s, execute_s, total_s}}
    "segments": [],                # 每段: {bert, enc, first, vits, ar:{...}}
    "total": 0.0,                  # 合成总秒
    "audio_len": 0.0,              # 音频秒
}

_SEG = None          # 当前段统计 dict
_CUR = {}            # 当前 run 计时
_LAST = {}           # 上一次 run 的三段(AR 步级读取)
_START = None        # begin() 时刻
_G2P_COUNT = 0       # 切分阶段 G2P 调用计数(由 qnn_tts 维护差值)


def _ns():
    return time.perf_counter_ns()


def begin():
    """合成开始:清空重来(g2p_first_load 保留——模块 import 时记录的会话级数据)"""
    global _SEG, _CUR, _LAST, _START
    REPORT["split"] = None
    REPORT["g2p_calls"] = 0
    REPORT["gsv"]["init"] = []
    REPORT["gsv"]["by_graph"] = {}
    REPORT["segments"] = []
    _SEG = None
    _CUR = {}
    _LAST = {}
    _START = _ns()


class T:
    """阶段计时: with perf.T("bert"): ... → 累计到当前段对应键"""

    def __init__(self, name):
        self.name = name

    def __enter__(self):
        self.t0 = _ns()
        return self

    def __exit__(self, *a):
        dt = (_ns() - self.t0) / 1e9
        if _SEG is not None:
            _SEG[self.name] = _SEG.get(self.name, 0.0) + dt
        return False


def begin_segment():
    """开始一段:返回该段统计 dict(ar 子结构预置)"""
    global _SEG
    _SEG = {"ar": {"steps": 0, "total_s": 0.0,
                   "per_step": {"execute": [], "alloc": [], "ffi": [], "total": []},
                   "seq": {"kv_len": [], "execute_ms": [], "alloc_ms": [], "ffi_ms": []}}}
    REPORT["segments"].append(_SEG)
    return _SEG


def end_segment():
    """段结束:ar 子结构收拢为均值/极值"""
    global _SEG
    if _SEG is None:
        return
    ar = _SEG.get("ar")
    if ar and ar["steps"]:
        def agg(key):
            v = ar["per_step"][key]
            return {"avg_ms": 1000.0 * sum(v) / len(v),
                    "min_ms": 1000.0 * min(v), "max_ms": 1000.0 * max(v),
                    "n": len(v)} if v else None
        ar["per_step"] = {k: agg(k) for k in ("execute", "alloc", "ffi", "total")}
    _SEG = None


def gsv_init_done(graph, dt):
    """一次 context 重建完成: graph 名 + 秒"""
    REPORT["gsv"]["init"].append((graph, round(dt, 3)))


def gsv_begin_run(graph):
    """单次 run 开始:记录起点与 graph(三段计时在 run 内手动调用 tick)"""
    _CUR["graph"] = graph
    _CUR["t0"] = _ns()
    _CUR["ffi"] = _CUR.get("ffi", 0)
    _CUR["alloc"] = _CUR.get("alloc", 0)
    _CUR["execute"] = _CUR.get("execute", 0)


def gsv_tick_ffi():
    _CUR["_t"] = _ns()


def gsv_tick_alloc():
    if "_t" in _CUR:
        _CUR["ffi"] += _ns() - _CUR["_t"]
    _CUR["_t"] = _ns()


def gsv_tick_execute():
    if "_t" in _CUR:
        _CUR["alloc"] += _ns() - _CUR["_t"]
    _CUR["_t"] = _ns()


def gsv_run_done():
    """单次 run 结束:收三段 → by_graph 累计 + _LAST(供 AR 步读取)"""
    global _CUR, _LAST
    if "_t" in _CUR:
        _CUR["execute"] += _ns() - _CUR["_t"]
    total = _ns() - _CUR.get("t0", _ns())
    g = _CUR.get("graph", "?")
    bg = REPORT["gsv"]["by_graph"].setdefault(
        g, {"runs": 0, "ffi_s": 0.0, "alloc_s": 0.0, "execute_s": 0.0, "total_s": 0.0})
    bg["runs"] += 1
    bg["ffi_s"] += _CUR["ffi"] / 1e9
    bg["alloc_s"] += _CUR["alloc"] / 1e9
    bg["execute_s"] += _CUR["execute"] / 1e9
    bg["total_s"] += total / 1e9
    _LAST = {"ffi": _CUR["ffi"] / 1e9, "alloc": _CUR["alloc"] / 1e9,
             "execute": _CUR["execute"] / 1e9, "total": total / 1e9}
    _CUR.clear()


def ar_step(kv_len=None):
    """AR 一步结束:读上一次 run 三段, 累计到当前段 ar.per_step
    kv_len: 当前步 KV 长度(传则记入序列,用于步耗时 vs kv_len 曲线)"""
    if _SEG is None or not _LAST:
        return
    ar = _SEG["ar"]
    ar["steps"] += 1
    ar["total_s"] += _LAST.get("total", 0.0)
    for k in ("execute", "alloc", "ffi", "total"):
        ar["per_step"][k].append(_LAST.get(k, 0.0))
    if kv_len is not None:
        ar["seq"]["kv_len"].append(kv_len)
        ar["seq"]["execute_ms"].append(round(_LAST.get("execute", 0.0) * 1000, 2))
        ar["seq"]["alloc_ms"].append(round(_LAST.get("alloc", 0.0) * 1000, 2))
        ar["seq"]["ffi_ms"].append(round(_LAST.get("ffi", 0.0) * 1000, 2))


def set_split(dt, g2p_calls):
    REPORT["split"] = round(dt, 3)
    REPORT["g2p_calls"] = g2p_calls


def report(files_dir=None):
    """打印 JSON + 写 perf_report.json。返回 dict。"""
    if _START is not None:
        REPORT["total"] = round((_ns() - _START) / 1e9, 3)
    try:
        s = json.dumps(REPORT, ensure_ascii=False, indent=1)
    except Exception:
        s = str(REPORT)
    print("===PERF===")
    print(s)
    print("===PERF_END===")
    if files_dir:
        try:
            with open(os.path.join(files_dir, "perf_report.json"), "w",
                      encoding="utf-8") as f:
                f.write(s)
        except Exception:
            pass
    return REPORT
