import subprocess, time, os

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace")

time.sleep(15)
print("=== logcat RVC ===")
print(sh(["adb", "logcat", "-d", "-s", "RVC:I"])[-1500:])

# 拉 profiling 文件 (先试 /sdcard/rvc_exp, 再试 filesDir)
print("=== pull profiling ===")
for remote in ["/sdcard/rvc_exp", "/data/data/com.rvc.app/files/rvc_exp"]:
    files = sh(["adb", "shell", "run-as", "com.rvc.app", "ls", remote]).strip()
    print(remote, "->", files[:300])
    if files and "No such" not in files:
        for f in files.split():
            if "prof" in f or ".jsonl" in f:
                loc = remote if remote.startswith("/sdcard") else "files/rvc_exp"
                cmd = ["adb", "shell", "run-as", "com.rvc.app", "cat", "%s/%s" % (loc, f)]
                out = sh(cmd)
                dst = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\%s" % f
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                open(dst, "w", encoding="utf-8").write(out)
                print("  saved", dst, len(out), "bytes")
