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

# 当前激活角色目录(角色包解压后的根目录)。None = 旧行为(全部文件从 files_dir 读)。
# 角色件(models/*.bin.bin、testdata/gf_*、periphery/索引)优先从这里读；
# 共享件(hubert/rmvpe/fcpe/df3r、mel/emb/sine/cent_table/proj)不在角色包里，
# _loc 自动回退到 files_dir。
_ROLE_DIR = None

_TYPE_DT = {0x32: np.int32, 0x64: np.int64, 0x216: np.float16, 0x232: np.float32}


def set_role_dir(d):
    """设置/清除当前角色目录(None = 清除,回退 files_dir 全量)。"""
    global _ROLE_DIR
    _ROLE_DIR = d


def _loc(base, rel):
    """角色件优先从 role_dir 读,否则回退 base(files_dir)。rel 用正斜杠相对路径。"""
    if _ROLE_DIR:
        cand = os.path.join(_ROLE_DIR, *rel.split("/"))
        if os.path.isfile(cand):
            return cand
    return os.path.join(base, *rel.split("/"))


def _preload(lib_dir):
    """bionic 启动时缓存搜索路径,setenv LD_LIBRARY_PATH 对已缓存路径无效
    → 必须按 soname 主动 dlopen(GSV 验证)。2.47 无 CalculatorStub,用 V69Stub。"""
    order = ["libqti_dsp.so", "libvmmem.so", "libcdsprpc.so", "libhidlbase.so",
             "libhidltransport.so", "libhwbinder.so", "libhardware.so",
             "libutils.so", "liblog.so", "libcutils.so", "libdmabufheap.so",
             "libbase.so", "libc++.so", "libc++_shared.so",
             "libQnnHtpV69Stub.so", "libQnnHtpV69CalculatorStub.so",
             "libQnnHtpNetRunExtensions.so",
             "vendor.qti.hardware.dsp@1.0.so"]
    for name in order:
        p = os.path.join(lib_dir, name)
        if os.path.exists(p):
            try:
                ctypes.CDLL(p, mode=ctypes.RTLD_GLOBAL)
            except Exception:
                pass


def init(native_lib_dir, files_dir, uid, profile=False, role_dir=None):
    """幂等初始化:env(三件套) + 预加载 + libgsv_qnn.so 绑定。
    role_dir: 当前角色包解压根目录;None 表示旧行为(全从 files_dir 读)。
    profile=True → QnnProfile 采集,结果可 profile_dump 到 /sdcard/rvc_exp。
    注意:GSV_NOPROFILE 由 libgsv_qnn.so 加载时缓存 → 切换 profiling 需重启 App 生效。"""
    if role_dir:
        set_role_dir(role_dir)
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

    # ---- 3. gen(老 gen_fp32 官方完整模型) ----
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
    gsv.init(os.path.join(p, "models", "dec_short_t61.bin.bin"), "dec_short_T61")
    R, B, WIN, UPP = 12, 37, 224, 400
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


def run_route2_istft(gsv, files_dir, out_dir, f0_method="fcpe", brightness=0.0):
    """iSTFT dec 块级。f0_method: fcpe(快) / rmvpe(与PC一致, 放最前绕切换坑) /
    gf_ref(直接用 PC 参考输入 gf_* 跳过现场提取, 验证 z_producer+dec+istft 链路)。
    T37 块窗 dec(R=12,B=13) 每块出 mag/phase -> numpy iSTFT -> 中间13帧拼接。
    """
    import json
    from rvc_periphery import (hubert_blocks, mel_dlc, f0_decode, f0_to_coarse,
                               pitch_embedding, sine_source, f0_uv_interp,
                               _interp_nearest, istft_numpy, fcpe_decode,
                               brightness_apply)
    out_dir = _writable_dir(gsv, out_dir, files_dir)
    p = files_dir
    R, B = 12, 13
    t0 = time.perf_counter()

    if f0_method == "gf_ref":
        # ---- 验证模式: 完全用 PC 参考输入, 跳过 hubert/f0 ----
        phone_dlc = np.fromfile(os.path.join(p, "testdata", "gf_phone.bin"),
                                np.float32).reshape(1, 768, 224)
        pe = np.fromfile(os.path.join(p, "testdata", "gf_pitch_emb.bin"),
                         np.float32).reshape(1, 192, 224)
        sine = np.fromfile(os.path.join(p, "testdata", "gf_sine.bin"),
                           np.float32).reshape(1, 1, 89600)
        g = np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"),
                        np.float32)
        rnd = np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32)
        tag = "gf_ref"
        t_f0 = t_ph = 0.0
    else:
        # ---- 1. mel + f0 -> coarse -> pitch_emb / sine ----
        audio = np.fromfile(os.path.join(p, "testdata", "gya_audio.raw"), np.float32)
        mel_basis = np.fromfile(os.path.join(p, "periphery", "mel_basis.bin"),
                                np.float32).reshape(128, 513)
        mel_in = mel_dlc(audio, mel_basis)
        if f0_method == "rmvpe":
            gsv.init(os.path.join(p, "models", "rmvpe_fp32_256.bin"), "rmvpe_fp32_256")
            o = _execute(gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
            sal = np.asarray(list(o.values())[0]).reshape(256, 360)
            f0 = f0_decode(sal, thred=0.03)
        else:
            gsv.init(os.path.join(p, "models", "fcpe_256.bin"), "fcpe_256")
            cent_table = np.fromfile(os.path.join(p, "periphery", "cent_table.bin"),
                                     np.float32)
            o = _execute(gsv, {"mel": np.ascontiguousarray(mel_in[0].T.reshape(-1))})
            latent = np.asarray(list(o.values())[0]).reshape(256, 360)
            f0 = fcpe_decode(latent, cent_table, 0.006)
        f0 = f0_uv_interp(f0)
        # 轻中值平滑(窗口3): 去单帧抖动, 提升 dec 输出透亮度(对齐 PC 参考平滑)
        _f0v = f0.copy()
        for _i in range(f0.size):
            _a, _b = max(0, _i - 1), min(f0.size, _i + 2)
            _f0v[_i] = np.median(f0[_a:_b])
        f0 = _f0v
        coarse = f0_to_coarse(f0, 224)
        emb = np.load(os.path.join(p, "periphery", "emb_params.npz"))
        pe = pitch_embedding(coarse[None], emb["emb_pitch_weight"]).transpose(0, 2, 1)
        params = json.load(open(os.path.join(p, "periphery", "sine_params.json")))
        sine = sine_source(f0[:224].astype(np.float32)[None], params,
                           seed=0, use_random=True).transpose(0, 2, 1)
        t_f0 = (time.perf_counter() - t0) * 1000

        # ---- 2. hubert 8 块 -> phone [1,768,224] NCW ----
        gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
                 "hubert_mix_def_t4800")
        blocks, _ = hubert_blocks(audio)
        feats = []
        for b in blocks:
            o = _execute(gsv, {"source": np.ascontiguousarray(b)})
            feats.append(np.asarray(list(o.values())[0]).reshape(14, 768))
        F50 = np.concatenate(feats, 0)[:112]
        f = np.concatenate([F50, F50[-1:]], 0)
        f100 = _interp_nearest(f, 2)[:224]
        phone_dlc = f100.astype(np.float32)[None].transpose(0, 2, 1)
        t_ph = (time.perf_counter() - t0) * 1000
        g = np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"), np.float32)
        rnd = np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32)
        tag = f0_method
        # 现场 vs 参考 分段对比(定位差异段)
        _gfp = np.fromfile(os.path.join(p, "testdata", "gf_phone.bin"), np.float32).reshape(1, 768, 224)
        _gpe = np.fromfile(os.path.join(p, "testdata", "gf_pitch_emb.bin"), np.float32).reshape(1, 192, 224)
        _gsi = np.fromfile(os.path.join(p, "testdata", "gf_sine.bin"), np.float32).reshape(1, 1, 89600)
        _cp = np.corrcoef(phone_dlc.ravel(), _gfp.ravel())[0, 1]
        _ce = np.corrcoef(pe.ravel(), _gpe.ravel())[0, 1]
        _cs = np.corrcoef(sine.ravel(), _gsi.ravel())[0, 1]
        print("[RVC-ISTFT] 现场vs参考(%s): phone=%.3f pe=%.3f sine=%.3f" % (tag, _cp, _ce, _cs), flush=True)

    # ---- 3. gen: z_producer + T37 dec 块级 + numpy iSTFT ----
    gsv.init(os.path.join(p, "models", "z_producer.bin.bin"), "z_producer")
    o = _execute(gsv, {
        "phone": np.ascontiguousarray(phone_dlc.reshape(-1)),
        "g": np.ascontiguousarray(g.reshape(-1)),
        "pitch_emb": np.ascontiguousarray(pe.reshape(-1)),
        "lengths": np.array([224], np.int32),
        "rnd": np.ascontiguousarray(rnd.reshape(-1)),
    })
    z = np.asarray(list(o.values())[0]).reshape(1, 192, 224)
    t_zp = (time.perf_counter() - t0) * 1000

    gsv.init(os.path.join(p, "models",
             "dec_distill_jielaide_4lvl_T37.sm8475.bin.bin"),
             "dec_distill_jielaide_4lvl_T37")
    zp = np.concatenate([np.repeat(z[:, :, :1], R, 2), z,
                         np.repeat(z[:, :, -1:], R + B, 2)], 2)      # [1,192,261]
    sp = np.concatenate([np.repeat(sine[:, :, :400], R, 2), sine,
                         np.repeat(sine[:, :, -400:], R + B, 2)], 2)
    segs, t_dec = [], 0.0
    for s0 in range(0, 224, B):            # 18 blocks
        lo, hi = s0, s0 + B + 2 * R
        t1 = time.perf_counter()
        outs = gsv.run({
            "z": np.ascontiguousarray(zp[:, :, lo:hi].transpose(0, 2, 1).reshape(-1)),
            "sine": np.ascontiguousarray(sp[:, :, lo * 400:hi * 400].reshape(-1)),
            "g": np.ascontiguousarray(g.reshape(-1))})
        t_dec += time.perf_counter() - t1
        oo = list(outs.values())                 # [0]=mag [1]=phase
        mag = np.asarray(oo[0]).ravel().astype(np.float32).reshape(1, 9, -1)
        phase = np.asarray(oo[1]).ravel().astype(np.float32).reshape(1, 9, -1)
        w = istft_numpy(mag, phase, 16, 4, length=37 * 400)[0, 0]
        segs.append(w[R * 400:(R + B) * 400])
    audio_out = np.concatenate(segs)[:224 * 400]
    if brightness > 0:
        audio_out = brightness_apply(audio_out, float(brightness))
    audio_out.tofile(os.path.join(out_dir, "route2_istft_audio.raw"))
    ref = np.fromfile(os.path.join(p, "testdata", "gya_ref_audio.raw"),
                      np.float32).ravel()
    n = min(audio_out.size, ref.size)
    ca = np.corrcoef(audio_out[:n], ref[:n])[0, 1]
    res = ("route2_istft(%s,b=%d): f0=%.0fms phone=%.0fms z_prod=%.0fms "
           "dec=%d块 avg=%.0fms corr=%.4f out=%dB" % (
        tag, int(round(brightness * 100)), t_f0, t_ph, t_zp, len(segs), t_dec / len(segs) * 1000, ca, audio_out.nbytes))
    print("[RVC-ISTFT] " + res, flush=True)
    return res


def self_test_route2_istft(native_lib_dir, files_dir, uid, profile=False,
                           out_dir="/sdcard/rvc_exp", f0_method="fcpe", brightness=0.0):
    import traceback
    try:
        gsv = init(native_lib_dir, files_dir, uid, profile)
        return run_route2_istft(gsv, files_dir, out_dir, f0_method, brightness)
    except Exception:
        tb = traceback.format_exc()
        print("[RVC-ISTFT] EXC: %s" % tb, flush=True)
        return "EXC: " + tb.replace(chr(10), " | ")


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
_PROJ_CACHE = {}
_IDX_V2 = None


def _proj_mat(p):
    """通用随机投影矩阵 768→256(固定种子,与数据无关;换模型/角色复用)。"""
    if "proj" not in _PROJ_CACHE:
        _PROJ_CACHE["proj"] = np.fromfile(
            _loc(p, "periphery/proj.bin"), np.float32).reshape(768, 256)
    return _PROJ_CACHE["proj"]


def _idx_vecs(p):
    """降维搜索库(256 维):L2 最近邻搜索用(53MB)。"""
    if "idx" not in _IDX_CACHE:
        _IDX_CACHE["idx"] = np.fromfile(
            _loc(p, "periphery/idx256.bin"), np.float32).reshape(-1, 256)
    return _IDX_CACHE["idx"]


def _idx_vecs_full(p):
    """原始混合库(768 维):最近邻命中后取原始向量加权混合,输出 768 维喂 gen。"""
    if "idx_full" not in _IDX_CACHE:
        _IDX_CACHE["idx_full"] = np.fromfile(
            _loc(p, "periphery/naiqiawang_idx.bin"), np.float32).reshape(-1, 768)
    return _IDX_CACHE["idx_full"]


def _idx_v2(p):
    """索引平方和(常量,预处理缓存一次)——避免每轮对 52140×768 逐元素平方(~300ms)。"""
    global _IDX_V2
    if _IDX_V2 is None:
        _IDX_V2 = np.sum(_idx_vecs_full(p) * _idx_vecs_full(p), axis=1)
    return _IDX_V2


_IVF_CACHE = {}


def _ivf_cent768(p):
    """IVF 簇中心 [K,768] fp32。"""
    if "cent" not in _IVF_CACHE:
        _IVF_CACHE["cent"] = np.fromfile(
            _loc(p, "periphery/ivf_cent768.bin"), np.float32).reshape(-1, 768)
    return _IVF_CACHE["cent"]


def _ivf_members(p):
    """IVF 簇成员全局行号(按簇排序)[N] int32。"""
    if "members" not in _IVF_CACHE:
        _IVF_CACHE["members"] = np.fromfile(
            _loc(p, "periphery/ivf_members.bin"), np.int32)
    return _IVF_CACHE["members"]


def _ivf_offsets(p):
    """IVF 簇偏移 [K+1] int32。"""
    if "offsets" not in _IVF_CACHE:
        _IVF_CACHE["offsets"] = np.fromfile(
            _loc(p, "periphery/ivf_offsets.bin"), np.int32)
    return _IVF_CACHE["offsets"]


def _ivf_cent2(p):
    """IVF 簇中心平方和 [K] fp32。"""
    if "cent2" not in _IVF_CACHE:
        _IVF_CACHE["cent2"] = np.fromfile(
            _loc(p, "periphery/ivf_cent2.bin"), np.float32)
    return _IVF_CACHE["cent2"]


def _idx_mix_ivf(feats, p, rate, k=8, m=2):
    """768 精确空间的迷你 IVF index_mix(批量/流式共用)。"""
    from rvc_periphery import index_mix_ivf
    return index_mix_ivf(feats, _idx_vecs_full(p), _ivf_cent768(p),
                         _ivf_members(p), _ivf_offsets(p), _ivf_cent2(p),
                         _idx_v2(p), rate, k=k, m=m)


def _gen_newdec(gsv, p, phone_dlc, pe, sine, g, rnd):
    """新 gen: z_producer 整窗出 z[1,192,224] + T37 dec 块级(R=12,B=13) 18块
    -> mag/phase -> numpy iSTFT -> 中间13帧拼接 -> audio[1,1,89600]。"""
    from rvc_periphery import istft_numpy
    gsv.init(os.path.join(p, "models", "z_producer.bin.bin"), "z_producer")
    o = _execute(gsv, {
        "phone": np.ascontiguousarray(phone_dlc.reshape(-1)),
        "g": np.ascontiguousarray(g.reshape(-1)),
        "pitch_emb": np.ascontiguousarray(pe.reshape(-1)),
        "lengths": np.array([224], np.int32),
        "rnd": np.ascontiguousarray(rnd.reshape(-1)),
    })
    z = np.asarray(list(o.values())[0]).reshape(1, 192, 224)
    gsv.init(os.path.join(p, "models",
             "dec_distill_jielaide_4lvl_T37.sm8475.bin.bin"),
             "dec_distill_jielaide_4lvl_T37")
    R, B = 12, 13
    zp = np.concatenate([np.repeat(z[:, :, :1], R, 2), z,
                         np.repeat(z[:, :, -1:], R + B, 2)], 2)
    sp = np.concatenate([np.repeat(sine[:, :, :400], R, 2), sine,
                         np.repeat(sine[:, :, -400:], R + B, 2)], 2)
    segs_ = []
    for s0 in range(0, 224, B):
        lo, hi = s0, s0 + B + 2 * R
        outs = gsv.run({
            "z": np.ascontiguousarray(zp[:, :, lo:hi].transpose(0, 2, 1).reshape(-1)),
            "sine": np.ascontiguousarray(sp[:, :, lo * 400:hi * 400].reshape(-1)),
            "g": np.ascontiguousarray(g.reshape(-1))})
        oo = list(outs.values())
        mag = np.asarray(oo[0]).ravel().astype(np.float32).reshape(1, 9, -1)
        phase = np.asarray(oo[1]).ravel().astype(np.float32).reshape(1, 9, -1)
        w = istft_numpy(mag, phase, 16, 4, length=37 * 400)[0, 0]
        segs_.append(w[R * 400:(R + B) * 400])
    return np.concatenate(segs_)[:224 * 400]


def _gen_stream_decshort(gsv, p, phone_dlc, pe, sine, g, rnd):
    """T61 大窗流式: z_producer 每块滑窗(lengths=61, 61帧窗=12过去+37新+12未来)
    -> 前61帧z -> dec_short_t61(顺序 z,g,sine) 块级 -> 中间37帧拼接。
    dec 230ms/块 < 370ms 块音频 → 实时余量充足。"""
    T, R, B = 224, 12, 37
    WIN = R + B + R                    # 61 帧窗
    # pad 首尾(R 帧边缘复制), 保证滑窗在界内
    phone_pad = np.concatenate([np.repeat(phone_dlc[:, :, :1], R, 2), phone_dlc,
                                np.repeat(phone_dlc[:, :, -1:], R + B, 2)], 2)  # [1,768,285]
    pe_pad = np.concatenate([np.repeat(pe[:, :, :1], R, 2), pe,
                             np.repeat(pe[:, :, -1:], R + B, 2)], 2)          # [1,192,285]
    sine_pad = np.concatenate([np.repeat(sine[:, :, :400], R, 2), sine,
                               np.repeat(sine[:, :, -400:], R + B, 2)], 2)    # [1,1,114000]
    rnd2 = rnd.reshape(1, 224, 192)
    rnd_pad = np.concatenate([np.repeat(rnd2[:, :1, :], R, 1), rnd2,
                              np.repeat(rnd2[:, -1:, :], R + B, 1)], 1)       # [1,285,192]
    # 1) 6 块 z_producer 滑窗
    gsv.init(os.path.join(p, "models", "z_producer.bin.bin"), "z_producer")
    z_blocks = []
    for s0 in range(0, T, B):
        lo, hi = s0, s0 + WIN
        pp = np.zeros((1, 768, T), np.float32); pp[:, :, :WIN] = phone_pad[:, :, lo:hi]
        ee = np.zeros((1, 192, T), np.float32); ee[:, :, :WIN] = pe_pad[:, :, lo:hi]
        rr = np.zeros((1, T, 192), np.float32); rr[0, :WIN, :] = rnd_pad[0, lo:hi]
        o = _execute(gsv, {
            "phone": np.ascontiguousarray(pp.reshape(-1)),
            "g": np.ascontiguousarray(g.reshape(-1)),
            "pitch_emb": np.ascontiguousarray(ee.reshape(-1)),
            "lengths": np.array([WIN], np.int32),
            "rnd": np.ascontiguousarray(rr.reshape(-1)),
        })
        z61 = np.asarray(list(o.values())[0]).reshape(1, 192, T)[:, :, :WIN]
        z_blocks.append(z61)
    # 2) 6 块 dec_short_t61(顺序 z,g,sine)
    gsv.init(os.path.join(p, "models", "dec_short_t61.bin.bin"), "dec_short_T61")
    segs = []
    for i, s0 in enumerate(range(0, T, B)):
        lo, hi = s0, s0 + WIN
        z_in = np.ascontiguousarray(z_blocks[i].transpose(0, 2, 1).reshape(-1))
        s_in = np.ascontiguousarray(sine_pad[:, :, lo * 400:hi * 400].reshape(-1))
        o = _execute(gsv, {
            "z": z_in,
            "g": np.ascontiguousarray(g.reshape(-1)),
            "sine": s_in})
        a61 = np.asarray(list(o.values())[0]).ravel().astype(np.float32).reshape(WIN * 400)
        segs.append(a61[R * 400:(R + B) * 400])
    return np.concatenate(segs)[:T * 400]


def process_audio(native_lib_dir, files_dir, uid, audio_bytes, profile=False,
                  f0_up_key=0, rms_mix_rate=0.75, index_rate=0.75, protect=0.33,
                  f0_method="rmvpe"):
    """M2 实时链路入口:内存 16k fp32 音频(bytes) → 40k fp32 变声音频(bytes)。
    完整 RVC 推理:mel→rmvpe/fcpe→f0(uv插值+变调)→index音色混合→protect→phone
    →gen→rms混合。f0_up_key 变调半音; rms_mix_rate RMS 占比(默认0.25);
    index_rate 音色索引强度(GUI默认0.75); protect 清音保护(GUI默认0.33);
    f0_method F0 提取器(rmvpe=音准好/慢, fcpe=快8.8x/音准略逊)。"""
    import json
    from rvc_periphery import (hubert_blocks, mel_dlc, f0_decode, f0_to_coarse,
                               pitch_embedding, sine_source, f0_uv_interp,
                               change_rms, index_mix, _interp_nearest,
                               fcpe_decode)
    gsv = init(native_lib_dir, files_dir, uid, profile)
    p = files_dir
    audio = np.frombuffer(bytes(audio_bytes), np.float32)
    t0 = time.perf_counter()
    _tm = [t0]

    # ---- 1. mel 提取 → rmvpe/fcpe → f0(f0 供 protect 用) ----
    mel_basis = np.fromfile(os.path.join(p, "periphery", "mel_basis.bin"),
                            np.float32).reshape(128, 513)
    mel_in = mel_dlc(audio, mel_basis)                      # [1,256,128]
    if f0_method == "fcpe":
        gsv.init(os.path.join(p, "models", "fcpe_256.bin"), "fcpe_256")
        cent_table = np.fromfile(os.path.join(p, "periphery", "cent_table.bin"),
                                 np.float32)
        o = _execute(gsv, {"mel": np.ascontiguousarray(mel_in[0].T.reshape(-1))})
        latent = np.asarray(list(o.values())[0]).reshape(256, 360)
        f0 = fcpe_decode(latent, cent_table, 0.006)
        if _STATE.get("profile"):
            try:
                od = _writable_dir(gsv, "/sdcard/rvc_exp", p)
                gsv.profile_dump(os.path.join(od, "live_fcpe_prof.jsonl"), 0, 0)
            except Exception:
                pass
    else:
        gsv.init(os.path.join(p, "models", "rmvpe_fp32_256.bin"), "rmvpe_fp32_256")
        o = _execute(gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
        sal = np.asarray(list(o.values())[0]).reshape(256, 360)
        f0 = f0_decode(sal, thred=0.03)
    _tm.append(("mel+f0(%s)" % f0_method, time.perf_counter()))
    f0 = f0_uv_interp(f0)                                   # 无声帧插值
    if f0_up_key != 0:
        f0 = f0 * pow(2, f0_up_key / 12)                    # 变调
    coarse = f0_to_coarse(f0, 224)
    emb = np.load(os.path.join(p, "periphery", "emb_params.npz"))
    pe = pitch_embedding(coarse[None], emb["emb_pitch_weight"]).transpose(0, 2, 1)
    params = json.load(open(os.path.join(p, "periphery", "sine_params.json")))
    sine = sine_source(f0[:224].astype(np.float32)[None], params,
                       seed=0, use_random=True).transpose(0, 2, 1)
    _tm.append(("f0post", time.perf_counter()))

        # ---- 2+3. 固定hop hubert(整段F50连续) + 整段phone(224帧,pad过去12) ----
    #     + 块级 z_producer/dec_short_t61 + overlap-add 交叉淡化(消除接缝/顿挫)
    gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
             "hubert_mix_def_t4800")
    _tm.append(("ph:init", time.perf_counter()))
    blocks, nf50 = hubert_blocks(audio)
    feats = []
    for b in blocks:
        o = _execute(gsv, {"source": np.ascontiguousarray(b)})
        feats.append(np.asarray(list(o.values())[0]).reshape(14, 768))
    F50 = np.concatenate(feats, 0)[:112]                    # [112,768] 固定hop连续
    _tm.append(("ph:hubert", time.perf_counter()))
    if index_rate > 0:
        F50m = _idx_mix_ivf(F50, p, index_rate)
    else:
        F50m = F50
    _tm.append(("ph:idxmix", time.perf_counter()))
    F50o = F50.copy() if protect < 0.5 else None
    T, R, B, WIN = 224, 12, 37, 61
    # 整段 phone(100Hz 224帧) + protect
    idx_all = np.round(np.arange(T) / 2).astype(int).clip(0, 111)
    f_all = F50m[idx_all].astype(np.float32)                # [224,768]
    if F50o is not None:
        fo_all = F50o[idx_all]
        pitchff = np.full(T, protect, np.float32)
        pitchff[f0[:T] > 0] = 1.0
        f_all = f_all * pitchff[:, None] + fo_all * (1 - pitchff[:, None])
    phone_all = f_all[None].transpose(0, 2, 1)              # [1,768,224]
    phone_pad = np.concatenate([np.repeat(phone_all[:, :, :1], R, 2),
                                phone_all,
                                np.repeat(phone_all[:, :, -1:], R + B, 2)], 2)  # [1,768,285]
    _tm.append(("phone", time.perf_counter()))
    g = np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"), np.float32)
    rnd = np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32)
    rnd2 = rnd.reshape(1, 224, 192)
    # z/dec 需要的 pad(覆盖最后块窗到 283 帧)
    sine_pad = np.concatenate([np.repeat(sine[:, :, :400], R, 2), sine,
                               np.repeat(sine[:, :, -400:], R + B, 2)], 2)  # [1,1,114000]
    pe_pad = np.concatenate([np.repeat(pe[:, :, :1], R, 2), pe,
                             np.repeat(pe[:, :, -1:], R + B, 2)], 2)  # [1,192,285]
    rnd_pad = np.concatenate([np.repeat(rnd2[:, :1, :], R, 1), rnd2,
                              np.repeat(rnd2[:, -1:, :], R + B, 1)], 1)  # [1,285,192]
    # overlap-add 拼接(交叉淡化消除接缝)
    PAD0 = R * 400
    total = T * 400 + PAD0 + (R + B) * 400                  # 114000
    buf = np.zeros(total, np.float32)
    wsum = np.zeros(total, np.float32)
    ramp = 0.5 * (1 - np.cos(np.pi * np.arange(R * 400) / (R * 400)))  # 余弦淡入淡出(更平滑, 消除拐点)
    t_dec_all = 0.0
    for s0 in range(0, T, B):
        # 窗 = pad后 [s0, s0+61] = 12过去 + 37新 + 12未来
        pp = np.zeros((1, 768, T), np.float32); pp[:, :, :WIN] = phone_pad[:, :, s0:s0 + WIN]
        ee = np.zeros((1, 192, T), np.float32); ee[:, :, :WIN] = pe_pad[:, :, s0:s0 + WIN]
        rr = np.zeros((1, T, 192), np.float32); rr[0, :WIN, :] = rnd_pad[0, s0:s0 + WIN]
        # z_producer — 每块前 init(幂等缓存)
        gsv.init(os.path.join(p, "models", "z_producer.bin.bin"), "z_producer")
        o = _execute(gsv, {
            "phone": np.ascontiguousarray(pp.reshape(-1)),
            "g": np.ascontiguousarray(g.reshape(-1)),
            "pitch_emb": np.ascontiguousarray(ee.reshape(-1)),
            "lengths": np.array([WIN], np.int32),
            "rnd": np.ascontiguousarray(rr.reshape(-1))})
        z61 = np.asarray(list(o.values())[0]).reshape(1, 192, T)[:, :, :WIN]
        # dec_short_t61 — 每块前 init
        gsv.init(os.path.join(p, "models", "dec_short_t61.bin.bin"), "dec_short_T61")
        t0 = time.perf_counter()
        z_in = np.ascontiguousarray(z61.transpose(0, 2, 1).reshape(-1))
        s_in = np.ascontiguousarray(sine_pad[:, :, s0 * 400:(s0 + WIN) * 400].reshape(-1))
        o = _execute(gsv, {"z": z_in,
                           "g": np.ascontiguousarray(g.reshape(-1)),
                           "sine": s_in})
        t_dec_all += time.perf_counter() - t0
        a61 = np.asarray(list(o.values())[0]).ravel().astype(np.float32).reshape(WIN * 400)
        # a61[k] 对应窗内帧 k; 窗0=s0 pad后, 新帧区(k=12..49)对应输出帧 s0..s0+37
        base = (s0 - R) * 400 + PAD0
        w = np.ones(WIN * 400, np.float32)
        w[:R * 400] = ramp
        w[(WIN - R) * 400:] = 1.0 - ramp
        buf[base:base + WIN * 400] += a61 * w
        wsum[base:base + WIN * 400] += w
    out = (buf[PAD0:PAD0 + T * 400] /
           np.maximum(wsum[PAD0:PAD0 + T * 400], 1e-6)).astype(np.float32)
    _tm.append(("gen:stream", time.perf_counter()))
    if _STATE.get("profile"):
        try:
            od = _writable_dir(gsv, "/sdcard/rvc_exp", p)
            gsv.profile_dump(os.path.join(od, "live_gen_prof.jsonl"), 0, 0)
        except Exception:
            pass
# RVC GUI:rms_mix_rate RMS 包络混合(保留原声动态;1=纯输出不混合)
    if rms_mix_rate != 1:
        out = change_rms(audio, 16000, out, 40000, rms_mix_rate).astype(np.float32)
    _tm.append(("rms", time.perf_counter()))
    # 分段增量耗时(相对上一段)
    last = t0
    segs = []
    for name, t in _tm[1:]:
        segs.append("%s=%dms" % (name, (t - last) * 1000))
        last = t
    print("[RVC-T] %s TOTAL=%dms" % (" ".join(segs), (time.perf_counter() - t0) * 1000))
    return out.tobytes()


def process_stream_v2(native_lib_dir, files_dir, uid, audio_bytes, profile=False,
                      f0_up_key=0, rms_mix_rate=0.75, index_rate=0.75, protect=0.33,
                      f0_method="rmvpe", f0_win=64):
    """Step2 真流式核心(官方方式): f0 用 rmvpe 短窗逐块 + 滚动缓存(cache_pitch 思路)。
    每块 f0_win 帧mel窗 = 过去(f0_win-37-12) + 当前37 + 未来12 → rmvpe → 当前块37帧 f0 + 未来12帧。
    不需要整段 rmvpe256, 启动等 12 帧未来(300ms)即可; 其余(hubert/z/dec)复用 Step1 块级链。
    f0_win=64(默认,过去15,手机~35ms/块,用户选定) 或 96(过去47,更稳~55ms/块)。
    """
    import json
    from rvc_periphery import (hubert_blocks, hubert_blocks_at, mel_dlc, f0_decode, f0_to_coarse,
                               pitch_embedding, sine_source, f0_uv_interp,
                               change_rms, index_mix, _interp_nearest)
    gsv = init(native_lib_dir, files_dir, uid, profile)
    p = files_dir
    audio = np.frombuffer(bytes(audio_bytes), np.float32)
    t0 = time.perf_counter()
    _tm = [t0]
    mel_basis = np.fromfile(os.path.join(p, "periphery", "mel_basis.bin"),
                            np.float32).reshape(128, 513)

    # ---- 1. f0: rmvpe 短窗逐块 + 滚动缓存 ----
    T, R, B, WIN = 224, 12, 37, 61
    F0_POST = 12                                  # z/dec 未来帧(300ms)
    F0_PRE = f0_win - B - F0_POST                 # 96->47, 64->15
    gsv.init(os.path.join(p, "models", "rmvpe_fp32_%d.bin.bin" % f0_win),
             "rmvpe_fp32_%d" % f0_win)
    cache_f0 = np.zeros(T + F0_POST, np.float32)
    for s0 in range(0, T - B + 1, B):
        lo = (s0 - F0_PRE) * 160
        hi = (s0 + B + F0_POST) * 160
        seg = audio[max(0, lo):hi]
        if len(seg) < hi - max(0, lo):
            seg = np.pad(seg, (0, (hi - max(0, lo)) - len(seg)))
        mel_in = mel_dlc(seg, mel_basis, n_frames=f0_win)   # [1,f0_win,128]
        o = _execute(gsv, {"input": np.ascontiguousarray(mel_in.reshape(-1))})
        sal = np.asarray(list(o.values())[0]).reshape(f0_win, 360)
        f0w = f0_uv_interp(f0_decode(sal, thred=0.03))
        cache_f0[s0:s0 + B] = f0w[F0_PRE:F0_PRE + B]                    # 当前块
        if s0 + B + F0_POST <= len(cache_f0):
            cache_f0[s0 + B:s0 + B + F0_POST] = f0w[F0_PRE + B:]        # 未来12
    f0 = cache_f0[:T]
    _tm.append(("f0:shortwin", time.perf_counter()))
    f0 = f0_uv_interp(f0)
    if f0_up_key != 0:
        f0 = f0 * pow(2, f0_up_key / 12)
    coarse = f0_to_coarse(f0, T)
    emb = np.load(os.path.join(p, "periphery", "emb_params.npz"))
    pe = pitch_embedding(coarse[None], emb["emb_pitch_weight"]).transpose(0, 2, 1)
    params = json.load(open(os.path.join(p, "periphery", "sine_params.json")))
    sine = sine_source(f0[:T].astype(np.float32)[None], params,
                       seed=0, use_random=True).transpose(0, 2, 1)
    _tm.append(("f0post", time.perf_counter()))

    # ---- 2+3. hubert 块级增量 + phone 增量 + 块级 z/dec + overlap-add ----
    gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
             "hubert_mix_def_t4800")
    nf50 = int(np.floor(len(audio) / 320))
    F50m = np.zeros((nf50, 768), np.float32)
    F50raw = np.zeros((nf50, 768), np.float32)
    F50_mixed = np.zeros(nf50, np.bool_)
    blk_done = 0
    g = np.fromfile(os.path.join(p, "testdata", "gf_speaker_emb.bin"), np.float32)
    rnd = np.fromfile(os.path.join(p, "testdata", "gf_rnd.bin"), np.float32)
    rnd2 = rnd.reshape(1, 224, 192)
    sine_pad = np.concatenate([np.repeat(sine[:, :, :400], R, 2), sine,
                               np.repeat(sine[:, :, -400:], R + B, 2)], 2)
    pe_pad = np.concatenate([np.repeat(pe[:, :, :1], R, 2), pe,
                             np.repeat(pe[:, :, -1:], R + B, 2)], 2)
    rnd_pad = np.concatenate([np.repeat(rnd2[:, :1, :], R, 1), rnd2,
                              np.repeat(rnd2[:, -1:, :], R + B, 1)], 1)
    PAD0 = R * 400
    total = T * 400 + PAD0 + (R + B) * 400
    buf = np.zeros(total, np.float32)
    wsum = np.zeros(total, np.float32)
    ramp = 0.5 * (1 - np.cos(np.pi * np.arange(R * 400) / (R * 400)))
    for s0 in range(0, T, B):
        # hubert 增量: 本 dec 窗(dec帧[s0-R, s0+48])需要 F50 帧 → 按需算 hubert 块
        hi_f = int(np.ceil((s0 - R + WIN - 1) / 2))       # 需要覆盖的最大 F50 索引
        need_f = min(hi_f, nf50 - 1)
        need_blk = int(np.ceil((need_f + 1) / 14))
        while blk_done < need_blk:
            gsv.init(os.path.join(p, "models", "hubert_mix_def_t4800.bin"),
                     "hubert_mix_def_t4800")       # 幂等激活(C 层停在最后 init 的 bin)
            o = _execute(gsv, {"source": hubert_blocks_at(audio, blk_done)})
            fb = np.asarray(list(o.values())[0]).reshape(14, 768)
            b0 = blk_done * 14
            F50raw[b0:b0 + 14] = fb
            F50m[b0:b0 + 14] = fb
            blk_done += 1
        # index_mix 增量(连续未混帧批量 IVF)
        if index_rate > 0:
            j = 0
            while j <= need_f:
                if j < F50_mixed.shape[0] and not F50_mixed[j]:
                    j2 = j
                    while j2 <= need_f and j2 < F50_mixed.shape[0] and not F50_mixed[j2]:
                        j2 += 1
                    F50m[j:j2] = _idx_mix_ivf(F50m[j:j2], p, index_rate)
                    F50_mixed[j:j2] = True
                    j = j2
                else:
                    j += 1
        # 组装本 dec 窗 phone [1,768,WIN](dec帧→F50 取整, 与整段 idx_all 一致)
        idxw = np.round((np.arange(s0 - R, s0 - R + WIN)) / 2.0).astype(int).clip(0, nf50 - 1)
        fw = F50m[idxw].astype(np.float32)
        if protect < 0.5:
            fo = F50raw[idxw]
            pff = np.full(WIN, protect, np.float32)
            i0 = max(0, s0 - R)
            f0seg = f0[i0:i0 + WIN]
            pff[:len(f0seg)][f0seg > 0] = 1.0
            fw = fw * pff[:, None] + fo * (1 - pff[:, None])
        phone_w = fw[None].transpose(0, 2, 1)
        # z/dec 块级
        pp = np.zeros((1, 768, T), np.float32); pp[:, :, :WIN] = phone_w
        ee = np.zeros((1, 192, T), np.float32); ee[:, :, :WIN] = pe_pad[:, :, s0:s0 + WIN]
        rr = np.zeros((1, T, 192), np.float32); rr[0, :WIN, :] = rnd_pad[0, s0:s0 + WIN]
        gsv.init(os.path.join(p, "models", "z_producer.bin.bin"), "z_producer")
        o = _execute(gsv, {
            "phone": np.ascontiguousarray(pp.reshape(-1)),
            "g": np.ascontiguousarray(g.reshape(-1)),
            "pitch_emb": np.ascontiguousarray(ee.reshape(-1)),
            "lengths": np.array([WIN], np.int32),
            "rnd": np.ascontiguousarray(rr.reshape(-1))})
        z61 = np.asarray(list(o.values())[0]).reshape(1, 192, T)[:, :, :WIN]
        gsv.init(os.path.join(p, "models", "dec_short_t61.bin.bin"), "dec_short_T61")
        z_in = np.ascontiguousarray(z61.transpose(0, 2, 1).reshape(-1))
        s_in = np.ascontiguousarray(sine_pad[:, :, s0 * 400:(s0 + WIN) * 400].reshape(-1))
        o = _execute(gsv, {"z": z_in,
                           "g": np.ascontiguousarray(g.reshape(-1)),
                           "sine": s_in})
        a61 = np.asarray(list(o.values())[0]).ravel().astype(np.float32).reshape(WIN * 400)
        base = (s0 - R) * 400 + PAD0
        w = np.ones(WIN * 400, np.float32)
        w[:R * 400] = ramp
        w[(WIN - R) * 400:] = 1.0 - ramp
        buf[base:base + WIN * 400] += a61 * w
        wsum[base:base + WIN * 400] += w
    out = (buf[PAD0:PAD0 + T * 400] /
           np.maximum(wsum[PAD0:PAD0 + T * 400], 1e-6)).astype(np.float32)
    _tm.append(("gen:stream", time.perf_counter()))
    if _STATE.get("profile"):
        try:
            od = _writable_dir(gsv, "/sdcard/rvc_exp", p)
            gsv.profile_dump(os.path.join(od, "live_gen_prof.jsonl"), 0, 0)
        except Exception:
            pass
    if rms_mix_rate != 1:
        out = change_rms(audio, 16000, out, 40000, rms_mix_rate).astype(np.float32)
    _tm.append(("rms", time.perf_counter()))
    last = t0
    segs = []
    for name, t in _tm[1:]:
        segs.append("%s=%dms" % (name, (t - last) * 1000))
        last = t
    print("[RVC-T] %s TOTAL=%dms" % (" ".join(segs), (time.perf_counter() - t0) * 1000))
    return out.tobytes()


def process_audio_ref(native_lib_dir, files_dir, uid, profile=False,
                      f0_up_key=0, rms_mix_rate=0.75, index_rate=0.75, protect=0.33,
                      f0_method="rmvpe"):
    """模拟实时:打包参考音频(gya_audio.raw)走 process_audio 完整实时链路,
    返回 40k 变声音频 bytes(AudioTrack 播放)。用于对比 麦克风 vs 参考音频。"""
    audio = np.fromfile(os.path.join(files_dir, "testdata", "gya_audio.raw"),
                        np.float32)
    return process_audio(native_lib_dir, files_dir, uid, audio.tobytes(), profile,
                         f0_up_key, rms_mix_rate, index_rate, protect, f0_method)


def process_stream_v2_ref(native_lib_dir, files_dir, uid, profile=False,
                          f0_up_key=0, rms_mix_rate=0.75, index_rate=0.75, protect=0.33,
                          f0_method="rmvpe"):
    """Step2 真流式模拟:打包参考音频(gya_audio.raw)走 process_stream_v2(rmvpe64 短窗 f0),
    返回 40k 变声音频 bytes(AudioTrack 播放)。对比 Step1 整段 f0(process_audio_ref)。"""
    audio = np.fromfile(os.path.join(files_dir, "testdata", "gya_audio.raw"),
                        np.float32)
    return process_stream_v2(native_lib_dir, files_dir, uid, audio.tobytes(), profile,
                             f0_up_key, rms_mix_rate, index_rate, protect, f0_method)


def preload_default(native_lib_dir, files_dir, uid, profile=False):
    """启动预载默认管线:hubert + fcpe 常驻内存(实时零加载开销)。
    gen_fp32 是旧单模型路径,实时链路已改 z_producer+dec_short,不再预载;
    rmvpe 不预载,由 RVCStream 现场加载(rmvpe_fp32_64)。"""
    gsv = init(native_lib_dir, files_dir, uid, profile)
    for bin_name, gname in [("hubert_mix_def_t4800.bin", "hubert_mix_def_t4800"),
                            ("fcpe_256.bin", "fcpe_256")]:
        gsv.init(os.path.join(files_dir, "models", bin_name), gname)
    # BLAS 探针:判断 numpy 是否吃到 OpenBLAS(GFLOP/s >> 3 则有)
    try:
        import time as _t
        A = np.random.RandomState(1).rand(112, 768).astype(np.float32)
        B = np.random.RandomState(2).rand(20000, 768).astype(np.float32)
        _t0 = _t.perf_counter()
        A @ B.T
        _dt = _t.perf_counter() - _t0
        print("[RVC-BLAS] 768x20000 GEMM: %.0fms  %.1f GFLOP/s" % (_dt * 1000, 2 * 112 * 20000 * 768 / _dt / 1e9))
    except Exception as _e:
        print("[RVC-BLAS] probe fail: %s" % _e)
    return "ok"


def init_f0(native_lib_dir, files_dir, uid, f0_method, profile=False):
    """F0 提取器现场加载(gsv 同名缓存命中则直接激活,不重载)。"""
    gsv = init(native_lib_dir, files_dir, uid, profile)
    if f0_method == "fcpe":
        gsv.init(os.path.join(files_dir, "models", "fcpe_256.bin"), "fcpe_256")
    else:
        gsv.init(os.path.join(files_dir, "models", "rmvpe_fp32_256.bin"), "rmvpe_fp32_256")
    return "ok"


def self_test(native_lib_dir, files_dir, uid, profile=False,
              out_dir="/sdcard/rvc_exp"):
    gsv = init(native_lib_dir, files_dir, uid, profile)
    return run_gen(gsv, files_dir, out_dir)


# ---------------- RVCStream 真流式入口(Kotlin 实时 I/O 调用) ----------------
# 全局单例: Kotlin 通过 stream_* 调用, 状态机常驻跨块(音频块逐步 push/输出)。
_STREAM = None
# 实时调试统计(截幅诊断): 原始输入峰值/削波数 + 输出硬clip前峰值/超1.0数
_DBG = {"in_peak": 0.0, "in_clip": 0, "out_peak": 0.0, "out_clip": 0}


def stream_create(native_lib_dir, files_dir, uid, profile=False,
                  f0_up_key=0, rms_mix_rate=0.75, index_rate=0.75, protect=0.33,
                  f0_win=64, future=30, role_dir=None):
    """创建/重建 RVCStream 全局单例(真流式状态机)。参数变化时重建。
    role_dir: 角色包解压根目录;None = 全从 files_dir 读(旧行为)。
    角色切换:同名图槽(z_producer/dec_short_T61)先 remove,强制加载新 bin。"""
    global _STREAM, _DBG
    _DBG = {"in_peak": 0.0, "in_clip": 0, "out_peak": 0.0, "out_clip": 0}
    # 角色变了:先释放旧角色占用且同名缓存的图,否则 gsv_init 同名不重载
    old_role = _STATE.get("loaded_role_dir")
    if old_role is not None and old_role != role_dir:
        gsv = _STATE.get("gsv")
        if gsv is not None:
            for name in ("z_producer", "dec_short_T61"):
                try:
                    gsv.remove(name)
                except Exception:
                    pass
        _STATE["loaded_role_dir"] = None
    from rvc_stream import RVCStream
    _STREAM = RVCStream(native_lib_dir, files_dir, uid, profile=profile,
                        f0_up_key=f0_up_key, rms_mix_rate=rms_mix_rate,
                        index_rate=index_rate, protect=protect,
                        f0_win=f0_win, future=future, role_dir=role_dir)
    _STATE["loaded_role_dir"] = role_dir
    return True


def stream_push(audio_bytes):
    """推入 16k fp32 音频块, 返回 40k fp32 输出段 bytes(可能为空)。"""
    global _STREAM, _DBG
    if _STREAM is None:
        raise RuntimeError("stream not created")
    arr = np.frombuffer(bytes(audio_bytes), np.float32)
    if arr.size:
        a = np.abs(arr)
        _DBG["in_peak"] = max(_DBG["in_peak"], float(np.max(a)))
        _DBG["in_clip"] += int(np.sum(a >= 0.999))
    out = _STREAM.push(arr)
    if out.size == 0:
        return b""
    o = np.abs(out)
    _DBG["out_peak"] = max(_DBG["out_peak"], float(np.max(o)))
    _DBG["out_clip"] += int(np.sum(o > 1.0))
    return np.clip(out, -1.0, 1.0).astype(np.float32).tobytes()


def stream_debug_snapshot():
    """实时截幅诊断快照: 原始输入峰值/削波数 + 输出硬clip前峰值/超1.0数(字符串)。"""
    if _STREAM is None:
        return ""
    d = _DBG
    return ("in_peak=%.3f in_clip=%d out_peak=%.3f out_clip=%d"
            % (d["in_peak"], d["in_clip"], d["out_peak"], d["out_clip"]))


def stream_measure_latency(blocks=4):
    """延迟自测: 合成音频推入, 返回稳态每块 T_proc(ms, -1=未创建)。"""
    global _STREAM
    if _STREAM is None:
        return -1.0
    try:
        return float(_STREAM.measure_latency(blocks=blocks)["block_ms"])
    except Exception as _e:
        import traceback
        traceback.print_exc()
        return -2.0


def stream_reset():
    """重置状态机(释放旧实例, 下次 push 前需重建)。"""
    global _STREAM
    _STREAM = None
    return True


def stream_clear():
    """轻量清空当前 RVCStream 的内部流式状态(不释放/重载模型)。
    新一段录音开始前调用, 防止上一段音频尾残留在 acc/buf/f0/降噪器里。"""
    global _STREAM
    if _STREAM is None:
        return True
    _STREAM.clear_state()
    return True


def stream_cleanup():
    """彻底释放 gsv graph(停止桥接时调用), 防止反复开关桥接超过 8 graph 上限。"""
    global _STREAM
    _STREAM = None
    _STATE["loaded_role_dir"] = None
    gsv = _STATE.get("gsv")
    if gsv is not None:
        try:
            gsv.cleanup()
        except Exception:
            pass
    return True
