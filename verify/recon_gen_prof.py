import subprocess

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

print("=== App models ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/user/0/com.rvc.app/files/models/ 2>/dev/null"]))
print("=== App testdata (rnd/speaker) ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/user/0/com.rvc.app/files/testdata/gf_rnd.bin /data/user/0/com.rvc.app/files/testdata/gf_speaker_emb.bin 2>/dev/null"]))
print("=== App rvc_exp (full inputs fp32) ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/user/0/com.rvc.app/files/rvc_exp/ 2>/dev/null"]))
print("=== 手机 qnn-net-run + lib ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/local/tmp/qnn/qnn-net-run /data/local/tmp/qnn/libQnnHtp.so /data/local/tmp/qnn/libQnnHtpV69.so 2>/dev/null"]))
