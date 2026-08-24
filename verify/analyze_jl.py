import re, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

txt = open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl\jl_gen_stdout.txt", encoding="utf-8", errors="replace").read()

# 顶层统计
print("=== 顶层 execute 统计 ===")
for line in txt.splitlines():
    if any(k in line for k in ["NetRun:", "QNN (execute) time", "Accelerator (execute) time (cycles)",
                                "Accelerator (execute) time:", "RPC (execute) time", "HVX threads",
                                "Graph 0"]):
        print(" ", line.strip()[:90])

# 逐 op cycles
ops = []
for m in re.finditer(r"^\s+(.+?):OpId_?\d+ \(cycles\): (\d+) cycles$", txt, re.M):
    name, cyc = m.group(1).strip(), int(m.group(2))
    if cyc > 0:
        ops.append((name, cyc))

# 去重 (同一 op 多次 execute 采样 → 取平均)
from collections import OrderedDict
agg = OrderedDict()
for name, cyc in ops:
    if name not in agg:
        agg[name] = []
    agg[name].append(cyc)
uniq = [(n, sum(v) / len(v)) for n, v in agg.items()]
total = sum(c for _, c in uniq)
print("\n=== 唯一 op 数:", len(uniq), " 平均和 cycles:", int(total))

# 按 op 类型聚合 (去掉 OpId, 归并同名)
from collections import defaultdict
bytype = defaultdict(float)
for name, c in uniq:
    bytype[name] += c

# 按类型占比
types_sorted = sorted(bytype.items(), key=lambda x: -x[1])
print("\n=== 按 op 名聚合 top 20 (占比 = type/total) ===")
for name, c in types_sorted[:20]:
    print("%-64s %12d cycles  %6.2f%%" % (name, int(c), c / total * 100))

# top 个体 op
uniq_sorted = sorted(uniq, key=lambda x: -x[1])
print("\n=== 个体 op top 25 (单次 execute cycles) ===")
for name, c in uniq_sorted[:25]:
    print("%-64s %12d cycles  %6.2f%%" % (name, int(c), c / total * 100))
