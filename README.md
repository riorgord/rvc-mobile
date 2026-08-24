# rvc_app — RVC 手机实时变声 App（Android）

> 阶段决策（2026-08-23）：**App 化优先**。纯 NPU 计算其实只有 ~1.68s（hubert 0.2s + rmvpe 0.12s + gen 1.35s），
> qnn-net-run 的 wall 4.1s 里 ~2.4s 是 host 开销（文件 IO + context 加载 + 子进程）。
> App 化（常驻进程 + QNN 内存 API + context 复用）→ wall 压到 ~1.8-2.0s，**接近实时（2.24s）**。

## 目标
原生 Android App，APK 内打包 3 个模型 + QNN 运行库，普通 App 权限（unsigned PD）启用 HTP NPU，跑通 RVC 全链路变声。

## 需求（用户明确）
- **profiling 开关**：App 里做一个开关（设置/开发者选项），可控制是否输出 profiling 数据到 **`/sdcard/rvc_exp/`**
  - 默认 OFF（性能优先）；ON 时启用 QNN profiling（对应 qnn-net-run 的 `--profiling_level`，走 qnn_profile API）并把 profiling 文件/execution_metadata 写到 `/sdcard/rvc_exp/`
  - ⚠️ scoped storage 坑：Android 11+ 普通 App 不能直接写 `/sdcard` 根。方案：a) 申请 `MANAGE_EXTERNAL_STORAGE`（"所有文件访问"特殊权限，调试期可接受）b) 写 `/sdcard/Android/data/<pkg>/files/rvc_exp`（免特殊权限，adb 也能拉）——M0 先定方案 a，发布前降级
  - profiling 开关与性能测试复用：开关 ON 一次跑出 detailed profiling（各段 op cycles），OFF 跑真实 wall

## 模型（先打包进 APK，发布再调整）
| bin | 大小 | 源 |
|---|---|---|
| hubert_mix_def_t4800.bin | 190MB | `../mobile_prep/rvc_success_exp/models/` |
| rmvpe_fp32_256.bin | 178MB | 同上 |
| gen_fp32.bin | 63MB | 同上 |

## 待定/调研中
- [ ] unsigned PD 激活清单（subagent 调研 anima_phone + gpt-sovits 中）
- [ ] App 形态：Kotlin + JNI（C 调 QNN）vs 服务化
- [ ] 模型放置：assets/ 解压 vs 直接打包
- [ ] 前端：AudioRecord 采集 → 处理 → AudioTrack（实时）vs 文件输入（先验证）

## 性能基线（修好后，手机实测）
| 环节 | wall(无prof) | 纯NPU | host |
|---|---|---|---|
| hubert | 1021ms | ~200ms | ~820ms |
| rmvpe | 1017ms | 123ms | ~894ms |
| gen_fp32 | 2050ms | 1354ms | ~696ms |
| Σ | ~4.09s | ~1.68s | ~2.4s |

## 里程碑
1. M0：APK 打包 3 模型 + libQnnHtp + hexagon-v69，unsigned PD 激活成功，单模型推理（文件输入）输出正确
2. M1：JNI 常驻服务，3 模型全链路跑通，wall ≈ 纯计算
3. M2：音频 IO（AudioRecord/AudioTrack）实时回路
4. M3：性能优化（dec 减帧 / 流式 / 流水线）
