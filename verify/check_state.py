import subprocess, sys

def sh(parts):
    return subprocess.run(parts, capture_output=True, text=True, timeout=60).stdout

pid = "28640"
print("=== wchan ===")
print(sh(["adb", "shell", "su", "-c", "cat /proc/%s/task/*/wchan 2>/dev/null | sort | uniq -c | head -3" % pid]))
print("=== cpu/mem ===")
print(sh(["adb", "shell", "top", "-b", "-n", "1"]).split("rvc")[-1][:200] if "rvc" in sh(["adb", "shell", "top", "-b", "-n", "1"]) else "no rvc line")
print("=== logcat tail 40 ===")
print(sh(["adb", "logcat", "-d", "-t", "40"])[-1800:])
