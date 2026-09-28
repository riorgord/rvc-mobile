# RVC Mobile

自研 RVC 实时变声引擎(Android)。通过 Magisk/KernelSU 虚拟 HAL 把系统麦克风接管成“可编程麦克风”,App 内加载角色包(RVC 模型)实时变声,可注入微信/QQ/游戏等任意使用系统麦克风的应用。

> 状态:开发中。当前仓库为 App 工程 + 模型打包脚本,不包含任何未授权音源。

## 致谢

- 感谢**蓝色大肥鱼(DeepSeek)**的辛勤付出(本项目大量代码与调试工作由其协助完成)。
- RVC 推理/训练代码来自 RVC-Project(Retrieval-based-Voice-Conversion-WebUI,MIT),完整第三方许可见 `THIRD_PARTY_NOTICES.md`。

## 特性

- 虚拟 HAL 麦克风(`audio.primary.rvc.so` 包装器 + `rvc_relay` 开机自启)
- 实时 RVC 流式推理(QNN/HTP NPU 加速)
- 角色包:单 zip 导入/导出,本地管理
- 共享件(hubert/rmvpe/fcpe/df3r + periphery)首启在线下载,支持抱脸/魔塔双镜像
- catalog.json 在线索引:按设备 SoC 自动过滤可用资源

## 设备兼容性与安装拦截

> 两个维度分开看:catalog 的 `devices` 表 = **NPU 推理支持**(哪些 SoC 有编译好的共享件);下表 = **HAL 安装门槛**(系统环境是否符合机架机制)。两者都满足才能完整使用。

### 已验证基线(开发机实测)

| 项目 | 要求 |
|---|---|
| 机型 | Redmi K50 Ultra(22081212C) |
| SoC / NPU | SM8475(骁龙 8+ Gen 1)/ QNN HTP V69 |
| 音频 HAL | Legacy / HIDL(存在 `audio.primary.*.so`) |
| 内核 | 官方内核(如 `5.10.81-android12-...`) |
| 系统 | Android 12(SDK 31)/ MIUI 13 |
| Root | Magisk |

### 安装拦截策略(App 内保守判定)

点击「安装/更新 HAL 模块」时按以下顺序判定,不满足条件会弹窗拦截:

| 档位 | 判定条件 | 表现 |
|---|---|---|
| 🟢 放行 | 官方内核 + Magisk + 存在 `audio.primary.*.so` + Android 12 | 直接安装 |
| 🟡 风险确认 | KernelSU;或 Android 非 12;或音频 HAL 无法确认 | 弹窗 + 10 秒倒计时 + 勾选「我已阅读并理解上述风险」后放行 |
| 🔴 拦截 | 无 root;或非官方内核(如 `-Jianke-Jiangnan` 昵称后缀);或 AIDL core(无 `audio.primary.*.so`) | 仅「退出」,不可跳过 |

> 非官方内核 / 官改 ROM 会导致音频驱动加载异常,机架无法工作;AIDL core 不加载
> `audio.primary.*.so`,HAL 包装器无效。为保护用户,这类设备一律拦截安装。

### NPU 共享件支持(按 SoC 过滤)

| SoC | soc_id | HTP 版本 | 设备示例 | 状态 |
|---|---|---|---|---|
| sm8475 | 42 | V69 | 骁龙 8+ Gen 1 | ✅ 已实测(K50U) |
| sm8450 | 36 | V69 | 骁龙 8 Gen 1 | ⚠ 已编译,未实测 |
| sm8550 | 43 | V73 | 骁龙 8 Gen 2 | ⚠ 已编译,未实测 |
| sm8650 | 57 | V75 | 骁龙 8 Gen 3 | ⚠ 已编译,未实测 |
| sm8750 | 69 | V79 | 骁龙 8 Elite | ⚠ 已编译,未实测 |
| sm8850 | 87 | V81 | 骁龙 8 Elite Gen 5 | ⚠ 已编译,未实测 |

## 仓库结构

```
android/                 Android App 工程
  app/src/main/assets/     APK 内置资源(hexagon-v69 / testdata / rvc_module.zip)
  app/src/main/java/...    Kotlin 源码
model-builder/            RVC 模型转换/打包工具
  build_shared.py          shared.zip 打包脚本(含 LICENSES)
  convert.py               ONNX -> QNN DLC 转换
  shared_src/              共享件源文件(不入库,gitignore)
  LICENSES/               许可证文本(随 shared.zip 分发)
LICENSE                   App 代码许可证(GPL-3.0)
THIRD_PARTY_NOTICES.md    第三方组件与许可声明
docs/                     发布与合规文档
```

## 构建

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 建议 JDK 21 |
| Android SDK | compileSdk 37 | ANDROID_HOME 指向 SDK |
| Gradle | 8.13 | 仓库自带 wrapper(`android/gradle/wrapper`) |
| AGP | 8.13 | 见 `android/settings.gradle.kts` |
| Chaquopy 构建 Python | 3.11 | 本地 Python,Chaquopy 17 用;构建前设 `RVC_BUILD_PYTHON=<python 绝对路径>`(如不设则走 PATH) |
| QNN SDK | 2.47+ | 仅 native 层编译需要头文件(见下) |

### 编译 QNN 头文件(仅 native/ 需要)

`native/qnn_include/`(QNN SDK 头文件)因 QTI 许可**不随本仓库分发**。构建 `libgsv_qnn.so` 前:

```bash
# 从你的 QNN SDK 安装目录拷贝头文件树
# SDK 解包后通常在 <sdk>/include/QNN/ 下
cp -r <qnn-sdk>/include/QNN/* native/qnn_include/
```

> 只编译 Android APK(不含 native 层改动)不需要这一步;`libgsv_qnn.so` 预编译产物随 `jniLibs/` 分发。

### 构建 APK

```bash
cd android
./gradlew clean :app:assembleDebug
# 注意:删除/变更 assets 里的大文件后必须 clean 重建,否则 APK 会虚胖
```

### 共享件打包

```bash
# 共享件源文件(model-builder/shared_src/)不入库,需从授权来源准备:
#   models/hubert_mix_def_t4800.bin / rmvpe / fcpe / df3r 等(见 THIRD_PARTY_NOTICES.md)
python model-builder/build_shared.py \
  --assets model-builder/shared_src \
  --out <staging> \
  --zip <output>/shared-v69-42.zip
```

## 在线目录(catalog)

- 索引文件:`catalog.json`(仓库根目录)
- 镜像:抱脸 `riorgord/rvc-mobile-share`、魔塔 `rirogord/rvc-mobile-share`
- App 流程:拉取 catalog → 识别本机 SoC → 过滤可用 shared/roles → 下载 + SHA256 校验
- 当前 `roles` 为空:角色包只在获得干净授权/从零训练后才会收录

## 许可

- App 代码:**GPL-3.0**(见 `LICENSE`)
- 共享件:MIT + Apache-2.0 混合(详见 `THIRD_PARTY_NOTICES.md`)
- 角色包:仅收录有明确授权/版权干净的模型。

## 红线

- 不收录、不传播任何未经授权的音源训练和编译好的模型
