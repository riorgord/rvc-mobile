# -*- coding: utf-8 -*-
"""RVC 全链路外围(numpy,零 torch/librosa 依赖)——PC 与 App 共用。

复刻 gen_full_inputs.py / assemble_inputs.py / infer.rmvpe.decode 的数值逻辑,
供 App 内从 NPU 模型输出组装 net_g 输入。
"""
import numpy as np


# ---------------- hubert 特征拼装 ----------------
def _interp_nearest(x, scale_factor=2):
    """F.interpolate(scale_factor=2, mode='nearest') 的 numpy 版(gen_full_inputs.py 用的默认 mode)。
    torch 坐标: src = (i+0.5)/scale - 0.5, index = round(src), clamp 到 [0, in_len-1]。"""
    in_len, c = x.shape
    out_len = in_len * scale_factor
    scale = float(scale_factor)
    y = np.empty((out_len, c), dtype=x.dtype)
    for i in range(out_len):
        src = (i + 0.5) / scale - 0.5
        if src < 0:
            src = 0.0
        idx = int(round(src))
        if idx >= in_len:
            idx = in_len - 1
        y[i] = x[idx]
    return y


def hubert_assemble(feats8, p_len=224):
    """8 块 hubert 输出 [14,768] → F50 [112,768] → cat 末帧 → interp×2 → [:224] → phone_nwc [224,768]"""
    F50 = np.concatenate(feats8, axis=0)[:112]                # [112,768]
    f = np.concatenate([F50, F50[-1:]], axis=0)               # [113,768]
    f100 = _interp_nearest(f, 2)[:p_len]                      # [224,768]
    return f100.astype(np.float32)


# ---------------- hubert 实时切块 ----------------
def hubert_blocks(audio, blk=4800, hop=4480, fr=14, frame_hop=320):
    """整段 16k 音频切 hubert 输入块(phone_hubert_feats.py 逻辑)。
    nf50=floor(len/320), nblocks=ceil(nf50/14), 尾部 pad 到 (nblocks-1)*4480+4800。
    → ([blk 数组...], nf50)"""
    nf50 = int(np.floor(len(audio) / frame_hop))
    nblocks = int(np.ceil(nf50 / fr))
    P = (nblocks - 1) * hop + blk
    if P > len(audio):
        audio = np.pad(audio, (0, P - len(audio)))
    return [np.ascontiguousarray(audio[k * hop:k * hop + blk], np.float32)
            for k in range(nblocks)], nf50


def hubert_blocks_at(audio, k, blk=4800, hop=4480):
    """第 k 个 hubert 输入块(增量): audio[k*hop : k*hop+blk], 尾部 pad 到 blk。
    与 hubert_blocks 的块窗完全一致(固定窗不滑), 供真流式按需逐块算 14 帧 F50。"""
    start = k * hop
    seg = audio[start:start + blk]
    if len(seg) < blk:
        seg = np.pad(seg, (0, blk - len(seg)))
    return np.ascontiguousarray(seg, np.float32)


# ---------------- f0 解码(salience → f0) ----------------
def f0_decode(sal, thred=0.03):
    """rmvpe.decode + to_local_average_cents(numpy 复刻)。sal: [T,360] → f0 [T]"""
    cents_mapping = 20 * np.arange(360) + 1997.3794084376191
    cents_mapping = np.pad(cents_mapping, (4, 4))             # [368]
    center = np.argmax(sal, axis=1)                           # [T]
    sal_p = np.pad(sal, ((0, 0), (4, 4)))                     # [T,368]
    center = center + 4
    starts = center - 4
    ends = center + 5
    T = sal.shape[0]
    todo_sal = np.array([sal_p[i, starts[i]:ends[i]] for i in range(T)])
    todo_cents = np.array([cents_mapping[starts[i]:ends[i]] for i in range(T)])
    devided = np.sum(todo_sal * todo_cents, 1) / np.sum(todo_sal, 1)
    maxx = np.max(sal_p, axis=1)
    devided[maxx <= thred] = 0
    f0 = 10 * (2 ** (devided / 1200))
    f0[f0 == 10] = 0
    return f0


def fcpe_decode(latent, cent_table, threshold=0.006):
    """torchfcpe latent2cents_local_decoder + cent_to_f0(numpy 版)。
    latent [T,360](已 sigmoid)→ f0 [T](unvoiced=0)。local_argmax 9 窗加权均值。"""
    T = latent.shape[0]
    confident = latent.max(axis=-1)
    max_index = latent.argmax(axis=-1)
    local_idx = (np.arange(9) + (max_index[:, None] - 4)).clip(0, 359)
    ci_l = cent_table[local_idx]                              # [T,9]
    y_l = np.take_along_axis(latent, local_idx, axis=-1)      # [T,9]
    cents = (ci_l * y_l).sum(-1) / (y_l.sum(-1) + 1e-8)       # [T]
    f0 = 10 * 2 ** (cents / 1200.0)
    f0[confident <= threshold] = 0
    return f0


def f0_to_coarse(f0, p_len=224):
    """f0 [T] → coarse [p_len] int64(1..255)"""
    f0 = f0[:p_len]
    f0_mel = 1127 * np.log(1 + f0 / 700.0)
    mel_min = 1127 * np.log(1 + 50 / 700.0)
    mel_max = 1127 * np.log(1 + 1100 / 700.0)
    mask = f0_mel > 0
    f0_mel[mask] = (f0_mel[mask] - mel_min) * 254.0 / (mel_max - mel_min) + 1
    f0_mel[f0_mel <= 1] = 1
    f0_mel[f0_mel > 255] = 255
    coarse = np.round(f0_mel).astype(np.int64)
    coarse = np.clip(coarse, 1, 255)
    return coarse


# ---------------- mel 提取(RMVPE) ----------------
def mel_extract(audio, mel_basis, n_fft=1024, hop=160, clamp=1e-5):
    """RMVPE.mel_extractor numpy 版:对称 hann + torch.stft(center=True, reflect pad)
    + mel_basis(htk) + log(clamp 1e-5)。audio [T] → logmel [128, frames] fp32"""
    win = np.hanning(n_fft).astype(np.float32)          # 对称 hann = torch.hann_window 默认
    x = np.pad(audio, (n_fft // 2, n_fft // 2), mode="reflect")
    frames = 1 + (len(x) - n_fft) // hop
    cols = []
    for f in range(frames):
        seg = x[f * hop:f * hop + n_fft] * win
        cols.append(np.abs(np.fft.rfft(seg, n_fft)))
    mag = np.stack(cols, 1)                              # [n_fft/2+1, frames]
    mel = mel_basis @ mag
    return np.log(np.clip(mel, clamp, None)).astype(np.float32)


def mel_dlc(audio, mel_basis, n_frames=256, n_fft=1024, hop=160):
    """音频 → dlc 输入 [1,256,128] fp32(尾部补零到 n_frames=256,与 golden 一致)"""
    logmel = mel_extract(audio, mel_basis, n_fft, hop)
    T = logmel.shape[1]
    pad = np.zeros((128, n_frames), np.float32)
    m = min(T, n_frames)
    pad[:, :m] = logmel[:, :m]
    return pad.T.astype(np.float32)[None]                # [1,256,128]


# ---------------- RVC GUI 后处理(f0 插值/变调/rms 混合) ----------------
def interp_linear_1d(x, out_len):
    """F.interpolate(size=out_len, mode='linear', align_corners=False) numpy 向量化版。
    x [in_len] → [out_len](坐标 src=(i+0.5)*in_len/out_len-0.5,clamp)。
    原 Python 循环(178880 次)→ 全向量化 gather。"""
    in_len = x.shape[0]
    scale = in_len / out_len
    pos = (np.arange(out_len, dtype=np.float64) + 0.5) * scale - 0.5
    pos = np.maximum(pos, 0.0)
    a = np.floor(pos).astype(np.int64)
    frac = pos - a
    safe_a = np.minimum(a, in_len - 2)
    y = np.where(a >= in_len - 1, x[-1],
                 x[safe_a] * (1 - frac) + x[safe_a + 1] * frac)
    return y


def f0_uv_interp(f0, silence_frames=20, fade=3):
    """RVC 邻近线性插值 + 长静音归零 + 边界渐变:
    1) f0=0 的帧先用邻近有声值线性插值填充(RVC 官方逻辑);
    2) 连续 unvoiced ≥ silence_frames(默认20=200ms@10ms/帧) 判定为停顿/静音 → 归零真静音,
       治"停顿嗯嗯"(静音段被插值成非零 f0 → sine 持续振荡);
    3) 归零段首/尾各 fade 帧(默认3=30ms) 用线性渐变过渡:
       段尾从 0 渐升到段后有声值, 段首从段前有声值渐降到 0 →
       避免"0→有声"硬阶跃(治刺耳), 兼顾嗯嗯与阶跃。"""
    uv = f0 == 0
    if uv.any():
        out = f0.copy()
        if (~uv).any():
            out[uv] = np.interp(np.where(uv)[0], np.where(~uv)[0], f0[~uv])
        # 连续 unvoiced 游程 ≥ silence_frames 的段强制归零 + 边界渐变
        idx = np.where(uv)[0]
        if len(idx) > 0:
            breaks = np.where(np.diff(idx) > 1)[0]
            for run in np.split(idx, breaks + 1):
                if len(run) >= silence_frames:
                    r0, r1 = run[0], run[-1]
                    out[run] = 0.0
                    # 段尾淡入: r1 后 fade 帧, 从 0 渐升到段后有值
                    if r1 + 1 < len(out):
                        target = out[r1 + 1]
                        for k in range(1, fade + 1):
                            j = r1 + k
                            if j >= len(out):
                                break
                            out[j] = target * k / (fade + 1)
                    # 段首淡出: r0 前 fade 帧, 从段前值渐降到 0
                    if r0 > 0:
                        prev = out[r0 - 1]
                        for k in range(1, fade + 1):
                            j = r0 - k
                            if j < 0:
                                break
                            out[j] = prev * (fade + 1 - k) / (fade + 1)
        return out
    return f0.copy()


def _rms(y, frame_length, hop_length):
    """librosa.feature.rms(center=True, pad 0) numpy 版。
    O(n) 前缀和:对 x² 做 cumsum,滑窗和=前缀差(避免大索引 gather,手机上快)。"""
    pad = frame_length // 2
    yp = np.pad(y, (pad, pad), mode="constant")
    n = len(yp)
    frames = 1 + (n - frame_length) // hop_length
    x2 = yp.astype(np.float64) ** 2
    c = np.empty(n + 1, dtype=np.float64)
    c[0] = 0.0
    np.cumsum(x2, out=c[1:])
    starts = np.arange(frames, dtype=np.int64) * hop_length
    sums = c[starts + frame_length] - c[starts]
    return np.sqrt(sums / frame_length)


def change_rms(inp, sr1, out, sr2, rate=0.25):
    """RVC change_rms:输出 RMS 包络按 (1-rate) 拉向输入(保留原声动态)。
    inp [T1] fp32(16k), out [T2] fp32(40k), rate=输出占比(默认 0.25)。"""
    rms1 = _rms(inp, frame_length=sr1 // 2 * 2, hop_length=sr1 // 2)
    rms2 = _rms(out, frame_length=sr2 // 2 * 2, hop_length=sr2 // 2)
    n = len(out)
    rms1i = interp_linear_1d(rms1, n)
    rms2i = interp_linear_1d(rms2, n)
    rms2i = np.maximum(rms2i, 1e-6)
    return out * (np.power(rms1i, 1.0 - rate) * np.power(rms2i, rate - 1.0))


# ---------------- index 音色索引(RVC index_rate) ----------------
def index_mix(feats, idx_vecs, rate, k=8, proj=None, idx_full=None, v2=None):
    """feats [T,768] → 角色特征库 L2 最近邻 top-k 加权混合 → [T,768]。
    兼容两种模式:
      proj=None(默认):768 维精确 L2(保真)。v2 传入预计算索引平方和(常量缓存,
                       避免每轮对 idx 逐元素平方 300ms)。
      proj[768,D]+idx_full:降维搜索(256 维)找最近邻行号,原始库加权混合。
    """
    T = feats.shape[0]
    if proj is not None and idx_full is not None:
        f_se = (feats @ proj).astype(np.float32)          # [T,D] 搜索特征
        f2 = np.sum(f_se * f_se, axis=1, keepdims=True)
        if v2 is None:
            v2 = np.sum(idx_vecs * idx_vecs, axis=1)
        d2 = f2 + v2[None, :] - 2.0 * (f_se @ idx_vecs.T)
    else:
        f2 = np.sum(feats * feats, axis=1, keepdims=True)
        if v2 is None:
            v2 = np.sum(idx_vecs * idx_vecs, axis=1)
        d2 = f2 + v2[None, :] - 2.0 * (feats @ idx_vecs.T)
    kk = min(k, idx_vecs.shape[0])
    part = np.argpartition(d2, kk - 1, axis=1)[:, :kk]          # [T,kk]
    score = -d2[np.arange(T)[:, None], part]                    # faiss L2 负距离
    w = np.square(1.0 / (score + 1e-6))
    w /= w.sum(axis=1, keepdims=True)
    if idx_full is not None:
        npy = np.sum(idx_full[part] * w[:, :, None], axis=1)    # [T,768] 原始混合
    else:
        npy = np.sum(idx_vecs[part] * w[:, :, None], axis=1)    # [T,D]
    # RVC: feats = npy*index_rate + (1-index_rate)*feats (index 占比=rate)
    return (npy * rate + feats * (1.0 - rate)).astype(np.float32)


# ---------------- 查表 / sine ----------------
def pitch_embedding(coarse, emb_weight):
    """coarse [1,T] int64, emb_weight [256,192] → [1,T,192] fp32"""
    return np.asarray(emb_weight, dtype=np.float32)[coarse]


def speaker_embedding(sid, emb_weight):
    """sid int, emb_weight [109,256] → [1,1,256] fp32"""
    return np.asarray(emb_weight, dtype=np.float32)[np.asarray(sid, np.int64)].reshape(1, 1, -1)


def sine_source(pitchf, params, seed=0, use_random=True):
    """pitchf [1,T] fp32, params(dict from sine_params.json) → sine [1,T*upp,1] fp32。
    复刻 SineGen._f02sine + SourceModuleHnNSF(l_linear+tanh)。"""
    sr = float(params["sampling_rate"])
    sine_amp = float(params["sine_amp"])
    noise_std = float(params["noise_std"])
    threshold = float(params["voiced_threshold"])
    upp = int(params["upp"])
    dim = int(params.get("harmonic_num", 0)) + 1
    w = np.asarray(params["linear_weight"], dtype=np.float64).reshape(1, 1)
    b = np.asarray(params["linear_bias"], dtype=np.float64).reshape(1)
    rng = np.random.RandomState(seed)
    f0 = np.asarray(pitchf, dtype=np.float64)
    T = f0.shape[1]
    f0b = f0[:, None].transpose(0, 2, 1)                       # [1,T,1]
    ar = np.arange(1, upp + 1, dtype=np.float64)
    rad = f0b / sr * ar[None, None, :]                         # [1,T,upp]
    rad2 = np.fmod(rad[..., -1:] + 0.5, 1.0) - 0.5             # [1,T,1]
    rad_acc = np.fmod(np.cumsum(rad2, axis=1), 1.0)
    padded = np.concatenate([np.zeros_like(rad_acc[:, :1]), rad_acc[:, :-1]], axis=1)
    rad = rad + padded
    rad = rad.reshape(1, -1, 1)
    rad = rad * np.arange(1, dim + 1, dtype=np.float64).reshape(1, 1, -1)
    if use_random:
        rand_ini = rng.rand(1, 1, dim)
        rand_ini[..., 0] = 0
        rad = rad + rand_ini
    sine_wavs = np.sin(2 * np.pi * rad) * sine_amp             # [1,T*upp,dim]
    uv = (f0b > threshold).astype(np.float64)
    uv = np.repeat(uv, upp, axis=1)
    noise_amp = uv * noise_std + (1 - uv) * sine_amp / 3.0
    if use_random:
        noise = noise_amp * rng.randn(1, T * upp, dim)
    else:
        noise = np.zeros_like(sine_wavs)
    sine_wavs = sine_wavs * uv + noise
    sine_merge = (sine_wavs @ w.T + b).reshape(1, T * upp, 1)
    sine_merge = np.tanh(sine_merge)
    return sine_merge.astype(np.float32)


def _hann(n):
    """n 点 Hann (非周期窗, 同 PC istft)。"""
    return np.hanning(n + 1)[:n].astype(np.float32)


def istft_numpy(mag, phase, istft_filter, istft_hop, length=None):
    """纯 numpy iSTFT (OLA, np.bincount 折叠, 无需 BLAS)。同 newdec/istft_phone.py。
    mag, phase: [B, n_bins, T_hop] float32 -> wav [B, 1, T_audio]。
    用于 iSTFT dec(输出 mag/phase) 在手机 CPU 展开成波形。"""
    mag = np.asarray(mag, dtype=np.float32)
    phase = np.asarray(phase, dtype=np.float32)
    B, n_bins, T_hop = mag.shape
    n_fft = istft_filter
    n_freq = n_fft // 2 + 1
    assert n_bins == n_freq, "n_bins=%d n_freq=%d" % (n_bins, n_freq)
    pad = n_fft // 2
    spec = mag * np.exp(1j * phase)
    spec_p = np.pad(spec, ((0, 0), (0, 0), (pad, pad)))
    frames = np.fft.irfft(spec_p, n=n_fft, axis=1)
    w = _hann(n_fft)
    frames = frames * w[None, :, None]
    Tp = frames.shape[-1]
    y_len = (Tp - 1) * istft_hop + n_fft
    idx = np.arange(Tp)[None, :] * istft_hop + np.arange(n_fft)[:, None]  # [n_fft, Tp]
    w2 = (w * w)[:, None]
    w2f = np.broadcast_to(w2, (n_fft, Tp))
    out = np.zeros((B, y_len), dtype=np.float64)
    for b in range(B):
        y = np.bincount(idx.ravel(), weights=frames[b].ravel(), minlength=y_len)
        ws = np.bincount(idx.ravel(), weights=w2f.ravel(), minlength=y_len)
        out[b] = y[:y_len] / np.maximum(ws[:y_len], 1e-8)
    out_len = T_hop * istft_hop if length is None else length
    y_out = out[:, pad * istft_hop: pad * istft_hop + out_len]
    return y_out[:, None, :].astype(np.float32)  # [B, 1, T_audio]
def brightness_apply(x, amount=0.0):
    """亮度/高频补偿: amount 0..1 (UI slider 0~100 -> /100)。
    y = x + 0.5*amount*highpass(x); 一阶高通(alpha=0.85, ~2.4kHz@40k 拐点)。
    默认 0 完全不变(泛用安全); 上限约 +3.5dB 高频增强, 不动低频。
    """
    if amount <= 0:
        return np.asarray(x, np.float32)
    alpha = np.float32(0.85)
    x = np.asarray(x, np.float32)
    hp = np.empty_like(x)
    prev = np.float32(0.0)
    hp[0] = np.float32(0.0)
    for i in range(1, x.size):
        prev = alpha * (prev + x[i] - x[i - 1])
        hp[i] = prev
    return x + np.float32(0.5 * amount) * hp
