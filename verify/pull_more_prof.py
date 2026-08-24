import subprocess, os

def pull(src, dst):
    r = subprocess.run(["adb", "exec-out", "su", "-c", "cat %s" % src], capture_output=True, timeout=180)
    open(dst, "wb").write(r.stdout)
    return len(r.stdout)

os.makedirs(r"D:\AI\claw_repair\research\rvc\mobile_prep\profs", exist_ok=True)
srcs = [
    ("/data/local/tmp/qnn/rvc_test/gen_stages/out_prof28/qnn-profiling-data_0.log",
     r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\gen_stages28.bin"),
    ("/data/local/tmp/ph_bench/out_dec_prof5/qnn-profiling-data_0.log",
     r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\dec5.bin"),
    ("/data/local/tmp/ph_chain2/out_gen_1/qnn-profiling-data_0.log",
     r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\chain_gen.bin"),
]
for s, d in srcs:
    try:
        n = pull(s, d)
        print(d.split("\\")[-1], n, "bytes", "<--", s)
    except Exception as e:
        print("FAIL", s, e)
