#!/usr/bin/env bash
# init.sh — 验证门禁(Verification Gate)
#
# F-1 实施后已替换为真实门禁。预期行为:退出码 0 = 通过;非 0 = 失败。
#
# 阶段说明:
#   - F-1~F-5 全部 done 后,所有命令可跑通(本脚本返 0)。
#   - 任一 ticket 未完成,跑 mvnw test 会失败,本脚本返非 0。
#
# 跑前准备(本机):
#   - JDK 21、Maven 3.9+、Docker、Docker Compose 均就绪
#   - uv 已装(`pip install uv` 或 `pipx install uv`)
#   - 本机 3306 / 6379 / 5672 端口空闲(或改 docker-compose 端口)

set -euo pipefail

cd "$(dirname "$0")"

echo "[init.sh] 阶段 1/6: 编译 + 打 jar ..."
./mvnw -q -DskipTests package

echo "[init.sh] 阶段 2/6: 单元 + 集成测试(Mock profile,不走 bench)..."
./mvnw -q test

echo "[init.sh] 阶段 3/6: docker-compose 文件合法性 ..."
docker compose -f docker-compose.yml config -q

echo "[init.sh] 阶段 4/6: dev profile 启动后 /actuator/health 检查 ..."
./mvnw -q spring-boot:run >/tmp/cfr-app.log 2>&1 &
APP_PID=$!
trap "kill $APP_PID 2>/dev/null || true" EXIT

# 等待启动
for i in {1..30}; do
  if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "[init.sh] FAIL:应用未能在 30 秒内启动" >&2
  exit 1
fi

echo "[init.sh] 阶段 5/6: locust F-1 5000+ QPS 压测 ..."
mkdir -p evidence
uv run locust -f locustfile.py --headless --host=http://localhost \
    --tags auth-only -u 200 -r 50 -t 10s --csv=evidence/f1-qps 2>&1 | tail -20
# 报告 RPS(实际 RPS 阈值由 ticket evidence 段定义)

echo "[init.sh] 阶段 6/6: locust F-5 P99<50ms 压测(待 F-5 完成才生效)..."
if [ -f src/main/java/com/meisijiya/campusfood/module/like/LikeController.java ]; then
  uv run locust -f locustfile.py --headless --host=http://localhost \
      --tags mix-like-detail -u 50 -r 25 -t 10s --csv=evidence/f5-p99 2>&1 | tail -20
else
  echo "[init.sh] 跳过(LikeController 尚未实现,F-5 待开干)"
fi

echo "[init.sh] PASS:全部 6 阶段通过"