import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== rvc_test/generator ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/local/tmp/qnn/rvc_test/generator/ 2>/dev/null"]))
print("=== generator/out_native ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/local/tmp/qnn/rvc_test/generator/out_native/ /data/local/tmp/qnn/rvc_test/generator/out_ncw/ 2>/dev/null"]))
print("=== rvc_test 根 ===")
print(sh(["adb", "shell", "su", "-c", "ls /data/local/tmp/qnn/rvc_test/ 2>/dev/null"]))
print("=== 手机可执行 qnn-net-run ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/local/tmp/qnn/qnn-net-run /data/local/tmp/qnn/libQnnHtp.so 2>/dev/null"]))
