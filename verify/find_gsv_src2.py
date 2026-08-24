import os, subprocess

# 1) 搜 PC 全盘 gsv_qnn_service.c / gsv 源码
print("=== PC 搜 gsv_qnn_service / gsv 源码 ===")
roots = [r"D:\AI\claw_repair", r"D:\AI", r"C:\Users"]
seen = set()
for root in roots:
    if not os.path.exists(root):
        continue
    for dp, dn, fn in os.walk(root):
        # 跳过超大/无关目录
        if any(x in dp for x in ["node_modules", ".git", "qwen3tts", "9.1G"]):
            continue
        for f in fn:
            if f in ("gsv_qnn_service.c",) or (f.endswith(".c") and "gsv" in f.lower()):
                p = os.path.join(dp, f)
                if p not in seen:
                    seen.add(p)
                    print(" ", p)
print("done scanning")

# 2) WSL tar 列 wsl_gsv_convert.tar.gz 顶层
print("\n=== wsl_gsv_convert.tar.gz (WSL tar) ===")
r = subprocess.run(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc",
                    "tar -tzf /mnt/d/AI/claw_repair/archive/gsv/wsl_convert/wsl_gsv_convert.tar.gz 2>/dev/null | grep -iE 'qnn_service|libgsv|gsv.*\\.(c|cpp|h)$' | head -30"],
                   capture_output=True, text=True, timeout=300)
print(r.stdout or "(none)")
