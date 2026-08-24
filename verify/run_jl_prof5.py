import subprocess, os

def sh(parts, t=600):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

D = "/data/local/tmp/qnn/jl_prof"
Q = "/data/local/tmp/qnn"

# 重写 input_list: 纯文件名 空格分隔 (bin 输入顺序: phone, phone_lengths, rnd, sine, speaker_emb, pitch_emb)
il = "phone.bin phone_lengths.bin rnd.bin sine.bin speaker_emb.bin pitch_emb.bin\n"
PC = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl"
open(os.path.join(PC, "input_list.txt"), "wb").write(il.encode("ascii"))
print(sh(["adb", "push", os.path.join(PC, "input_list.txt"), "%s/input_list.txt" % D]).strip())

# chmod 所有输入 + bin 可读
print(sh(["adb", "shell", "su", "-c",
          "chmod 666 %s/gen_fp32.bin %s/phone.bin %s/pitch_emb.bin %s/rnd.bin %s/sine.bin %s/speaker_emb.bin %s/phone_lengths.bin %s/input_list.txt" % ((D,) * 8)]).strip() or "chmod ok")

run = (
    "export LD_LIBRARY_PATH=%s:/vendor/dsp/cdsp:/system/lib64:/vendor/lib64; "
    "export ADSP_LIBRARY_PATH=%s; "
    "cd %s && rm -rf out && "
    "%s/qnn-net-run --retrieve_context gen_fp32.bin --backend %s/libQnnHtp.so "
    "--input_list input_list.txt --output_dir out --profiling_level detailed "
    "--native_input_tensor_names=gen_fp32:phone,phone_lengths,rnd,sine,speaker_emb,pitch_emb "
    "2>&1 | tail -8" % (Q, Q, D, Q, Q)
)
print("=== qnn-net-run (修正 input_list) ===")
print(sh(["adb", "shell", "su", "-c", run]))
print("=== out ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/out/ && wc -c %s/out/qnn-profiling-data_0.log" % (D, D)]))
