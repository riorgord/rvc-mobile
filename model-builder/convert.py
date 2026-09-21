#!/usr/bin/env python3
# convert.py —— RVC 模型转换唯一入口（v0.2：完整角色包链路）
#
# 流程：
#   1) .pth -> z_producer.onnx + dec_short_T61.onnx + g.bin/rnd.bin   （export_onnx.py）
#   2) ONNX -> DLC -> QNN context binary                              （snpe-onnx-to-dlc / qnn-context-binary-generator）
#   3) .index -> App 索引文件                                         （build_index.py）
#   4) 组装 role_pack/ 散文件（models/testdata/periphery/manifest.json）
#   5) 打包 ZIP_STORED 角色包 zip                                     （pack_role.py）
#
# 跨环境：
#   - export 步骤：在装有 torch + RVC 包的环境跑（Windows Python 或 WSL 均可）
#   - QNN 编译步骤：需要 QNN SDK，优先在 WSL 里跑；Windows 下自动用 `wsl bash -lc`
#
# 用法：
#   python convert.py --config config.yaml
#   python convert.py --check
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))


def log(s):
    print("[convert]", s, flush=True)


def run(cmd, shell=False):
    log("$ " + (cmd if isinstance(cmd, str) else " ".join(cmd)))
    r = subprocess.run(cmd, shell=shell)
    if r.returncode != 0:
        raise RuntimeError("命令失败，退出码 %d: %s" % (r.returncode, cmd))
    return r


def in_wsl():
    try:
        with open("/proc/version") as f:
            return "microsoft" in f.read().lower()
    except Exception:
        return False


def load_config(path):
    try:
        import yaml
    except ImportError:
        raise SystemExit("缺少 pyyaml：pip install pyyaml")
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def to_wsl(p):
    """Windows 路径 -> WSL /mnt/<drive>/... 路径；已是 Linux 路径则原样返回。"""
    if os.name != "nt":
        return p
    p = str(p)
    if p.startswith("/"):
        return p
    m = re.match(r"^([A-Za-z]):[\\/](.*)$", p)
    if m:
        return "/mnt/%s/%s" % (m.group(1).lower(), m.group(2).replace("\\", "/"))
    return p.replace("\\", "/")


def run_in_wsl_or_bash(cmd, distro=None):
    """Windows 上有 wsl.exe 就进指定/默认 WSL；否则假设当前就是 Linux 直接跑。"""
    if os.name == "nt":
        if shutil.which("wsl.exe"):
            base = ["wsl.exe"] + (["-d", distro] if distro else []) + ["bash", "-lc", cmd]
            run(base)
            return
        raise SystemExit("Windows 下找不到 wsl.exe，请按 README 第 5 章装 WSL2")
    run(["bash", "-lc", cmd])


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def find_ctx_binary(ctx_dir, name, arch):
    """qnn-context-binary-generator 实际产物是 <name>.<arch>.bin.bin，兜底匹配 .bin。"""
    candidates = [
        os.path.join(ctx_dir, "%s.%s.bin.bin" % (name, arch)),
        os.path.join(ctx_dir, "%s.%s.bin" % (name, arch)),
        os.path.join(ctx_dir, "%s.bin.bin" % name),
        os.path.join(ctx_dir, "%s.bin" % name),
    ]
    for c in candidates:
        if os.path.isfile(c):
            return c
    # 再按前缀找
    hits = [f for f in os.listdir(ctx_dir) if f.startswith(name + ".") or f.startswith(name + "_")]
    if hits:
        return os.path.join(ctx_dir, hits[0])
    raise FileNotFoundError("找不到 %s 的 context binary（目录: %s）" % (name, ctx_dir))


def step_export(cfg):
    pth = cfg.get("pth_path", "")
    if not pth:
        log("pth_path 为空，跳过 export（使用已有 ONNX）")
        return
    onnx_dir = os.path.join(cfg["output_dir"], "onnx")
    os.makedirs(onnx_dir, exist_ok=True)
    cmd = [
        sys.executable, os.path.join(_HERE, "export_onnx.py"),
        "--pth", pth,
        "--ref-wav", cfg.get("ref_wav", ""),
        "--rvc-pkg", cfg.get("rvc_pkg", ""),
        "--out", onnx_dir,
    ]
    if cfg.get("verify_onnx"):
        cmd.append("--verify")
    if cfg.get("device"):
        cmd += ["--device", cfg["device"]]
    run(cmd)


def step_qnn(cfg):
    sdk = cfg.get("qnn_sdk_root") or os.environ.get("QNN_SDK_ROOT", "")
    if not sdk:
        log("未设置 qnn_sdk_root / QNN_SDK_ROOT，跳过 QNN 编译")
        return
    distro = cfg.get("wsl_distro", "")
    onnx_dir = os.path.join(cfg["output_dir"], "onnx")
    dlc_dir = os.path.join(cfg["output_dir"], "dlc")
    ctx_dir = os.path.join(cfg["output_dir"], "ctx")
    os.makedirs(dlc_dir, exist_ok=True)
    os.makedirs(ctx_dir, exist_ok=True)
    arch = cfg.get("arch", "sm8475")
    htp_cfg = cfg.get("htp_cfg_dir", os.path.join(_HERE, "htp_cfg"))
    backend = os.path.join(sdk, "lib", "x86_64-linux-clang", "libQnnHtp.so")

    onnx_dir_w = to_wsl(onnx_dir)
    dlc_dir_w = to_wsl(dlc_dir)
    ctx_dir_w = to_wsl(ctx_dir)
    htp_cfg_w = to_wsl(htp_cfg)

    for comp in cfg.get("components", []):
        name = comp["name"]
        onnx = comp.get("onnx", os.path.join(onnx_dir, name + ".onnx"))
        dlc = os.path.join(dlc_dir, name + ".dlc")
        binary = os.path.join(ctx_dir, name + "." + arch + ".bin")
        onnx_w = to_wsl(onnx)
        dlc_w = to_wsl(dlc)
        binary_w = to_wsl(binary)
        inputs = " ".join("-d %s %s" % (k, " ".join(map(str, v)))
                          for k, v in comp.get("inputs", {}).items())
        cmd1 = ("cd %s && snpe-onnx-to-dlc --input_network %s --float_bitwidth %s %s --output_path %s"
                % (onnx_dir_w, onnx_w, comp.get("float_bitwidth", 32), inputs, dlc_w))
        run_in_wsl_or_bash("source %s/bin/envsetup.sh && %s" % (sdk, cmd1), distro)
        cfg_file = comp.get("htp_config", os.path.join(htp_cfg, name + ".backend.json"))
        cfg_file_w = to_wsl(cfg_file)
        cmd2 = ("cd %s && qnn-context-binary-generator --dlc_path %s --backend %s "
                "--config_file %s --binary_file %s --output_dir %s"
                % (dlc_dir_w, dlc_w, backend, cfg_file_w, os.path.basename(binary_w), ctx_dir_w))
        run_in_wsl_or_bash("source %s/bin/envsetup.sh && %s" % (sdk, cmd2), distro)
        actual = find_ctx_binary(ctx_dir, name, arch)
        log("组件 %s 编译完成: %s" % (name, actual))


def step_index(cfg):
    idx = cfg.get("index_path", "")
    if not idx:
        log("index_path 为空，跳过索引处理")
        return
    proj = cfg.get("proj_path", "") or os.path.join(_HERE, "shared", "proj.bin")
    if not os.path.isfile(proj):
        log("[error] 缺 proj.bin，请在 config 里配 proj_path（共享投影矩阵，App assets/periphery/proj.bin）")
        return
    out = os.path.join(cfg["output_dir"], "role_pack")
    os.makedirs(out, exist_ok=True)
    cmd = [sys.executable, os.path.join(_HERE, "build_index.py"),
           "--index", idx, "--proj", proj, "--out", out]
    run(cmd)


def step_assemble(cfg):
    """把 onnx 常量 / ctx 模型 / 索引散文件组装成 App 可用的 role_pack 目录。"""
    out = cfg["output_dir"]
    role = os.path.join(out, "role_pack")
    os.makedirs(role, exist_ok=True)
    arch = cfg.get("arch", "sm8475")
    ctx_dir = os.path.join(out, "ctx")
    onnx_dir = os.path.join(out, "onnx")

    # models/ —— 角色专属模型件（文件名必须和 App rvc_stream.py 写死的一致）
    models = os.path.join(role, "models")
    os.makedirs(models, exist_ok=True)
    zp_src = find_ctx_binary(ctx_dir, "z_producer", arch)
    dec_src = find_ctx_binary(ctx_dir, "dec_short_T61", arch)
    zp_dst = os.path.join(models, "z_producer.bin.bin")
    dec_dst = os.path.join(models, "dec_short_t61.bin.bin")
    shutil.copyfile(zp_src, zp_dst)
    shutil.copyfile(dec_src, dec_dst)
    log("models: %s <- %s" % (zp_dst, zp_src))
    log("models: %s <- %s" % (dec_dst, dec_src))

    # testdata/ —— 角色专属常量（App 写死 gf_speaker_emb.bin / gf_rnd.bin）
    testdata = os.path.join(role, "testdata")
    os.makedirs(testdata, exist_ok=True)
    g_src = os.path.join(onnx_dir, "g.bin")
    rnd_src = os.path.join(onnx_dir, "rnd.bin")
    if os.path.isfile(g_src) and os.path.isfile(rnd_src):
        shutil.copyfile(g_src, os.path.join(testdata, "gf_speaker_emb.bin"))
        shutil.copyfile(rnd_src, os.path.join(testdata, "gf_rnd.bin"))
        log("testdata: gf_speaker_emb.bin / gf_rnd.bin")
    else:
        log("[warn] 缺 g.bin/rnd.bin，testdata 未生成（检查 export 步骤）")

    # periphery/ —— 索引由 build_index.py 写入；若缺则警告
    periphery = os.path.join(role, "periphery")
    if os.path.isdir(periphery):
        log("periphery: %d 个索引文件" % len(os.listdir(periphery)))
    else:
        os.makedirs(periphery, exist_ok=True)
        log("[warn] 没有索引文件（index_path 为空或 build_index 失败）")

    # manifest.json —— 自动生成文件清单 + SHA256
    manifest = dict(cfg.get("manifest", {}))
    files = []
    for root, _, fns in os.walk(role):
        for fn in fns:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, role).replace("\\", "/")
            files.append({"name": rel, "sha256": sha256(full)})
    files.sort(key=lambda x: x["name"])
    manifest.setdefault("arch", arch)
    manifest.setdefault("sdk_version", cfg.get("sdk_version", "2.47"))
    manifest["files"] = files
    with open(os.path.join(role, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    log("manifest.json: %d 个文件" % len(files))


def step_pack(cfg):
    role_dir = os.path.join(cfg["output_dir"], "role_pack")
    if not os.path.isdir(role_dir):
        log("role_pack 目录不存在，跳过打包")
        return
    model_id = cfg.get("manifest", {}).get("model_id") or os.path.basename(role_dir)
    out_zip = os.path.join(cfg["output_dir"], model_id + ".zip")
    run([sys.executable, os.path.join(_HERE, "pack_role.py"),
         "--dir", role_dir, "--out", out_zip])


def main():
    ap = argparse.ArgumentParser(description="RVC 模型转换入口")
    ap.add_argument("--config", default="config.yaml")
    ap.add_argument("--check", action="store_true", help="只检查环境/配置")
    args = ap.parse_args()

    cfg = load_config(args.config)
    if args.check:
        log("环境：WSL=%s Python=%s" % (in_wsl(), sys.executable))
        log("QNN_SDK_ROOT=%s" % (cfg.get("qnn_sdk_root") or os.environ.get("QNN_SDK_ROOT", "未设置")))
        log("配置字段：pth=%s index=%s arch=%s out=%s" % (
            cfg.get("pth_path"), cfg.get("index_path"), cfg.get("arch"), cfg.get("output_dir")))
        return 0

    os.makedirs(cfg.get("output_dir", "./out"), exist_ok=True)
    step_export(cfg)
    step_qnn(cfg)
    step_index(cfg)
    step_assemble(cfg)
    step_pack(cfg)
    log("全部完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
