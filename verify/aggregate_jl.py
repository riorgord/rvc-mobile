import re, sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from collections import OrderedDict, defaultdict

txt = open(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl\jl_gen_stdout.txt", encoding="utf-8", errors="replace").read()

ops = []
for m in re.finditer(r"^\s+(.+?):OpId_?\d+ \(cycles\): (\d+) cycles$", txt, re.M):
    name, cyc = m.group(1).strip(), int(m.group(2))
    if cyc > 0:
        ops.append((name, cyc))
agg = OrderedDict()
for name, cyc in ops:
    agg.setdefault(name, []).append(cyc)
uniq = [(n, sum(v) / len(v)) for n, v in agg.items()]
total = sum(c for _, c in uniq)

# 按模块聚合 (第一/二级路径)
mod = defaultdict(float)
modcount = defaultdict(int)
for name, c in uniq:
    parts = name.split("/")
    m = parts[1] if len(parts) > 1 else name  # e.g. dec / enc / flow
    mod[m] += c
    modcount[m] += 1

print("=== 模块聚合 (gen 内部) ===")
for m, c in sorted(mod.items(), key=lambda x: -x[1]):
    print("%-20s %12.0f cycles  %6.2f%%   (%d op)" % (m, c, c / total * 100, modcount[m]))

# 按 op 类型聚合 (Conv_2d / ConvTranspose / MatMul / ...)
typ = defaultdict(float)
for name, c in uniq:
    t = name.split("/")[-1]
    typ[t] += c
print("\n=== 按 op 类型 ===")
for t, c in sorted(typ.items(), key=lambda x: -x[1])[:15]:
    print("%-28s %12.0f cycles  %6.2f%%" % (t, c, c / total * 100))

# dec resblocks convs 合计
dec_rb_conv = sum(c for name, c in uniq if "/dec/resblocks." in name and "Conv_2d" in name)
print("\ndec resblocks Conv_2d 合计: %.1f%%" % (dec_rb_conv / total * 100))
