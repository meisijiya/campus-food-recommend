#!/usr/bin/env bash
# init.sh — 验证门禁(Verification Gate)
#
# 当前阶段: 仓库骨架阶段,F-1 工单启动前此脚本**必须**返回非零。
# 这是有意的——任何人在没有跑过 F-1 把脚手架搭起来之前,
# 跑 init.sh 必须看到失败,而不是"看起来通过了"。
#
# 一旦 F-1 完成,把本脚本替换为真实门禁(预计组合见下方 EXPECTED)。

set -euo pipefail

EXPECTED=<<'EOF'
  1. ./mvnw -q -DskipTests package           # 编译 + 打可执行 jar
  2. ./mvnw -q test                          # 单元测试与集成测试
  3. docker compose -f docker-compose.yml config -q   # compose 文件合法
  4. ./mvnw -q spring-boot:run &            # 启动后健康检查
     sleep 20 && curl -fsS http://localhost:8080/actuator/health
EOF

err() {
  cat <<MSG >&2
[init.sh] 当前阶段尚未搭好验证门禁。
预期的完整门禁命令(在 F-1 完成后替换本脚本):

${EXPECTED}

当前结果: FAIL(预期内)。这是仓库骨架阶段的占位失败,
不属于缺陷,但意味着现在不能声称任何 ticket 完成。

MSG
  exit 1
}

err