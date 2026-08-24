import numpy as np, json, sys
sys.path.insert(0, r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\python")
from rvc_periphery import *
PH = r"D:\AI\claw_repair\research\rvc\mobile_prep\qnn\phone_pipe"
ONX = r"D:\AI\claw_repair\research\rvc\mobile_prep\onnx"

# 1. phone 拼装(PC hubert 特征,corr 1.0 同手机数据)
hf = [np.fromfile(f"{PH}/pull_hf{k}.raw", np.float32).reshape(14, 768) for k in range(8)]
phone_nwc = hubert_assemble(hf)                       # [224,768]
gf_phone = np.fromfile(f"{PH}/gf_phone.bin", np.float32).reshape(1, 768, 224)
phone_dlc = phone_nwc[None].transpose(0, 2, 1)        # [1,768,224] NCW
print("phone  corr=%.6f maxerr=%.6f" % (
    np.corrcoef(phone_dlc.ravel(), gf_phone.ravel())[0, 1],
    np.abs(phone_dlc.ravel() - gf_phone.ravel()).max()))

# 2. f0 → coarse → pitch_emb
sal = np.fromfile(f"{PH}/pull_gya_rv.raw", np.float32).reshape(256, 360)
f0 = f0_decode(sal, thred=0.03)
print("f0 voiced=%d len=%d" % (int((f0 > 0).sum()), len(f0)))
coarse = f0_to_coarse(f0, 224)
emb = np.load(f"{ONX}/emb_params.npz")
pe_nwc = pitch_embedding(coarse[None], emb["emb_pitch_weight"])   # [1,224,192]
gf_pe = np.fromfile(f"{PH}/gf_pitch_emb.bin", np.float32).reshape(1, 192, 224)
pe_dlc = pe_nwc.transpose(0, 2, 1)                    # [1,192,224]
print("p_emb  corr=%.6f maxerr=%.6f" % (
    np.corrcoef(pe_dlc.ravel(), gf_pe.ravel())[0, 1],
    np.abs(pe_dlc.ravel() - gf_pe.ravel()).max()))

# 3. sine
params = json.load(open(f"{ONX}/sine_params.json", encoding="utf-8"))
nsff0 = f0[:224].astype(np.float32)[None]             # [1,224]
sine_nwc = sine_source(nsff0, params, seed=0, use_random=True)    # [1,89600,1]
gf_sine = np.fromfile(f"{PH}/gf_sine.bin", np.float32).reshape(1, 1, 89600)
sine_dlc = sine_nwc.transpose(0, 2, 1)                # [1,1,89600]
print("sine   corr=%.6f maxerr=%.6f" % (
    np.corrcoef(sine_dlc.ravel(), gf_sine.ravel())[0, 1],
    np.abs(sine_dlc.ravel() - gf_sine.ravel()).max()))

# 4. speaker_emb / rnd 是打包直接用的(gf_*.bin),跳过
