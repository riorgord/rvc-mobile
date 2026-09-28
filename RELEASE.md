# RELEASE 发布检查清单(RVC Mobile)

> 本文档是**发布前逐项核对清单**(C 类),不是使用说明(见 README.md)。
> 状态:0.1.1a(开发中,未正式发布)

## 1. 版本与构建

- [ ] `android/app/build.gradle.kts`:versionCode / versionName 与本次发布一致
  - 当前:versionCode 6,versionName 0.1.1a
  - 规则:versionCode 单调递增;versionName 语义化 + 后缀(a=开发/b=测试/空=正式)
- [ ] 设置页「关于」显示的版本号与 build.gradle 一致(SettingsScreen.kt 硬编码,需同步改)
- [ ] `gradlew :app:assembleRelease` 构建通过(签名见下)

## 2. 签名

- [ ] release keystore:`rvc-release.keystore`(仓库外,`D:\AI\claw_repair\rvc-release.keystore`)
- [ ] `android/keystore.properties` 存在且被 gitignore(不入库)
- [ ] 签名后 `apksigner verify --print-certs` 确认
- [ ] 升级安装(旧版本 → 新版本)签名一致,能覆盖安装

## 3. 许可与合规(开源发布前必查)

- [ ] `LICENSE`(GPL-3.0)存在
- [ ] `THIRD_PARTY_NOTICES.md` 覆盖全部运行依赖:
  - [ ] Miuix(ui/preference/icons 0.9.3,Apache-2.0)✅ 2026-09-28 已补
  - [ ] activity-compose / lifecycle-viewmodel-compose / navigationevent-compose(AndroidX,Apache-2.0)✅ 已补
  - [ ] Compose Multiplatform / Kotlin stdlib / numpy / Chaquopy / ONNX Runtime / LLVM libc++(既有条目)
  - [ ] Qualcomm QNN SDK 组件(QTI AI Stack License,对象码随应用分发条款)✅ 既有条目
  - [ ] **不打包**任何设备镜像提取的 vendor 库
- [ ] **模型授权红线**(不得进仓库/catalog):
  - [ ] RVC 预训练底模(lj1995/VoiceConversionWebUI,仅研究用)→ 需自训底模才可二次分发(见记忆 rvc-mobile-compose-ui.md)
  - [ ] 游戏厂音源 / CC-BY-NC / 未授权音源一律不收录
- [ ] 仓库无敏感信息:keystore.properties / 签名文件 / 个人路径(检查 git 历史)

## 4. 分发物清单

| 产物 | 生成方式 | 说明 |
|---|---|---|
| APK(release) | `gradlew :app:assembleRelease` | 主分发物 |
| shared.zip | `model-builder/build_shared.py` | 在线库下载 + SAF 导入;含 LICENSES/ |
| 角色包(zip) | `model-builder/build_role.py` | 转化脚本产出,本地导入 |
| HAL 模块(rvc_module.zip) | APK assets 内置 | Magisk/KernelSU 安装 |
| catalog.json | 手动维护 | 在线索引;`devices` 表按 SoC 过滤 |

## 5. 发布前功能回归(真机 K50U)

- [ ] 冷启动 → 默认停在「变声」tab
- [ ] 大开关:开启/关闭变声;HAL 模块未装时被拦截提示
- [ ] 参数:改 key=12 重启变声后变调真实生效(回归:此 bug 曾因硬编码参数)
- [ ] 改参数 → 桥接自动停止 + 提示;切角色 → 桥接自动停止 + 提示
- [ ] 角色:导入(SAF)/ 切换 / 管理(重命名/删除)
- [ ] 模型包:共享件状态 / 下载 / SAF 导入 / 在线角色库刷新
- [ ] 设置:安装/更新 HAL 模块(红/黄/绿守卫 + 倒计时)/ SoC 选择
- [ ] 调试页:版本号 5 连击进入;9 个测试按钮可用;日志滚动
- [ ] 主题:跟随系统明暗切换
- [ ] 后台/熄屏:桥接保持(防 MIUI 冻结依赖前台服务)

## 6. 已知限制(发布说明中声明)

- 已验证基线仅 K50U(MIUI13 / Android 12 / Magisk / 官方内核);其余设备为黄档或拦截
- AIDL core 设备(无 audio.primary.*.so)不工作,一律拦截
- HTP V69:fp16 卷积全灭(htp-fp16-conv-broken),模型用 fp32
- 熄屏降频 ~4.5x,长任务建议亮屏
- 变调 key 是半音单位;f0 提取器 fcpe/rmvpe/gf_ref 切换后需重启变声
