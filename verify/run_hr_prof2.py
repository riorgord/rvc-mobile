import subprocess, os

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

def shb(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout

A = "/data/user/0/com.rvc.app/files"
D = "/data/local/tmp/qnn/hr_prof"
Q = "/data/local/tmp/qnn"
PC = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\hr"
os.makedirs(PC, exist_ok=True)

# 建目录 + chmod 777 + 复制
cmds = [
    "mkdir -p %s" % D,
    "chmod 777 %s" % D,
    "cp %s/models/hubert_mix_def_t4800.bin %s/hubert.bin" % (A, D),
    "cp %s/models/rmvpe_fp32_256.bin %s/rmvpe.bin" % (A, D),
    "cp %s/testdata/hb0.bin %s/hb0.bin" % (A, D),
    "cp %s/testdata/gya_mel_t.bin %s/mel.bin" % (A, D),
    "chmod 666 %s/* 2>/dev/null" % D,
]
for c in cmds:
    o = sh(["adb", "shell", "su", "-c", c])
    if o.strip():
        print(">>", o.strip()[:100])

def run(bin, il_content, graph, outname, tag):
    open(os.path.join(PC, "il_%s.txt" % tag), "wb").write(il_content.encode())
    print(sh(["adb", "push", os.path.join(PC, "il_%s.txt" % tag), "%s/il_%s.txt" % (D, tag)]).strip())
    cmd = (
        "export LD_LIBRARY_PATH=%s:/vendor/dsp/cdsp:/system/lib64:/vendor/lib64; "
        "export ADSP_LIBRARY_PATH=%s; "
        "cd %s && rm -rf out_%s && "
        "%s/qnn-net-run --retrieve_context %s --backend %s/libQnnHtp.so "
        "--input_list il_%s.txt --output_dir out_%s --profiling_level detailed "
        "--native_input_tensor_names=%s 2>&1 | tail -4" % (Q, Q, D, outname, Q, bin, Q, tag, outname, graph)
    )
    print("=== %s ===" % tag)
    print(sh(["adb", "shell", "su", "-c", cmd]))
    raw = shb(["adb", "exec-out", "su", "-c", "cat %s/out_%s/qnn-profiling-data_0.log" % (D, outname)])
    dst = os.path.join(PC, "%s_prof.bin" % tag)
    open(dst, "wb").write(raw)
    print("  pulled", len(raw), "bytes ->", dst)

run("%s/hubert.bin" % D, "hb0.bin\n", "hubert_mix_def_t4800:source", "hub", "hubert")
run("%s/rmvpe.bin" % D, "mel.bin\n", "rmvpe_fp32_256:input", "rmv", "rmvpe")
