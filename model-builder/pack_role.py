#!/usr/bin/env python3
# pack_role.py —— 把角色包散文件打成 ZIP_STORED 单文件，并校验 manifest SHA256
# 用法：
#   python pack_role.py --dir ./out/role_pack --out ./out/角色名.zip
#
# 原则：ZIP_STORED（只打包不压缩），manifest.json 放根目录，条目名保持相对路径。
import argparse
import hashlib
import json
import os
import sys
import zipfile


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description="打包 ZIP_STORED 角色包")
    ap.add_argument("--dir", required=True, help="散文件角色包目录")
    ap.add_argument("--out", required=True, help="输出 zip 路径")
    args = ap.parse_args()

    if not os.path.isdir(args.dir):
        print(f"[error] 目录不存在: {args.dir}")
        return 1

    manifest_path = os.path.join(args.dir, "manifest.json")
    if not os.path.exists(manifest_path):
        print("[error] 缺少 manifest.json")
        return 1
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    # 校验 manifest.files 里列出的文件 SHA256
    bad = 0
    for item in manifest.get("files", []):
        rel = item.get("name", "")
        full = os.path.join(args.dir, rel.replace("/", os.sep))
        if not os.path.isfile(full):
            print(f"[error] manifest 列出的文件不存在: {rel}")
            bad += 1
            continue
        want = item.get("sha256", "")
        if want and sha256(full) != want:
            print(f"[error] SHA256 不匹配: {rel}")
            bad += 1
    if bad:
        print(f"[error] {bad} 个文件校验失败，不打包")
        return 1

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with zipfile.ZipFile(args.out, "w", compression=zipfile.ZIP_STORED) as z:
        for root, _, files in os.walk(args.dir):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, args.dir).replace("\\", "/")
                z.write(full, rel)

    n = len(manifest.get("files", []))
    print(f"[ok] 角色包: {args.out}（{n} 个文件，ZIP_STORED）")
    print(f"[ok] manifest.model_id = {manifest.get('model_id', '?')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
