import numpy as np, os, sys
import torch
import librosa
PH = r"D:\AI\claw_repair\research\rvc\mobile_prep\qnn\phone_pipe"
PER = r"D:\AI\claw_repair\research\rvc\rvc_app\android\app\src\main\assets\periphery"

# 0. mel_basis(librosa htk) 打包供 App 用
mb = librosa.filters.mel(sr=16000, n_fft=1024, n_mels=128, fmin=30, fmax=8000, htk=True)
mb = np.asarray(mb, np.float32)                       # [128,513]
mb.tofile(os.path.join(PER, "mel_basis.bin"))
print("mel_basis", mb.shape, "packed", mb.nbytes, "bytes")

a16 = np.load(os.path.join(PH, "gya_16k_224.npy"))    # [35840]
win = torch.hann_window(1024).numpy().astype(np.float32)  # 对称 hann (RMVPE 默认)


def stft_mag(x, n_fft=1024, hop=160, window=win):
    x = np.pad(x, (n_fft // 2, n_fft // 2), mode="reflect")
    frames = 1 + (len(x) - n_fft) // hop
    cols = []
    for f in range(frames):
        seg = x[f * hop:f * hop + n_fft] * window
        cols.append(np.abs(np.fft.rfft(seg, n_fft)))
    return np.stack(cols, 1)                            # [513, frames]


mag = stft_mag(a16)
mel = mb @ mag
logmel = np.log(np.clip(mel, 1e-5, None)).astype(np.float32)   # [128, T]
T = logmel.shape[1]
print("mel frames", T, "(torch golden 应为 225)")

pad = np.zeros((128, 256), np.float32)
pad[:, :T] = logmel
mel_t = pad.T                                            # [256,128]
ref = np.fromfile(os.path.join(PH, "gya_mel_t.bin"), np.float32).reshape(256, 128)
c = np.corrcoef(mel_t.ravel(), ref.ravel())[0, 1]
e = np.abs(mel_t.ravel() - ref.ravel()).max()
print("numpy mel_t vs golden corr=%.6f maxerr=%.6f" % (c, e))
print("dtype 差异: golden 由 fp16 存储转换,numpy fp32 计算")
