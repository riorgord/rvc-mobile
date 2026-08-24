import numpy as np, sys, torch, torch.nn.functional as F
PH = r"D:\AI\claw_repair\research\rvc\mobile_prep\qnn\phone_pipe"
hf = [np.fromfile(f"{PH}/pull_hf{k}.raw", np.float32).reshape(14, 768) for k in range(8)]
F50 = np.concatenate(hf, 0)[:112]
f50t = torch.from_numpy(F50)[None]
f = torch.cat((f50t, f50t[:, -1:, :]), 1)
f100 = F.interpolate(f.permute(0, 2, 1), scale_factor=2).permute(0, 2, 1)[:, :224]
phone_nwc = f100[0].numpy()
gf = np.fromfile(f"{PH}/gf_phone.bin", np.float32).reshape(1, 768, 224)
phone_dlc = phone_nwc[None].transpose(0, 2, 1)
print("torch vs gf  corr=%.6f maxerr=%.6f" % (
    np.corrcoef(phone_dlc.ravel(), gf.ravel())[0, 1],
    np.abs(phone_dlc.ravel() - gf.ravel()).max()))
# numpy vs torch
sys.path.insert(0, r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\python")
from rvc_periphery import _interp_linear
f_np = np.concatenate([F50, F50[-1:]], 0)
f100_np = _interp_linear(f_np, 2)[:224]
print("numpy vs torch corr=%.6f maxerr=%.6f" % (
    np.corrcoef(f100_np.ravel(), f100[0].numpy().ravel())[0, 1],
    np.abs(f100_np.ravel() - f100[0].numpy().ravel()).max()))
