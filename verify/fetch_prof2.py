import subprocess, time, os

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace")

print("=== logcat RVC ===")
print(sh(["adb", "logcat", "-d", "-s", "RVC:I"])[-2000:])

print("=== /sdcard/rvc_exp ===")
print(sh(["adb", "shell", "ls", "-la", "/sdcard/rvc_exp"]))
print("=== files/rvc_exp ===")
print(sh(["adb", "shell", "run-as", "com.rvc.app", "ls", "-la", "files/rvc_exp"]))

print("=== pull all profiling-ish files ===")
os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", exist_ok=True)
for remote in ["/sdcard/rvc_exp", "/data/data/com.rvc.app/files/rvc_exp"]:
    loc = "files/rvc_exp" if remote.startswith("/data") else "rvc_exp"
    listing = sh(["adb", "shell", "run-as", "com.rvc.app", "ls", remote]).strip()
    if not listing or "No such" in listing:
        # sdcard may need no run-as
        listing = sh(["adb", "shell", "ls", remote]).strip()
        loc = remote
    print(remote, "files:", listing[:400])
    for f in listing.split():
        if any(k in f.lower() for k in ["prof", ".jsonl", "report", "full_", "live_"]):
            if loc.startswith("files/"):
                content = sh(["adb", "shell", "run-as", "com.rvc.app", "cat", "%s/%s" % (loc, f)])
            else:
                content = sh(["adb", "shell", "cat", "%s/%s" % (loc, f)])
            dst = os.path.join(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", f)
            open(dst, "w", encoding="utf-8", errors="replace").write(content)
            print("  saved", f, len(content), "bytes")
