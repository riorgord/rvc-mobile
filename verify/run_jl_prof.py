import subprocess, os

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

D = "/data/local/tmp/qnn/jl_prof"
PC = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl"

# 重写 input_list.txt 为 LF only (二进制写, 避免 CRLF)
il = ("phone:=%s/phone.bin\npitch_emb:=%s/pitch_emb.bin\n"
      "phone_lengths:=%s/phone_lengths.bin\nrnd:=%s/rnd.bin\n"
      "speaker_emb:=%s/speaker_emb.bin\nsine:=%s/sine.bin\n" % (D, D, D, D, D, D))
open(os.path.join(PC, "input_list.txt"), "wb").write(il.encode("ascii"))
print(sh(["adb", "push", os.path.join(PC, "input_list.txt"), "%s/input_list.txt" % D]).strip())

# 跑 qnn-net-run detailed profiling (root)
run = (
    "export ADSP_LIBRARY_PATH=/data/local/tmp/qnn; "
    "export LD_LIBRARY_PATH=/data/local/tmp/qnn:/system/lib64:/vendor/lib64; "
    "cd %s && rm -rf out && "
    "/data/local/tmp/qnn/qnn-net-run "
    "--retrieve_context gen_fp32.bin --backend libQnnHtp.so "
    "--input_list input_list.txt --output_dir out "
    "--profiling_level detailed "
    "--native_input_tensor_names=gen_fp32:phone,phone_lengths,rnd,sine,speaker_emb,pitch_emb "
    "2>&1 | tail -8" % D
)
print("=== qnn-net-run (detailed profiling) ===")
print(sh(["adb", "shell", "su", "-c", run]))
print("=== out 内容 ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/out/ && wc -c %s/out/qnn-profiling-data_0.log 2>/dev/null" % (D, D)]))
