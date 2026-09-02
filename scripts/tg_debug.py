#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""精简 debug: 定位 in RMS 矛盾 + Segfault"""
import ctypes as ct
import numpy as np
import os, time

LIB = "/data/local/tmp/route2/libtg.so"

def rms(x):
    return float(np.sqrt((x.astype(np.float64) ** 2).mean()))

x = np.frombuffer(open("/data/local/tmp/route2/noisy_pause.raw", "rb").read(), dtype="<f4")
n = len(x)
print("x len=%d rms=%.4f" % (n, rms(x)), flush=True)

lib = ct.CDLL(LIB)
lib.tg_create.restype = ct.c_void_p
lib.tg_create.argtypes = [ct.c_int, ct.c_int, ct.c_int, ct.c_float, ct.c_float,
                          ct.c_int, ct.c_int, ct.c_float, ct.c_int]
h = lib.tg_create(16000, 640, 160, ct.c_float(0.7), ct.c_float(1.5), 500, 50, ct.c_float(0.3), 3)
print("h=%s" % h, flush=True)
lib.tg_process.argtypes = [ct.c_void_p, ct.POINTER(ct.c_float), ct.c_int, ct.POINTER(ct.c_float)]
lib.tg_process.restype = None

BLOCK = 1600
acc = np.zeros(0, np.float32)
outs = []
t0 = time.time()
pos = 0
sizes = [800, 1200, 1600, 960, 640]
i = 0
push_n = 0
while pos < n:
    sz = sizes[i % len(sizes)]; i += 1
    chunk = x[pos:pos + sz]; pos += len(chunk)
    acc = np.concatenate([acc, chunk])
    n_full = (len(acc) // BLOCK) * BLOCK
    if n_full == 0:
        continue
    blk = acc[:n_full].reshape(-1, BLOCK)
    nb = blk.shape[0]
    out = np.empty((nb, BLOCK), np.float32)
    for j in range(nb):
        b = np.ascontiguousarray(blk[j])
        lib.tg_process(h, b.ctypes.data_as(ct.POINTER(ct.c_float)), BLOCK,
                       out[j].ctypes.data_as(ct.POINTER(ct.c_float)))
    outs.append(out.reshape(-1))
    acc = acc[n_full:]
    push_n += nb
print("处理块数=%d 耗时=%.0fms" % (push_n, (time.time()-t0)*1000), flush=True)
tail = acc
y = np.concatenate(outs) if outs else np.zeros(0, np.float32)
y = np.concatenate([y, tail])
print("y len=%d rms=%.4f" % (len(y), rms(y)), flush=True)
m = min(len(y), n)
print("m=%d  xt_rms=%.4f yt_rms=%.4f" % (m, rms(x[:m]), rms(y[:m])), flush=True)
print("x[:6]=", x[:6], flush=True)
print("y[:6]=", y[:6], flush=True)
lib.tg_destroy(h)
print("done", flush=True)
