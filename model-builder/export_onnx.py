#!/usr/bin/env python3
# export_onnx.py —— 从 RVC 官方整合包微调好的 .pth 导出手机部署用的两个 ONNX：
#   z_producer.onnx : phone[1,W,768]+pitch_emb[1,W,192]+lengths+rnd[1,192,W]+g[1,256,1] -> z[1,192,W]
#   dec_short.onnx  : z[1,192,WDEC]+sine[1,1,WDEC*400]+g[1,256,1] -> audio[1,1,WDEC*400]
#
# 逻辑移植自 research/rvc/mobile_prep/stream/{dec_slice_exp,route2_split_verify}.py（已验证）。
#
# 依赖：torch / numpy / librosa / soundfile / onnxruntime（可选验证）
#       以及 RVC 官方整合包路径（提供 infer.lib.infer_pack.models.SynthesizerTrnMs768NSFsid）
#
# 用法：
#   python export_onnx.py --pth G_xxx.pth --ref-wav ref.wav --rvc-pkg <RVC包根目录> --out ./out
import argparse
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

UPP = 400
SEED = 0
WIN = 224          # z_producer 部署窗（编译固定）
B = 37             # dec 每块新帧（App 的 dec_short_t61：12过去+37新+12未来=61）
R = 12             # dec 左右 lookahead/lookback 帧

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)
sys.path.insert(0, os.path.join(_HERE, "scripts"))


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


def cos(a, b):
    a = a.reshape(-1)
    b = b.reshape(-1)
    return float((a @ b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))


class ZProducer(torch.nn.Module):
    """enc_p(TextEncoder, pitch 预嵌入) + flow -> z。与 App/gen 图契约一致(pitch_emb 输入)."""
    def __init__(self, enc_p, flow):
        super().__init__()
        self.emb_phone = enc_p.emb_phone
        self.lrelu = enc_p.lrelu
        self.encoder = enc_p.encoder
        self.proj = enc_p.proj
        self.out_channels = enc_p.out_channels
        self.hidden_channels = enc_p.hidden_channels
        self.flow = flow

    def forward(self, phone, pitch_emb, lengths, rnd, g):
        x = self.emb_phone(phone) + pitch_emb
        x = x * math.sqrt(self.hidden_channels)
        x = self.lrelu(x)
        x = torch.transpose(x, 1, -1)
        import sys
        from infer.lib.infer_pack import commons
        x_mask = torch.unsqueeze(commons.sequence_mask(lengths, x.size(2)), 1).to(x.dtype)
        x = self.encoder(x * x_mask, x_mask)
        stats = self.proj(x) * x_mask
        m, logs = torch.split(stats, self.out_channels, dim=1)
        z_p = (m + torch.exp(logs) * rnd) * x_mask
        z = self.flow(z_p, x_mask, g=g, reverse=True)
        return z * x_mask


class DecExt(torch.nn.Module):
    """GeneratorNSF forward, sine 为外部输入（共享 dec 全部子模块, 无 m_source）。"""
    def __init__(self, dec):
        super().__init__()
        self.conv_pre = dec.conv_pre
        self.cond = dec.cond
        self.ups = dec.ups
        self.noise_convs = dec.noise_convs
        self.resblocks = dec.resblocks
        self.conv_post = dec.conv_post
        self.num_kernels = dec.num_kernels
        self.num_upsamples = dec.num_upsamples
        self.lrelu_slope = dec.lrelu_slope
        self.upp = dec.upp

    def forward(self, x, har, g=None):
        # har_source 已按 [1,1,T*400] 传入（conv1d 期望 [B,C,T]）
        x = self.conv_pre(x)
        if g is not None:
            x = x + self.cond(g)
        for i, (ups, noise_convs) in enumerate(zip(self.ups, self.noise_convs)):
            if i < self.num_upsamples:
                x = F.leaky_relu(x, self.lrelu_slope)
                x = ups(x)
                x_source = noise_convs(har)
                x = x + x_source
                xs = None
                l = [i * self.num_kernels + j for j in range(self.num_kernels)]
                for j, resblock in enumerate(self.resblocks):
                    if j in l:
                        xs = resblock(x) if xs is None else xs + resblock(x)
                x = xs / self.num_kernels
        x = F.leaky_relu(x)
        x = self.conv_post(x)
        x = torch.tanh(x)
        return x


def main():
    ap = argparse.ArgumentParser(description="RVC .pth -> z_producer.onnx + dec_short.onnx")
    ap.add_argument("--pth", required=True, help="官方整合包微调好的 .pth")
    ap.add_argument("--ref-wav", required=True, help="参考音频(用于导出时的输入形状/特征)")
    ap.add_argument("--rvc-pkg", required=True, help="RVC 官方整合包根目录(含 infer/)")
    ap.add_argument("--out", required=True, help="输出目录")
    ap.add_argument("--hubert-pt", default=None, help="hubert_base.pt 路径(默认 <rvc-pkg>/assets/hubert/hubert_base.pt)")
    ap.add_argument("--rmvpe-pt", default=None, help="rmvpe.pt 路径(默认 <rvc-pkg>/assets/rmvpe/rmvpe.pt)")
    ap.add_argument("--win", type=int, default=224, help="z_producer 窗长(默认 224)")
    ap.add_argument("--B", type=int, default=37, help="dec 每块新帧(默认 37=App dec_short_t61)")
    ap.add_argument("--R", type=int, default=12, help="dec lookahead/lookback(默认 12)")
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--verify", action="store_true", help="用 onnxruntime 验证导出")
    args = ap.parse_args()

    DEV = args.device
    B, R = args.B, args.R
    WIN = args.win
    WDEC = B + 2 * R

    pkg = args.rvc_pkg
    sys.path.insert(0, pkg)
    from infer.lib.infer_pack.models import SynthesizerTrnMs768NSFsid
    from infer.lib.rmvpe import RMVPE
    import rvc_hubert as RvcHubertModule
    RvcHubert = RvcHubertModule.RvcHubert

    os.makedirs(args.out, exist_ok=True)
    torch.manual_seed(SEED)

    # ---------- 加载模型 ----------
    print("[1/4] 加载模型:", args.pth)
    cpt = torch.load(args.pth, map_location="cpu", weights_only=False)
    cfg = list(cpt["config"])
    cfg[-3] = cpt["weight"]["emb_g.weight"].shape[0]
    net = SynthesizerTrnMs768NSFsid(*cfg, is_half=False)
    del net.enc_q
    net.load_state_dict(cpt["weight"], strict=False)
    net = net.float().eval().to(DEV)
    net.remove_weight_norm()
    dec = net.dec
    print("      tgt_sr =", cfg[-1])

    # ---------- 特征 + f0 ----------
    print("[2/4] 计算 hubert 特征 + f0")
    import librosa
    import soundfile as sf
    x0, sr0 = sf.read(args.ref_wav, dtype="float32")
    a16 = librosa.resample(x0, orig_sr=sr0, target_sr=16000)
    p_len, nf50, P = target_p_len(a16)
    x = a16 if len(a16) >= P else np.pad(a16, (0, P - len(a16)))

    hubert_pt = args.hubert_pt or os.path.join(pkg, "assets", "hubert", "hubert_base.pt")
    rmvpe_pt = args.rmvpe_pt or os.path.join(pkg, "assets", "rmvpe", "rmvpe.pt")
    model = RvcHubert.from_fairseq(hubert_pt, pos_mode="symmetric").eval().to(DEV)
    xv = torch.from_numpy(x).float().to(DEV)[None]
    with torch.no_grad():
        A50 = model(xv, w_back=nf50, w_fwd=nf50).cpu().numpy()[0]

    rmvpe = RMVPE(rmvpe_pt, is_half=False, device=torch.device(DEV))
    xt = torch.from_numpy(x).float().to(DEV)
    with torch.no_grad():
        f0 = np.asarray(rmvpe.infer_from_audio(xt, thred=0.03))[:p_len].copy()
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

    # ---------- 导出 z_producer ----------
    print("[3/4] 导出 z_producer.onnx")
    with torch.no_grad():
        pitch_emb_full = net.enc_p.emb_pitch(coarse)
        noiseF = torch.randn_like(torch.zeros(1, 192, p_len, device=DEV)) * 0.66666
    zp = ZProducer(net.enc_p, net.flow).eval().to(DEV)
    phone_e = f[:, :WIN, :].contiguous()
    with torch.no_grad():
        pitch_emb_e = net.enc_p.emb_pitch(coarse[:, :WIN]).detach().contiguous()
    len_e = torch.LongTensor([WIN]).to(DEV)
    rnd_e = noiseF[:, :, :WIN].contiguous()
    p_zp = os.path.join(args.out, "z_producer.onnx")
    with torch.no_grad():
        torch.onnx.export(
            zp, (phone_e, pitch_emb_e, len_e, rnd_e, g), p_zp,
            input_names=["phone", "pitch_emb", "lengths", "rnd", "g"],
            output_names=["z"], opset_version=17, dynamic_axes=None)
    print("      ->", p_zp)

    # ---------- 导出 dec_short ----------
    print("[4/4] 导出 dec_short_T61.onnx")
    with torch.no_grad():
        mF, lgF, xmF = net.enc_p(f, coarse, lengths)
        zF = net.flow((mF + torch.exp(lgF) * noiseF) * xmF, xmF, g=g, reverse=True) * xmF
        har_full, _, _ = dec.m_source(nsff0, UPP)
    dec_ext = DecExt(dec).eval().to(DEV)
    lo, hi = 0, WDEC   # 导出只需要固定形状，取前 WDEC 帧即可
    zs = zF[:, :, lo:hi].contiguous()
    hs = har_full[:, lo * UPP:hi * UPP].transpose(1, 2).contiguous()  # [1,1,24400]
    p_dec = os.path.join(args.out, "dec_short_T61.onnx")
    with torch.no_grad():
        torch.onnx.export(
            dec_ext, (zs, hs, g), p_dec,
            input_names=["z", "sine", "g"], output_names=["audio"], opset_version=17)
    print("      ->", p_dec)

    # ---------- 图名对齐（App 按图名绑 tensor；dlc 文件名=图名，htp 配置才生效） ----------
    import onnx
    for path, gname in [(p_zp, "z_producer"), (p_dec, "dec_short_T61")]:
        mm = onnx.load(path)
        mm.graph.name = gname
        onnx.save(mm, path)
        print("      graph name ->", gname)

    # ---------- 保存角色常量（App testdata 布局） ----------
    # g: [256] 平铺；rnd: App 用 [1,224,192] 时间主序（DLC 输入布局）
    g_flat = g.detach().cpu().numpy().astype("<f4").reshape(-1)
    rnd_flat = noiseF[:, :, :WIN].transpose(1, 2).contiguous().cpu().numpy().astype("<f4").reshape(-1)
    with open(os.path.join(args.out, "g.bin"), "wb") as f:
        f.write(g_flat.tobytes())
    with open(os.path.join(args.out, "rnd.bin"), "wb") as f:
        f.write(rnd_flat.tobytes())
    print("      saved g.bin(%d) rnd.bin(%d)" % (g_flat.size, rnd_flat.size))

    # ---------- 可选验证 ----------
    if args.verify:
        print("[verify] onnxruntime 复现")
        import onnxruntime as ort
        with torch.no_grad():
            z_expected = zp(phone_e, pitch_emb_e, len_e, rnd_e, g).cpu().numpy()
        sess = ort.InferenceSession(p_zp, providers=["CPUExecutionProvider"])
        z_ort = sess.run(["z"], {"phone": phone_e.detach().cpu().numpy(),
                                 "pitch_emb": pitch_emb_e.detach().cpu().numpy(),
                                 "lengths": len_e.detach().cpu().numpy(),
                                 "rnd": rnd_e.detach().cpu().numpy(),
                                 "g": g.detach().cpu().numpy()})[0]
        print("      z_producer max|diff|=%.3e cos=%.6f" % (
            float(np.abs(z_ort - z_expected).max()), cos(z_ort, z_expected)))
        with torch.no_grad():
            a_expected = dec_ext(zs, hs, g).cpu().numpy()
        sess2 = ort.InferenceSession(p_dec, providers=["CPUExecutionProvider"])
        a_ort = sess2.run(["audio"], {"z": zs.detach().cpu().numpy(),
                                      "sine": hs.detach().cpu().numpy(),
                                      "g": g.detach().cpu().numpy()})[0]
        print("      dec_short max|diff|=%.3e cos=%.6f" % (
            float(np.abs(a_ort - a_expected).max()), cos(a_ort, a_expected)))

    print("[done] 导出完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
