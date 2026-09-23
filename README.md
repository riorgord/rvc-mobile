# RVC Mobile

自研 RVC 实时变声引擎(Android)。通过 Magisk/KernelSU 虚拟 HAL 把系统麦克风接管成“可编程麦克风”,App 内加载角色包(RVC 模型)实时变声,可注入微信/QQ/游戏等任意使用系统麦克风的应用。

> 状态:开发中。当前仓库为 App 工程 + 模型打包脚本,不包含任何未授权音源。

## 特性

- 虚拟 HAL 麦克风(`audio.primary.rvc.so` 包装器 + `rvc_relay` 开机自启)
- 实时 RVC 流式推理(QNN/HTP NPU 加速)
- 角色包:单 zip 导入/导出,本地管理
- 共享件(hubert/rmvpe/fcpe/df3r + periphery)首启在线下载,支持抱脸/魔塔双镜像
- catalog.json 在线索引:按设备 SoC 自动过滤可用资源
- 支持设备表见 `model-builder/catalog.json` 的 `devices` 字段

## 仓库结构

```
android/                 Android App 工程
  app/src/main/assets/     APK 内置资源(hexagon-v69 / testdata / rvc_module.zip)
  app/src/main/java/...    Kotlin 源码
model-builder/            RVC 模型转换/打包工具
  build_shared.py          shared.zip 打包脚本(含 LICENSES)
  build_role.py            角色包打包脚本(如存在)
  convert.py               ONNX -> QNN DLC 转换
  shared_src/              共享件源文件(不入库,gitignore)
  LICENSES/               许可证文本(随 shared.zip 分发)
LICENSE                   App 代码许可证(GPL-3.0)
THIRD_PARTY_NOTICES.md    第三方组件与许可声明
docs/                     发布与合规文档
```

## 构建

```bash
# Android APK
cd android
./gradlew clean :app:assembleDebug
# 注意:删除/变更 assets 里的大文件后必须 clean 重建,否则 APK 会虚胖

# 共享件打包
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
- 角色包:仅收录有明确授权/版权干净的模型;游戏厂音源、CC-BY-NC 等一律只允许本地导入

## 红线

- 不收录、不传播任何游戏厂/未授权音源(米哈游、NEXON、库洛、鹰角、SHIFT UP 等)
- 语雀作者那批(CC-BY-NC + 禁二次配布)只做本地导入
- RVC 官方 lj1995 底模带「仅供研究使用」条款,基于它的微调模型不进 catalog
