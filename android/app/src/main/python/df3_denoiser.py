# -*- coding: utf-8 -*-
"""DF3Denoiser: onnxruntime(gsv_ort) 跑 DeepFilterNet3 实时降噪。
流程(用户定案, 精简版): 录音 48k → DF3 降噪(48k, T18 含 2 帧 lookahead) → 下采样 16k 给 RVC。
块: 输入 9120 = 前480(上一块尾/首块0) + 7680(160ms) + 960(20ms lookahead);
    stft 18 帧(16 有效 + 2 lookahead) → 特征(EMA 跨块连续) → T18 onnx → istft 仅 16 有效帧(OLA mem 跨块连续)
    → 输出 7680 (48k) → 下采样 2560 (16k), 与输入块精确对齐无偏移。
"""
import os
import numpy as np

SR48, FFT, HOP, NB_ERB, NB_DF, ALPHA = 48000, 960, 480, 32, 96, 0.990
BLOCK48 = 7680          # 160ms @ 48k (16 帧)
FUTURE48 = 960          # 20ms lookahead @ 48k (2 帧)
PREV48 = 480            # 上一块尾 480 (块间 STFT 窗口连续)
NEEDED = PREV48 + BLOCK48 + FUTURE48   # 9120
T_FRAMES = BLOCK48 // HOP              # 16
T_IN = T_FRAMES + 2                    # 18 (含 lookahead)

_n = np.arange(FFT)
_sin1 = np.sin(np.pi * (_n + 0.5) / FFT)
WINDOW = np.sin(0.5 * np.pi * _sin1 * _sin1).astype(np.float32)
WNORM = np.float32(1.0 / (FFT ** 2 / (2.0 * HOP)))


def _freq2erb(f):
    return 9.265 * np.log1p(f / (24.7 * 9.265))


def _erb2freq(e):
    return 24.7 * 9.265 * (np.exp(e / 9.265) - 1)


def _erb_fb(sr, fft_size, nb_bands, min_nb_freqs):
    nyq = sr / 2
    freq_width = sr / fft_size
    erb_low = _freq2erb(0.0)
    erb_high = _freq2erb(nyq)
    step = (erb_high - erb_low) / nb_bands
    erb = []
    prev_freq = 0
    freq_over = 0
    for i in range(1, nb_bands + 1):
        f = _erb2freq(erb_low + i * step)
        fb = int(np.floor(f / freq_width + 0.5))
        nf = fb - prev_freq - freq_over
        if nf < min_nb_freqs:
            freq_over = min_nb_freqs - nf
            nf = min_nb_freqs
        else:
            freq_over = 0
        erb.append(int(nf))
        prev_freq = fb
    erb[-1] += 1
    too_large = sum(erb) - (fft_size // 2 + 1)
    if too_large > 0:
        erb[-1] -= too_large
    return np.array(erb, dtype=np.int32)


ERB_FB = _erb_fb(SR48, FFT, NB_ERB, 2)


def _down3(x):
    """48k -> 16k (3x 抽取, 相位对齐)。DF3 降噪输出 0-8k 语音带主导, 直接抽取可接受。"""
    return x[::3].copy()


def _stft(audio48, T):
    """audio48: (T*480+480,) 已含前 480(帧0窗口起点)。帧 i 窗口 = audio48[i*480 : i*480+960]。"""
    frames = np.empty((T, FFT), np.float32)
    for i in range(T):
        s = i * HOP
        frames[i] = audio48[s:s + FFT]
    frames *= WINDOW
    return (np.fft.rfft(frames, n=FFT, axis=1).astype(np.complex64) * WNORM)


def _erb_feat_state(spec, state):
    """state: (32,) float32, 块间持续 (初始 linspace(-60,-90,32))。"""
    T = spec.shape[0]
    out = np.zeros((T, NB_ERB), np.float32)
    bc = 0
    for b, sz in enumerate(ERB_FB):
        seg = spec[:, bc:bc + sz]
        out[:, b] = (seg.real ** 2 + seg.imag ** 2).sum(1) / sz
        bc += sz
    out = (np.log10(out + 1e-10) * 10).astype(np.float32)
    for t in range(T):
        state[:] = out[t] * (1 - ALPHA) + state * ALPHA
        out[t] = (out[t] - state) / 40.0
    return out


def _fspec_feat_state(spec, state):
    """state: (96,) float32, 块间持续 (初始 linspace(0.001,0.0001,96))。"""
    T = spec.shape[0]
    out = spec[:, :NB_DF].copy()
    for t in range(T):
        state[:] = np.abs(out[t]) * (1 - ALPHA) + state * ALPHA
        out[t] /= np.sqrt(state)
    return out


def _istft_state(spec_e, mem):
    """OLA 流式 istft, mem (480,) 块间持续。返回 (out, mems) —— mems[t] = 处理帧 t 后的 mem。
    libdf synthesis 输出相对输入延迟 480: 帧 t 输出 ≈ 输入帧 t-1。
    因此块输出需裁剪 [480:480+7680](帧1-16) 才与本块对齐; mem 传递用 mems[T_FRAMES-1](有效帧15后)。"""
    T = spec_e.shape[0]
    x = (np.fft.irfft(spec_e, n=FFT, axis=1) * FFT).astype(np.float32) * WINDOW
    out = np.empty((T * HOP,), np.float32)
    mems = [None] * T
    for t in range(T):
        out[t * HOP:(t + 1) * HOP] = x[t, :HOP] + mem
        mem = x[t, HOP:].copy()
        mems[t] = mem
    return out, mems


class DF3Denoiser:
    """gsv_ort(onnxruntime ctypes) + DeepFilterNet3 T18 onnx 实时降噪。
    接口: process(x48k) 收 48k 音频块 → 输出 16k 降噪块(给 RVC)。真正流式, 块间状态连续。
    """

    def __init__(self, native_lib_dir, files_dir, model_name="df3r_T18_emb.onnx"):
        os.environ["GSV_ORT_LIB"] = os.path.join(native_lib_dir, "libonnxruntime.so")
        import gsv_ort
        self._sess = gsv_ort.InferenceSession(os.path.join(files_dir, "models", model_name))
        self.acc = np.zeros(PREV48, np.float32)   # 48k 累积, 预填 480 (首块前帧 mem 0)
        self._erb_state = np.linspace(-60.0, -90.0, NB_ERB, dtype=np.float32)
        self._fspec_state = np.linspace(0.001, 0.0001, NB_DF, dtype=np.float32)
        self._istft_mem = np.zeros(HOP, np.float32)

    def process(self, x48k):
        """输入 48k float 块 → 降噪 16k 块。累积够 9120(160ms+前480+20ms lookahead) 才处理。"""
        x48k = np.asarray(x48k, np.float32)
        if len(x48k) == 0:
            return np.zeros(0, np.float32)
        self.acc = np.concatenate([self.acc, x48k])
        if len(self.acc) < NEEDED:
            return np.zeros(0, np.float32)
        blk = self.acc[:NEEDED]              # 9120
        self.acc = self.acc[BLOCK48:]        # 保留 1440 (下块前480 + 未来960)
        return self._process_block(blk)      # 返回 2560 (16k)

    def _process_block(self, x48):
        """x48: (9120,) 48k = 前480 + 7680 本块 + 960 lookahead → 输出 2560 (16k)"""
        # 18 帧 STFT (16 有效 + 2 lookahead)
        spec = _stft(x48, T_IN)
        # 特征 (EMA 跨块连续)
        erb_f = _erb_feat_state(spec, self._erb_state)
        fspec_f = _fspec_feat_state(spec, self._fspec_state)
        spec_in = np.ascontiguousarray(np.stack([spec.real, spec.imag], -1))[None][None]
        erb_in = np.ascontiguousarray(erb_f[None])[None]
        fspec_in = np.ascontiguousarray(np.stack([fspec_f.real, fspec_f.imag], -1))[None][None]
        # onnx → spec_e 18 帧
        se = self._sess.run(["spec_e"],
                            {"spec": spec_in, "erb": erb_in, "fspec": fspec_in})[0][0, 0]
        se_c = (se[..., 0] + 1j * se[..., 1]).astype(np.complex64)
        # istft 18 帧 (延迟480 语义), 裁剪 [480:480+7680] = 帧1-16 = 本块 7680 对齐
        out48, _mems = _istft_state(se_c, self._istft_mem)
        self._istft_mem = _mems[T_FRAMES - 1]   # 有效帧15后的 mem, 给下块帧0
        out48 = out48[FFT - HOP: FFT - HOP + BLOCK48]
        # 下采样 16k 2560
        return _down3(out48)

    def flush(self):
        self.acc = np.zeros(PREV48, np.float32)
        return np.zeros(0, np.float32)

    def close(self):
        self._sess = None
