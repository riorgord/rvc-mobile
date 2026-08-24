import csv, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

rows = list(csv.reader(open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\dec\dec_full.csv", encoding="utf-8", errors="replace")))
print("总行数:", len(rows))
print("表头:", rows[0])

# 提取 op cycles 行 (Message 含 cycles)
ops = []
for r in rows:
    if len(r) >= 2 and "cycles" in r[1].lower():
        # 提取 op 名 + cycles
        msg = r[1]
        # 格式: op名 (cycles): N cycles
        import re
        m = re.match(r"(.+?):OpId_?\d+ \(cycles\): (\d+) cycles", msg)
        if m:
            name = m.group(1).strip()
            cycles = int(m.group(2))
            if cycles > 0:
                ops.append((name, cycles))

total = sum(c for _, c in ops)
print("\n总有效 cycles:", total, " (Accelerator execute cycles 1533713165)")
print("有 cycles 的 op 数:", len(ops))
print("\n=== top 25 op 按 cycles ===")
ops.sort(key=lambda x: -x[1])
for name, c in ops[:25]:
    print("%-60s %12d cycles  %6.2f%%" % (name, c, c / total * 100))
