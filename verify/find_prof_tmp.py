import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== 手机 /data/local/tmp/qnn ===")
print(sh(["adb", "shell", "su", "-c", "find /data/local/tmp/qnn -iname '*prof*' -o -iname '*.log' -o -iname '*.yaml' -o -iname '*.csv' 2>/dev/null | head -30"]))
print("=== 手机 /data/local/tmp/qnn 目录树 ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/local/tmp/qnn/ 2>/dev/null"]))
print("=== 手机所有 profiling log (全局) ===")
print(sh(["adb", "shell", "su", "-c", "find / -name 'qnn-profiling-data*.log' 2>/dev/null | head -20"]))
