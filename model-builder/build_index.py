#!/usr/bin/env python3
# build_index.py —— .index(faiss, 768 维) → App 角色包索引文件
#
# 原理（已用 jielaide.index + App 现网文件实锤）：
#   - RVC 官方 .index 是 768 维 faiss IVF，直接 reconstruct_n 就是 App 要的 naiqiawang_idx.bin
#   - idx256.bin = naiqiawang_idx @ proj.bin（共享投影，已验证逐字节 0 误差）
#   - ivf_cent768/ivf_members/ivf_offsets/ivf_cent2 从同一 IVF 结构提取
#
# 用法：
#   python build_index.py --index model.index --proj proj.bin --out ./out/role_pack
#
# 输出（写到 --out/periphery/）：
#   naiqiawang_idx.bin [N,768] f32
#   idx256.bin          [N,256] f32
#   ivf_cent768.bin     [K,768] f32
#   ivf_members.bin     [N]     i32（全局行号，按簇排列）
#   ivf_offsets.bin     [K+1]   i32
#   ivf_cent2.bin       [K]     f32（簇中心平方和）
import argparse
import os
import sys

import numpy as np


def write_bin(path, arr):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    arr.tofile(path)
    print("      ->", path, arr.shape, arr.dtype)


def main():
    ap = argparse.ArgumentParser(description="RVC .index -> App 索引")
    ap.add_argument("--index", required=True, help="RVC 官方 .index（768 维 faiss IVF）")
    ap.add_argument("--out", required=True, help="输出目录（角色包根目录，索引写入 out/periphery/）")
    ap.add_argument("--proj", required=True, help="共享 proj.bin [768,256] f32")
    args = ap.parse_args()

    import faiss
    idx = faiss.read_index(args.index)
    print("[1/4] index:", args.index)
    print("      type=%s d=%d ntotal=%d" % (type(idx).__name__, idx.d, idx.ntotal))
    if idx.d != 768:
        print("[warn] 非 768 维索引: d=%d（App 当前按 768 维加载，可能不兼容）" % idx.d)

    # ---------- 1. naiqiawang_idx.bin（完整 768 向量） ----------
    print("[2/4] reconstruct 768 向量")
    full = idx.reconstruct_n(0, idx.ntotal).astype(np.float32)
    if not full.flags.c_contiguous:
        full = np.ascontiguousarray(full)
    write_bin(os.path.join(args.out, "periphery", "naiqiawang_idx.bin"), full)

    # ---------- 2. idx256.bin（768 → 256 投影） ----------
    print("[3/4] 计算 idx256 = full @ proj")
    proj = np.fromfile(args.proj, dtype=np.float32).reshape(768, 256)
    idx256 = np.ascontiguousarray(full @ proj)
    write_bin(os.path.join(args.out, "periphery", "idx256.bin"), idx256)

    # ---------- 3. IVF 结构 ----------
    print("[4/4] 提取 IVF 结构")
    ivf = faiss.extract_index_ivf(idx)
    nlist = ivf.nlist
    inv = ivf.invlists

    # 簇中心
    cent = ivf.quantizer.reconstruct_n(0, nlist).astype(np.float32)
    write_bin(os.path.join(args.out, "periphery", "ivf_cent768.bin"), cent)
    cent2 = np.sum(cent * cent, axis=1).astype(np.float32)
    write_bin(os.path.join(args.out, "periphery", "ivf_cent2.bin"), cent2)

    # 倒排成员 + 偏移
    offsets = np.zeros(nlist + 1, dtype=np.int32)
    members = np.empty(idx.ntotal, dtype=np.int32)
    pos = 0
    for i in range(nlist):
        sz = inv.list_size(i)
        if sz > 0:
            ids = faiss.rev_swig_ptr(inv.get_ids(i), sz).astype(np.int32)
            members[pos:pos + sz] = ids
        pos += sz
        offsets[i + 1] = pos
    if pos != idx.ntotal:
        print("[warn] 倒排成员数 %d != ntotal %d，检查 index" % (pos, idx.ntotal))
    write_bin(os.path.join(args.out, "periphery", "ivf_members.bin"), members)
    write_bin(os.path.join(args.out, "periphery", "ivf_offsets.bin"), offsets)

    print("[done] 索引文件已生成 ->", os.path.join(args.out, "periphery"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
