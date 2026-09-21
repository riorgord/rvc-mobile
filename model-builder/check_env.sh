#!/bin/bash
# check_env.sh —— 环境体检（v1 占位）
# 只输出事实，不做“通过/失败”判决。判断权交给 README 第 4 章 + 执行者。

set -u

echo "=== check_env.sh: 事实输出 ==="
echo "系统: $(uname -a)"
if grep -qi microsoft /proc/version 2>/dev/null; then echo "WSL: 是"; else echo "WSL: 否"; fi
echo "Python: $(python3 --version 2>&1 || echo 未安装)"
echo "QNN_SDK_ROOT: ${QNN_SDK_ROOT:-未设置}"
echo "qnn-context-binary-generator: $(which qnn-context-binary-generator 2>/dev/null || echo 未找到)"
echo "snpe-onnx-to-dlc: $(which snpe-onnx-to-dlc 2>/dev/null || echo 未找到)"
echo "docker: $(docker --version 2>/dev/null || echo 未安装)"
echo "磁盘: $(df -h . | tail -1)"
echo "=== 完 ==="
