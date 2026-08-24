import subprocess

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

D = "/data/local/tmp/qnn/jl_prof"
Q = "/data/local/tmp/qnn"

run = (
    "export LD_LIBRARY_PATH=%s:/vendor/dsp/cdsp:/system/lib64:/vendor/lib64; "
    "export ADSP_LIBRARY_PATH=%s; "
    "cd %s && rm -rf out && "
    "%s/qnn-net-run "
    "--retrieve_context gen_fp32.bin --backend %s/libQnnHtp.so "
    "--input_list input_list.txt --output_dir out "
    "--profiling_level detailed "
    "--native_input_tensor_names=gen_fp32:phone,phone_lengths,rnd,sine,speaker_emb,pitch_emb "
    "2>&1 | tail -10" % (Q, Q, D, Q, Q)
)
print("=== qnn-net-run detailed (cdsp added) ===")
print(sh(["adb", "shell", "su", "-c", run]))
print("=== out ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/out/ && wc -c %s/out/qnn-profiling-data_0.log" % (D, D)]))
