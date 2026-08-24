# -*- coding: utf-8 -*-
"""gsv 服务封装:libgsv_qnn.so 常驻单 context,按名字绑定输入输出。

与 Termux qnn_stage_svc.py 同款模式,但模型切换时 gsv_cleanup + 重新
gsv_init(enc/first/stage/vits 各自 init 一次;stage 常驻跑 AR 循环)。
"""
import ctypes
import os
import time

import numpy as np

import perf

# QNN_DATATYPE 位编码:0x0216=fp16, 0x0232=fp32, 0x0032=int32,
# 0x0064=int64, 0x0108=uint8, 0x0508=bool8
_TYPE_MAP = {
    0x216: np.float16, 0x232: np.float32, 0x32: np.int32,
    0x64: np.int64, 0x108: np.uint8, 0x508: np.uint8,
}


class Gsv:
    """libgsv_qnn.so 的 python 绑定(单例使用:进程内一个 lib,多 context 切换)"""

    def __init__(self, native_lib_dir, files_dir):
        self.native = native_lib_dir
        self.files = files_dir
        self.htp = os.path.join(native_lib_dir, "libQnnHtp.so")
        self.sysso = os.path.join(native_lib_dir, "libQnnSystem.so")
        self._lib = None
        self.n_in = self.n_out = 0
        self.graph_name = None

    def load(self):
        # 性能轮:关闭 QnnProfile 采集(DETAILED per-API 每步 +2-5ms,全轮 +130s);
        # profiling 轮:注释下行 + qnn_tts.py 的 _PROFILE_ENABLED=True
        os.environ.setdefault("GSV_NOPROFILE", "1")
        lib = ctypes.CDLL(os.path.join(self.native, "libgsv_qnn.so"),
                          mode=ctypes.RTLD_GLOBAL)
        lib.gsv_init.argtypes = [ctypes.c_char_p] * 4
        lib.gsv_init.restype = ctypes.c_int
        lib.gsv_n_inputs.restype = ctypes.c_int
        lib.gsv_n_outputs.restype = ctypes.c_int
        lib.gsv_in_name.argtypes = [ctypes.c_int]
        lib.gsv_in_name.restype = ctypes.c_char_p
        lib.gsv_out_name.argtypes = [ctypes.c_int]
        lib.gsv_out_name.restype = ctypes.c_char_p
        lib.gsv_in_bytes.argtypes = [ctypes.c_int]
        lib.gsv_in_bytes.restype = ctypes.c_size_t
        lib.gsv_out_bytes.argtypes = [ctypes.c_int]
        lib.gsv_out_bytes.restype = ctypes.c_size_t
        lib.gsv_in_type.argtypes = [ctypes.c_int]
        lib.gsv_in_type.restype = ctypes.c_int
        lib.gsv_out_type.argtypes = [ctypes.c_int]
        lib.gsv_out_type.restype = ctypes.c_int
        lib.gsv_execute.argtypes = [ctypes.POINTER(ctypes.c_void_p),
                                    ctypes.POINTER(ctypes.c_void_p)]
        lib.gsv_execute.restype = ctypes.c_int
        lib.gsv_profile_dump.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
        lib.gsv_profile_dump.restype = ctypes.c_int
        lib.gsv_cleanup.restype = None
        self._lib = lib
        return lib

    def init(self, bin_name, graph_name):
        """切换模型:multi-slot 常驻(C 侧按 graph_name 缓存复用,无需 cleanup;
        首段 5 次全量 init,后续段全部缓存命中,省 ~2.6s×14)"""
        assert self._lib is not None, "先 load()"
        t0 = time.perf_counter()
        bin_path = os.path.join(self.files, bin_name)
        if not os.path.exists(bin_path):
            raise RuntimeError("缺 contract: %s" % bin_path)
        rc = self._lib.gsv_init(self.htp.encode(), self.sysso.encode(),
                                bin_path.encode(), graph_name.encode())
        if rc != 0:
            raise RuntimeError("gsv_init(%s) rc=%d" % (graph_name, rc))
        self.n_in = self._lib.gsv_n_inputs()
        self.n_out = self._lib.gsv_n_outputs()
        self.graph_name = graph_name
        perf.gsv_init_done(graph_name, time.perf_counter() - t0)
        return self.n_in, self.n_out

    def in_name(self, i):
        return (self._lib.gsv_in_name(i) or b"?").decode("utf-8", "replace")

    def out_name(self, i):
        return (self._lib.gsv_out_name(i) or b"?").decode("utf-8", "replace")

    def run(self, tensors):
        """按名字填输入 → execute → 按名字返回 {name: np_array}(零拷贝)
        计时分解:ffi(名字/类型查询) / alloc(输出分配) / execute(gsv_execute)。"""
        lib = self._lib
        n_in, n_out = self.n_in, self.n_out
        perf.gsv_begin_run(self.graph_name or "?")
        in_ptrs = (ctypes.c_void_p * n_in)()
        perf.gsv_tick_ffi()
        for i in range(n_in):
            name = self.in_name(i)
            arr = np.ascontiguousarray(tensors[name])
            in_ptrs[i] = ctypes.c_void_p(arr.ctypes.data)
        out_bufs = []
        out_ptrs = (ctypes.c_void_p * n_out)()
        perf.gsv_tick_alloc()
        for i in range(n_out):
            t = lib.gsv_out_type(i)
            dt = _TYPE_MAP.get(t, np.float32)
            arr = np.empty(lib.gsv_out_bytes(i) // np.dtype(dt).itemsize, dtype=dt)
            out_bufs.append(arr)
            out_ptrs[i] = ctypes.c_void_p(arr.ctypes.data)
        perf.gsv_tick_execute()
        rc = lib.gsv_execute(in_ptrs, out_ptrs)
        perf.gsv_run_done()
        if rc != 0:
            raise RuntimeError("gsv_execute(%s) rc=%d" % (self.graph_name, rc))
        return {self.out_name(i): out_bufs[i] for i in range(n_out)}

    def profile_dump(self, path, step, kv_len):
        """QnnProfile per-op 事件树追加 dump(JSONL 一行一采样)"""
        try:
            return self._lib.gsv_profile_dump(path.encode("utf-8"), step, kv_len)
        except Exception:
            return -1

    def cleanup(self):
        if self._lib is not None:
            self._lib.gsv_cleanup()
