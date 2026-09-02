#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""测试3: ctypes 精确复现 tg_min2 分块(完整1600读文件) + 测试4: 变长累积"""
import ctypes as ct
import numpy as np

lib = ct.CDLL("/data/local/tmp/route2/libtg.so")
lib.tg_create.restype = ct.c_void_p
lib.tg_create.argtypes = [ct.c_int, ct.c_int, ct.c_int, ct.c_float, ct.c_float,
                          ct.c_int, ct.c_int, ct.c_float, ct.c_int]
lib.tg_process.argtypes = [ct.c_void_p, ct.POINTER(ct.c_float), ct.c_int, ct.POINTER(ct.c_float)]
lib.tg_process.restype = None
lib.tg_destroy.argtypes = [ct.c_void_p]

x = np.frombuffer(open("/data/local/tmp/route2/noisy_pause.raw", "rb").read(), dtype="<f4")
n = len(x)

def run_blockwise(block):
    h = lib.tg_create(16000, 640, 160, ct.c_float(0.7), ct.c_float(1.5), 500, 50, ct.c_float(0.3), 3)
    nb = 0
    for pos in range(0, n, block):
        seg = np.ascontiguousarray(x[pos:pos+block])
        m = len(seg)
        m_pad = ((m + 159) // 160) * 160
        buf = np.zeros(m_pad, np.float32)
        buf[:m] = seg
        out = np.zeros(m_pad, np.float32)
        lib.tg_process(h, buf.ctypes.data_as(ct.POINTER(ct.c_float)), m_pad,
                       out.ctypes.data_as(ct.POINTER(ct.c_float)))
        nb += 1
    lib.tg_destroy(h)
    print("blockwise(block=%d): %d 块 OK" % (block, nb), flush=True)

print("=== 测试3: ctypes 完整1600分块(同 tg_min2) ===", flush=True)
run_blockwise(1600)
print("=== 测试3b: ctypes 完整5920分块 ===", flush=True)
run_blockwise(5920)

print("=== 测试4: 变长 chunk 累积 ===", flush=True)
h = lib.tg_create(16000, 640, 160, ct.c_float(0.7), ct.c_float(1.5), 500, 50, ct.c_float(0.3), 3)
acc = np.zeros(0, np.float32)
BLOCK = 1600
sizes = [800, 1200, 1600, 960, 640]
pos = 0; i = 0; blocks = 0
while pos < n:
    sz = sizes[i % len(sizes)]; i += 1
    chunk = x[pos:pos+sz]; pos += len(chunk)
    acc = np.concatenate([acc, chunk])
    nf = (len(acc) // BLOCK) * BLOCK
    if nf == 0:
        continue
    blk = np.ascontiguousarray(acc[:nf].reshape(-1, BLOCK))
    for j in range(blk.shape[0]):
        out = np.zeros(BLOCK, np.float32)
        lib.tg_process(h, blk[j].ctypes.data_as(ct.POINTER(ct.c_float)), BLOCK,
                       out.ctypes.data_as(ct.POINTER(ct.c_float)))
        blocks += 1
    acc = acc[nf:]
lib.tg_destroy(h)
print("变长累积: %d 块 OK" % blocks, flush=True)
print("ALL DONE", flush=True)
