import os, subprocess

# 1) 搜 archive/gsv 所有 C/C++/头文件
print("=== archive/gsv 源码文件 ===")
hits = []
for dp, dn, fn in os.walk(r"D:\AI\claw_repair\archive\gsv"):
    for f in fn:
        if f.endswith((".c", ".cpp", ".cc", ".h", ".hpp")):
            hits.append(os.path.join(dp, f))
for h in hits[:50]:
    print(" ", h)
print("total:", len(hits))

# 2) 列 wsl_gsv_convert.tar.gz 找 gsv_qnn_service / libgsv 源码
print("\n=== wsl_gsv_convert.tar.gz 里 gsv 源码 ===")
r = subprocess.run(["tar", "-tzf", r"D:\AI\claw_repair\archive\gsv\wsl_convert\wsl_gsv_convert.tar.gz"],
                   capture_output=True, text=True, timeout=300)
for line in r.stdout.splitlines():
    low = line.lower()
    if any(k in low for k in ["qnn_service", "libgsv", "gsv_qnn", "profile", ".c", ".cpp", ".h"]):
        print(" ", line)
