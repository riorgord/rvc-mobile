import subprocess

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

PKG = "/data/data/com.termux/files/home/bge_phone/runtime/phone_pkg"
D = "/data/local/tmp/qnn/jl_prof"

# 确认 phone_pkg 存在
print(sh(["adb", "shell", "su", "-c", "ls %s/bin/qnn-net-run %s/lib/libQnnHtp.so %s/lib/hexagon-v69/ 2>&1 | head -5" % (PKG, PKG, PKG)]))

run = (
    "export LD_LIBRARY_PATH=%s/lib:/vendor/dsp/cdsp:/vendor/lib64; "
    "export ADSP_LIBRARY_PATH=%s/lib/hexagon-v69; "
    "cd %s && rm -rf out && "
    "%s/bin/qnn-net-run "
    "--retrieve_context gen_fp32.bin --backend %s/lib/libQnnHtp.so "
    "--input_list input_list.txt --output_dir out "
    "--profiling_level detailed "
    "--native_input_tensor_names=gen_fp32:phone,phone_lengths,rnd,sine,speaker_emb,pitch_emb "
    "2>&1 | tail -10" % (PKG, PKG, D, PKG, PKG)
)
print("=== qnn-net-run detailed ===")
print(sh(["adb", "shell", "su", "-c", run]))
print("=== out ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/out/ && wc -c %s/out/qnn-profiling-data_0.log" % (D, D)]))
