# -*- coding: utf-8 -*-
"""GSV 全链路 TTS(APK):角色 zip → 4 contract + ref → gsv 服务 → 全 QNN TTS → wav

入口:
  run(native_lib_dir, files_dir, uid, text=None, speaker=None)  UI 合成(返回日志)
  synth(text, speaker, out_wav, max_steps, seed, interval)      合成核心(UI/HTTP 共用)
  list_speakers()                                               扫描角色 zip
  start_http(bind)/stop_http()                                  HTTP 服务开关

speaker: /sdcard/gsv-voices/ 下的 zip 文件名(如 "test.zip" 或 "test");
         空 → 第一个 zip。
"""
import ctypes
import glob
import os
import shutil
import sys
import threading

LOGS = []
_STATE = {}          # native_lib_dir/files_dir/gsv
_SYNTH_LOCK = threading.Lock()   # QNN 单 context:UI/HTTP 合成全局串行


def log(s):
    LOGS.append(str(s))
    print(s, flush=True)


def find_zip(speaker=None):
    if speaker:
        for p in ["/sdcard/gsv-voices/%s.zip" % speaker,
                  "/sdcard/gsv-voices/%s" % speaker]:
            hits = sorted(glob.glob(p))
            if hits:
                return hits[0]
    hits = sorted(glob.glob("/sdcard/gsv-voices/*.zip"))
    return hits[0] if hits else None


def list_speakers():
    """返回角色 zip 文件名列表,以换行分隔(Chaquopy 跨层传递简单可靠)"""
    return "\n".join(os.path.basename(p)
                     for p in sorted(glob.glob("/sdcard/gsv-voices/*.zip")))


def load_zip_entries(zpath):
    """按 zip 条目读入内存。返回 {basename: bytes}(仅小文件/兼容入口用)"""
    import zipfile as _zf
    entries = {}
    with _zf.ZipFile(zpath) as z:
        for m in z.infolist():
            if m.filename.endswith("/"):
                continue
            entries[os.path.basename(m.filename)] = z.open(m).read()
    return entries


def _ensure_contracts(zpath, files_dir):
    """按需取用:只打开 zip 中央目录,缺失条目才流式单条目落盘(不整包入内存)。
    与 anima_phone 一致:zip 只做分发容器,已落盘则完全不碰。"""
    import zipfile as _zf
    expect = {"t2s_enc_contract.bin", "t2s_first_contract.bin",
              "t2s_stage_contract.bin", "vits_clean.bin", "ref.npz"}
    with _zf.ZipFile(zpath) as z:
        names = {os.path.basename(m.filename) for m in z.infolist()
                 if not m.filename.endswith("/")}
        missing = expect - names
        if missing:
            raise RuntimeError("zip 缺文件: %s" % missing)
        for name in expect:
            p = os.path.join(files_dir, name)
            if os.path.exists(p):
                continue
            log("  落盘 %s (%.0f MB)…" % (name, z.getinfo(next(
                m for m in z.infolist() if os.path.basename(m.filename) == name
            )).file_size / 1e6))
            with z.open(name) as src, open(p, "wb") as dst:
                shutil.copyfileobj(src, dst, 1 << 20)   # 流式,不整读内存


def _preload(lib_dir):
    """bionic 启动时缓存搜索路径,setenv LD_LIBRARY_PATH 对已缓存路径无效
    → 必须按 soname 主动 dlopen(gsv_qnn_service.c 验证)"""
    order = ["libqti_dsp.so", "libvmmem.so", "libcdsprpc.so", "libhidlbase.so",
             "libhidltransport.so", "libhwbinder.so", "libhardware.so",
             "libutils.so", "liblog.so", "libcutils.so", "libdmabufheap.so",
             "libbase.so", "libc++.so", "libQnnHtpV69CalculatorStub.so",
             "libQnnHtpV69Stub.so", "libQnnHtpNetRunExtensions.so"]
    for name in order:
        p = os.path.join(lib_dir, name)
        if os.path.exists(p):
            try:
                ctypes.CDLL(p, mode=ctypes.RTLD_GLOBAL)
            except Exception:
                pass


def _ensure_init(native_lib_dir, files_dir, uid):
    """幂等初始化:环境 + gsv 服务 + GSV 注入。返回 Gsv 实例。"""
    if _STATE.get("gsv") is not None:
        return _STATE["gsv"]
    os.environ["ADSP_LIBRARY_PATH"] = native_lib_dir
    os.environ["LD_LIBRARY_PATH"] = ":".join(
        [native_lib_dir, "/system/lib64", "/vendor/lib64"])
    os.environ["GENIE_DATA_DIR"] = os.path.join(files_dir, "GenieData")
    _preload(native_lib_dir)

    from gsv_api import Gsv
    gsv = Gsv(native_lib_dir, files_dir)
    gsv.load()
    log("libgsv_qnn.so 加载 OK")

    import qnn_enc
    import qnn_first
    import qnn_stage_svc
    import qnn_vits_clean
    import qnn_bert
    for m in (qnn_enc, qnn_first, qnn_stage_svc, qnn_vits_clean):
        m.GSV = gsv
    qnn_bert.GSV = gsv
    qnn_bert.TOK_PATH = os.path.join(files_dir, "roberta_tokenizer", "tokenizer.json")

    _STATE["native_lib_dir"] = native_lib_dir
    _STATE["files_dir"] = files_dir
    _STATE["uid"] = uid
    _STATE["gsv"] = gsv

    # HTTP 模块注入(gsv_http 需要合成核心 + files_dir)
    import gsv_http
    gsv_http._synth = synth
    gsv_http._state["files_dir"] = files_dir
    return gsv


def _prepare_ref(speaker, files_dir):
    """找 zip → 校验(不整包读) → 落盘缺失 contract → 返回 ref dict(text_bert 真值)
    roberta_contract.bin 平级放 /sdcard/gsv-voices/(大模型不进角色 zip);
    prompt.txt 在 zip 内(角色参考文本,缺失 → ref text_bert 回退全零);
    ref text_bert 真值算一次落盘 ref_text_bert.npy,之后直接读(省 roberta init)"""
    zpath = find_zip(speaker)
    if not zpath:
        raise RuntimeError("未找到 /sdcard/gsv-voices/*.zip")
    log("角色包: %s (%.0f MB)" % (zpath, os.path.getsize(zpath) / 1e6))
    _ensure_contracts(zpath, files_dir)
    # roberta contract(600MB)平级文件:首次落盘
    rb = os.path.join(files_dir, "roberta_contract.bin")
    if not os.path.exists(rb):
        src = "/sdcard/gsv-voices/roberta_contract.bin"
        if os.path.exists(src):
            log("roberta_contract.bin 落盘(%.0f MB)…" % (os.path.getsize(src) / 1e6))
            with open(src, "rb") as fin, open(rb, "wb") as fout:
                shutil.copyfileobj(fin, fout, 1 << 20)
        else:
            log("!! 无 roberta_contract.bin → bert 将回退全零")
    import numpy as np
    ref = np.load(os.path.join(files_dir, "ref.npz"), allow_pickle=True)
    out = {k: ref[k] for k in ref.files}
    # ref prompt 真 bert:缓存 npy 优先;无缓存且角色 zip 带 prompt.txt 时现算
    rtb = os.path.join(files_dir, "ref_text_bert.npy")
    if os.path.exists(rtb):
        out["text_bert"] = np.load(rtb)
        log("ref text_bert 读缓存: %s mean %.3f"
            % (out["text_bert"].shape, out["text_bert"].mean()))
    elif os.path.exists(rb):
        import zipfile as _zf
        with _zf.ZipFile(zpath) as z:
            names = {os.path.basename(m.filename) for m in z.infolist()
                     if not m.filename.endswith("/")}
            prompt_text = (z.read("prompt.txt").decode("utf-8").strip()
                           if "prompt.txt" in names else None)
        if prompt_text:
            try:
                from qnn_bert import prompt_bert
                out["text_bert"] = prompt_bert(prompt_text, out["phonemes_seq"])
                np.save(rtb, out["text_bert"])
                log("ref text_bert 真值(已落盘): %s mean %.3f"
                    % (out["text_bert"].shape, out["text_bert"].mean()))
            except Exception as e:
                log("prompt_bert 失败(回退全零): %r" % e)
        else:
            log("无 prompt.txt → ref text_bert 全零")
    else:
        log("无 roberta → ref text_bert 全零")
    return out


def synth(text, speaker, out_wav, max_steps=500, seed=20260813, interval=0.6,
          progress=None):
    """合成核心(UI/HTTP 共用,全局串行锁)。返回 (audio fp32, semantics 列表)。
    progress: 可选回调 progress(seg_idx, nseg),由 HTTP 服务用于状态上报"""
    with _SYNTH_LOCK:
        files_dir = _STATE.get("files_dir")
        assert files_dir, "先 run() 初始化"
        ref = _prepare_ref(speaker, files_dir)
        import time
        import qnn_tts
        t0 = time.time()
        audio, semantics = qnn_tts.tts(text, ref, out_wav,
                                       max_steps=max_steps, seed=seed,
                                       interval=interval, progress=progress)
        dt = time.time() - t0
        log("=== TTS 完成: %.1fs, %.2fs 音频 → %s ===" % (dt, len(audio) / 32000, out_wav))
        return audio, semantics


def run(native_lib_dir, files_dir, uid, text=None, speaker=None,
        max_steps=500, seed=20260813, interval=0.3):
    """UI 入口:初始化 + 合成 + 对比日志。返回日志文本。"""
    del LOGS[:]
    log("=== GSV 全链路 TTS ===")
    log("uid=%s python=%s" % (uid, sys.version.split()[0]))
    try:
        import numpy
        log("numpy %s OK" % numpy.__version__)
    except Exception as e:
        log("numpy 缺失: %r" % e)

    try:
        _ensure_init(native_lib_dir, files_dir, uid)
        # 外部文本文件优先(adb push UTF-8 → /sdcard/gsv-voices/tts_text.txt)
        _tf = "/sdcard/gsv-voices/tts_text.txt"
        if (not text or text == "这个音频是QNN声码器测试") and os.path.exists(_tf):
            try:
                with open(_tf, encoding="utf-8") as f:
                    text = f.read().strip()
                log("使用外部文本 tts_text.txt: %s …" % text[:36])
            except Exception:
                pass
        text = (text or "这个音频是QNN声码器测试").strip()
        if not text:
            raise RuntimeError("文本为空")
        out_wav = os.path.join(files_dir, "tts_out.wav")
        audio, semantics = synth(text, speaker, out_wav,
                                 max_steps=max_steps, seed=seed, interval=interval)
        import numpy as np

        # 与 gt 对比(enc_test/gt_tts.npz 存在时)
        gt_path = os.path.join(files_dir, "enc_test", "gt_tts.npz")
        if os.path.exists(gt_path):
            gt = np.load(gt_path)
            gtok = gt["tokens"].tolist()
            head_ok = True
            for si, sem in enumerate(semantics):
                q = sem[0, 0].tolist()
                g = gtok[1:1 + len(q)]
                n0 = min(10, len(q), len(g))
                head_ok &= q[:n0] == g[:n0]
                log("  段%d semantic %d 帧: 前%d帧=%s 全长=%s"
                    % (si, len(q), n0, "一致" if q[:n0] == g[:n0] else "不一致",
                       "一致" if q == g else "后段发散"))
            rms_ok = abs(np.sqrt((audio ** 2).mean()) - 0.08) < 0.05
            log("  audio: %.2fs rms=%.4f(健康范围 ~0.03-0.13: %s)"
                % (len(audio) / 32000, np.sqrt((audio ** 2).mean()),
                   "OK" if rms_ok else "异常"))
            log("========== 全链路 TTS APK 免 root: %s =========="
                % ("PASS" if head_ok and rms_ok else "FAIL"))
        else:
            log("无 gt_tts.npz,跳过 token 对比(仍可听 wav)")
    except Exception:
        import traceback
        log("!! 异常:")
        log(traceback.format_exc())
    return "\n".join(LOGS)


def init_only(native_lib_dir, files_dir, uid):
    """只初始化(QNN 服务 + GSV 注入),不合成。HTTP 启动前调用(替代旧启动自检)。"""
    _ensure_init(native_lib_dir, files_dir, uid)
    log("init_only OK(HTTP 核心已就绪)")
    return "ok"


def start_http(bind="0.0.0.0"):
    """HTTP 服务开关(由 MainActivity 调用)。返回端口或 0。"""
    try:
        import gsv_http
        return gsv_http.start(bind)
    except Exception as e:
        log("HTTP 启动失败: %r" % e)
        return 0


def stop_http():
    try:
        import gsv_http
        gsv_http.stop()
    except Exception:
        pass
