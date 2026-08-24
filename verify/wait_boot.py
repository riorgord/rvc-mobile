import subprocess, time

def sh(parts, t=120):
    return subprocess.run(parts, capture_output=True, text=True, timeout=t).stdout

print("waiting device...")
sh(["adb", "wait-for-device"])
time.sleep(5)
# 等待开机完成
for i in range(30):
    boot = sh(["adb", "shell", "getprop", "sys.boot_completed"]).strip()
    if boot == "1":
        print("boot completed after", i * 5, "s")
        break
    time.sleep(5)
time.sleep(8)
print("=== focus ===")
print(sh(["adb", "shell", "dumpsys window | grep mCurrentFocus"]))
