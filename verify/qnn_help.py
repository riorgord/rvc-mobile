import subprocess

def sh(parts, t=60):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print(sh(["adb", "shell", "su", "-c", "/data/data/com.termux/files/home/rvc/bin/qnn-net-run --help 2>&1"]))
