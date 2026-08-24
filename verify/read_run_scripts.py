import subprocess

def sh(parts, t=60):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== phone_probe_htp.sh ===")
print(sh(["adb", "shell", "su", "-c", "cat /data/local/tmp/qnn/rvc_test/phone_probe_htp.sh 2>/dev/null"]))
print("=== bench_t224.sh ===")
print(sh(["adb", "shell", "su", "-c", "cat /data/local/tmp/qnn/rvc_test/bench_t224.sh 2>/dev/null"]))
print("=== out_native metadata (跑通那次) ===")
print(sh(["adb", "shell", "su", "-c", "cat /data/local/tmp/qnn/rvc_test/generator/out_native/execution_metadata.yaml 2>/dev/null | head -5"]))
