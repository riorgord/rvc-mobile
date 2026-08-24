import subprocess, os

def wsl(script, t=180):
    r = subprocess.run(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", script],
                       capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

for name in ["chain_gen", "gen_stages28", "dec5"]:
    outfile = "/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/%s_stdout.txt" % name
    wsl(
        'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
        '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
        '--input_log=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/%s.bin '
        '> %s 2>&1; wc -l %s' % (name, outfile, outfile))
    print("=== %s ===" % name, "rows:", 
          len(open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\%s_stdout.txt" % name,
                   encoding="utf-8", errors="replace").read().splitlines()))
