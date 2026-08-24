import subprocess, os

def sh(parts, t=180):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout

# 拉 profiling log
raw = sh(["adb", "exec-out", "su", "-c", "cat /data/local/tmp/qnn/jl_prof/out/qnn-profiling-data_0.log"])
dst = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl\jl_gen_prof.bin"
open(dst, "wb").write(raw)
print("pulled", len(raw), "bytes")

# qnn-profile-viewer 解析 (stdout 有逐 op cycles)
def wsl(script, t=180):
    r = subprocess.run(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", script],
                       capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

outfile = "/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/jl/jl_gen_stdout.txt"
wsl(
    'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
    '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
    '--input_log=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/jl/jl_gen_prof.bin '
    '> %s 2>&1; wc -l %s' % (outfile, outfile))
