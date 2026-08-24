import subprocess, time

def sh(parts):
    return subprocess.run(parts, capture_output=True, text=True, timeout=60).stdout

# 1. 禁用锁屏 (root)
print("disable keyguard:")
print(sh(["adb", "shell", "su", "-c", "locksettings set-disabled true"]).strip() or "(ok)")
print(sh(["adb", "shell", "su", "-c", "settings put secure lockscreen.disabled 1"]).strip() or "(ok)")
time.sleep(2)
print("=== focus after ===")
print(sh(["adb", "shell", "dumpsys window | grep mCurrentFocus"]))
# 2. 确保屏幕亮
sh(["adb", "shell", "svc", "power", "stayon", "true"])
sh(["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
time.sleep(2)
print("=== focus after wake ===")
print(sh(["adb", "shell", "dumpsys window | grep mCurrentFocus"]))
