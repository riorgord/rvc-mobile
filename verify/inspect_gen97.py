import subprocess, os

def wsl(script, t=180):
    r = subprocess.run(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", script],
                       capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

# 1) 看 ph_chain2 gen 的 97B 内容
print("=== ph_chain2 gen 97B raw ===")
raw = open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\ph_chain2\gen_prof.bin", "rb").read()
print(repr(raw[:200]))

# 2) dec 完整 CSV 输出
wsl(
    'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
    '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
    '--input_log=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/dec/dec_prof.bin '
    '--output=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/dec/dec_full.csv > /dev/null 2>&1; '
    'echo CSV done')
