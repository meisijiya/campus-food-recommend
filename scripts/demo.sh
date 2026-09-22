#!/usr/bin/env bash
# scripts/demo.sh — 端到端演示 5 条简历 bullet(POSIX shell 等价物)
# 前提:cfr-app 已起(localhost:8080 / localhost Nginx),MySQL/Redis/RabbitMQ 已就绪
# 用法:bash scripts/demo.sh
#      BASE=http://localhost bash scripts/demo.sh  (经 Nginx 80)

set -euo pipefail
BASE="${BASE:-http://localhost:8080}"

section() { printf "\n==== %s ====\n" "$1"; }
say_ok()   { printf "  \033[32m%s\033[0m\n" "$1"; }
say_info() { printf "  %s\n" "$1"; }

req() {
    local method="$1" url="$2" token="${3:-}" body="${4:-}"
    local -a args=(-sS -X "$method" -H "Accept: application/json" --max-time 10 "$url")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body"  ] && args+=(-H "Content-Type: application/json" -d "$body")
    curl "${args[@]}"
}

# 0. 健康检查
section "0. 健康检查"
health=$(req GET "$BASE/actuator/health")
say_ok "/actuator/health         → $(echo "$health" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"

# 1. 登录
section "1. F-1 无状态架构 / JWT 鉴权"
login=$(req POST "$BASE/api/auth/login" "" '{"username":"demo","password":"demo"}')
token=$(echo "$login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
say_ok "login → 拿到 accessToken(长度 ${#token})"

# 2. 会话槽位(F-2)
section "2. F-2 会话槽位约束 / 渐进检索"
declare -a steps=(
    "INIT|/api/session/init|"
    "ZONE|/api/session/zone|{\"zoneId\":3}"
    "CUISINE|/api/session/cuisine|{\"cuisineId\":12}"
    "MERCHANT|/api/session/merchant|{\"merchantId\":7}"
)
for s in "${steps[@]}"; do
    IFS='|' read -r name path body <<< "$s"
    out=$(req POST "$BASE$path" "$token" "$body")
    stage=$(echo "$out" | sed -n 's/.*"stage":"\([^"]*\)".*/\1/p')
    say_ok "$name → ${stage:-FAIL}"
done

# 3. 推荐(F-3)
section "3. F-3 结构化输出 / 反思重试"
rec=$(req POST "$BASE/api/recommend" "$token" '{"userMessage":"清淡,不要辣","sessionId":"demo-001"}')
rec_count=$(echo "$rec" | grep -o '"merchantId"' | wc -l | tr -d ' ')
tokens=$(echo "$rec" | sed -n 's/.*"tokensUsed":\([0-9]*\).*/\1/p')
say_ok "recommendations: $rec_count 条 / tokensUsed: $tokens"

# 4. 点赞幂等(F-5)
section "4. F-5 点赞幂等 / Redis NX 60s"
l1=$(req POST "$BASE/api/like/7" "$token")
l2=$(req POST "$BASE/api/like/7" "$token")
say_ok "第 1 次 → $(echo "$l1" | sed -n 's/.*"liked":\([a-z]*\).*/\1/p')"
say_ok "第 2 次 → $(echo "$l2" | sed -n 's/.*"liked":\([a-z]*\).*/\1/p') (60s 内重复拦截)"

# 5. 商户详情(F-4 + F-5)
section "5. F-4 预热 / F-5 L0→L1→L2 三级降级"
detail=$(req GET "$BASE/api/merchant/7" "$token")
mid=$(echo "$detail" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
mname=$(echo "$detail" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')
heat=$(echo "$detail" | sed -n 's/.*"heat":\([0-9]*\).*/\1/p')
say_ok "merchant #$mid ($mname) heat=$heat"

# 6. 预热(F-4)
section "6. F-4 离线预热(手动触发)"
preheat=$(req POST "$BASE/admin/preheat/trigger" "$token")
say_ok "preheat → $(echo "$preheat" | sed -n 's/.*"merchantCount":\([0-9]*\).*/\1/p') merchants"

printf "\n==== 演示完成 ====\n"
echo "如需压测 F-1 5000+ QPS:    bash scripts/bench-jmeter.sh"
echo "如需压测 F-5 P99<100ms:    bash scripts/bench-locust.sh  (burst=100000 覆盖)"
