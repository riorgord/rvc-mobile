import subprocess, re, sys, io, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from collections import OrderedDict, defaultdict

def sh(parts, t=300):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

BASE = "/mnt/d/AI/claw_repair/research/rvc/mobile_prep/profs/hr"
PC = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\hr"

def analyze(tag, prof_bin, topn=20):
    outfile = os.path.join(PC, "%s_stdout.txt" % tag)
    sh(["wsl", "-d", "Ubuntu-2204-QNN", "-e", "bash", "-lc",
        'export LD_LIBRARY_PATH=/home/riorg/qnn_sdk/lib/x86_64-linux-clang && '
        '/home/riorg/qnn_sdk/bin/x86_64-linux-clang/qnn-profile-viewer '
        '--input_log=%s > %s 2>&1' % (BASE + "/" + prof_bin,
                                      "/mnt/d/" + outfile.replace("\\", "/").replace("D:/", "").lstrip("/"))])
    txt = open(outfile, encoding="utf-8", errors="replace").read()
    print("\n\n########## %s ##########" % tag)
    for line in txt.splitlines():
        if any(k in line for k in ["NetRun:", "QNN (execute) time", "Accelerator (execute) time (cycles)",
                                    "Backend (RPC (execute) time)", "HVX threads", "Graph 0"]):
            print("  ", line.strip()[:90])
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
    print("  唯一 op:", len(uniq), " 周期和:", int(total))
    if not uniq:
        return
    # 类型聚合
    typ = defaultdict(float)
    for name, c in uniq:
        typ[name.split("/")[-1]] += c
    print("  == op 类型 top 12 ==")
    for t, c in sorted(typ.items(), key=lambda x: -x[1])[:12]:
        print("   %-30s %12.0f  %6.2f%%" % (t, c, c / total * 100))
    # top 个体
    print("  == top %d 个体 op ==" % topn)
    for name, c in sorted(uniq, key=lambda x: -x[1])[:topn]:
        print("   %-60s %12.0f  %6.2f%%" % (name, c, c / total * 100))

analyze("hubert", "hubert_prof.bin")
analyze("rmvpe", "rmvpe_prof.bin")
