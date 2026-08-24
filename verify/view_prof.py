import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

# 用 qnn-profile-viewer 解析现有 profiling log
cmd = (
    'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
    '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
    '/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/qnn-profiling-data_0.log '
    '2>&1 | head -80'
)
print(sh(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", cmd]))
