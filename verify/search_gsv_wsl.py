import subprocess

def sh(parts, t=180):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

for distro in ["Ubuntu24.04", "Ubuntu-2204-QNN"]:
    print("===== %s =====" % distro)
    print(sh(["wsl", "-d", distro, "-e", "bash", "-lc",
              "find /home /opt /root /mnt/c /mnt/d -maxdepth 6 \\( -iname '*gsv*' -o -name 'gsv_qnn_service.c' -o -iname 'libgsv*' \\) -not -path '*/node_modules/*' 2>/dev/null | head -40"]))
