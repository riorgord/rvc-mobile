# Third-Party Notices

本文件列出 RVC Mobile 分发物中涉及的主要第三方组件与许可。完整许可证原文见 `model-builder/LICENSES/`。

> 本文档由项目维护者整理,不构成法律意见。

## App 代码

| 组件 | 来源 | 许可证 | 版权 |
|---|---|---|---|
| RVC 推理/训练代码 | RVC-Project/Retrieval-based-Voice-Conversion-WebUI | MIT | Copyright (c) 2023 liujing04, 源文雨, Ftps |
| RVC Mobile 自有代码 | 本项目 | GPL-3.0 | RVC Mobile 作者 |

## 共享件(shared.zip)

| 组件 | 来源 | 许可证 | 版权 |
|---|---|---|---|
| models/hubert_mix_def_t4800.bin | RVC-Project / fairseq HuBERT | MIT | Copyright (c) 2023 liujing04, 源文雨, Ftps; fairseq (c) 2019 Facebook |
| models/rmvpe_fp32_64.bin.bin | Dream-High/RMVPE | Apache-2.0 | 见 Apache-2.0.txt |
| models/fcpe_256.bin | CNChTu/FCPE | MIT | Copyright (c) 2023 CN_ChiTu |
| models/df3r_T18_emb.onnx | Rikorose/DeepFilterNet | MIT | Copyright (c) 2021 Hendrik Schröter |
| periphery/* | RVC-Project/Retrieval-based-Voice-Conversion-WebUI | MIT | Copyright (c) 2023 liujing04, 源文雨, Ftps |

## 不随本仓库分发的组件

以下组件**不包含在本仓库/APK/catalog 中**,仅供用户本地自行处理时知悉:

| 组件 | 说明 |
|---|---|
| RVC 预训练底模(G/D) | 来自 lj1995/VoiceConversionWebUI,其 LICENSE 含「仅供研究使用」条款。基于它全量微调的模型被视为衍生作品,不建议再分发/商用 |
| 游戏厂/未授权音源 | 米哈游、NEXON、库洛、鹰角、SHIFT UP 等一律不收录 |
| CC-BY-NC 模型 | 语雀作者那批(CC-BY-NC + 禁二次配布)只允许本地导入 |

## APK 运行依赖

| 组件 | 来源 | 许可证 | 说明 |
|---|---|---|---|
| numpy | NumPy 项目 | BSD-3-Clause | Python 运行时数组计算 |
| Chaquopy 运行时 | chaquo/chaquopy | MIT | Python 嵌入运行时 |
| ONNX Runtime | microsoft/onnxruntime | MIT | `libonnxruntime.so` |
| LLVM libc++ | LLVM 项目 | Apache-2.0 + LLVM 例外 | `libc++.so` |
| Android 平台库(AOSP) | Android Open Source Project | Apache-2.0 | libbase / cutils / hardware / hidl / log / utils 等 |
| Qualcomm QNN/HTP 运行时 | Qualcomm | 专有(Qualcomm SDK 条款) | libQnnHtp* / libQnnSystem / libcdsprpc / libqti_dsp / assets/hexagon-v69/* |
| Kotlin 标准库 | JetBrains | Apache-2.0 | 随 APK |
| libgsv_qnn.so | 本项目 | GPL-3.0 | 自有 QNN 推理封装 |

> 说明:Qualcomm 相关库来自 Qualcomm QNN/Hexagon SDK,随 APK 分发需遵守 Qualcomm 的许可条款。完整清单以构建配置为准,若有遗漏以各依赖自带许可证为准。
