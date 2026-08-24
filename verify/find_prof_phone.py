import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== 手机 rvc 目录所有 profiling 相关 ===")
print(sh(["adb", "shell", "su", "-c",
          "find /data/data/com.termux/files/home/rvc -iname '*prof*' -o -iname '*.log' 2>/dev/null | head -40"]))
print("=== 手机 out_prof 内容 ===")
print(sh(["adb", "shell", "su", "-c",
          "ls -la /data/data/com.termux/files/home/rvc/out_prof/ /data/data/com.termux/files/home/rvc/out_prof/Result_0/ 2>/dev/null"]))
