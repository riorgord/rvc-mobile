import subprocess, time, os

def sh(parts, t=120):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace")

print("=== logcat RVC tail ===")
print(sh(["adb", "logcat", "-d", "-s", "RVC:I"])[-2000:])

print("=== files/rvc_exp ===")
listing = sh(["adb", "shell", "run-as", "com.rvc.app", "ls", "-la", "files/rvc_exp"]).strip()
print(listing)

os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", exist_ok=True)
for f in listing.split():
    if f.startswith("-") or f.startswith("total") or f.startswith("d"):
        continue
    if any(k in f.lower() for k in ["prof", ".jsonl", ".raw"]):
        content = sh(["adb", "shell", "run-as", "com.rvc.app", "cat", "files/rvc_exp/%s" % f])
        dst = os.path.join(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", f)
        open(dst, "w", encoding="utf-8", errors="replace").write(content)
        print("saved", f, len(content), "bytes")
