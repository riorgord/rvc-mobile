#!/usr/bin/env python3
# make_verify_inputs.py —— 生成手机 qnn-net-run 验证输入（App 原生布局）
# 产出（写到 --out）：
#   g.bin        [1,1,256]  平铺（App 喂法）
#   z61_%02d.bin [1,61,192] 时间主序（App 喂法）
#   s61_%02d.bin [1,1,24400] 平铺（C=1 无布局问题）
# 供 dec_short_T61 图新旧 bin 数值对比。
import argparse
import os
import sys

import numpy as np
import torch
import torch.nn.functional as F

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
sys.path.insert(0, os.path.join(_HERE, "scripts"))
import rvc_hubert as RvcHubertModule

UPP = 400
T, R, B, WIN = 224, 12, 37, 61


def hubert_frames(P):
    c = (P - 10) // 5 + 1
    for k in [(3, 2), (3, 2), (3, 2), (3, 2), (2, 2), (2, 2)]:
        c = (c - k[0]) // k[1] + 1
    return c


def target_p_len(a16):
    P = len(a16)
    while True:
        pl = P // 160
        if pl == 2 * hubert_frames(P):
            return pl, hubert_frames(P), P
        P += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pth", required=True)
    ap.add_argument("--ref-wav", required=True)
    ap.add_argument("--rvc-pkg", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--blocks", type=int, default=6)
    args = ap.parse_args()

    DEV = "cuda" if torch.cuda.is_available() else "cpu"
    pkg = args.rvc_pkg
    sys.path.insert(0, pkg)
    from infer.lib.infer_pack.models import SynthesizerTrnMs768NSFsid
    from infer.lib.rmvpe import RMVPE
    RvcHubert = RvcHubertModule.RvcHubert

    os.makedirs(args.out, exist_ok=True)
    cpt = torch.load(args.pth, map_location="cpu", weights_only=False)
    cfg = list(cpt["config"])
    cfg[-3] = cpt["weight"]["emb_g.weight"].shape[0]
    net = SynthesizerTrnMs768NSFsid(*cfg, is_half=False)
    del net.enc_q
    net.load_state_dict(cpt["weight"], strict=False)
    net = net.float().eval().to(DEV)
    net.remove_weight_norm()
    dec = net.dec

    import librosa
    import soundfile as sf
    x0, sr0 = sf.read(args.ref_wav, dtype="float32")
    a16 = librosa.resample(x0, orig_sr=sr0, target_sr=16000)
    p_len, nf50, P = target_p_len(a16)
    x = a16 if len(a16) >= P else np.pad(a16, (0, P - len(a16)))

    hubert_pt = os.path.join(pkg, "assets", "hubert", "hubert_base.pt")
    rmvpe_pt = os.path.join(pkg, "assets", "rmvpe", "rmvpe.pt")
    model = RvcHubert.from_fairseq(hubert_pt, pos_mode="symmetric").eval().to(DEV)
    with torch.no_grad():
        A50 = model(torch.from_numpy(x).float().to(DEV)[None],
                    w_back=nf50, w_fwd=nf50).cpu().numpy()[0]
    rmvpe = RMVPE(rmvpe_pt, is_half=False, device=torch.device(DEV))
    with torch.no_grad():
        f0 = np.asarray(rmvpe.infer_from_audio(torch.from_numpy(x).float().to(DEV),
                                               thred=0.03))[:p_len].copy()
    f0t = torch.from_numpy(f0).float().squeeze()
    f0_mel = 1127 * torch.log(1 + f0t / 700)
    mel_min = 1127 * np.log(1 + 50 / 700)
    mel_max = 1127 * np.log(1 + 1100 / 700)
    f0_mel[f0_mel > 0] = (f0_mel[f0_mel > 0] - mel_min) * 254 / (mel_max - mel_min) + 1
    f0_mel[f0_mel <= 1] = 1
    f0_mel[f0_mel > 255] = 255
    coarse = torch.round(f0_mel).long().clamp(1, 255).unsqueeze(0).to(DEV)
    nsff0 = f0t.unsqueeze(0).to(DEV)

    f = torch.from_numpy(A50).float().to(DEV)[None]
    f = torch.cat((f, f[:, -1:, :]), 1)
    f = F.interpolate(f.permute(0, 2, 1), scale_factor=2).permute(0, 2, 1)[:, :p_len]
    lengths = torch.LongTensor([p_len]).to(DEV)
    sid = torch.LongTensor([0]).to(DEV)
    g = net.emb_g(sid).unsqueeze(-1)

    with torch.no_grad():
        mF, lgF, xmF = net.enc_p(f, coarse, lengths)
        noiseF = torch.randn_like(mF) * 0.66666
        zF = net.flow((mF + torch.exp(lgF) * noiseF) * xmF, xmF, g=g, reverse=True) * xmF
        har_full, _, _ = dec.m_source(nsff0, UPP)   # [1,p_len*400,1]

    # pad like app live route
    z_pad = torch.cat([zF[:, :, :1].repeat(1, 1, R), zF[:, :, :T],
                       zF[:, :, -1:].repeat(1, 1, R + B)], 2)          # [1,192,285]
    sine_full = har_full[:, :T * UPP]                                  # [1,89600,1]
    sine_pad = torch.cat([sine_full[:, :400].repeat(1, R, 1), sine_full,
                          sine_full[:, -400:].repeat(1, R + B, 1)], 1)  # [1,114000,1]

    # ---------- z_producer 输入（App 原生布局） ----------
    with torch.no_grad():
        pitch_emb_full = net.enc_p.emb_pitch(coarse[:, :T])          # [1,224,192] 时间主序
    phone224 = torch.zeros(1, 768, T)
    phone224[:, :, :WIN] = f[:, :WIN, :].transpose(1, 2)             # [1,768,224] 通道主序
    pe224 = torch.zeros(1, 192, T)
    pe224[:, :, :WIN] = pitch_emb_full[:, :WIN, :].transpose(1, 2)   # [1,192,224] 通道主序
    rnd224 = noiseF[:, :, :T].transpose(1, 2).contiguous()           # [1,224,192] 时间主序
    lengths = np.array([WIN], np.int32)
    phone224.numpy().astype(np.float32).tofile(os.path.join(args.out, "phone.bin"))
    pe224.numpy().astype(np.float32).tofile(os.path.join(args.out, "pitch_emb.bin"))
    rnd224.detach().cpu().numpy().astype(np.float32).tofile(os.path.join(args.out, "rnd.bin"))
    lengths.tofile(os.path.join(args.out, "lengths.bin"))
    print("saved z_producer inputs phone/pe/rnd/lengths", flush=True)

    # g 写 [1,1,256]
    g.detach().cpu().numpy().astype(np.float32).reshape(1, 1, 256).tofile(os.path.join(args.out, "g.bin"))
    for i, s0 in enumerate(range(0, T, B)):
        if i >= args.blocks:
            break
        lo, hi = s0, s0 + WIN
        z61 = z_pad[:, :, lo:hi].permute(0, 2, 1).contiguous()         # [1,61,192] 时间主序
        s61 = sine_pad[:, lo * UPP:hi * UPP].contiguous()              # [1,1,24400]
        z61.detach().cpu().numpy().astype(np.float32).tofile(os.path.join(args.out, "z61_%02d.bin" % i))
        s61.detach().cpu().numpy().astype(np.float32).tofile(os.path.join(args.out, "s61_%02d.bin" % i))
        print("block %d z%s s%s" % (i, tuple(z61.shape), tuple(s61.shape)), flush=True)
    print("saved ->", args.out, flush=True)


if __name__ == "__main__":
    main()
