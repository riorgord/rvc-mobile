# OWNER.md（内部，不分发）

> 这份是给项目所有者自己看的，**不要随转换脚本一起分发**。
> 公开 README 只讲“转换产出 + 本地使用”，不包含本文件内容。

## 发布计划（进行中）

- **共享件 shared.zip**（~408MiB）已由 `build_shared.py` 产出：`test_out/shared.zip`。
  - 上传到 **魔塔(ModelScope)** 和 **Hugging Face** 各一份（角色件同理可传）。
  - 上传后把两个直链填进 App 源码：
    `android/app/src/main/java/com/rvc/app/SharedDownloader.kt` 的 `SHARED_URLS`，
    把 `enabled` 置 `true`（App 首启自动探测选低延迟源下载）。
  - 抱脸直链格式：`https://huggingface.co/<组织>/<repo>/resolve/main/shared.zip`
  - 魔塔直链格式：`https://www.modelscope.cn/models/<组织>/<repo>/resolve/master/shared.zip`
- **架构绑定**:shared.zip 和 role.zip 里的 QNN .bin 都烙死 `soc_type=SM8475` / `V69`(没有 .maf 文件,标识在 bin 内部 + manifest 的 `arch` 字段)。
  - 上传/命名建议带架构,如 `shared-sm8475.zip`、`naiqiawang-sm8475.zip`,避免跨机型混用。
  - App 导入已按 `manifest.arch` 校验,不匹配会拒绝;多机型支持后续按 arch 分别编译/下载。
- 角色包计划上传：魔塔 / Hugging Face（App 在线角色库后续做）。
- 维护一张登记表：模型名、作者/训练者、许可、署名要求、适用 SOC、下载地址。
- App 侧策略：先查登记表自动下载；检测不到就手动选 SOC 下载，或选本地角色包（SAF 导入）。
- 共享件（hubert / f0 / df3r / mel / emb / sine / cent_table / proj）→ 首启下载或 SAF 导入；角色件 → 角色包。

## 合规与隐私提醒

- 发布他人训练的模型时，必须按模型训练者要求注明作者/署名/许可。
- 注意发布内容的社会/合规风险，避免给自己带来不必要的麻烦。
- 涉及敏感角色/内容的模型，建议只保留在本地，不公开发布。
