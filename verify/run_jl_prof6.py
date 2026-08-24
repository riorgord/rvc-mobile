import subprocess, os

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

D = "/data/local/tmp/qnn/jl_prof"
Q = "/data/local/tmp/qnn"
PC = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl"

# bin 输入顺序 (App dict 顺序): phone, pitch_emb, phone_lengths, rnd, speaker_emb, sine
il = "phone.bin pitch_emb.bin phone_lengths.bin rnd.bin speaker_emb.bin sine.bin\n"
open(os.path.join(PC, "input_list.txt"), "wb").write(il.encode("ascii"))
print(sh(["adb", "push", os.path.join(PC, "input_list.txt"), "%s/input_list.txt" % D]).strip())

run = (
    "export LD_LIBRARY_PATH=%s:/vendor/dsp/cdsp:/system/lib64:/vendor/lib64; "
    "export ADSP_LIBRARY_PATH=%s; "
    "cd %s && rm -rf out && "
    "%s/qnn-net-run --retrieve_context gen_fp32.bin --backend %s/libQnnHtp.so "
    "--input_list input_list.txt --output_dir out --profiling_level detailed "
    "--native_input_tensor_names=gen_fp32:phone,pitch_emb,phone_lengths,rnd,speaker_emb,sine "
    "2>&1 | tail -8" % (Q, Q, D, Q, Q)
)
print("=== qnn-net-run (App dict order) ===")
print(sh(["adb", "shell", "su", "-c", run]))
print("=== out ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/out/ && wc -c %s/out/qnn-profiling-data_0.log" % (D, D)]))
