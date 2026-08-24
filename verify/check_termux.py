import subprocess

def sh(parts, t=60):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== rvc dir ===")
print(sh(["adb", "shell", "su", "-c", "ls /data/data/com.termux/files/home/rvc/"]))
print("=== bin ===")
print(sh(["adb", "shell", "su", "-c", "ls /data/data/com.termux/files/home/rvc/bin/ 2>/dev/null | head"]))
print("=== python? ===")
print(sh(["adb", "shell", "su", "-c", "ls /data/data/com.termux/files/usr/bin/python* 2>/dev/null"]))
print("=== qnn-net-run help (profile?) ===")
print(sh(["adb", "shell", "su", "-c", "/data/data/com.termux/files/home/rvc/bin/qnn-net-run --help 2>&1 | grep -iE 'profile|debug|perf'"]))
