#!/usr/bin/env bash
# init.sh — 验证门禁(Verification Gate)
#
# 预期行为:退出码 0 = 通过;非 0 = 失败。
#
# 跑前准备(本机):
#   - JDK 21、Maven 3.9+、Docker、Docker Compose、JMeter 5.6+、Python 3.11+ 均就绪
#   - uv 已装(`pip install uv` 或 `pipx install uv`)
#   - 本机 8080 端口空闲(或改 compose 的 app 服务端口映射)
#
# ADR-0005:F-1 5000+ QPS 阶段改用 JMeter(locust 在 Windows GIL 受限,无法跨阈值)。
#
# 实现:此文件是薄壳 — 全部命令委托给 init.ps1(Windows 原生 PowerShell 脚本)。
# 双击/调度层面统一从 bash init.sh 进入;PowerShell 走 6 个阶段(详见 init.ps1)。
#
# 用户也可以直接 `powershell -ExecutionPolicy Bypass -File init.ps1`(无需 bash 入口)。

set -euo pipefail

cd "$(dirname "$0")"

SCRIPT_DIR="$(pwd)"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/init.ps1"