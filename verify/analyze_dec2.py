import subprocess, os, re, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

def wsl(script, t=180):
    r = subprocess.run(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc", script],
                       capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

outfile = "/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/dec/dec_stdout.txt"
wsl(
    'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
    '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
    '--input_log=/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/dec/dec_prof.bin '
    '> %s 2>&1; wc -l %s' % (outfile, outfile))

txt = open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\dec\dec_stdout.txt", encoding="utf-8", errors="replace").read()
# 解析 "op:OpId_N (cycles): N cycles"
ops = []
for m in re.finditer(r"^\s+(.+?):OpId_?\d+ \(cycles\): (\d+) cycles$", txt, re.M):
    name, cyc = m.group(1).strip(), int(m.group(2))
    if cyc > 0:
        ops.append((name, cyc))
# 顶层 Accelerator cycles
am = re.search(r"Accelerator \(execute\) time \(cycles\): (\d+) cycles", txt)
total_acc = int(am.group(1)) if am else 0
total = sum(c for _, c in ops)
print("Accelerator execute cycles:", total_acc)
print("逐 op 求和 cycles:", total, " (覆盖率 %.1f%%)" % (total / total_acc * 100 if total_acc else 0))
print("有 cycles op 数:", len(ops))
ops.sort(key=lambda x: -x[1])
print("\n=== top 30 op 按 cycles ===")
for name, c in ops[:30]:
    print("%-62s %12d cycles  %6.2f%%" % (name, c, c / total_acc * 100))
