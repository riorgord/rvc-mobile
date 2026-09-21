# -*- coding: utf-8 -*-
"""build_shared.py —— 把 App 的"共享件"打成 shared.zip(首次启动下载/SAF 导入用)。

共享件 = 所有角色共用的文件(hubert/f0/降噪 + mel/emb/sine/cent_table/proj),
由 App 内置改为首启下载后,APK 不再打包角色件也不打包共享件。

用法:
  python build_shared.py --assets <rvc_app/model-builder/shared_src> \
      --out <输出目录> [--zip out/shared-v69-42.zip]
  python build_shared.py --verify shared.zip          # 校验一个已有 shared.zip

manifest.json:
  {"type":"shared","arch":"sm8475","sdk_version":"2.47","version":1,
   "files":[{"name":"models/hubert_mix_def_t4800.bin","size":N,"sha256":"..."}]}
App 下载/导入后按 files 逐项落盘并校验 SHA256。
"""
import argparse
import hashlib
import json
import os
import sys
import tempfile
import zipfile

# 共享件相对 assets 的路径(运行时必用)。角色件(z_producer/dec_short/gf_*/索引)不在列。
SHARED_FILES = [
    "models/hubert_mix_def_t4800.bin",
    "models/rmvpe_fp32_64.bin.bin",
    "models/fcpe_256.bin",
    "models/df3r_T18_emb.onnx",
    "periphery/mel_basis.bin",
    "periphery/emb_params.npz",
    "periphery/sine_params.json",
    "periphery/cent_table.bin",
    "periphery/proj.bin",
]

ARCH = "sm8475"
SDK_VERSION = "2.47"
VERSION = 1


def sha256_file(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as fp:
        while True:
            b = fp.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def build(assets, out_dir, zip_path):
    os.makedirs(out_dir, exist_ok=True)
    missing = [rel for rel in SHARED_FILES if not os.path.isfile(os.path.join(assets, *rel.split("/")))]
    if missing:
        for rel in missing:
            print("[error] 缺失共享件: %s" % rel)
        sys.exit(2)

    files = []
    for rel in SHARED_FILES:
        src = os.path.join(assets, *rel.split("/"))
        dst = os.path.join(out_dir, *rel.split("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(src, "rb") as ins, open(dst, "wb") as outs:
            while True:
                b = ins.read(1 << 20)
                if not b:
                    break
                outs.write(b)
        files.append({"name": rel, "size": os.path.getsize(dst),
                      "sha256": sha256_file(dst)})
        print("  [%d/%d] %s (%d)" % (len(files), len(SHARED_FILES), rel, files[-1]["size"]))

    manifest = {"type": "shared", "arch": ARCH, "sdk_version": SDK_VERSION,
                "version": VERSION, "files": files}
    mpath = os.path.join(out_dir, "manifest.json")
    with open(mpath, "w", encoding="utf-8") as fp:
        json.dump(manifest, fp, ensure_ascii=False, indent=2)
    print("manifest.json: %d 个文件" % len(files))

    if zip_path:
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_STORED, allowZip64=True) as zf:
            for root, _, names in os.walk(out_dir):
                for name in names:
                    full = os.path.join(root, name)
                    rel = os.path.relpath(full, out_dir).replace("\\", "/")
                    zf.write(full, rel)
        print("[ok] shared.zip: %s (%d bytes, ZIP_STORED, %d 文件)"
              % (zip_path, os.path.getsize(zip_path), len(files) + 1))


def verify(zip_path):
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        if "manifest.json" not in names:
            print("[error] 缺 manifest.json")
            sys.exit(2)
        manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
        print("type=%s arch=%s sdk=%s version=%s files=%d"
              % (manifest.get("type"), manifest.get("arch"), manifest.get("sdk_version"),
                 manifest.get("version"), len(manifest.get("files", []))))
        ok = True
        for f in manifest.get("files", []):
            name, expect = f["name"], f["sha256"]
            if name not in names:
                print("[error] zip 缺文件: %s" % name)
                ok = False
                continue
            h = hashlib.sha256(zf.read(name)).hexdigest()
            if h != expect:
                print("[error] SHA256 不符: %s" % name)
                ok = False
            else:
                print("[ok] %s" % name)
        extra = [n for n in names if n != "manifest.json"
                 and n not in {f["name"] for f in manifest.get("files", [])}]
        if extra:
            print("[warn] zip 里多余文件: %s" % extra)
        print("[%s] shared.zip 校验" % ("ok" if ok else "FAILED"))
        sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--zip", default="")
    ap.add_argument("--verify", default="")
    a = ap.parse_args()

    if a.verify:
        verify(a.verify)
        return
    if not a.assets or not a.out:
        ap.error("需要 --assets 和 --out(或 --verify)")
    zip_path = a.zip or os.path.join(a.out, "shared.zip")
    build(a.assets, a.out, zip_path)


if __name__ == "__main__":
    main()
