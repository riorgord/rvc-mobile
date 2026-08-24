import subprocess

def sh(parts, t=180):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

cmd = (
    'cd /data/data/com.termux/files/home/rvc && '
    'export LD_LIBRARY_PATH="$PWD/lib:/vendor/dsp/cdsp:/vendor/lib64" && '
    'export ADSP_LIBRARY_PATH="$PWD/lib/hexagon-v69" && '
    'rm -rf out_prof && '
    './bin/qnn-net-run --retrieve_context models/gen_fp32.bin '
    '--backend lib/libQnnHtp.so --input_list test/generator/il32.txt '
    '--output_dir out_prof --profiling_level detailed 2>&1 | tail -5'
)
print("=== qnn-net-run profiling ===")
print(sh(["adb", "shell", "su", "-c", cmd]))
print("=== out_prof files ===")
print(sh(["adb", "shell", "su", "-c", "ls -la /data/data/com.termux/files/home/rvc/out_prof/"]))
