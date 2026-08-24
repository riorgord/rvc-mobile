import subprocess

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

Q = "/data/local/tmp/qnn"
D = "/data/local/tmp/qnn/jl_prof"
G = "/data/local/tmp/qnn/rvc_test/generator"

def run_net(bin_path, il_path, graph, outdir, cwd):
    return (
        "export LD_LIBRARY_PATH=%s:/vendor/dsp/cdsp:/system/lib64:/vendor/lib64; "
        "export ADSP_LIBRARY_PATH=%s; "
        "cd %s && rm -rf %s && "
        "%s/qnn-net-run --retrieve_context %s --backend %s/libQnnHtp.so "
        "--input_list %s --output_dir %s --profiling_level detailed "
        "--native_input_tensor_names=%s:phone,phone_lengths,rnd,sine,speaker_emb,pitch_emb "
        "2>&1 | tail -6" % (Q, Q, cwd, outdir, Q, bin_path, Q, il_path, outdir, graph)
    )

# 1) chmod 666 jielaide bin + 重跑
print(sh(["adb", "shell", "su", "-c", "chmod 666 %s/gen_fp32.bin" % D]).strip() or "chmod ok")
print("=== A: jielaide gen_fp32.bin ===")
print(sh(["adb", "shell", "su", "-c", run_net("%s/gen_fp32.bin" % D, "%s/input_list.txt" % D,
                                             "gen_fp32", "%s/out" % D, D)]))

# 2) 已知能加载的 gen_htp.bin.bin (naiqiawang fp16)
print("=== B: gen_htp.bin.bin (已知能加载) ===")
print(sh(["adb", "shell", "su", "-c", run_net("%s/gen_htp.bin.bin" % G, "%s/input_list.txt" % G,
                                             "gen_htp", "%s/out_compare" % D, G)]))
