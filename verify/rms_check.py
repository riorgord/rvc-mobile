import numpy as np, torch, torch.nn.functional as F, librosa, sys
sys.path.insert(0, r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\python")
from rvc_periphery import change_rms as cr_np
TD = r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\assets\testdata"
PH = r"D:\AI\claw_repair\research\rvc\mobile_prep\qnn\phone_pipe"
inp = np.fromfile(TD + r"\gya_audio.raw", np.float32)
out = np.fromfile(PH + r"\gya_ref_audio.raw", np.float32)

# torch 版(RVC change_rms 原实现,rate=0.25)
rms1 = librosa.feature.rms(y=inp, frame_length=16000, hop_length=8000)
rms2 = librosa.feature.rms(y=out, frame_length=40000, hop_length=20000)
rms1 = torch.from_numpy(rms1); rms1 = F.interpolate(rms1.unsqueeze(0), size=len(out), mode="linear").squeeze()
rms2 = torch.from_numpy(rms2); rms2 = F.interpolate(rms2.unsqueeze(0), size=len(out), mode="linear").squeeze()
rms2 = torch.max(rms2, torch.zeros_like(rms2) + 1e-6)
res_t = (out * (torch.pow(rms1, torch.tensor(0.75)) * torch.pow(rms2, torch.tensor(-0.75))).numpy()).astype(np.float32)
res_np = cr_np(inp, 16000, out.copy(), 40000, 0.25).astype(np.float32)
print("rms-mix torch vs numpy corr=%.6f maxerr=%.6f" % (
    np.corrcoef(res_t, res_np)[0, 1], np.abs(res_t - res_np).max()))
print("out rms 前 %.4f / rms混合后 %.4f (输入 rms %.4f)" % (
    np.sqrt((out.astype(np.float64)**2).mean()), np.sqrt((res_t.astype(np.float64)**2).mean()),
    np.sqrt((inp.astype(np.float64)**2).mean())))
