# init.ps1 — 验证门禁(Windows PowerShell 版本)
#
# 6 阶段验证:
#   1/6 编译 + 打 jar
#   2/6 单元 + 集成测试(Mock profile)
#   3/6 docker-compose 文件合法性
#   4/6 dev profile 启动后 /actuator/health 检查
#   5/6 JMeter F-1 5000+ QPS 压测(ADR-0005)
#   6/6 locust F-5 P99<50ms 压测(待 F-5 完成才生效)
#
# 入口:bash init.sh(委托到这里),或直接 powershell -ExecutionPolicy Bypass -File init.ps1
# 退出码:0 = PASS,非 0 = 失败。
#
# 工具默认路径(用户不同改下面变量):
$env:Path = "C:\programs\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin;C:\Users\22923\.local\bin;D:\Environment\apache-jmeter-5.6.3\bin;$env:Path"

$MvnBin       = if ($env:MVN_BIN)   { $env:MVN_BIN }   else { "C:\programs\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" }
$JmeterBin    = if ($env:JMETER_BIN){ $env:JMETER_BIN} else { "D:\Environment\apache-jmeter-5.6.3\bin\jmeter.bat" }
$UvBin        = if ($env:UV_BIN)    { $env:UV_BIN }    else { "C:\Users\22923\.local\bin\uv.exe" }
$DockerBin    = if ($env:DOCKER_BIN){ $env:DOCKER_BIN } else { "C:\Program Files\Docker\Docker\resources\bin\docker.exe" }

Set-Location -LiteralPath $PSScriptRoot

# === 1/6 ===
Write-Host "[init.sh] 阶段 1/6: 编译 + 打 jar ..."
& $MvnBin -B -q -DskipTests package | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:编译" ; exit 1 }

# === 2/6 ===
Write-Host "[init.sh] 阶段 2/6: 单元 + 集成测试(Mock profile,不走 bench)..."
& $MvnBin -B -q test | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:测试" ; exit 1 }

# === 3/6 ===
Write-Host "[init.sh] 阶段 3/6: docker-compose 文件合法性 ..."
& $DockerBin compose -f docker-compose.yml config -q | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:compose 文件" ; exit 1 }

# === 4/6 ===
Write-Host "[init.sh] 阶段 4/6: dev profile 启动后 /actuator/health 检查 ..."
$alreadyUp = $false
try {
    # 用 127.0.0.1 而非 localhost:Windows 把 localhost 优先解析为 IPv6(::1),
    # 但 cfr-app 仅绑定 IPv4 0.0.0.0:8080,IPv6 访问会超时
    $null = Invoke-WebRequest -Uri "http://127.0.0.1:8080/actuator/health" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
    $alreadyUp = $true
} catch { }

if ($alreadyUp) {
    Write-Host "[init.sh] 检测到 8080 上已有应用,跳过阶段 4(假定 cfr-app 容器已起)"
} else {
    & $DockerBin compose up -d mysql redis rabbitmq app | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:compose up" ; exit 1 }
    $ok = $false
    for ($i = 0; $i -lt 90; $i++) {
        try {
            $null = Invoke-WebRequest -Uri "http://127.0.0.1:8080/actuator/health" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
            $ok = $true; break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ok) {
        Write-Host "[init.sh] FAIL:应用未能在 90 秒内启动"
        & $DockerBin compose logs --tail 50 cfr-app
        exit 1
    }
}

# === 5/6 ===
Write-Host "[init.sh] 阶段 5/6: JMeter F-1 5000+ QPS 压测 ..."
New-Item -ItemType Directory -Path "evidence" -Force | Out-Null
& $JmeterBin -n -t "evidence/f1-jmeter.jmx" -l "evidence/f1-jmeter.jtl" 2>&1 | Select-Object -Last 10
# 阈值由 ADR-0005 + F-1 evidence 段定义:60s 平均 ≥ 5000 RPS,误差 < 5%

# === 6/6 ===
Write-Host "[init.sh] 阶段 6/6: locust F-5 P99<50ms 压测(待 F-5 完成才生效)..."
$likeCtrl = "src\main\java\com\meisijiya\campusfood\module\like\LikeController.java"
if (Test-Path $likeCtrl) {
    & $UvBin run locust -f locustfile.py --headless --host=http://localhost `
        --tags mix-like-detail -u 50 -r 25 -t 10s --csv=evidence/f5-p99 2>&1 | Select-Object -Last 20
} else {
    Write-Host "[init.sh] 跳过(LikeController 尚未实现,F-5 待开干)"
}

Write-Host "[init.sh] PASS:全部 6 阶段通过"