import subprocess

pid = "29827"
cmd = (
    "for t in /proc/%s/task/*/; do "
    "echo \"$(cat $t/comm 2>/dev/null):$(cat $t/wchan 2>/dev/null)\"; done" % pid
)
r = subprocess.run(["adb", "shell", "su", "-c", cmd], capture_output=True, text=True, timeout=30)
print(r.stdout)
print(r.stderr)
