# 发布检查清单(Release Checklist)

每次对外发布 RVC Mobile 前,按以下逐项核对。

## 1. 许可证与合规

- [ ] `LICENSE`(GPL-3.0)在仓库根目录
- [ ] `THIRD_PARTY_NOTICES.md` 内容与实际分发物一致
- [ ] `model-builder/LICENSES/` 包含 MIT / Apache-2.0 / AGPL-3.0 / GPL-3.0 官方文本
- [ ] `shared.zip` 内含 `LICENSES/`(打包脚本已默认带上)
- [ ] 两个镜像(抱脸/魔塔)上的 `shared-v69-42.zip` 与 `LICENSES/` 是最新版
- [ ] `catalog.json` 的 `sha256` / `size` 与实际文件一致,且两仓已同步

## 2. 内容红线

- [ ] `catalog.json` 的 `roles` 为空,或仅含**有明确授权/从零训练**的模型
- [ ] 无任何游戏厂/未授权音源
- [ ] 无 CC-BY-NC / 禁二次配布模型(只能本地导入,不进 catalog)

## 3. 构建与产物

- [ ] `./gradlew clean :app:assembleDebug` 全量 clean 构建(避免 assets 变更导致 APK 虚胖)
- [ ] 安装后确认 App 本体体积正常(约 120MB 级)
- [ ] 变声链路自测通过(本地角色 + 系统麦克风)
- [ ] 首启/清数据流程:拉 catalog → 下载 shared → 解压 → 可用(若有条件实测一次)
- [ ] 使用正式签名(keystore)出 release 包

## 4. 文档

- [ ] README 中的特性/目录/许可与实际一致
- [ ] 支持设备表与 catalog `devices` 一致

## 5. 其他

- [ ] 隐私说明(如收集内容、数据处理)按需补充
- [ ] 若商用:已确认所用底模/组件的许可允许(当前共享件 MIT/Apache 可,角色需干净底模)
