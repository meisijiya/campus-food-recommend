# f11-startup-verify.ps1 — F-11 FeatureFlag 启动期 4 场景 prod 实战验证
#
# 用途:起 cfr-app + curl 端到端验证 4 种启动期 Redis fallback 行为
# 配套:docs/observability/feature-flag-defaults.md (理论契约)
#       src/test/.../FeatureFlagIT.java (in-process IT 验证)
#       evidence/f11-startup-scenarios.md (本次跑出来的 evidence 文档)
#
# 4 场景:
#   1. Redis hash empty (cold start)              → yml defaults 全生效
#   2. Redis unreachable (catch 降级)             → yml defaults 全生效
#   3. Redis contains malformed JSON (脏数据)     → 单条 skip + yml defaults 兜底
#   4. Redis partial + yml fill (merge 语义)      → Redis 赢覆盖, yml putIfAbsent 填补缺失
#
# 用法:
#   pwsh -File scripts/f11-startup-verify.ps1
#
# 退出码:
#   0 = 全部场景通过 (curl 响应符合预期)
#   1 = 任一场景失败
#
# 注意:
#   - 假设 cfr-app + cfr-redis 容器已起(若未起会 docker compose up -d)
#   - 假设 cfr-app 已经走过完整 build(`docker compose up -d --build app`)
#   - 跑完后会 DEL Redis hash + 重启 cfr-app, 还原到 yml 默认状态
#
# 输出:控制台 + 不写 evidence(evidence/f11-startup-scenarios.md 是 2026-09-21 那次跑的产物,
#       本脚本只验证当前 cfr-app 行为;若要重新生成 evidence 文档,把控制台输出抓走即可)

$ErrorActionPreference = 'Continue'
$AppHost = 'http://127.0.0.1:8080'
$RedisContainer = 'cfr-redis'

function Wait-AppHealthy {
    param([int]$MaxSeconds = 30)
    for ($i = 0; $i -lt $MaxSeconds; $i++) {
        $h = & curl.exe -s -m 2 "$AppHost/actuator/health" 2>$null
        if ($h -like '*UP*') {
            Write-Host "[OK] cfr-app healthy after $i seconds"
            return $true
        }
        Start-Sleep -Seconds 1
    }
    Write-Host "[FAIL] cfr-app not healthy in $MaxSeconds seconds"
    return $false
}

function Get-Flag {
    param([string]$FlagKey, [string]$StudentId = '')
    $qs = if ($StudentId) { "?studentId=$StudentId" } else { '' }
    & curl.exe -s "$AppHost/api/feature-flag/$FlagKey/check$qs"
}

function Assert-Flag {
    param(
        [string]$Scenario,
        [string]$ExpectedMode,
        [string]$ActualJson,
        [string]$FlagKey
    )
    if ($ActualJson -like "*`"mode`":`"$ExpectedMode`"*") {
        Write-Host "  [OK] $Scenario $FlagKey mode=$ExpectedMode"
        return $true
    } else {
        Write-Host "  [FAIL] $Scenario $FlagKey expected mode=$ExpectedMode, got: $ActualJson"
        return $false
    }
}

# ============ 前置:确认容器在跑 ============
Write-Host "=== Pre-flight: containers ==="
docker ps --filter "name=cfr-app" --filter "name=cfr-redis" --format "{{.Names}} {{.Status}}"
$appExists = docker ps --filter "name=cfr-app" --filter "status=running" -q 2>$null
if (-not $appExists) {
    Write-Host "cfr-app not running, starting docker compose..."
    docker compose up -d mysql redis rabbitmq app | Out-Null
}

$failures = @()

# ============ Scenario 1: Redis hash empty (cold start) ============
Write-Host ""
Write-Host "=== Scenario 1: Redis hash empty (cold start) ==="
docker exec $RedisContainer redis-cli DEL feature_flags | Out-Null
docker compose restart app | Out-Null
if (-not (Wait-AppHealthy)) { $failures += "S1-app-not-healthy"; exit 1 }

$r1 = Get-Flag -FlagKey 'recommend-v2' -StudentId '100'
$r2 = Get-Flag -FlagKey 'like-cache-bypass' -StudentId '1'
$r3 = Get-Flag -FlagKey 'merchant-detail-new'
if (-not (Assert-Flag 'S1' 'ALL_ON' $r1 'recommend-v2'))         { $failures += 'S1-rv2' }
if (-not (Assert-Flag 'S1' 'WHITELIST_ONLY' $r2 'like-cache-bypass')) { $failures += 'S1-lcb' }
if (-not (Assert-Flag 'S1' 'ALL_ON' $r3 'merchant-detail-new'))  { $failures += 'S1-mdn' }

# ============ Scenario 2: Redis unreachable ============
Write-Host ""
Write-Host "=== Scenario 2: Redis unreachable ==="
docker compose stop redis | Out-Null
docker compose restart app | Out-Null
if (-not (Wait-AppHealthy)) { $failures += "S2-app-not-healthy"; exit 1 }

$r1 = Get-Flag -FlagKey 'recommend-v2' -StudentId '100'
$r2 = Get-Flag -FlagKey 'like-cache-bypass' -StudentId '1'
$r3 = Get-Flag -FlagKey 'merchant-detail-new'
if (-not (Assert-Flag 'S2' 'ALL_ON' $r1 'recommend-v2'))         { $failures += 'S2-rv2' }
if (-not (Assert-Flag 'S2' 'WHITELIST_ONLY' $r2 'like-cache-bypass')) { $failures += 'S2-lcb' }
if (-not (Assert-Flag 'S2' 'ALL_ON' $r3 'merchant-detail-new'))  { $failures += 'S2-mdn' }

# 恢复 redis
docker compose start redis | Out-Null
Start-Sleep -Seconds 5

# ============ Scenario 3: Redis malformed JSON ============
Write-Host ""
Write-Host "=== Scenario 3: Redis contains malformed JSON ==="
docker exec $RedisContainer redis-cli DEL feature_flags | Out-Null
# 注意:PowerShell + docker exec shell 嵌套外部单引号可能被吞,导致写入 malformed
# 故意用最简方式写 2 条 — 第 2 条 JSON 内部双引号会被 shell 吃掉
docker exec $RedisContainer redis-cli HSET feature_flags recommend-v2 'this-is-not-valid-json-garbage' | Out-Null
docker exec $RedisContainer redis-cli HSET feature_flags like-cache-bypass '{mode:WHITELIST_ONLY,whitelist:[42,99]}' | Out-Null
docker compose restart app | Out-Null
if (-not (Wait-AppHealthy)) { $failures += "S3-app-not-healthy"; exit 1 }

$r1 = Get-Flag -FlagKey 'recommend-v2' -StudentId '100'   # 期望 ALL_ON (yml fallback)
$r2 = Get-Flag -FlagKey 'like-cache-bypass' -StudentId '1'  # 期望 WHITELIST_ONLY yml [1,2,3] 命中
$r3 = Get-Flag -FlagKey 'merchant-detail-new'              # 期望 ALL_ON (yml fallback,Redis 没)
if (-not (Assert-Flag 'S3' 'ALL_ON' $r1 'recommend-v2'))         { $failures += 'S3-rv2' }
if (-not (Assert-Flag 'S3' 'WHITELIST_ONLY' $r2 'like-cache-bypass')) { $failures += 'S3-lcb' }
if (-not (Assert-Flag 'S3' 'ALL_ON' $r3 'merchant-detail-new'))  { $failures += 'S3-mdn' }

# ============ Scenario 4: Redis partial + yml fill ============
Write-Host ""
Write-Host "=== Scenario 4: Redis partial + yml fill ==="
docker exec $RedisContainer redis-cli DEL feature_flags | Out-Null
# PowerShell 单引号字符串 \" 是字面量,会写到 Redis 成 malformed JSON 触发 yml fallback。
# 用双引号变量赋值,让 \" 转义为 ",才能让 Redis 拿到 clean JSON {"mode":"ALL_OFF"...}。
$jsonValue = '{"mode":"ALL_OFF","whitelist":[],"percentage":0}'
docker exec $RedisContainer redis-cli HSET feature_flags recommend-v2 $jsonValue | Out-Null
docker compose restart app | Out-Null
if (-not (Wait-AppHealthy)) { $failures += "S4-app-not-healthy"; exit 1 }

$r1 = Get-Flag -FlagKey 'recommend-v2' -StudentId '100'   # 期望 ALL_OFF (Redis 赢)
$r2 = Get-Flag -FlagKey 'like-cache-bypass' -StudentId '1'  # 期望 WHITELIST_ONLY (yml 兜底)
$r3 = Get-Flag -FlagKey 'merchant-detail-new'              # 期望 ALL_ON (yml 兜底)
if (-not (Assert-Flag 'S4' 'ALL_OFF' $r1 'recommend-v2'))       { $failures += 'S4-rv2' }
if (-not (Assert-Flag 'S4' 'WHITELIST_ONLY' $r2 'like-cache-bypass')) { $failures += 'S4-lcb' }
if (-not (Assert-Flag 'S4' 'ALL_ON' $r3 'merchant-detail-new'))  { $failures += 'S4-mdn' }

# ============ 还原 ============
Write-Host ""
Write-Host "=== Restoring: DEL Redis hash + restart app ==="
docker exec $RedisContainer redis-cli DEL feature_flags | Out-Null
docker compose restart app | Out-Null
Wait-AppHealthy | Out-Null

# ============ 汇总 ============
Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "[PASS] F-11 startup scenarios 4/4 verified"
    exit 0
} else {
    Write-Host "[FAIL] F-11 startup scenarios failed: $($failures -join ', ')"
    exit 1
}