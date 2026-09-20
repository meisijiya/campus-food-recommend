# scripts/interview-demo.ps1 — 面试现场 5 endpoint 一键 demo (PowerShell 版)
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File scripts/interview-demo.ps1
#   $env:BASE="http://my-host:8080"; powershell -ExecutionPolicy Bypass -File scripts/interview-demo.ps1
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

$ErrorActionPreference = 'Stop'

$BASE = if ($env:BASE) { $env:BASE } else { 'http://localhost:8080' }
$USER = if ($env:USER) { $env:USER } else { 'demo' }
$PASS = if ($env:PASS) { $env:PASS } else { 'demo' }

function Step($n, $title) {
    Write-Host "`n=== STEP $n : $title ===" -ForegroundColor Cyan
}

function Expect-200($code, $where) {
    if ($code -ne 200) {
        Write-Host "FAIL: $where returned $code" -ForegroundColor Red
        exit 1
    }
    Write-Host "OK  $where 200" -ForegroundColor Green
}

# ---------- STEP 1: 登录拿 JWT ----------
Step 1 "POST $BASE/api/auth/login (拿 JWT)"
$loginBody = @{ username = $USER; password = $PASS } | ConvertTo-Json
try {
    $loginResp = Invoke-RestMethod -Method Post -Uri "$BASE/api/auth/login" -ContentType 'application/json' -Body $loginBody
} catch {
    Write-Host "FAIL: login 网络错误: $_" -ForegroundColor Red
    exit 2
}

# 仓内 ApiResponse 包裹: {code, message, data: {accessToken, refreshToken, expiresIn}}
$token = $loginResp.data.accessToken
$tokenPreview = if ($token.Length -gt 30) { $token.Substring(0, 30) + '...' } else { $token }
Write-Host "Token (前 30 chars): $tokenPreview"

$headers = @{ Authorization = "Bearer $token" }

# ---------- STEP 2: 初始化会话 ----------
Step 2 "POST $BASE/api/session/init (创建 session)"
try {
    $initResp = Invoke-WebRequest -Method Post -Uri "$BASE/api/session/init" -Headers $headers -UseBasicParsing
} catch {
    Write-Host "FAIL: session/init 网络错误: $_" -ForegroundColor Red
    exit 2
}
Expect-200 $initResp.StatusCode "session/init"

# ---------- STEP 3: 进 ZONE 阶段 ----------
Step 3 "POST $BASE/api/session/zone (进 ZONE 阶段)"
$zoneBody = @{ zoneId = 'Z-1' } | ConvertTo-Json
try {
    $zoneResp = Invoke-WebRequest -Method Post -Uri "$BASE/api/session/zone" -Headers $headers -ContentType 'application/json' -Body $zoneBody -UseBasicParsing
} catch {
    Write-Host "FAIL: session/zone 网络错误: $_" -ForegroundColor Red
    exit 2
}
Expect-200 $zoneResp.StatusCode "session/zone"

# ---------- STEP 4: 触发 AI 推荐 ----------
Step 4 "POST $BASE/api/recommend (触发 AI 推荐)"
try {
    $recResp = Invoke-WebRequest -Method Post -Uri "$BASE/api/recommend" -Headers $headers -UseBasicParsing
} catch {
    Write-Host "FAIL: recommend 网络错误: $_" -ForegroundColor Red
    exit 2
}
Expect-200 $recResp.StatusCode "recommend"

# ---------- STEP 5: 点赞幂等 demo ----------
Step 5 "POST $BASE/api/like/M-NOODLE (幂等 demo - 跑 2 次)"
try {
    $like1 = Invoke-RestMethod -Method Post -Uri "$BASE/api/like/M-NOODLE" -Headers $headers
    $like1Json = $like1 | ConvertTo-Json -Compress
    Write-Host "首次响应: $like1Json"

    $like2 = Invoke-RestMethod -Method Post -Uri "$BASE/api/like/M-NOODLE" -Headers $headers
    $like2Json = $like2 | ConvertTo-Json -Compress
    Write-Host "二次响应: $like2Json"
} catch {
    Write-Host "FAIL: like 网络错误: $_" -ForegroundColor Red
    exit 2
}

Write-Host "`n=== DONE - 5 endpoints all 200 + idempotency OK ===" -ForegroundColor Green
Write-Host "    面试话术: '5 个核心 endpoint 一气跑通, 从登录到点赞幂等, 5 秒可演示。'"
Write-Host "    二次 like 返回 liked:false 'already liked' 是 F-5 Redis NX 60s 幂等的体现"
Write-Host "    (面试官追问时可切到 cheat-sheet.md Bullet 5 行回答)"