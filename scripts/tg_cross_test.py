#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 OLA 式块间 crossfade: 长度守恒 + 块边界跳变消除。与 rvc_stream.py 同代码"""
import ctypes as ct
import numpy as np
import os, time

class TorchGateDenoiser:
    def __init__(self, native_lib_dir, block=1600, sr=16000, n_fft=640, hop=160,
                 prop_decrease=0.7, n_std=1.5, freq_smooth_hz=500,
                 time_smooth_ms=50, noise_percentile=0.3, ref_sec=3, cross=320):
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
        self.CROSS = cross
        self.HOP = block - cross
        self._prev_tail = None
        self.acc = np.zeros(0, np.float32)

    def process(self, x16k):
        x16k = np.asarray(x16k, np.float32)
        if len(x16k) == 0:
            return np.zeros(0, np.float32)
        self.acc = np.concatenate([self.acc, x16k])
        HOP, BLOCK, CROSS = self.HOP, self.BLOCK, self.CROSS
        ramp = np.linspace(0.0, 1.0, CROSS, dtype=np.float32)
        result = []
        while len(self.acc) >= BLOCK:
            blk = np.ascontiguousarray(self.acc[:BLOCK])
            o = np.zeros(BLOCK, np.float32)
            self.lib.tg_process(self.h, blk.ctypes.data_as(self._ct.POINTER(self._ct.c_float)),
                                BLOCK, o.ctypes.data_as(self._ct.POINTER(self._ct.c_float)))
            if self._prev_tail is not None:
                o[:CROSS] = self._prev_tail * (1.0 - ramp) + o[:CROSS] * ramp
            self._prev_tail = o[BLOCK - CROSS:].copy()
            result.append(o[:HOP])
            self.acc = self.acc[HOP:]
        return np.concatenate(result) if result else np.zeros(0, np.float32)

    def flush(self):
        CROSS = self.CROSS
        r = self.acc[CROSS:] if len(self.acc) > CROSS else np.zeros(0, np.float32)
        if self._prev_tail is not None:
            r = np.concatenate([r, self._prev_tail])
            self._prev_tail = None
        self.acc = np.zeros(0, np.float32)
        return r

    def close(self):
        if self.h is not None:
            self.lib.tg_destroy(self.h)
            self.h = None


def rms(x):
    return float(np.sqrt((x.astype(np.float64) ** 2).mean()))


x = np.frombuffer(open("/data/local/tmp/route2/noisy_pause.raw", "rb").read(), dtype="<f4")
n = len(x)
den = TorchGateDenoiser("/data/local/tmp/route2")
outs = []
sizes = [800, 1200, 1600, 960, 640]
pos = 0; i = 0
t0 = time.time()
while pos < n:
    sz = sizes[i % len(sizes)]; i += 1
    seg = x[pos:pos+sz]
    o = den.process(seg)
    pos += len(seg)
    if len(o): outs.append(o)
tail = den.flush()
if len(tail): outs.append(tail)
proc = time.time() - t0
y = np.concatenate(outs) if outs else np.zeros(0, np.float32)
den.close()
open("/data/local/tmp/route2/out_cross.raw", "wb").write(y.astype("<f4").tobytes())
print("输出 %d 样本 (输入 %d) 差值 %d 耗时 %.0fms CPU=%.1f%%" % (len(y), n, len(y)-n, proc*1000, proc/(n/16000)*100), flush=True)
m = min(len(y), n)
print("corr(对齐前)=%.4f" % np.corrcoef(y[:m], x[:m])[0,1], flush=True)
# 去掉开头 HOP 延迟再对齐
if len(y) > 1280:
    yt = y[1280:1280+n]
    m2 = min(len(yt), n)
    print("corr(去1280延迟)=%.4f" % np.corrcoef(yt[:m2], x[:m2])[0,1], flush=True)
for name, s, e in [("停顿", 0, 8000), ("语音", 8000, 35840)]:
    if e <= len(y):
        a, b2 = x[s:e], y[s:e]
        print("  %s: in=%.4f out=%.4f 压制=%.1f dB corr=%.3f" % (name, rms(a), rms(b2),
              20*np.log10(rms(a)/(rms(b2)+1e-9)), np.corrcoef(a,b2)[0,1]), flush=True)
