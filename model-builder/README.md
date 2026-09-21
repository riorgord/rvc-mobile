# RVC 模型转换手册（rvc-model-builder）

把**官方整合包微调好的 RVC 模型**（`.pth` + `.index`）批量转换成手机端可用的**角色包**（QNN context binary + 音色索引 + manifest）。

本手册同时写给三类人/AI 看：**Windows 傻瓜用户**、**技术用户**、**本地 Agent / AI 开发者**。
请先读第 1 章，对号入座，再按对应路径走。

---

## 1. 三条路径，先对号入座

| 你是哪种人 | 走哪条 |
|---|---|
| Windows 用户，不想装任何东西 | **第 2 章**：网页 AI 当老师 → 装本地 Agent → 把本手册丢给本地 Agent |
| 技术用户（Ubuntu / WSL2 / Docker） | **第 4、5、6 章**：自己装环境、自己跑 |
| AI / 本地 Agent（有命令行执行能力） | **第 3、4、5、6 章**：按执行规范照做 |

> 无论哪条路，**最终都是在本机执行转换**，不是云端跑。第 2 章只是帮你把“谁来敲命令”外包给本地 Agent。

---

## 2. Windows 傻瓜路径：网页 AI 当老师

你不需要懂 Linux、不需要装 WSL/Docker 到能自己用。你的角色只是“把老师请来、把活交给本地 Agent”。

### 2.1 打开任意网页 AI（豆包都行）

复制下面这段，发给它：

```text
请手把手教我在 Windows 上安装一个本地 agent 框架：
1. 要免费或便宜、安装省事、能执行命令行操作的；
2. 需要配置 API 密钥/令牌的话，教我去生成、配置好；
3. 装好后告诉我，怎么把我手上的一份操作手册（Markdown 文件）交给它，让它照着自动执行。

过程中请一步一步来，我完全不懂技术，每次只让我做一个动作，并告诉我为什么要做。
```

### 2.2 老师装好后

把**本手册（README.md）整个文件**丢给本地 Agent，对它说：

```text
请严格按照这份手册执行：先做环境侦察（第 4 章），再准备环境（第 5 章），
然后按第 6 章把模型转成角色包。遇到需要我授权的（管理员、重启）就停下来提示我。
模型文件在：<在这里写你的 .pth 和 .index 路径>
```

### 2.3 你需要配合的点（仅这些）

- 管理员授权弹窗：点“允许”
- 需要重启 Windows：照做，重启后重新打开 Agent 继续
- 下载 QNN SDK 需要注册 Qualcomm 账号：按 Agent 给的链接去注册（免费）

> 你不需要记住任何命令。所有“听不懂”的都让 Agent 解释。

---

## 3. 给本地 Agent / AI 的执行规范（硬性）

如果你是 AI 或本地 Agent，请遵守以下纪律，否则容易白跑：

1. **第一件事必须是环境侦察（第 4 章）**，不许跳过、不许假设。
2. **`check_env.sh` 只当交叉验证**，它的输出可能是错的；以第 4 章你自己跑出来的事实为准。
3. **每一步执行完要自检**：预期输出对不上 → 停下来，贴日志，不硬猜。
4. **失败时**：把完整错误贴给用户，并给出 2~3 个可能原因和处理建议，而不是自己乱改配置重试。
5. **拿不准就问用户**，特别是：SDK 实际路径、是否 WSL、模型文件路径、是否愿意花时间下载大文件。
6. 每完成一个大阶段，输出一行小结：`[阶段] 完成/失败，下一步是 ...`，方便用户追踪。

---

## 4. 环境侦察清单（必做）

> 目的：搞清楚“我现在到底在什么环境里跑”。**先跑下面这些，把输出看完再往下走。**

```bash
# 4.1 这是什么系统？
uname -a
cat /etc/os-release

# 4.2 是不是 WSL？（有 microsoft 字样就是 WSL）
grep -i microsoft /proc/version

# 4.3 能不能用 Docker？
docker --version 2>/dev/null

# 4.4 QNN SDK 在哪？环境变量有没有？
echo "QNN_SDK_ROOT=$QNN_SDK_ROOT"
which qnn-context-binary-generator 2>/dev/null
which snpe-onnx-to-dlc 2>/dev/null
ls -d /home/*/qnn_sdk /opt/qnn* /mnt/*/qnn* 2>/dev/null

# 4.5 Python / 依赖
python3 --version
pip3 list 2>/dev/null | grep -iE "onnx|torch|numpy"

# 4.6 当前目录、磁盘空间、权限
pwd
df -h .
id
```

### 4.2 怎么判断“我在哪种环境”

| 看到什么 | 结论 |
|---|---|
| `/proc/version` 里有 `microsoft` | **WSL2**，路径按 `/mnt/c/...`、`/mnt/d/...` 走 |
| `/etc/os-release` 是 Ubuntu 22.04，且不是 WSL | **Ubuntu 原生** |
| `docker --version` 有输出、当前在容器里（`/proc/1/cgroup` 有 docker 字样） | **Docker 容器** |
| 以上都不是 | 停下，问用户怎么装 |

> 侦察完把结论写下来：`当前环境 = <Ubuntu原生 / WSL2 / Docker>`，`SDK = <有/没有，路径>`，`Python = <版本>`。

---

## 5. 环境准备

### 5.1 Ubuntu 22.04 原生

直接跳到 5.4。

### 5.2 Windows → WSL2（推荐给 Windows 用户）

在 **PowerShell（管理员）** 里执行：

```powershell
wsl --install -d Ubuntu-22.04
```

- 首次会要求**重启一次**。
- 重启后打开“Ubuntu”开始菜单项，完成用户名/密码设置。
- 进入 WSL 后，后续所有命令都在 WSL 终端里跑（路径形如 `/mnt/d/...`）。

### 5.3 Docker（备选，已有 Docker 的人）

```bash
docker run -it --rm -v "$(pwd):/work" ubuntu:22.04 bash
apt-get update && apt-get install -y python3 python3-pip unzip curl
```

### 5.4 QNN SDK 2.47

- **下载地址**：Qualcomm 开发者官网（需免费注册账号）。搜索关键字：`Qualcomm AI Engine Direct (QNN) SDK`，选择 **Linux x86_64** 版本 **2.47**。
- 下载后解压到一个固定目录，例如：
  ```bash
  mkdir -p ~/qnn_sdk && tar -xzf qnn-sdk-2.47-linux-x86_64.tar.gz -C ~/qnn_sdk
  ```
- **每次转换前都要加载环境**（把路径换成你实际的）：
  ```bash
  source ~/qnn_sdk/bin/envsetup.sh
  ```
  或者设环境变量：
  ```bash
  export QNN_SDK_ROOT=~/qnn_sdk
  ```
- 自检：`which qnn-context-binary-generator` 有输出即 OK。

> 备注：2.47 只是当前验证过的版本。以后适配新soc可能要换 SDK 版本，届时在本手册追加“版本对照表”。

### 5.5 Python 依赖

```bash
python3 -m pip install --upgrade pip
python3 -m pip install numpy onnx onnxruntime torch
```

> `torch` 只用于 `.pth → ONNX` 导出那一步。

---

## 6. 转换流程（核心）

> 通用流程：`.pth → ONNX → DLC → QNN .bin`，再处理索引，最后组装角色包。
> 详细已验证命令以 `mobile_prep/onnx/` 下现有脚本为参照（本工具会逐步把这些脚本的参数化）。

### 6.1 `.pth` → ONNX

```bash
python3 scripts/export_onnx.py --pth /path/to/G_xxxx.pth --out ./out/model.onnx
```

- 提取模型里的生成器（net_g）权重，导出 ONNX。
- 导出参数（输入形状、动态轴等）见 `config.yaml`。
- 预期输出：`model.onnx` 生成，且 `onnx.checker` 通过。

> ⚠️ 这一步依赖 RVC 官方导出逻辑，不同模型（v2/v3、蒸馏版）细节可能不同。先跑，失败贴日志。

### 6.2 ONNX → DLC

```bash
snpe-onnx-to-dlc --input_network ./out/model.onnx \
  --float_bitwidth 32 \
  -d phone 1,224,768 \
  -d rnd 1,192,224 \
  --output_path ./out/model.dlc
```

> 输入名和形状**按模型实际定义写**（上面的 phone/rnd 只是例子，来自已验证的 jielaide 脚本）。

### 6.3 DLC → QNN context binary（角色件）

```bash
qnn-context-binary-generator \
  --dlc_path ./out/model.dlc \
  --backend $QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so \
  --config_file ./htp_cfg/backend_extensions.json \
  --binary_file ./out/model.sm8475.bin \
  --output_dir ./out
```

- `--binary_file` 的名字带架构后缀，例如 `dec_short_t61.sm8475.bin`。
- `htp_cfg/` 下是已验证的 HTP 配置（backend_extensions.json + htp_config.json），后续按架构扩展。
- 预期输出：`model.sm8475.bin.bin`（注意可能带 `.bin.bin` 双后缀，App 侧就是按这个名加载的）。

### 6.4 索引处理（音色索引）

```bash
python3 scripts/build_index.py --index /path/to/xxx.index --out ./out/
```

- 产出 App 需要的索引文件（`idx256.bin` / `naiqiawang_idx.bin`，具体以角色包清单为准）。

### 6.5 组装角色包 + manifest

**角色包 = 单个 zip（store 不压缩），一个角色一个文件**，像 `.mcpack` 一样：分发就一个文件，使用时从里面直接取条目。

zip 内部结构（示例）：

```
角色名.zip            ← ZIP_STORED（只打包不压缩）
├── manifest.json
├── dec_short_t61.sm8475.bin.bin
├── idx256.bin
├── naiqiawang_idx.bin
└── proj.bin          # 如该角色需要
```

> `output_dir/` 下会同时保留散文件目录，仅作本地调试用；**对外分发/给 App 用的是 zip**。

`manifest.json` 字段：

```json
{
  "model_id": "唯一ID，如 my_char_v1",
  "name": "角色显示名",
  "author": "模型训练者/作者",
  "license": "许可类型",
  "attribution": "作者要求的署名/注明方式",
  "arch": "sm8475",
  "sdk_version": "2.47",
  "files": [
    {"name": "dec_short_t61.sm8475.bin.bin", "sha256": "..."},
    {"name": "idx256.bin", "sha256": "..."}
  ]
}
```

> `python3 convert.py --config config.yaml` 会顺带生成 manifest、校验 SHA256，并打成一个 `ZIP_STORED` 的 zip。

---

## 7. config.yaml（所有变量，满注释）

见同目录 `config.yaml`。所有可改项都在里面，**不要改脚本，只改配置**：

- 模型路径、输出目录
- 是否打包成 zip（`pack_as_zip: true`）
- 架构（`arch: sm8475`）
- SDK 路径 / 是否自动加载 envsetup
- 各组件导出参数（输入名/形状/量化）
- manifest 的作者、署名、许可

---

## 8. 验证

1. **qnn-net-run 自测**：用 `mobile_prep/phone_pkg/` 里的方式跑一遍生成的 `.bin`，对比参考输出。
2. **手机 App 实测**：把角色包放到 App 的模型目录，发一条微信语音试听效果。
3. **对照表**：确认与 PC 参考输出的相关性/SNR 达标（参考 `mobile_prep/output/*/full_metrics.json` 的做法）。

---

## 9. 常见报错对照表

| 报错 | 原因 | 处理 |
|---|---|---|
| `qnn-context-binary-generator: command not found` | SDK 环境没加载 | `source $QNN_SDK_ROOT/bin/envsetup.sh` |
| `snpe-onnx-to-dlc: command not found` | 缺 SNPE 工具 | 确认 SDK 里带 SNPE，或换 `qnn-onnx-converter` |
| `failed to open libQnnHtp.so` | backend 路径不对 | 改成 `$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so` |
| DLC 生成报 shape 不匹配 | 输入名/形状写错 | 用 `onnx` 打印模型输入，改 config.yaml |
| `.bin.bin` 文件名奇怪 | 正常现象 | App 侧就是按 `.bin.bin` 加载，别改 |

（此表会随实测持续扩充。）

---

## 10. 输出与使用

转换完成后得到：

- **角色包**：一个 `角色名.zip`（`ZIP_STORED`），内含 `manifest.json` + 角色模型 + 索引文件。
- 手机 App 里点“导入角色包”(SAF 文件选择器) 选这个 zip，App 读 `manifest.json`、逐文件 SHA256 校验后整包解压到私有目录 `filesDir/roles/<model_id>/`。
- **共享件**：`build_shared.py` 产出 `shared.zip`（hubert / f0 / df3r / mel / emb / sine / cent_table / proj），App 首启自动探测抱脸/魔塔下载，失败可 SAF 手动导入；不随角色包分发。
- App 选中角色后，实时链路从 `roles/<model_id>/` 读角色件、从共享目录读共享件。

> 本脚本只负责“转换产出”，不涉及任何发布/上传流程。

---

## 11. 附录：给 AI 的执行纪律（重申）

1. 不盲信 `check_env.sh`，以第 4 章手动侦察为准。
2. 每步自检，预期输出对不上就停。
3. 失败贴日志，给原因 + 处理建议，不乱改配置重试。
4. 拿不准就问用户（SDK 路径、是否 WSL、模型路径、大文件下载意愿）。
5. 每阶段结束输出一行小结。

---

## 配套文件

```
setup.sh          # 环境准备（可被 Agent 直接执行）
check_env.sh      # 只输出事实，不做判决
convert.py        # 唯一入口，config 驱动，非交互
config.yaml       # 全注释变量
export_onnx.py    # .pth → ONNX
build_index.py    # .index → App 索引
build_shared.py   # App 共享件 → shared.zip（首启下载/SAF 导入用）
pack_role.py      # 组装 ZIP_STORED 角色包 + manifest + SHA256
manifests/        # manifest 模板
htp_cfg/          # HTP 配置（backend_extensions / htp_config）
```

> v1 状态：README 是完整骨架，脚本是占位；`mobile_prep/onnx/` 里已验证的脚本会逐步搬进来参数化。
