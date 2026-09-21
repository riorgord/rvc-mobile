#!/bin/bash
# setup.sh —— 环境准备（v1 占位）
# 目标：检测系统、装 Python 依赖、引导安装 QNN SDK 2.47、写 QNN_SDK_ROOT。
# 注意：本脚本只输出“事实”和“建议”，不做武断判断；真正的判断由执行者（人或 AI）按 README 第 4 章做。

set -u

echo "=== setup.sh: 环境准备 ==="

# 1. 检测系统
if grep -qi microsoft /proc/version 2>/dev/null; then
    echo "[env] 检测到 WSL"
elif [ -f /etc/os-release ]; then
    . /etc/os-release
    echo "[env] $PRETTY_NAME"
else
    echo "[env] 未知系统"
fi

# 2. Python
echo "[python] $(python3 --version 2>&1 || echo '未安装 python3')"

# 3. QNN SDK 路径（只探测，不假设）
echo "[qnn] QNN_SDK_ROOT=${QNN_SDK_ROOT:-未设置}"
echo "[qnn] qnn-context-binary-generator: $(which qnn-context-binary-generator 2>/dev/null || echo '未找到')"
echo "[qnn] snpe-onnx-to-dlc: $(which snpe-onnx-to-dlc 2>/dev/null || echo '未找到')"

echo
echo "=== 下一步 ==="
echo "按 README 第 4 章做环境侦察；缺 QNN SDK 2.47 就去 Qualcomm 开发者站下载并 source envsetup.sh。"
