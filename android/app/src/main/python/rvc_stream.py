# -*- coding: utf-8 -*-
"""真流式状态机 RVCStream —— 输入 16k 音频块 → 输出 40k 变声块(每块 37 dec 帧=370ms)。
状态跨块保留: f0 滚动缓存 + sine 绝对相位 + hubert F50 缓存 + overlap-add 输出缓冲。
与 process_stream_v2(整段) 逐块输出应 bitwise 一致。依赖 rvc_api.init/_execute 与 rvc_periphery。
"""
import json
import os
import time
import ctypes as _ct
import numpy as np

# ---- logcat 写入 (App python 环境有 liblog.so, 日志进 logcat 供 adb 查看) ----
_lblog = None
for _l in ("liblog.so", "/system/lib64/liblog.so", "/vendor/lib64/liblog.so"):
    try:
        _lblog = _ct.CDLL(_l)
        _lblog.__android_log_print.argtypes = [_ct.c_int, _ct.c_char_p, _ct.c_char_p]
        _lblog.__android_log_print.restype = _ct.c_int
        break
    except Exception:
        _lblog = None
_LOG_PATH = "/data/local/tmp/route2/rvc_prof.log"

def _logcat(msg):
    try:
        if _lblog is not None:
            _lblog.__android_log_print(4, b"RVC_PY", ("[rvc] " + str(msg)).encode())
        with open(_LOG_PATH, "a") as _f:
            _f.write(time.strftime("%H:%M:%S ") + str(msg) + "\n")
    except Exception:
        pass

from rvc_periphery import (mel_dlc, f0_decode, f0_to_coarse, pitch_embedding,
                           f0_uv_interp, hubert_blocks_at, index_mix,
                           index_mix_ivf, _rms, interp_linear_1d)
from df3_denoiser import DF3Denoiser


def _causal_interp(f0):
    """因果后向保持插值: unvoiced(0) 帧用左侧最近 voiced 值, 开头无则 0。
    历史稳定(只依赖已算帧, 不随未来变化)——流式 sine/pe 一致性的关键。"""
    out = f0.copy()
    last = 0.0
    for i in range(len(f0)):
        v = f0[i]
        if v > 0:
            last = v
        else:
            out[i] = last
    return out


class TorchGateDenoiser:
    """libtg.so 的 ctypes 封装: 块级流式 TorchGate 谱门控降噪。
    输入 16k float 任意块, 内部累积到 BLOCK(默认 1600=100ms) 再处理,
    不足一块的尾部缓存在 acc 中(引入 ≈BLOCK/sr 延迟)。
    定案参数: sr=16k n_fft=640 hop=160(10ms) prop=0.5 n_std=1.5
    freq_smooth=1000Hz time_smooth=150ms noise_percentile=0.3 ref=3s。
    (2026-09: prop 0.7→0.5 + freq_smooth 500→1000 + time_smooth 50→150,
     平滑增益谱减少谱门控音乐噪声/高频刺耳——见知乎《降噪算法中的音乐噪声问题》)。"""
    def __init__(self, native_lib_dir, block=1600, sr=16000, n_fft=640, hop=160,
                 prop_decrease=0.5, n_std=1.5, freq_smooth_hz=1000,
                 time_smooth_ms=150, noise_percentile=0.3, ref_sec=3, cross=320):
        import ctypes as ct
        self._ct = ct
        self.lib = ct.CDLL(os.path.join(native_lib_dir, "libtg.so"))
        self.lib.tg_create.restype = ct.c_void_p
        self.lib.tg_create.argtypes = [ct.c_int, ct.c_int, ct.c_int,
                                       ct.c_float, ct.c_float,
                                       ct.c_int, ct.c_int,
                                       ct.c_float, ct.c_int]
        self.h = self.lib.tg_create(sr, n_fft, hop,
                                    ct.c_float(prop_decrease), ct.c_float(n_std),
                                    freq_smooth_hz, time_smooth_ms,
                                    ct.c_float(noise_percentile), ref_sec)
        self.lib.tg_process.argtypes = [ct.c_void_p,
                                        ct.POINTER(ct.c_float), ct.c_int,
                                        ct.POINTER(ct.c_float)]
        self.lib.tg_process.restype = None
        self.lib.tg_destroy.argtypes = [ct.c_void_p]
        self.BLOCK = block
        self.CROSS = cross            # 块间交叉淡化区(默认 320=20ms), 消除块边界跳变
        self.HOP = block - cross      # 每块实际推进(块间重叠 CROSS, 同一输入两版本 crossfade)
        self._prev_tail = None        # 上一块末尾 CROSS 样本(处理版), 与当前块头混合
        self.acc = np.zeros(0, np.float32)

    def process(self, x16k):
        """输入 16k float 块 → 降噪输出。块间重叠 CROSS(20ms): 同一输入在两块各重建一次,
        边界交叉淡化消除跳变; 每块推进 HOP 样本, 长度守恒。不足一块时返回空。"""
        x16k = np.asarray(x16k, np.float32)
        if len(x16k) == 0:
            return np.zeros(0, np.float32)
        self.acc = np.concatenate([self.acc, x16k])
        HOP, BLOCK, CROSS = self.HOP, self.BLOCK, self.CROSS
        ramp = np.linspace(0.0, 1.0, CROSS, dtype=np.float32)
        result = []
        while len(self.acc) >= BLOCK:
            blk = np.ascontiguousarray(self.acc[:BLOCK])
            o = np.zeros(BLOCK, np.float32)
            self.lib.tg_process(self.h,
                                blk.ctypes.data_as(self._ct.POINTER(self._ct.c_float)),
                                BLOCK,
                                o.ctypes.data_as(self._ct.POINTER(self._ct.c_float)))
            # 块间 crossfade: 上一块尾(同一输入的旧版) 与 当前块头 线性混合
            if self._prev_tail is not None:
                o[:CROSS] = self._prev_tail * (1.0 - ramp) + o[:CROSS] * ramp
            self._prev_tail = o[BLOCK - CROSS:].copy()
            result.append(o[:HOP])
            self.acc = self.acc[HOP:]     # 推进 HOP, 保留 CROSS 重叠给下一块
        return np.concatenate(result) if result else np.zeros(0, np.float32)

    def flush(self):
        """清空尾部: 跳过最后重叠区(已在上一块处理), 输出未处理尾部 + 最后一块的 CROSS 尾。"""
        CROSS = self.CROSS
        r = self.acc[CROSS:] if len(self.acc) > CROSS else np.zeros(0, np.float32)
        if self._prev_tail is not None:
            r = np.concatenate([r, self._prev_tail])
            self._prev_tail = None
        self.acc = np.zeros(0, np.float32)
        return r

    def close(self):
        if self.h is not None:
            self.lib.tg_destroy(self.h)
            self.h = None


class RVCStream:
    def __init__(self, native_lib_dir, files_dir, uid, profile=False,
                 f0_up_key=0, rms_mix_rate=0.75, index_rate=0.75, protect=0.33,
                 f0_win=64, future=30, f0_smooth=3, denoise=True):
        """future: 启动/稳态未来(dec 帧, 默认30=300ms, 覆盖 hubert 块粒度 280ms)。
        f0_smooth: f0_i 轻量滑动平均核宽(>1 启用, 降块边界/暂估跳变去"电"; 1=关)。
        denoise: 输入侧 DeepFilterNet3 降噪(onnxruntime, 48k 直进 → 下采样 16k 给 RVC,
                 压平稳底噪/治"嗡嗡声变声化", 替代旧 TorchGate 谱门控)。"""
        import rvc_api
        self._api = rvc_api
        self.p = files_dir
        self.gsv = rvc_api.init(native_lib_dir, files_dir, uid, profile)
        # 输入侧降噪(DF3 onnxruntime: 48k 直进 → 16k 输出, 块级流式 +20ms lookahead)
        self._den = DF3Denoiser(native_lib_dir, files_dir) if denoise else None
        # 常量
        self.T, self.R, self.B, self.WIN = 224, 12, 37, 61
        self.F0_POST = 12
        self.F0_PRE = f0_win - self.B - self.F0_POST     # 64 -> 15
        self.f0_win = f0_win
        self.FUTURE = future                              # dec 帧
        self.f0_smooth = f0_smooth
        # 参数
        self.f0_up_key = f0_up_key
        self.rms_mix_rate = rms_mix_rate
        self.index_rate = index_rate
        self.protect = protect
        # 外围数据
        self.mel_basis = np.fromfile(os.path.join(files_dir, "periphery", "mel_basis.bin"),
                                     np.float32).reshape(128, 513)
        self.emb = np.load(os.path.join(files_dir, "periphery", "emb_params.npz"))
        with open(os.path.join(files_dir, "periphery", "sine_params.json")) as fp:
            self.sine_params = json.load(fp)
        self.g = np.fromfile(os.path.join(files_dir, "testdata", "gf_speaker_emb.bin"), np.float32)
        self.rnd = np.fromfile(os.path.join(files_dir, "testdata", "gf_rnd.bin"), np.float32)
        self.rnd2 = self.rnd.reshape(1, 224, 192)
        # index 缓存
        self._iv = None
        self._iv2 = None
        # 流式状态
        self.acc = np.zeros(0, np.float32)                # 累积 16k 音频
        self.s0 = 0                                       # 下一个 dec 块起点(全局 dec 帧)
        self.f0 = np.zeros(0, np.float32)                 # 原始 f0(全局 dec 帧粒度)
        self.f0_i = np.zeros(0, np.float32)               # uv_interp 后 f0(因果, 每块重插)
        self.F50m = np.zeros((0, 768), np.float32)        # hubert index 混合后(全局 F50 帧)
        self.F50raw = np.zeros((0, 768), np.float32)      # hubert 原始
        self.F50_mixed = np.zeros(0, np.bool_)
        self.blk_done = 0                                 # hubert 已算块数
        self.buf = np.zeros(0, np.float32)                # 40k 输出 overlap-add(全局 40k 采样)
        self.wsum = np.zeros(0, np.float32)
        self.out_base = -self.R * 400                     # buf[0] 对应全局 40k 位置(-PAD0)
        self._out_hist = np.zeros(0, np.float32)          # 原始输出(未 rms)累积, 滚动裁剪留最近 2s
        self._out_done = 0                                # 已输出 40k 采样数(全局, rms 定位用)
        self.RMS_HIST = 2 * 40000                         # _out_hist 保留上限(2s, rms 慢变够用)
        # sine 绝对相位(逐帧累积到"已生成帧数")
        self._sine_done_frames = 0                        # 已生成 sine 的绝对帧数
        self._sine_rad_acc = 0.0                          # 上一帧绝对累积相位
        self._sine_cache = []                             # 已生成帧的 sine(每帧 upp 采样)
        self._rand_ini = None
        self._sine_rng = np.random.RandomState(0)
        # 统计
        self.proc_times = []
        # LATLOG: 延时/堆积统计
        self._in_total = 0
        self._out_total = 0
        self._last_log = 0.0
        self._stage_ms = []   # 每块各环节耗时 (f0, hub, asm, zdec, misc) 秒
        # debug 录音(临时定位用): files_dir/dump_on 存在才录三层, 定期批量写
        #   dump_pre.raw  (降噪前 16k), dump_post.raw (降噪后 16k), dump_out.raw (变声输出 40k)
        self._dump = os.path.exists(os.path.join(files_dir, "dump_on"))
        self._dump_b = {"pre": np.zeros(0, np.float32),
                        "post": np.zeros(0, np.float32),
                        "out": np.zeros(0, np.float32)}
        self._dump_last = 0.0

    # ---------------- 输入接口 ----------------
    def push(self, audio_48k):
        """追加 48k 音频(录音), 输入侧 DF3 降噪 → 下采样 16k → RVC 处理, 返回 40k 变声块(可能空)。
        LATLOG: 每秒打印一次当前实际延时(= 已输入秒 - 已输出秒, 反映时间堆积)。"""
        audio_48k = np.asarray(audio_48k, np.float32)
        self._in_total += len(audio_48k)              # 原始输入(48k 计数, 延时统计含降噪缓存)
        if self._dump:
            self._dump_b["pre"] = np.concatenate([self._dump_b["pre"], audio_48k])
        if self._den is not None:
            audio_16k = self._den.process(audio_48k)  # DF3 降噪: 48k 直进 → 16k 输出(可能为空/20ms 缓冲)
        else:
            audio_16k = audio_48k[::3].copy()         # 无降噪: 48k→16k 直接抽取
        if self._dump and len(audio_16k):
            self._dump_b["post"] = np.concatenate([self._dump_b["post"], audio_16k])
        self.acc = np.concatenate([self.acc, audio_16k])
        outs = []
        while len(self.acc) >= (self.s0 + self.B + self.FUTURE) * 160:
            outs.append(self._process_block())
        out = np.concatenate(outs) if outs else np.zeros(0, np.float32)
        if self._dump and len(out):
            self._dump_b["out"] = np.concatenate([self._dump_b["out"], out])
            self._dump_flush()
        self._out_total += len(out)
        now = time.time()
        if now - self._last_log >= 1.0:
            in_s = self._in_total / 48000.0
            out_s = self._out_total / 40000.0
            lat = in_s - out_s
            print("[stream-lat] in=%.2fs out=%.2fs 实际延时=%.2fs (处理帧=%d)"
                  % (in_s, out_s, lat, self.s0), flush=True)
            if self._stage_ms:
                a = np.asarray(self._stage_ms[-20:])
                tot = a.sum(1).mean() * 1000
                _logcat("T=%.0fms f0=%.0f hub=%.0f asm=%.0f zdec=%.0f misc=%.0f | 延时=%.2fs 帧=%d"
                        % (tot, a[:, 0].mean() * 1000, a[:, 1].mean() * 1000, a[:, 2].mean() * 1000,
                           a[:, 3].mean() * 1000, a[:, 4].mean() * 1000, lat, self.s0))
                # 最近5块原始耗时(看波动)
                last5 = a[-5:].sum(1) * 1000
                _logcat("最近5块=[%s]ms" % ",".join("%.0f" % v for v in last5))
            self._last_log = now
        return out

    # ---------------- 单块处理 ----------------
    def _process_block(self):
        t0 = time.perf_counter()
        s0 = self.s0
        self._ensure_f0(s0); t1 = time.perf_counter()
        self._ensure_hubert(s0); t2 = time.perf_counter()
        phone_w = self._phone_window(s0)
        pe_w = self._pe_window(s0)
        sine_w = self._sine_window(s0); t3 = time.perf_counter()
        a61 = self._run_z_dec(s0, phone_w, pe_w, sine_w); t4 = time.perf_counter()
        self._overlap_add(s0, a61)
        seg = self._output_segment(s0); t5 = time.perf_counter()
        self.s0 = s0 + self.B
        self._trim_buf()
        self._stage_ms.append((t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4))
        self.proc_times.append(t5 - t0)
        return seg

    def _trim_buf(self):
        """滚动裁剪 buf/wsum 已输出前部(推进 out_base), 保持恒定大小(治累积变慢)。"""
        keep_from = (self.s0 - self.R - 1) * 400      # 下一块窗口起点再前 1 块
        cut = keep_from - self.out_base
        if cut > 0 and cut < self.buf.shape[0]:
            self.buf = self.buf[cut:]
            self.wsum = self.wsum[cut:]
            self.out_base = keep_from

    def _dump_flush(self):
        """debug 录音: 每 3 秒把累积的三层音频批量追加写文件(避免每块 I/O)。"""
        now = time.time()
        if now - self._dump_last < 3.0:
            return
        for k, arr in self._dump_b.items():
            if len(arr):
                with open(os.path.join(self.p, "dump_%s.raw" % k), "ab") as fp:
                    fp.write(arr.astype("<f4").tobytes())
                self._dump_b[k] = np.zeros(0, np.float32)
        self._dump_last = now

    # ---------------- f0(每块独立 64 窗, 与整段 cache_f0 一致) ----------------
    def _ensure_f0(self, s0):
        need = s0 + self.B + self.F0_POST
        if self.f0.shape[0] >= need:
            return
        lo = (s0 - self.F0_PRE) * 160
        hi = (s0 + self.B + self.F0_POST) * 160
        seg = self.acc[max(0, lo):hi]
        if len(seg) < hi - max(0, lo):
            seg = np.pad(seg, (0, (hi - max(0, lo)) - len(seg)))
        mel_in = mel_dlc(seg, self.mel_basis, n_frames=self.f0_win)   # [1,f0_win,128]
        self.gsv.init(os.path.join(self.p, "models", "rmvpe_fp32_%d.bin.bin" % self.f0_win),
                      "rmvpe_fp32_%d" % self.f0_win)
        o = self._api._execute(self.gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
        sal = np.asarray(list(o.values())[0]).reshape(self.f0_win, 360)
        f0w = f0_uv_interp(f0_decode(sal, thred=0.03))
        n = need - self.f0.shape[0]
        self.f0 = np.concatenate([self.f0, np.zeros(n, np.float32)])
        self.f0[s0:s0 + self.B] = f0w[self.F0_PRE:self.F0_PRE + self.B]
        if s0 + self.B + self.F0_POST <= self.f0.shape[0]:
            self.f0[s0 + self.B:s0 + self.B + self.F0_POST] = f0w[self.F0_PRE + self.B:]
        # 标准 uv_interp(对已算 f0, 含 300ms 未来 → 大多数 unvoiced 区已闭合, 与整段一致)
        self.f0_i = f0_uv_interp(self.f0)
        if self.f0_smooth > 1 and self.f0_i.shape[0] >= self.f0_smooth:
            k = self.f0_smooth
            kern = np.ones(k, np.float32) / k
            p = np.pad(self.f0_i, (k // 2, k - 1 - k // 2), mode='edge')
            self.f0_i = np.convolve(p, kern, mode='valid')

    # ---------------- hubert 增量(按需算块) ----------------
    def _ensure_hubert(self, s0):
        hi_f = int(np.ceil((s0 - self.R + self.WIN - 1) / 2))
        nf50 = int(np.floor(len(self.acc) / 320))
        need_f = min(hi_f, nf50 - 1)
        need_blk = int(np.ceil((need_f + 1) / 14))
        while self.blk_done < need_blk:
            self.gsv.init(os.path.join(self.p, "models", "hubert_mix_def_t4800.bin"),
                          "hubert_mix_def_t4800")
            o = self._api._execute(self.gsv, {"source": hubert_blocks_at(self.acc, self.blk_done)})
            fb = np.asarray(list(o.values())[0]).reshape(14, 768)
            b0 = self.blk_done * 14
            if b0 + 14 > self.F50m.shape[0]:
                grow = b0 + 14 - self.F50m.shape[0]
                self.F50m = np.concatenate([self.F50m, np.zeros((grow, 768), np.float32)], 0)
                self.F50raw = np.concatenate([self.F50raw, np.zeros((grow, 768), np.float32)], 0)
                self.F50_mixed = np.concatenate([self.F50_mixed, np.zeros(grow, np.bool_)])
            self.F50raw[b0:b0 + 14] = fb
            self.F50m[b0:b0 + 14] = fb
            self.blk_done += 1
        if self.index_rate > 0 and need_f >= 0:
            if self._iv is None:
                self._iv = self._api._idx_vecs_full(self.p)      # 768 混合
                self._iv_cent = self._api._ivf_cent768(self.p)
                self._iv_members = self._api._ivf_members(self.p)
                self._iv_offsets = self._api._ivf_offsets(self.p)
                self._iv_cent2 = self._api._ivf_cent2(self.p)
                self._iv2 = self._api._idx_v2(self.p)            # 768 索引平方和缓存
            # 批量 index_mix(768 空间 IVF): 连续未 mixed 帧段一次调用
            j = 0
            while j <= need_f:
                if j < self.F50_mixed.shape[0] and not self.F50_mixed[j]:
                    j2 = j
                    while j2 <= need_f and j2 < self.F50_mixed.shape[0] and not self.F50_mixed[j2]:
                        j2 += 1
                    self.F50m[j:j2] = index_mix_ivf(
                        self.F50m[j:j2], self._iv, self._iv_cent, self._iv_members,
                        self._iv_offsets, self._iv_cent2, self._iv2, self.index_rate)
                    self.F50_mixed[j:j2] = True
                    j = j2
                else:
                    j += 1

    # ---------------- 窗组装 ----------------
    def _phone_window(self, s0):
        nf50 = self.F50m.shape[0]
        idxw = np.round((np.arange(s0 - self.R, s0 - self.R + self.WIN)) / 2.0).astype(int).clip(0, nf50 - 1)
        fw = self.F50m[idxw].astype(np.float32)
        if self.protect < 0.5:
            fo = self.F50raw[idxw]
            pff = np.full(self.WIN, self.protect, np.float32)
            i0 = max(0, s0 - self.R)
            f0seg = self.f0[i0:i0 + self.WIN]
            pff[:len(f0seg)][f0seg > 0] = 1.0
            fw = fw * pff[:, None] + fo * (1 - pff[:, None])
        return fw[None].transpose(0, 2, 1)                       # [1,768,WIN]

    def _pe_window(self, s0):
        i0 = max(0, s0 - self.R)
        f0seg = self.f0_i[i0:i0 + self.WIN]
        if len(f0seg) < self.WIN:
            f0seg = np.pad(f0seg, (0, self.WIN - len(f0seg)))
        if self.f0_up_key != 0:
            f0seg = f0seg * pow(2, self.f0_up_key / 12)
        coarse = f0_to_coarse(f0seg, self.WIN)
        pe = pitch_embedding(coarse[None], self.emb["emb_pitch_weight"]).transpose(0, 2, 1)
        return pe                                             # [1,192,WIN]

    def _sine_window(self, s0):
        """sine 绝对相位+噪声逐帧增量: 返回 dec 帧 [s0-R, s0-R+WIN) 的 sine [1,WIN*upp,1]。
        负帧用帧0复制, 帧≥224 用帧223复制(与整段 sine_pad repeat 一致)。"""
        sp = self.sine_params
        sr = float(sp["sampling_rate"])
        sine_amp = float(sp["sine_amp"])
        noise_std = float(sp["noise_std"])
        threshold = float(sp["voiced_threshold"])
        upp = int(sp["upp"])
        dim = int(sp.get("harmonic_num", 0)) + 1
        w = np.asarray(sp["linear_weight"], np.float64).reshape(1, 1)
        b = np.asarray(sp["linear_bias"], np.float64).reshape(1)
        ar = np.arange(1, upp + 1, dtype=np.float64).reshape(1, -1)   # [1,upp]
        harm = np.arange(1, dim + 1, dtype=np.float64).reshape(1, 1, -1)
        if self._rand_ini is None:
            self._rand_ini = self._sine_rng.rand(1, 1, dim)
            self._rand_ini[..., 0] = 0
        start = s0 - self.R
        need_frames = start + self.WIN
        # 逐帧生成 [self._sine_done_frames, need_frames) 的 sine(每帧 upp 采样, 存 cache)
        while self._sine_done_frames < need_frames:
            i = self._sine_done_frames
            fi = self.f0_i[i] if i < self.f0_i.shape[0] else 0.0
            fseg = fi
            if self.f0_up_key != 0:
                fseg = fseg * pow(2, self.f0_up_key / 12)
            rad2 = np.fmod(fseg / sr * upp + 0.5, 1.0) - 0.5
            prev = np.fmod(self._sine_rad_acc + rad2, 1.0)   # 帧 i 绝对累积
            padded = self._sine_rad_acc                       # 帧 i 的"前一帧累积"
            self._sine_rad_acc = prev
            rad = (fseg / sr * ar + padded) * harm            # [1,upp,1]*... -> [1,upp,dim]
            rad = rad.reshape(1, upp, dim) + self._rand_ini
            sine_wav = np.sin(2 * np.pi * rad) * sine_amp
            uv = np.float64(1.0 if fseg > threshold else 0.0)
            noise_amp = uv * noise_std + (1 - uv) * sine_amp / 3.0
            noise = noise_amp * self._sine_rng.randn(upp, dim)
            sine_wav = sine_wav * uv + noise
            merge = np.tanh((sine_wav @ w.T + b).reshape(upp))   # [upp]
            self._sine_cache.append(merge.astype(np.float32))
            self._sine_done_frames += 1
        # 取窗段 [start, start+WIN), 负帧用帧0; FIX: 帧>=224 直接用 cache[fi](全程生成, 与整段 sine_source 一致)
        out = np.zeros(self.WIN * upp, np.float32)
        for k in range(self.WIN):
            fi = start + k
            src = self._sine_cache[0] if fi < 0 else self._sine_cache[fi]
            out[k * upp:(k + 1) * upp] = src
        return out.reshape(1, self.WIN * upp, 1)

    # ---------------- z/dec 块 + overlap-add ----------------
    def _run_z_dec(self, s0, phone_w, pe_w, sine_w):
        pp = np.zeros((1, 768, self.T), np.float32); pp[:, :, :self.WIN] = phone_w
        ee = np.zeros((1, 192, self.T), np.float32); ee[:, :, :self.WIN] = pe_w
        rr = np.zeros((1, self.T, 192), np.float32)
        for k in range(self.WIN):
            di = s0 - self.R + k
            # FIX: 长序列循环复用 rnd(di%224), 不钉死 223; 负帧用 0
            idx = 0 if di < 0 else (di % 224)
            rr[0, k, :] = self.rnd2[0, idx, :]
        self.gsv.init(os.path.join(self.p, "models", "z_producer.bin.bin"), "z_producer")
        o = self._api._execute(self.gsv, {
            "phone": np.ascontiguousarray(pp.reshape(-1)),
            "g": np.ascontiguousarray(self.g.reshape(-1)),
            "pitch_emb": np.ascontiguousarray(ee.reshape(-1)),
            "lengths": np.array([self.WIN], np.int32),
            "rnd": np.ascontiguousarray(rr.reshape(-1))})
        z61 = np.asarray(list(o.values())[0]).reshape(1, 192, self.T)[:, :, :self.WIN]
        self.gsv.init(os.path.join(self.p, "models", "dec_short_t61.bin.bin"), "dec_short_T61")
        z_in = np.ascontiguousarray(z61.transpose(0, 2, 1).reshape(-1))
        s_in = np.ascontiguousarray(sine_w.reshape(-1))
        o = self._api._execute(self.gsv, {"z": z_in,
                                          "g": np.ascontiguousarray(self.g.reshape(-1)),
                                          "sine": s_in})
        a61 = np.asarray(list(o.values())[0]).ravel().astype(np.float32).reshape(self.WIN * 400)
        return a61

    def _overlap_add(self, s0, a61):
        base = (s0 - self.R) * 400                      # 全局 40k 位置
        end = base + self.WIN * 400
        if end > self.buf.shape[0] + self.out_base:
            grow = end - (self.buf.shape[0] + self.out_base)
            self.buf = np.concatenate([self.buf, np.zeros(grow, np.float32)])
            self.wsum = np.concatenate([self.wsum, np.zeros(grow, np.float32)])
        off = base - self.out_base
        ramp = 0.5 * (1 - np.cos(np.pi * np.arange(self.R * 400) / (self.R * 400)))
        w = np.ones(self.WIN * 400, np.float32)
        w[:self.R * 400] = ramp
        w[(self.WIN - self.R) * 400:] = 1.0 - ramp
        self.buf[off:off + self.WIN * 400] += a61 * w
        self.wsum[off:off + self.WIN * 400] += w

    def _rms_gain(self, seg_len, n_before_out):
        """当前段(B*400)的滞后 rms 增益, O(窗口) 不随历史增长。
        rms1=输入侧当前段对应 16k 位置±1s 窗, rms2=已输出原始 _out_hist 最近 2s。
        与整段 change_rms 同参数(帧长=采样率,hop=半帧), 慢变包络近似(听感等价)。"""
        sr1, sr2 = 16000, 40000
        r = self.rms_mix_rate
        if r <= 0.0:
            return None
        in_pos = int(n_before_out * sr1 // sr2)            # 当前段对应 16k 位置
        lo1 = max(0, in_pos - sr1)
        hi1 = min(len(self.acc), in_pos + seg_len // 2 + sr1)
        if hi1 - lo1 < sr1 // 4 or len(self._out_hist) < sr2 // 4:
            return None                                    # 历史不足, 跳过
        rms1 = _rms(self.acc[lo1:hi1], sr1, sr1 // 2)
        rms2 = _rms(self._out_hist, sr2, sr2 // 2)
        g1 = interp_linear_1d(rms1, seg_len)
        g2 = np.maximum(interp_linear_1d(rms2, seg_len), 1e-3)   # 官方实时版下限 1e-3,防增益爆炸
        return (np.power(g1, 1.0 - r) * np.power(g2, r - 1.0)).astype(np.float32)

    def _output_segment(self, s0):
        """当前块 37 帧的 40k 输出段: 全局 (s0)*400 .. (s0+B)*400。含滞后 rms 匹配。"""
        lo = s0 * 400 - self.out_base
        hi = (s0 + self.B) * 400 - self.out_base
        if lo < 0:
            return np.zeros(self.B * 400, np.float32)
        if hi > self.buf.shape[0]:
            self.buf = np.concatenate([self.buf, np.zeros(hi - self.buf.shape[0], np.float32)])
            self.wsum = np.concatenate([self.wsum, np.zeros(hi - self.wsum.shape[0], np.float32)])
        raw = self.buf[lo:hi] / np.maximum(self.wsum[lo:hi], 1e-6)
        seg = raw
        gain = self._rms_gain(len(seg), self._out_done)
        if gain is not None:
            seg = seg * gain
        # 追加原始段到 rms 历史, 滚动裁剪留最近 2s
        self._out_hist = np.concatenate([self._out_hist, raw])
        if len(self._out_hist) > self.RMS_HIST:
            self._out_hist = self._out_hist[-self.RMS_HIST:]
        self._out_done += len(seg)
        return seg.astype(np.float32)

    def latency_ms(self):
        """当前平均每块处理耗时(ms)。"""
        if not self.proc_times:
            return 0.0
        return float(np.mean(self.proc_times) * 1000)

    def measure_latency(self, blocks=4):
        """延迟自测: 合成音频推入触发处理, 测稳态每块耗时(去掉 init 预热块)。
        返回 dict {block_ms, f0_ms, hubert_ms, zdec_ms}——NPU 推理耗时与输入内容无关,
        故用低幅噪声即可标定真实 T_proc(与麦克风语音等价)。"""
        # 干净对象重测
        self.acc = np.zeros(0, np.float32)
        self.s0 = 0
        self.f0 = np.zeros(0, np.float32); self.f0_i = np.zeros(0, np.float32)
        self.F50m = np.zeros((0, 768), np.float32); self.F50raw = np.zeros((0, 768), np.float32)
        self.F50_mixed = np.zeros(0, np.bool_); self.blk_done = 0
        self.buf = np.zeros(0, np.float32); self.wsum = np.zeros(0, np.float32)
        self.out_base = -self.R * 400
        self._out_hist = np.zeros(0, np.float32); self._out_done = 0
        self._sine_done_frames = 0; self._sine_rad_acc = 0.0
        self._sine_cache = []; self._rand_ini = None
        self._sine_rng = np.random.RandomState(0)
        n = (blocks + 1) * (self.B + self.FUTURE) * 160     # 足够处理 blocks+1 块
        noise = (np.random.RandomState(42).randn(n) * 0.05).astype(np.float32)
        self.proc_times.clear()
        self.push(noise)
        times = np.array(self.proc_times[-blocks:]) * 1000.0
        # 分步统计(稳态最后一块)
        t0 = 0.0
        return {"block_ms": float(times.mean()), "steps": []}

    def close(self):
        if self._dump:
            for k, arr in self._dump_b.items():
                if len(arr):
                    with open(os.path.join(self.p, "dump_%s.raw" % k), "ab") as fp:
                        fp.write(arr.astype("<f4").tobytes())
            self._dump_b = {k: np.zeros(0, np.float32) for k in self._dump_b}
        if self._den is not None:
            self._den.close()
