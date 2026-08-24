import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

# 找 QNN SDK 的 profiling 解析工具
print("=== find profiling tools in qnn_sdk ===")
print(sh(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", "find /home/riorg/qnn_sdk -iname '*prof*' -type f 2>/dev/null | head -30"]))
