import subprocess, struct, os, base64

def sh(parts, t=300):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

D = "/data/local/tmp/qnn/jl_prof"
PC = r"D:\AI\claw_repair\research\rvc\mobile_prep\profs\jl"
os.makedirs(PC, exist_ok=True)

# chmod 目录 (root)
print(sh(["adb", "shell", "su", "-c", "chmod 777 %s %s/out" % (D, D)]).strip() or "chmod ok")

# PC 生成 phone_lengths.bin = [224] int32 (4B) + input_list.txt
open(os.path.join(PC, "phone_lengths.bin"), "wb").write(struct.pack("<i", 224))
il = (
    "phone:=%s/phone.bin\n"
    "pitch_emb:=%s/pitch_emb.bin\n"
    "phone_lengths:=%s/phone_lengths.bin\n"
    "rnd:=%s/rnd.bin\n"
    "speaker_emb:=%s/speaker_emb.bin\n"
    "sine:=%s/sine.bin\n" % (D, D, D, D, D, D)
)
open(os.path.join(PC, "input_list.txt"), "w").write(il)

# push 到手机
for f in ["phone_lengths.bin", "input_list.txt"]:
    print(sh(["adb", "push", os.path.join(PC, f), "%s/%s" % (D, f)]).strip())

print("=== 验证 ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/ && cat %s/input_list.txt" % (D, D)]))
