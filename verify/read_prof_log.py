import subprocess

def sh(parts, t=60):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== qnn-profiling-data_0.log ===")
print(sh(["adb", "shell", "su", "-c", "cat /data/data/com.termux/files/home/rvc/out_prof/qnn-profiling-data_0.log"]))
print("=== bin perms ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/data/com.termux/files/home/rvc/bin/"]))
