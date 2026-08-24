import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

D = "/data/local/tmp/qnn/jl_prof"
print("=== 文件 stat ===")
print(sh(["adb", "shell", "su", "-c", "stat %s/gen_fp32.bin 2>&1 | head -8" % D]))
print("=== 前 16 字节 (magic) ===")
print(sh(["adb", "shell", "su", "-c", "head -c 16 %s/gen_fp32.bin 2>&1 | od -A x -t x1 | head -2" % D]))
print("=== wc 确认大小 ===")
print(sh(["adb", "shell", "su", "-c", "wc -c %s/gen_fp32.bin 2>&1" % D]))
print("=== 用 cat 读测试 ===")
print(sh(["adb", "shell", "su", "-c", "cat %s/gen_fp32.bin > /dev/null 2>&1 && echo READABLE || echo READ-FAIL" % D]))
