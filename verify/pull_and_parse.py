import subprocess, os

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout

# exec-out 二进制安全拉取
raw = sh(["adb", "exec-out", "su", "-c", "cat /data/data/com.termux/files/home/rvc/out_prof/qnn-profiling-data_0.log"])
dst = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\qnn-profiling-data_0.bin"
open(dst, "wb").write(raw)
print("pulled", len(raw), "bytes")

# 解析
def wsl(script, t=120):
    r = subprocess.run(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", script],
                       capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

out = wsl(
    'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
    '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
    '--input_log=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/qnn-profiling-data_0.bin '
    '--output=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/hubert_prof.csv')
print(out[:500])
