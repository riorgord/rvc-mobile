import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== ph_chain2 完整内容 ===")
print(sh(["adb", "shell", "su", "-c", "find /data/local/tmp/qnn/ph_chain2 -type f 2>/dev/null | head -40"]))
print("=== ph_chain2 各 out execution_metadata ===")
print(sh(["adb", "shell", "su", "-c", "for d in /data/local/tmp/qnn/ph_chain2/*/; do echo \"--- $d\"; cat $d/execution_metadata.yaml 2>/dev/null | head -12; done"]))
print("=== rvc_test/generator metadata ===")
print(sh(["adb", "shell", "su", "-c", "cat /data/local/tmp/qnn/rvc_test/generator/out_native/execution_metadata.yaml /data/local/tmp/qnn/rvc_test/generator/out_ncw/execution_metadata.yaml 2>/dev/null"]))
