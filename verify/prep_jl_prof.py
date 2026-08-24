import subprocess

def sh(parts, t=300):
    r = subprocess.run(parts, capture_output=True, timeout=t)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

A = "/data/user/0/com.rvc.app/files"
D = "/data/local/tmp/qnn/jl_prof"
Q = "/data/local/tmp/qnn"

# 1) 准备目录 + 复制资产
cmds = [
    "mkdir -p %s/out" % D,
    "cp %s/models/gen_fp32.bin %s/gen_fp32.bin" % (A, D),
    "cp %s/rvc_exp/full_phone.bin %s/phone.bin" % (A, D),
    "cp %s/rvc_exp/full_pitch_emb.bin %s/pitch_emb.bin" % (A, D),
    "cp %s/rvc_exp/full_sine.bin %s/sine.bin" % (A, D),
    "cp %s/testdata/gf_rnd.bin %s/rnd.bin" % (A, D),
    "cp %s/testdata/gf_speaker_emb.bin %s/speaker_emb.bin" % (A, D),
    # phone_lengths = [224] int32 = 4B (0xE0 00 00 00)
    "printf '\\xe0\\x00\\x00\\x00' > %s/phone_lengths.bin" % D,
]
for c in cmds:
    out = sh(["adb", "shell", "su", "-c", c])
    if out.strip():
        print(">>", c, "->", out.strip()[:80])

# 2) input_list.txt
il = (
    "phone:=%s/phone.bin\n"
    "pitch_emb:=%s/pitch_emb.bin\n"
    "phone_lengths:=%s/phone_lengths.bin\n"
    "rnd:=%s/rnd.bin\n"
    "speaker_emb:=%s/speaker_emb.bin\n"
    "sine:=%s/sine.bin\n" % (D, D, D, D, D, D)
)
import base64
b64 = base64.b64encode(il.encode()).decode()
print("input_list:", repr(il[:60]))
print(">> echo b64 -> file")
print(sh(["adb", "shell", "su", "-c", "echo %s | base64 -d > %s/input_list.txt" % (b64, D)]).strip())

# 3) 验证
print("=== 准备完成 ===")
print(sh(["adb", "shell", "su", "-c", "ls -la %s/" % D]))
print(sh(["adb", "shell", "su", "-c", "cat %s/input_list.txt" % D]))
