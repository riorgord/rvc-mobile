import subprocess, time

def sh(parts, t=120):
    return subprocess.run(parts, capture_output=True, text=True, timeout=t).stdout

# 唤醒 + 保持亮屏 + 解锁
sh(["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
sh(["adb", "shell", "svc", "power", "stayon", "true"])
sh(["adb", "shell", "wm", "dismiss-keyguard"])
time.sleep(2)
print("focus:", sh(["adb", "shell", "dumpsys window | grep mCurrentFocus"]).strip())

# 装新 APK
print(sh(["adb", "install", "-r", r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\build\outputs\apk\debug\app-debug.apk"]).strip())
# 触发带 profiling 的全链路 (intent extra)
sh(["adb", "logcat", "-c"])
sh(["adb", "shell", "am", "force-stop", "com.rvc.app"])
time.sleep(1)
sh(["adb", "shell", "am", "start", "-n", "com.rvc.app/.MainActivity", "--ez", "profile", "true"])
print("started with profile=true, waiting 40s...")
time.sleep(40)
print("=== logcat ===")
print(sh(["adb", "logcat", "-d", "-s", "RVC:I"])[-1200:])
