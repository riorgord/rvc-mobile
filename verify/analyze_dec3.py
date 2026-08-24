import subprocess, os, re, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

txt = open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\dec\dec_stdout.txt", encoding="utf-8", errors="replace").read()
ops = []
for m in re.finditer(r"^\s+(.+?):OpId_?\d+ \(cycles\): (\d+) cycles$", txt, re.M):
    name, cyc = m.group(1).strip(), int(m.group(2))
    if cyc > 0:
        ops.append((name, cyc))

# 找顶层 Accelerator cycles 原始行
for line in txt.splitlines():
    if "Accelerator (execute) time" in line and "cycles" in line:
        print("顶层行:", line.strip()[:100])

total = sum(c for _, c in ops)
print("逐 op 求和 cycles:", total, " op 数:", len(ops))
ops.sort(key=lambda x: -x[1])
print("\n=== top 35 op 按 cycles (占比 = op/total) ===")
for name, c in ops[:35]:
    print("%-62s %12d cycles  %6.2f%%" % (name, c, c / total * 100))
# 累计
cum = 0
print("\n=== 累计占比 ===")
for i, (name, c) in enumerate(ops):
    cum += c
    if cum / total >= 0.9 and i < 20:
        print("前 %d 个 op 累计 %.1f%%" % (i + 1, cum / total * 100))
        break
