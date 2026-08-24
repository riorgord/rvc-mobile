# -*- coding: utf-8 -*-
"""RVC App M1a: unsigned PD 激活 + 3 模型(hubert/rmvpe/gen)单模型推理验证。
复用 GSV 的 libgsv_qnn.so 常驻封装(通用:任意 bin + graph_name,multi-slot 缓存)。
profile 开关 → /sdcard/rvc_exp (默认关)。
"""
import ctypes
import os
import time

import numpy as np

from gsv_api import Gsv

_STATE = {}

_TYPE_DT = {0x32: np.int32, 0x64: np.int64, 0x216: np.float16, 0x232: np.float32}


def _preload(lib_dir):
    """bionic 启动时缓存搜索路径,setenv LD_LIBRARY_PATH 对已缓存路径无效
    → 必须按 soname 主动 dlopen(GSV 验证)。2.47 无 CalculatorStub,用 V69Stub。"""
    order = ["libqti_dsp.so", "libvmmem.so", "libcdsprpc.so", "libhidlbase.so",
             "libhidltransport.so", "libhwbinder.so", "libhardware.so",
             "libutils.so", "liblog.so", "libcutils.so", "libdmabufheap.so",
             "libbase.so", "libc++.so",
             "libQnnHtpV69Stub.so", "libQnnHtpNetRunExtensions.so"]
    for name in order:
        p = os.path.join(lib_dir, name)
        if os.path.exists(p):
            try:
                ctypes.CDLL(p, mode=ctypes.RTLD_GLOBAL)
            except Exception:
                pass


def init(native_lib_dir, files_dir, uid, profile=False):
    """幂等初始化:env(三件套) + 预加载 + libgsv_qnn.so 绑定。
    profile=True → QnnProfile 采集,结果可 profile_dump 到 /sdcard/rvc_exp。
    注意:GSV_NOPROFILE 由 libgsv_qnn.so 加载时缓存 → 切换 profiling 需重启 App 生效。"""
    if _STATE.get("gsv") is not None:
        # 幂等早退也同步 profile 状态:profile=true 一旦设过就保持(true 优先)
        # (GSV_NOPROFILE 是 libgsv 加载时缓存 → 真正生效需冷启动;这里保 _STATE 供 dump)
        _STATE["profile"] = _STATE.get("profile", False) or profile
        return _STATE["gsv"]
    os.environ["GSV_NOPROFILE"] = "0" if profile else "1"
    os.environ["ADSP_LIBRARY_PATH"] = native_lib_dir  # 纯 QNN 目录(含 Skel/V69)
    os.environ["LD_LIBRARY_PATH"] = ":".join(
        [native_lib_dir, "/system/lib64", "/vendor/lib64"])
    _preload(native_lib_dir)
    gsv = Gsv(native_lib_dir, files_dir)
    gsv.load()
    _STATE.update(gsv=gsv, native=native_lib_dir, files=files_dir, uid=uid,
                  profile=profile)
    return gsv


def _writable_dir(gsv, out_dir, files_dir):
    """/sdcard/rvc_exp 需要 MANAGE_EXTERNAL_STORAGE 授权;未授权自动落到 filesDir。"""
    try:
        os.makedirs(out_dir, exist_ok=True)
        probe = os.path.join(out_dir, ".w")
        open(probe, "w").close()
        os.remove(probe)
        return out_dir
    except Exception:
        fb = os.path.join(files_dir, "rvc_exp")
        os.makedirs(fb, exist_ok=True)
        return fb


def _read_input(gsv, files_dir, fname, tensor_name):
    """按 tensor 名字找输入 index,读文件(按 gsv_in_type 的 dtype)。"""
    lib = gsv._lib
    for i in range(gsv.n_in):
        if gsv.in_name(i) == tensor_name:
            nb = lib.gsv_in_bytes(i)
            dt = _TYPE_DT.get(lib.gsv_in_type(i), np.float32)
            a = np.fromfile(os.path.join(files_dir, "testdata", fname), dtype=dt)
            if a.nbytes != nb:
                raise RuntimeError("%s(%s) bytes %d != %d" % (
                    tensor_name, fname, a.nbytes, nb))
            return a
    raise RuntimeError("no input tensor %s" % tensor_name)


def run_model(gsv, files_dir, out_dir, bin_name, graph_name,
              inputs, ref_name, out_tag):
    """加载 bin → 按名喂输入 → execute → 对比 PC 参考 → 写输出 + 返回描述。"""
    print("[rvc] %s: init %s …" % (out_tag, bin_name))
    t_init = time.perf_counter()
    gsv.init(os.path.join(files_dir, "models", bin_name), graph_name)
    print("[rvc] %s: init done %.1fs inputs=%s" % (
        out_tag, time.perf_counter() - t_init,
        list(gsv.in_name(i) for i in range(gsv.n_in))))
    tensors = {name: _read_input(gsv, files_dir, fname, name)
               for name, fname in inputs.items()}
    t0 = time.perf_counter()
    out = gsv.run(tensors)
    dt = (time.perf_counter() - t0) * 1000
    out_tensor = list(out.keys())[0]
    a = np.asarray(out[out_tensor]).ravel().astype(np.float32)
    out_dir = _writable_dir(gsv, out_dir, files_dir)
    a.tofile(os.path.join(out_dir, "%s_out.raw" % out_tag))
    ref = np.fromfile(os.path.join(files_dir, "testdata", ref_name),
                      np.float32).ravel()
    n = min(a.size, ref.size)
    c = float(np.corrcoef(a[:n], ref[:n])[0, 1])
    res = "%s: corr=%.4f execute=%.1fms out=%dB" % (out_tag, c, dt, a.nbytes)
    if _STATE.get("profile"):
        try:
            gsv.profile_dump(os.path.join(out_dir, "%s_prof.jsonl" % out_tag), 0, 0)
            res += " profiled→" + out_dir
        except Exception as e:
            res += " prof_fail=%s" % e
    return res


def run_hubert(gsv, files_dir, out_dir):
    """hubert_mix_def_t4800: source[1,4800]F32 → features[1,14,768]F32"""
    return run_model(gsv, files_dir, out_dir, "hubert_mix_def_t4800.bin",
                     "hubert_mix_def_t4800", {"source": "hb0.bin"},
                     "hubert_ref.bin", "hubert")


def run_rmvpe(gsv, files_dir, out_dir):
    """rmvpe_fp32_256: input[1,256,128]F32 → output[1,256,360]F32"""
    return run_model(gsv, files_dir, out_dir, "rmvpe_fp32_256.bin",
                     "rmvpe_fp32_256", {"input": "gya_mel_t.bin"},
                     "rmvpe_ref.bin", "rmvpe")


def run_gen(gsv, files_dir, out_dir):
    """gen_fp32: 6 输入(phone/pitch_emb/phone_lengths/rnd/speaker_emb/sine) → audio[1,1,89600]"""
    gf = {"phone": "gf_phone.bin", "pitch_emb": "gf_pitch_emb.bin",
          "phone_lengths": "gf_phone_lengths.bin", "rnd": "gf_rnd.bin",
          "speaker_emb": "gf_speaker_emb.bin", "sine": "gf_sine.bin"}
    return run_model(gsv, files_dir, out_dir, "gen_fp32.bin", "gen_fp32", gf,
                     "gya_ref_audio.raw", "gen")


def self_test_all(native_lib_dir, files_dir, uid, profile=False,
                  out_dir="/sdcard/rvc_exp"):
    gsv = init(native_lib_dir, files_dir, uid, profile)
    parts = []
    for fn in (run_hubert, run_rmvpe, run_gen):
        try:
            parts.append(fn(gsv, files_dir, out_dir))
        except Exception as e:
            parts.append("%s FAIL: %s" % (fn.__name__, e))
    return " | ".join(parts)


def _execute(gsv, tensors):
    """直接喂 numpy 数组执行(不读文件),返回 {out_name: np_array}。"""
    import numpy as _np
    outs = gsv.run({k: _np.ascontiguousarray(v) for k, v in tensors.items()})
    return outs


def run_full(gsv, files_dir, out_dir):
    """M1b 全链路:hubert 8块→phone, rmvpe→f0→pitch_emb/sine, gen→audio。
    中间产物 vs gf_*.bin 逐段对比,最终 audio vs PC 参考。"""
    import json
    from rvc_periphery import (hubert_assemble, f0_decode, f0_to_coarse,
                               pitch_embedding, sine_source)
    out_dir = _writable_dir(gsv, out_dir, files_dir)
    p = files_dir
    res = []

    # ---- 1. hubert 8 块 → phone [1,768,224] NCW ----
    gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
             "hubert_mix_def_t4800")
    feats = []
    for k in range(8):
        o = _execute(gsv, {"source": np.fromfile(
            os.path.join(p, "testdata", "hb%d.bin" % k), np.float32)})
        feats.append(np.asarray(list(o.values())[0]).reshape(14, 768))
    t0 = time.perf_counter()
    phone_nwc = hubert_assemble(feats, 224)                    # [224,768]
    phone_dlc = phone_nwc[None].transpose(0, 2, 1)             # [1,768,224]
    t_ass = (time.perf_counter() - t0) * 1000
    gf_ph = np.fromfile(os.path.join(p, "testdata", "gf_phone.bin"),
                        np.float32).ravel()
    c = np.corrcoef(phone_dlc.ravel(), gf_ph)[0, 1]
    res.append("phone corr=%.4f assemble=%.0fms" % (c, t_ass))
    phone_dlc.tofile(os.path.join(out_dir, "full_phone.bin"))

    # ---- 2. App 内 mel 提取 + rmvpe → salience → f0 → coarse → pitch_emb ----
    from rvc_periphery import mel_dlc
    gsv.init(os.path.join(p, "models", "rmvpe_fp32_256.bin"), "rmvpe_fp32_256")
    t0 = time.perf_counter()
    audio = np.fromfile(os.path.join(p, "testdata", "gya_audio.raw"), np.float32)
    mel_basis = np.fromfile(os.path.join(p, "periphery", "mel_basis.bin"),
                            np.float32).reshape(128, 513)
    mel_in = mel_dlc(audio, mel_basis)                         # [1,256,128]
    t_mel = (time.perf_counter() - t0) * 1000
    gf_mel = np.fromfile(os.path.join(p, "testdata", "gya_mel_t.bin"),
                         np.float32).reshape(256, 128)
    cm = np.corrcoef(mel_in[0].ravel(), gf_mel.ravel())[0, 1]
    o = _execute(gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
    sal = np.asarray(list(o.values())[0]).reshape(256, 360)
    t0 = time.perf_counter()
    f0 = f0_decode(sal, thred=0.03)
    coarse = f0_to_coarse(f0, 224)
    emb = np.load(os.path.join(p, "periphery", "emb_params.npz"))
    pe_nwc = pitch_embedding(coarse[None], emb["emb_pitch_weight"])  # [1,224,192]
    pe_dlc = pe_nwc.transpose(0, 2, 1)                                # [1,192,224]
    params = json.load(open(os.path.join(p, "periphery", "sine_params.json")))
    nsff0 = f0[:224].astype(np.float32)[None]
    sine_nwc = sine_source(nsff0, params, seed=0, use_random=True)    # [1,89600,1]
    sine_dlc = sine_nwc.transpose(0, 2, 1)                            # [1,1,89600]
    t_dsp = (time.perf_counter() - t0) * 1000
    gf_pe = np.fromfile(os.path.join(p, "testdata", "gf_pitch_emb.bin"),
                        np.float32).ravel()
    gf_sine = np.fromfile(os.path.join(p, "testdata", "gf_sine.bin"),
                          np.float32).ravel()
    cp = np.corrcoef(pe_dlc.ravel(), gf_pe)[0, 1]
    cs = np.corrcoef(sine_dlc.ravel(), gf_sine)[0, 1]
    res.append("mel corr=%.4f(%.0fms) rmvpe corr=%.4f f0=%d p_emb=%.4f sine=%.4f dsp=%.0fms" % (
        cm, t_mel,
        np.corrcoef(sal.ravel(), np.fromfile(
            os.path.join(p, "testdata", "rmvpe_ref.bin"), np.float32))[0, 1],
        int((f0 > 0).sum()), cp, cs, t_dsp))
    pe_dlc.tofile(os.path.join(out_dir, "full_pitch_emb.bin"))
    sine_dlc.tofile(os.path.join(out_dir, "full_sine.bin"))

    # ---- 3. gen: phone/pitch_emb/sine 用算出的,rnd/speaker_emb 用打包的 ----
    gsv.init(os.path.join(p, "models", "gen_fp32.bin"), "gen_fp32")
    tensors = {
        "phone": phone_dlc,
        "pitch_emb": pe_dlc,
        "phone_lengths": np.array([224], np.int32),
        "rnd": np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32),
        "speaker_emb": np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"), np.float32),
        "sine": sine_dlc,
    }
    t0 = time.perf_counter()
    o = _execute(gsv, tensors)
    t_gen = (time.perf_counter() - t0) * 1000
    audio = np.asarray(list(o.values())[0]).ravel().astype(np.float32)
    ref = np.fromfile(os.path.join(p, "testdata", "gya_ref_audio.raw"),
                      np.float32).ravel()
    n = min(audio.size, ref.size)
    ca = np.corrcoef(audio[:n], ref[:n])[0, 1]
    audio.tofile(os.path.join(out_dir, "full_audio.raw"))
    if _STATE.get("profile"):
        try:
            od = _writable_dir(gsv, out_dir, p)
            gsv.profile_dump(os.path.join(od, "gen_prof.jsonl"), 0, 0)
            res.append(" profiled")
        except Exception as e:
            res.append(" prof_fail=%s" % e)
    res.append("gen corr=%.4f exec=%.0fms out=%dB" % (ca, t_gen, audio.nbytes))
    return " | ".join(res)


def run_route2(gsv, files_dir, out_dir):
    """② 拆分验证(LLM KV cache 思想):hubert→phone, mel/rmvpe→pitch_emb/sine
    (同 run_full 前半), 第 3 步替换为 z_producer(enc_p+flow)整窗出 z 缓存
    + dec_short 滑窗[过去12+新13+未来12]每块只算短窗 → 取中间 13 帧拼接。
    z 环形缓冲 = KV cache 跨块复用, dec 每块只算新帧(手机实测 137ms/块)。"""
    import json
    from rvc_periphery import (hubert_assemble, f0_decode, f0_to_coarse,
                               pitch_embedding, sine_source, mel_dlc)
    out_dir = _writable_dir(gsv, out_dir, files_dir)
    p = files_dir
    res = []

    # ---- 1. hubert 8 块 → phone [1,768,224] NCW ----
    gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
             "hubert_mix_def_t4800")
    feats = []
    for k in range(8):
        o = _execute(gsv, {"source": np.fromfile(
            os.path.join(p, "testdata", "hb%d.bin" % k), np.float32)})
        feats.append(np.asarray(list(o.values())[0]).reshape(14, 768))
    phone_nwc = hubert_assemble(feats, 224)
    phone_dlc = phone_nwc[None].transpose(0, 2, 1)              # [1,768,224]

    # ---- 2. mel + rmvpe → f0 → coarse → pitch_emb / sine ----
    gsv.init(os.path.join(p, "models", "rmvpe_fp32_256.bin"), "rmvpe_fp32_256")
    audio = np.fromfile(os.path.join(p, "testdata", "gya_audio.raw"), np.float32)
    mel_basis = np.fromfile(os.path.join(p, "periphery", "mel_basis.bin"),
                            np.float32).reshape(128, 513)
    mel_in = mel_dlc(audio, mel_basis)                          # [1,256,128]
    o = _execute(gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
    sal = np.asarray(list(o.values())[0]).reshape(256, 360)
    f0 = f0_decode(sal, thred=0.03)
    coarse = f0_to_coarse(f0, 224)
    emb = np.load(os.path.join(p, "periphery", "emb_params.npz"))
    pe_nwc = pitch_embedding(coarse[None], emb["emb_pitch_weight"])  # [1,224,192] 帧外层
    pe_dlc = pe_nwc.transpose(0, 2, 1)                               # [1,192,224] 通道外层
    params = json.load(open(os.path.join(p, "periphery", "sine_params.json")))
    sine_nwc = sine_source(f0[:224].astype(np.float32)[None], params,
                           seed=0, use_random=True)             # [1,89600,1]
    sine_dlc = sine_nwc.transpose(0, 2, 1)                      # [1,1,89600]

    # ---- 3. z_producer(enc_p+flow 整窗出 z, 缓存复用 = KV cache) ----
    # HTP 布局(手机实测 = onnx 转置序): phone 768×224 通道外层(phone_dlc),
    #   pitch_emb 192×224 通道外层(pe_dlc), rnd 224×192 帧外层(gf_rnd 原样),
    #   lengths int32, g 256 点。dec 端 z 切片再转 37×192 帧外层。
    gsv.init(os.path.join(p, "models", "z_producer.bin.bin"), "z_producer")
    gf_rnd = np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32)
    g = np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"), np.float32)
    t0 = time.perf_counter()
    o = _execute(gsv, {
        "phone": np.ascontiguousarray(phone_dlc.reshape(-1)),            # 768×224 通道外层
        "g": np.ascontiguousarray(g.reshape(-1)),
        "pitch_emb": np.ascontiguousarray(pe_dlc.reshape(-1)),           # 192×224 通道外层
        "lengths": np.array([224], np.int32),
        "rnd": np.ascontiguousarray(gf_rnd.reshape(-1)),                 # 224×192 帧外层
    })
    t_zp = (time.perf_counter() - t0) * 1000
    z = np.asarray(list(o.values())[0]).reshape(1, 192, 224)    # [1,192,224]
    z.tofile(os.path.join(out_dir, "route2_z.bin"))

    # ---- 4. dec_short 滑窗: [过去12+新13+未来12] 37帧, 取中间 13 帧 ----
    gsv.init(os.path.join(p, "models", "dec_short.bin.bin"), "dec_short")
    R, B, WIN, UPP = 12, 13, 224, 400
    # z/sine 首尾 pad(复制边缘帧), 保证首末块窗在界内
    zp = np.concatenate([np.repeat(z[:, :, :1], R, 2), z,
                         np.repeat(z[:, :, -1:], R + B, 2)], 2)     # [1,192,261]
    sp = np.concatenate([np.repeat(sine_dlc[:, :, :UPP], R, 2),
                         sine_dlc,
                         np.repeat(sine_dlc[:, :, -UPP:], R + B, 2)], 2)
    segs, t_dec = [], 0.0
    for s0 in range(0, WIN, B):        # s0 = 0,13,...,221 (18块)
        lo, hi = s0, s0 + B + 2 * R
        t0 = time.perf_counter()
        o = _execute(gsv, {
            # z 块 [1,192,37] 通道外层 → 转 37×192 帧外层(HTP 期望)
            "z": np.ascontiguousarray(zp[:, :, lo:hi].transpose(0, 2, 1).reshape(-1)),
            "sine": np.ascontiguousarray(sp[:, :, lo * UPP:hi * UPP].reshape(-1)),
            "g": np.ascontiguousarray(g.reshape(-1))})
        t_dec += time.perf_counter() - t0
        blk = np.asarray(list(o.values())[0]).ravel().astype(np.float32)
        segs.append(blk[R * UPP:(R + B) * UPP])
    audio = np.concatenate(segs)[:WIN * UPP]                     # 234→224 帧
    audio.tofile(os.path.join(out_dir, "route2_audio.raw"))
    ref = np.fromfile(os.path.join(p, "testdata", "gya_ref_audio.raw"),
                      np.float32).ravel()
    n = min(audio.size, ref.size)
    ca = np.corrcoef(audio[:n], ref[:n])[0, 1]
    res.append("route2: z_producer=%.0fms dec=%d块 avg=%.0fms corr=%.4f out=%dB"
               % (t_zp, len(segs), t_dec / len(segs) * 1000, ca, audio.nbytes))
    return " | ".join(res)


def self_test_full(native_lib_dir, files_dir, uid, profile=False,
                   out_dir="/sdcard/rvc_exp"):
    gsv = init(native_lib_dir, files_dir, uid, profile)
    return run_full(gsv, files_dir, out_dir)


def self_test_route2(native_lib_dir, files_dir, uid, profile=False,
                     out_dir="/sdcard/rvc_exp"):
    gsv = init(native_lib_dir, files_dir, uid, profile)
    return run_route2(gsv, files_dir, out_dir)


def self_test_live_io(native_lib_dir, files_dir, uid, profile=False):
    """M2 验证:process_audio(gya 内存音频) 对比 PC 参考(内存链路 + 实时切块)。"""
    t0 = time.perf_counter()
    audio = np.fromfile(os.path.join(files_dir, "testdata", "gya_audio.raw"),
                        np.float32)
    out = np.frombuffer(process_audio(native_lib_dir, files_dir, uid,
                                      audio.tobytes(), profile,
                                      f0_up_key=0, rms_mix_rate=1.0,
                                      index_rate=0, protect=0.5), np.float32)
    dt = (time.perf_counter() - t0) * 1000
    ref = np.fromfile(os.path.join(files_dir, "testdata", "gya_ref_audio.raw"),
                      np.float32).ravel()
    n = min(out.size, ref.size)
    c = float(np.corrcoef(out[:n], ref[:n])[0, 1])
    return "LIVE_IO: corr=%.4f out=%d(%.2fs@40k) total=%.0fms" % (
        c, out.size, out.size / 40000.0, dt)


_IDX_CACHE = {}


def _idx_vecs(p):
    if "idx" not in _IDX_CACHE:
        _IDX_CACHE["idx"] = np.fromfile(
            os.path.join(p, "periphery", "naiqiawang_idx.bin"),
            np.float32).reshape(-1, 768)
    return _IDX_CACHE["idx"]


def process_audio(native_lib_dir, files_dir, uid, audio_bytes, profile=False,
                  f0_up_key=0, rms_mix_rate=0.25, index_rate=0.75, protect=0.33):
    """M2 实时链路入口:内存 16k fp32 音频(bytes) → 40k fp32 变声音频(bytes)。
    完整 RVC 推理:mel→rmvpe→f0(uv插值+变调)→index音色混合→protect→phone
    →gen→rms混合。f0_up_key 变调半音; rms_mix_rate RMS 占比(默认0.25);
    index_rate 音色索引强度(GUI默认0.75); protect 清音保护(GUI默认0.33)。"""
    import json
    from rvc_periphery import (hubert_blocks, mel_dlc, f0_decode, f0_to_coarse,
                               pitch_embedding, sine_source, f0_uv_interp,
                               change_rms, index_mix, _interp_nearest)
    gsv = init(native_lib_dir, files_dir, uid, profile)
    p = files_dir
    audio = np.frombuffer(bytes(audio_bytes), np.float32)

    # ---- 1. mel 提取 → rmvpe → f0(f0 供 protect 用) ----
    mel_basis = np.fromfile(os.path.join(p, "periphery", "mel_basis.bin"),
                            np.float32).reshape(128, 513)
    mel_in = mel_dlc(audio, mel_basis)                      # [1,256,128]
    gsv.init(os.path.join(p, "models", "rmvpe_fp32_256.bin"), "rmvpe_fp32_256")
    o = _execute(gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
    sal = np.asarray(list(o.values())[0]).reshape(256, 360)
    f0 = f0_decode(sal, thred=0.03)
    f0 = f0_uv_interp(f0)                                   # 无声帧插值
    if f0_up_key != 0:
        f0 = f0 * pow(2, f0_up_key / 12)                    # 变调
    coarse = f0_to_coarse(f0, 224)
    emb = np.load(os.path.join(p, "periphery", "emb_params.npz"))
    pe = pitch_embedding(coarse[None], emb["emb_pitch_weight"]).transpose(0, 2, 1)
    params = json.load(open(os.path.join(p, "periphery", "sine_params.json")))
    sine = sine_source(f0[:224].astype(np.float32)[None], params,
                       seed=0, use_random=True).transpose(0, 2, 1)

    # ---- 2. hubert 8 块 → index_mix → protect → interp → phone ----
    gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
             "hubert_mix_def_t4800")
    blocks, nf50 = hubert_blocks(audio)
    feats = []
    for b in blocks:
        o = _execute(gsv, {"source": np.ascontiguousarray(b)})
        feats.append(np.asarray(list(o.values())[0]).reshape(14, 768))
    F50 = np.concatenate(feats, 0)[:112]                    # [112,768]
    if index_rate > 0:
        F50m = index_mix(F50, _idx_vecs(p), index_rate)     # 音色映射
    else:
        F50m = F50
    F50o = F50.copy() if protect < 0.5 else None            # 原始特征(protect 用)
    f = np.concatenate([F50m, F50m[-1:]], 0)                # [113,768]
    f100 = _interp_nearest(f, 2)[:224]                      # [224,768]
    if F50o is not None:
        fo = np.concatenate([F50o, F50o[-1:]], 0)
        f100o = _interp_nearest(fo, 2)[:224]
        pitchff = np.full(224, protect, np.float32)         # 无声帧权重=protect
        pitchff[f0[:224] > 0] = 1.0
        f100 = f100 * pitchff[:, None] + f100o * (1 - pitchff[:, None])
    phone_nwc = f100.astype(np.float32)
    phone_dlc = phone_nwc[None].transpose(0, 2, 1)          # [1,768,224]

    # ---- 3. gen ----
    gsv.init(os.path.join(p, "models", "gen_fp32.bin"), "gen_fp32")
    tensors = {
        "phone": phone_dlc,
        "pitch_emb": pe,
        "phone_lengths": np.array([224], np.int32),
        "rnd": np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32),
        "speaker_emb": np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"), np.float32),
        "sine": sine,
    }
    o = _execute(gsv, tensors)
    out = np.asarray(list(o.values())[0]).ravel().astype(np.float32)
    if _STATE.get("profile"):
        try:
            od = _writable_dir(gsv, "/sdcard/rvc_exp", p)
            gsv.profile_dump(os.path.join(od, "live_gen_prof.jsonl"), 0, 0)
        except Exception:
            pass
    # RVC GUI:rms_mix_rate RMS 包络混合(保留原声动态;1=纯输出不混合)
    if rms_mix_rate != 1:
        out = change_rms(audio, 16000, out, 40000, rms_mix_rate).astype(np.float32)
    return out.tobytes()


def process_audio_ref(native_lib_dir, files_dir, uid, profile=False,
                      f0_up_key=0, rms_mix_rate=0.25, index_rate=0.75, protect=0.33):
    """模拟实时:打包参考音频(gya_audio.raw)走 process_audio 完整实时链路,
    返回 40k 变声音频 bytes(AudioTrack 播放)。用于对比 麦克风 vs 参考音频。"""
    audio = np.fromfile(os.path.join(files_dir, "testdata", "gya_audio.raw"),
                        np.float32)
    return process_audio(native_lib_dir, files_dir, uid, audio.tobytes(), profile,
                         f0_up_key, rms_mix_rate, index_rate, protect)


def self_test(native_lib_dir, files_dir, uid, profile=False,
              out_dir="/sdcard/rvc_exp"):
    gsv = init(native_lib_dir, files_dir, uid, profile)
    return run_gen(gsv, files_dir, out_dir)
