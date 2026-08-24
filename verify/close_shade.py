import subprocess, time

def sh(parts):
    return subprocess.run(parts, capture_output=True, text=True, timeout=60).stdout

for cmd in [
    ["adb", "shell", "su", "-c", "service call statusbar 1"],
    ["adb", "shell", "su", "-c", "cmd statusbar collapse"],
    ["adb", "shell", "su", "-c", "service call statusbar 2"],
    ["adb", "shell", "input", "keyevent", "111"],
]:
    print(">>", " ".join(cmd))
    print(sh(cmd).strip() or "(no output)")
    time.sleep(1)
print("=== focus ===")
print(sh(["adb", "shell", "dumpsys window | grep mCurrentFocus"]))
