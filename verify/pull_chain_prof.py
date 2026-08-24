import subprocess, os

def pull(src, dst):
    r = subprocess.run(["adb", "exec-out", "su", "-c", "cat %s" % src], capture_output=True, timeout=180)
    open(dst, "wb").write(r.stdout)
    return len(r.stdout)

os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\ph_chain2", exist_ok=True)
base = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\ph_chain2"
for name, sub in [("gen", "out_gen_1"), ("hubert", "out_hubert_1"), ("rmvpe", "out_rmvpe_1")]:
    src = "/data/local/tmp/qnn/ph_chain2/%s/qnn-profiling-data_0.log" % sub
    n = pull(src, os.path.join(base, "%s_prof.bin" % name))
    print(name, n, "bytes")

# 也拉 dec 的
os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\dec", exist_ok=True)
n = pull("/data/local/tmp/ph_bench/out_dec_prof/qnn-profiling-data_0.log",
         r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\dec\dec_prof.bin")
print("dec", n, "bytes")
