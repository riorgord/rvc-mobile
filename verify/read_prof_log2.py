import subprocess, os

def sh(parts, t=60):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout + r.stderr

raw = sh(["adb", "shell", "su", "-c", "cat /data/data/com.termux/files/home/rvc/out_prof/qnn-profiling-data_0.log"])
os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", exist_ok=True)
open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\qnn-profiling-data_0.log", "wb").write(raw)
print("saved", len(raw), "bytes")
print(raw[:3000].decode("utf-8", "replace"))
