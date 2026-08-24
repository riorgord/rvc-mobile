import subprocess, time, os

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace")

# 唤醒保持亮屏
sh(["adb", "shell", "svc", "power", "stayon", "true"])
sh(["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
sh(["adb", "shell", "wm", "dismiss-keyguard"])
# 装新 APK
print(sh(["adb", "install", "-r", r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\build\outputs\apk\debug\app-debug.apk"]).strip())
# 冷启动 profile=true
sh(["adb", "logcat", "-c"])
sh(["adb", "shell", "am", "force-stop", "com.rvc.app"])
time.sleep(2)
sh(["adb", "shell", "am", "start", "-n", "com.rvc.app/.MainActivity", "--ez", "profile", "true"])
print("cold start profile=true, waiting 45s...")
time.sleep(45)
print("=== logcat RESULT ===")
print(sh(["adb", "logcat", "-d", "-s", "RVC:I"])[-2500:])

# 拉 profiling jsonl
os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", exist_ok=True)
listing = sh(["adb", "shell", "run-as", "com.rvc.app", "ls", "files/rvc_exp"]).strip()
print("=== files/rvc_exp ===")
print(listing)
for f in listing.split():
    if "prof" in f or ".jsonl" in f:
        content = sh(["adb", "shell", "run-as", "com.rvc.app", "cat", "files/rvc_exp/%s" % f])
        dst = os.path.join(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", f)
        open(dst, "w", encoding="utf-8", errors="replace").write(content)
        print("saved", f, len(content), "bytes")
