# NPU 激活清单（unsigned PD，普通 App 免 root 直连 HTP）

> 来源：subagent 调研（2026-08-23），anima_phone + gsv-app 两项目实战验证。
> **核心结论：untrusted_app 直连 HTP ✅，零 root、零系统签名、零 Shizuku。** 14001 是配置错不是 SELinux。

## 三项必须同时正确（最深坑）
1. **context binary 用 soc_id=42 编译**（错用 53 → 恒 14001 `QNN_DEVICE_ERROR_INVALID_CONFIG`）
2. **ADSP_LIBRARY_PATH = 纯 QNN 目录**（绝不含 /vendor/lib/rfsa/adsp、/dsp 系统旧固件）
3. **LD_LIBRARY_PATH 含 /system/lib64:/vendor/lib64**（libQnnHtpV69Stub 依赖 libcdsprpc.so）——或把 libcdsprpc.so 打进 APK

## 权限（AndroidManifest）
```
EXECUTE_PRIVATE_BINARY        # QNN 必需：执行 app 私有目录原生二进制
MANAGE_EXTERNAL_STORAGE       # 读 /sdcard 模型/角色包
READ/WRITE_EXTERNAL_STORAGE
FOREGROUND_SERVICE + WAKE_LOCK  # 防 MIUI 冻结（长任务）
```
无 dsp group、无 sharedUserId、无 SELinux 修改。`android:extractNativeLibs="true"`（.so 真实落盘）。

## 库打包
- **jniLibs/arm64-v8a/**（ARM EM_AARCH64）：libQnnHtp.so、libQnnHtpV69Stub.so、libQnnHtpNetRunExtensions.so、libQnnSystem.so、libQnnHtpPrepare.so、libqnn_net_run.so（CLI 改名，可 execve）
- **assets/ 手动解压**（EM_HEXAGON=164，包管理器会过滤，必须 assets）：libQnnHtpV69Skel.so、libQnnHtpV69.so
- **libcdsprpc.so 也打进 APK 最稳**（GSV 做法，免 /vendor/lib64 依赖）；或 LD_LIBRARY_PATH 加 /vendor/lib64
- gradle：`abiFilters arm64-v8a` + `packaging { jniLibs { useLegacyPackaging = true } }`

## 编译期配置（baked 进 .bin，运行期零配置）
```json
// htp_cfg.json
{ "graphs": [{ "O": 3.0, "vtcm_mb": 8, "hvx_threads": 4, "graph_names": [...] }],
  "devices": [{ "soc_id": 42, "dsp_arch": "v69",
    "cores": [{ "perf_profile": "burst" }], "pd_session": "unsigned" }],
  "context": { "weight_sharing_enabled": false } }
// htp_ext.json（包装，顶层 graphs/devices 会静默失效）
{ "backend_extensions": { "shared_library_path": "libQnnHtpNetRunExtensions.so",
    "config_file_path": "htp_cfg.json" } }
```
qnn-context-binary-generator --config_file htp_ext.json → bin。✅ 我们 3 个 bin 已用 soc_id=42/dsp_arch=v69/unsigned 编译（htp_cfg_gen / o1 配置）——**已符合**。

## context 传递（最优：内存指针）
```c
bin_data = load_file(bin, &sz);          // 从 APK asset 读字节
contextCreateFromBinary(be, NULL, NULL, bin_data, sz, &ctx, NULL);  // 会拷贝，用完可 free
graphRetrieve(ctx, gname, &graph);
graphExecute(graph, in_tensors, n, &out, 1, NULL, NULL);
```
aarch64 不能用 --dlc_path 跑 HTP（error 1002），必须 retrieve_context。

## 运行时加载两模式（都验证过）
| 模式 | 参考 | 特点 |
|---|---|---|
| 子进程（qnn-net-run 改名 libqnn_net_run.so + env） | anima QnnRunner.kt / pipeline.py | 每次新进程，上下文开销大 |
| **常驻 C 服务（context 一次 create 多次 execute）** | GSV libgsv_qnn.so + ctypes | 0.022s/步 vs 2.5s/步（110×）⭐ App 首选 |

ctypes/常驻坑：**bionic 启动时缓存库搜索路径，setenv LD_LIBRARY_PATH 无效** → 按依赖序主动 dlopen 预加载（libqti_dsp→libvmmem→libcdsprpc→...→V69Stub→NetRunExtensions）。

## 坑速查
| 症状 | 真根因 |
|---|---|
| 14001 | soc_id 错 / ADSP 未设 / libcdsprpc 找不到 / ADSP 混系统路径——**不是 SELinux** |
| 输出全零 | 库没打包 / 契约错 / buffer 断开 |
| execute_no_trans 拒绝 | Android 10+ W^X → 原生二进制进 jniLibs |
| HEXAGON 库没了 | 包管理器过滤 EM_HEXAGON → assets 手动解压 |
| MIUI 冻结长任务 | 前台服务 + WakeLock |
| 跨 SDK bin 不兼容(5000) | SDK 与 bin 必须同版本 |
| HTP 输出按 fp32 分配读错 | 按 binary 声明 dtype 读（fp16/fp32） |

## 参考实现（照抄）
- C：`D:\AI\anima_phone\qnn_ops\anima_ctxbin_runner.c`（createFromBinary+execute）
- Kotlin 解压：`D:\AI\anima_phone\android\app\src\main\java\com\anima\phone\model\AssetExtractor.kt`
- env 修复版：`D:\AI\anima_phone\android\app\src\main\python\pipeline.py` L110-145
- GSV 全量 jniLibs+vendor：`D:\AI\claw_repair\archive\gsv\apk_source\gsv-app\app\src\main\jniLibs\arm64-v8a\`
