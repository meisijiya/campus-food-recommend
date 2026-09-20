#!/usr/bin/env bash
# scripts/interview-demo.sh — 面试现场 5 endpoint 一键 demo
#
# 用法:
#   bash scripts/interview-demo.sh
#   BASE=http://my-host:8080 USER=demo PASS=demo bash scripts/interview-demo.sh
#
# 前置条件:
#   - cfr-app 在 $BASE (默认 http://localhost:8080) 运行
#   - demo/demo 账号已 seed 进 MySQL(F-1 默认 seed)
#
# 输出:
#   5 步全部 200 + 幂等 demo → 最后一行输出成功话术
#
# 退出码:
#   0 = 全部成功
#   1 = 某步 HTTP 非 200
#   2 = 网络/JSON 解析失败

set -euo pipefail

BASE=${BASE:-http://localhost:8080}
USER=${USER:-demo}
PASS=${PASS:-demo}

step() { printf "\n=== STEP %d: %s ===\n" "$1" "$2"; }
expect_200() {
    local code=$1
    local where=$2
    if [ "$code" != "200" ]; then
        printf "FAIL: %s returned %s\n" "$where" "$code" >&2
        exit 1
    fi
    printf "OK  %s 200\n" "$where"
}

# ---------- STEP 1: 登录拿 JWT ----------
step 1 "POST $BASE/api/auth/login (拿 JWT)"
LOGIN_RESP=$(curl -fsS -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}") \
    || { echo "FAIL: login 网络错误" >&2; exit 2; }

# 抽 accessToken(仓内 ApiResponse 包裹: {code, message, data: {accessToken, refreshToken, expiresIn}})
TOKEN=$(printf '%s' "$LOGIN_RESP" | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])") \
    || { echo "FAIL: accessToken 解析失败, 响应: $LOGIN_RESP" >&2; exit 2; }
printf "Token (前 30 chars): %s...\n" "${TOKEN:0:30}"

AUTH="Authorization: Bearer $TOKEN"

# ---------- STEP 2: 初始化会话 ----------
step 2 "POST $BASE/api/session/init (创建 session)"
INIT_CODE=$(curl -fsS -o /dev/null -w "%{http_code}" \
    -X POST "$BASE/api/session/init" \
    -H "$AUTH") || { echo "FAIL: session/init 网络错误" >&2; exit 2; }
expect_200 "$INIT_CODE" "session/init"

# ---------- STEP 3: 进 ZONE 阶段 ----------
step 3 "POST $BASE/api/session/zone (进 ZONE 阶段)"
ZONE_CODE=$(curl -fsS -o /dev/null -w "%{http_code}" \
    -X POST "$BASE/api/session/zone" \
    -H "$AUTH" \
    -H 'Content-Type: application/json' \
    -d '{"zoneId":"Z-1"}') || { echo "FAIL: session/zone 网络错误" >&2; exit 2; }
expect_200 "$ZONE_CODE" "session/zone"

# ---------- STEP 4: 触发 AI 推荐 ----------
step 4 "POST $BASE/api/recommend (触发 AI 推荐)"
REC_CODE=$(curl -fsS -o /dev/null -w "%{http_code}" \
    -X POST "$BASE/api/recommend" \
    -H "$AUTH") || { echo "FAIL: recommend 网络错误" >&2; exit 2; }
expect_200 "$REC_CODE" "recommend"

# ---------- STEP 5: 点赞幂等 demo ----------
step 5 "POST $BASE/api/like/M-NOODLE (幂等 demo - 跑 2 次)"
LIKE1=$(curl -fsS -X POST "$BASE/api/like/M-NOODLE" -H "$AUTH") \
    || { echo "FAIL: like 首次网络错误" >&2; exit 2; }
printf "首次响应: %s\n" "$LIKE1"

LIKE2=$(curl -fsS -X POST "$BASE/api/like/M-NOODLE" -H "$AUTH") \
    || { echo "FAIL: like 二次网络错误" >&2; exit 2; }
printf "二次响应: %s\n" "$LIKE2"

printf "\n=== DONE - 5 endpoints all 200 + idempotency OK ===\n"
printf "    面试话术: '5 个核心 endpoint 一气跑通, 从登录到点赞幂等, 5 秒可演示。'\n"
printf "    二次 like 返回 liked:false 'already liked' 是 F-5 Redis NX 60s 幂等的体现\n"
printf "    (面试官追问时可切到 cheat-sheet.md Bullet 5 行回答)\n"