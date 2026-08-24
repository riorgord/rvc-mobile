# RVC App 外围移植计划（3 个 NPU 模型之外的全部外围）

> 来源：subagent 调研（2026-08-23），源码位置 `D:\AI\claw_repair\research\rvc`（新）+ `E:\...\RVC20240604Nvidia`（旧）。

## 全链路数据流（手机端目标）
```
AudioRecord(48k) → 混单声道 → 噪声门
  → resample 48k→16k → 高通滤波(5阶48Hz butter) → [hubert 块推进 hop 4480@16k]
      → hubert NPU → 特征 50Hz → 线性插值→100Hz → phone[1,224,768]
  → resample 16k → mel 提取(hann FFT1024/hop160 → 128 mel htk → log clamp)
      → rmvpe NPU → salience[256,360] → f0 decode → coarse / pitch_emb / sine
  → gen NPU(整图6输入) → audio[1,1,89600]@40k
  → resample 40k→48k → RMS 混音 → SOLA 交叉淡化 → AudioTrack(48k)
```

## A 档：纯计算，直接写 C++/Kotlin（90% 工作量）
| # | 组件 | 要点 | golden 对拍 |
|---|---|---|---|
| A1 | 重采样 3 组固定率（48k↔16k、40k↔48k） | 用 soxr/libsamplerate（C 档）或定长 sinc 核 | — |
| A2 | **mel 提取（最大坑）** | hann(1024,periodic) → FFT1024/hop160 center=True 前后补512零 → 128 mel(htk=True,fmin30,fmax8000) → log clamp 1e-5 | `phone_pkg/test/rmvpe/mel.bin` |
| A3 | salience→f0 解码 | argmax + ±4 pad + 9-bin 加权平均 + thred=0.03 置零 + 10·2^(cents/1200) + UV 线性插值 | `ref_salience_f32.bin` |
| A4 | f0→coarse | 1127log(1+f0/700) 归一化[1,255] clamp round | — |
| A5 | pitch_emb/speaker_emb | 纯查表 256×192 / 109×256（emb_params） | `pitch_emb.bin` `speaker_emb.bin` |
| A6 | sine 组装 | 相位累积 cumsum/fmod + sin + Linear + tanh；噪声用固定 seed RNG 或 use_random=False | `sine.bin` |
| A7 | hubert 后处理 | 50→100Hz 线性插值（align_corners=False）+ cat 末帧 + [:p_len] | `phone.bin` |
| A8 | 高通滤波/RMS 混音/SOLA/噪声门 | butter 系数固定、filtfilt 正反两次、librosa.rms 换滑动 RMS | — |
| A9 | rnd 生成 | randn×0.66666 固定 seed | `rnd.bin` |

## B 档：公式固定，需精确对齐
- B1 librosa `filters.mel(htk=True)` 三角核（128×513）→ PC 一次性导出常量表
- B2 `F.interpolate` linear ×2（align_corners=False 语义）
- B3 torchaudio Resample（kaiser 核）→ 换 soxr 或定长卷积

## C 档：现成库
- C1 重采样：**soxr / libsamplerate**（NDK）
- C2 FFT：**kissfft**（NDK）或 NE10
- C3 音频 IO：**Oboe**（低延迟）或 AudioRecord/AudioTrack
- C4 音频解码：MediaCodec / libavcodec（NDK）

## 落地顺序
1. **A2 mel**（卡住整个 rmvpe 链路）——用 `mel.bin` 对拍
2. A5/A6（pitch_emb/sine/speaker_emb/rnd）——最机械，抄 `mobile_prep/scripts/sine_source.py`
3. A3/A4/A7（decode/coarse/interp）
4. A1/C1 重采样、A8 滤波/RMS/SOLA、C3 音频 IO
5. B1 mel 核与 B2 插值语义先固定成常量 + 单元测试

## 待确认
- hubert 输入 do_normalize 归一化（手机 bin 吃原始 16k 还是归一化波形）
- 生成模型固定 40k（当前）还是 48k（影响重采样核）
