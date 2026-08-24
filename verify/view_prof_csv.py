import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

cmd = (
    'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
    '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
    '--input_log=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/qnn-profiling-data_0.log '
    '--output=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/hubert_prof.csv 2>&1 | head -40'
)
print(sh(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", cmd]))
