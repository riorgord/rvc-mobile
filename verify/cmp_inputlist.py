import subprocess

def sh(parts, t=60):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== generator/input_list.txt (跑通的 B) ===")
print(repr(sh(["adb", "shell", "su", "-c", "cat /data/local/tmp/qnn/rvc_test/generator/input_list.txt 2>&1"])))
print("=== jl_prof/input_list.txt (A) ===")
print(repr(sh(["adb", "shell", "su", "-c", "cat /data/local/tmp/qnn/jl_prof/input_list.txt 2>&1"])))
print("=== generator/ 输入文件 ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/local/tmp/qnn/rvc_test/generator/*.bin 2>/dev/null"]))
