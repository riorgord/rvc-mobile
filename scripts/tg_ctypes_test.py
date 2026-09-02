#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手机端验证 App 版 TorchGateDenoiser 的 ctypes 逻辑(与 rvc_stream.py 同代码)。
用小块分次 push(模拟真实输入流) + flush 尾部, 验证: 不崩 / 降噪效果 / 拼接无缝。"""
import ctypes as ct
import numpy as np
import os, sys, time

LIB = "/data/local/tmp/route2/libtg.so"

class TorchGateDenoiser:
    def __init__(self, native_lib_dir, block=1600, sr=16000, n_fft=640, hop=160,
                 prop_decrease=0.7, n_std=1.5, freq_smooth_hz=500,
                 time_smooth_ms=50, noise_percentile=0.3, ref_sec=3):
        self._ct = ct
        self.lib = ct.CDLL(os.path.join(native_lib_dir, "libtg.so"))
        self.lib.tg_create.restype = ct.c_void_p
        self.lib.tg_create.argtypes = [ct.c_int, ct.c_int, ct.c_int,
                                       ct.c_float, ct.c_float,
                                       ct.c_int, ct.c_int,
                                       ct.c_float, ct.c_int]
        self.h = self.lib.tg_create(sr, n_fft, hop,
                                    ct.c_float(prop_decrease), ct.c_float(n_std),
                                    freq_smooth_hz, time_smooth_ms,
                                    ct.c_float(noise_percentile), ref_sec)
        self.lib.tg_process.argtypes = [ct.c_void_p,
                                        ct.POINTER(ct.c_float), ct.c_int,
                                        ct.POINTER(ct.c_float)]
        self.lib.tg_process.restype = None
        self.lib.tg_destroy.argtypes = [ct.c_void_p]
        self.BLOCK = block
        self.acc = np.zeros(0, np.float32)

    def process(self, x16k):
        x16k = np.asarray(x16k, np.float32)
        if len(x16k) == 0:
            return x16k
        self.acc = np.concatenate([self.acc, x16k])
        n_full = (len(self.acc) // self.BLOCK) * self.BLOCK
        if n_full == 0:
            return np.zeros(0, np.float32)
        blk = self.acc[:n_full].reshape(-1, self.BLOCK)
        outs = np.empty((blk.shape[0], self.BLOCK), np.float32)
        for i, b in enumerate(blk):
            self.lib.tg_process(
                self.h,
                b.ctypes.data_as(self._ct.POINTER(self._ct.c_float)),
                self.BLOCK,
                outs[i].ctypes.data_as(self._ct.POINTER(self._ct.c_float)))
        self.acc = self.acc[n_full:]
        return outs.reshape(-1)

    def flush(self):
        r = self.acc
        self.acc = np.zeros(0, np.float32)
        return r

    def close(self):
        if self.h is not None:
            self.lib.tg_destroy(self.h)
            self.h = None


def rms(x):
    return float(np.sqrt((x.astype(np.float64) ** 2).mean()))


def main():
    path = "/data/local/tmp/route2/noisy_pause.raw"
    x = np.frombuffer(open(path, "rb").read(), dtype="<f4")
    n = len(x)
    print("输入: %d 样本 (%.2fs) RMS=%.4f" % (n, n / 16000, rms(x)), flush=True)

    den = TorchGateDenoiser("/data/local/tmp/route2", block=1600)
    # 分小块 push: 模拟 App 输入(变长块: 800/1200/1600 交替 + 边界)
    outs = []
    t0 = time.time()
    pos = 0
    sizes = [800, 1200, 1600, 960, 640]
    i = 0
    while pos < n:
        sz = sizes[i % len(sizes)]
        i += 1
        chunk = x[pos:pos + sz]
        pos += len(chunk)
        o = den.process(chunk)
        if len(o):
            outs.append(o)
    tail = den.flush()
    if len(tail):
        outs.append(tail)
    proc = time.time() - t0
    y = np.concatenate(outs) if outs else np.zeros(0, np.float32)
    den.close()

    # 对齐到输入长度(降噪块累积: y 可能有 ±BLOCK 偏差, 截取)
    m = min(len(y), n)
    yt, xt = y[:m], x[:m]
    print("输出: %d 样本 处理耗时 %.0fms (real %.0fms = CPU %.1f%%)" % (
        len(y), proc * 1000, n / 16, proc / (n / 16000) * 100), flush=True)
    print("整体: in RMS=%.4f out RMS=%.4f corr=%.4f" % (rms(xt), rms(yt), float(np.corrcoef(xt, yt)[0, 1])), flush=True)

    # 停顿段(前 8000 样本 = 0.5s) 与 语音段(8000-35840) 指标
    for name, s, e in [("停顿(前0.5s)", 0, 8000), ("语音(0.5-2.24s)", 8000, 35840), ("后停顿", 35840, 43840)]:
        a, b2 = x[s:e], y[s:e]
        if len(a) == 0 or len(b2) == 0:
            continue
        d = 20 * np.log10(rms(a) / (rms(b2) + 1e-9))
        print("  %s: in=%.4f out=%.4f 压制=%.1f dB corr=%.3f" % (name, rms(a), rms(b2), d, float(np.corrcoef(a, b2)[0, 1])), flush=True)


if __name__ == "__main__":
    main()
