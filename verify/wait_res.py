import subprocess, time

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace")

for i in range(6):
    time.sleep(20)
    log = sh(["adb", "logcat", "-d", "-s", "RVC:I"])
    if "RESULT_FULL" in log:
        print("=== DONE after %ds ===" % ((i + 1) * 20))
        print(log[-2500:])
        break
    proc = sh(["adb", "shell", "ps", "-A", "|", "grep", "rvc"]).strip()
    wchan = sh(["adb", "shell", "su", "-c", "cat /proc/$(pidof com.rvc.app)/task/*/wchan 2>/dev/null | sort | uniq -c | head -2"]).strip()
    print("[%ds] proc=%s wchan=%s" % ((i + 1) * 20, proc.split()[-1] if proc else "GONE", wchan[:60]))
else:
    print("still running after 120s")
    print(sh(["adb", "logcat", "-d", "-s", "RVC:I"])[-1500:])
