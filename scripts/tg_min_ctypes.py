#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""最小 ctypes 测试: create 一次 + 单块/多块 process, 二分定位崩溃点"""
import ctypes as ct
import numpy as np
import os, sys

lib = ct.CDLL("/data/local/tmp/route2/libtg.so")
lib.tg_create.restype = ct.c_void_p
lib.tg_create.argtypes = [ct.c_int, ct.c_int, ct.c_int, ct.c_float, ct.c_float,
                          ct.c_int, ct.c_int, ct.c_float, ct.c_int]
lib.tg_process.argtypes = [ct.c_void_p, ct.POINTER(ct.c_float), ct.c_int, ct.POINTER(ct.c_float)]
lib.tg_process.restype = None
lib.tg_destroy.argtypes = [ct.c_void_p]

def one_block(n=1600, times=1, contig=True):
    h = lib.tg_create(16000, 640, 160, ct.c_float(0.7), ct.c_float(1.5), 500, 50, ct.c_float(0.3), 3)
    print("create h=%s" % h, flush=True)
    inp = np.random.randn(n).astype(np.float32)
    if not contig:
        inp = np.concatenate([inp[:n//2], inp[n//2:]])  # 仍连续
    out = np.zeros(n, np.float32)
    for i in range(times):
        inp2 = np.ascontiguousarray(inp)
        lib.tg_process(h, inp2.ctypes.data_as(ct.POINTER(ct.c_float)), n,
                       out.ctypes.data_as(ct.POINTER(ct.c_float)))
        print("  process#%d out[:4]=%s out_rms=%.3f" % (i, out[:4], float(np.sqrt((out**2).mean()))), flush=True)
    lib.tg_destroy(h)
    print("  destroy ok", flush=True)

print("=== 测试1: 单块一次 ===", flush=True)
one_block(1600, 1)
print("=== 测试2: 单块两次(状态复用) ===", flush=True)
one_block(1600, 2)
print("ALL DONE", flush=True)
