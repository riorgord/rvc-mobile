import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== App testdata 全部 ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/user/0/com.rvc.app/files/testdata/ 2>/dev/null"]))
print("=== rvc_periphery hubert_blocks 定义 ===")
import os
p = r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\python\rvc_periphery.py"
if os.path.exists(p):
    txt = open(p, encoding="utf-8").read()
    i = txt.find("def hubert_blocks")
    print(txt[i:i+600] if i >= 0 else "(not found)")
else:
    print("file not found")
