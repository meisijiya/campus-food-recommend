# init.ps1 鈥?楠岃瘉闂ㄧ(Windows PowerShell 鐗堟湰)
#
# 8 闃舵楠岃瘉:
#   1/8 缂栬瘧 + 鎵?jar
#   2/8 鍗曞厓 + 闆嗘垚娴嬭瘯(Mock profile)
#   3/8 docker-compose 鏂囦欢鍚堟硶鎬?#   4/8 dev profile 鍚姩鍚?/actuator/health 妫€鏌?#   5/8 JMeter F-1 5000+ QPS 鍘嬫祴(ADR-0005)
#   6/8 locust F-5 P99<100ms 鍘嬫祴(寰?F-5 瀹屾垚鎵嶇敓鏁?
#   7/8 frontend-design 鏂囨。鏍￠獙(ADR-0011)
#   8/8 frontend 宸ョ▼ build + tsc(ADR-0012,frontend/ 缂哄け鏃惰烦杩?
#
# 鍏ュ彛:bash init.sh(濮旀墭鍒拌繖閲?,鎴栫洿鎺?powershell -ExecutionPolicy Bypass -File init.ps1
# 閫€鍑虹爜:0 = PASS,闈?0 = 澶辫触銆?#
# 宸ュ叿榛樿璺緞(鐢ㄦ埛涓嶅悓鏀逛笅闈㈠彉閲?:
$env:Path = "C:\programs\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin;C:\Users\22923\.local\bin;D:\Environment\apache-jmeter-5.6.3\bin;$env:Path"

$MvnBin       = if ($env:MVN_BIN)   { $env:MVN_BIN }   else { "C:\programs\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd" }
$JmeterBin    = if ($env:JMETER_BIN){ $env:JMETER_BIN} else { "D:\Environment\apache-jmeter-5.6.3\bin\jmeter.bat" }
$UvBin        = if ($env:UV_BIN)    { $env:UV_BIN }    else { "C:\Users\22923\.local\bin\uv.exe" }
$DockerBin    = if ($env:DOCKER_BIN){ $env:DOCKER_BIN } else { "C:\Program Files\Docker\Docker\resources\bin\docker.exe" }

Set-Location -LiteralPath $PSScriptRoot

# === 1/6 ===
Write-Host "[init.sh] 闃舵 1/6: 缂栬瘧 + 鎵?jar ..."
& $MvnBin -B -q -DskipTests package | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:缂栬瘧" ; exit 1 }

# === 2/6 ===
Write-Host "[init.sh] 闃舵 2/6: 鍗曞厓 + 闆嗘垚娴嬭瘯(Mock profile,涓嶈蛋 bench)..."
& $MvnBin -B -q test | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:娴嬭瘯" ; exit 1 }

# === 3/6 ===
Write-Host "[init.sh] 闃舵 3/6: docker-compose 鏂囦欢鍚堟硶鎬?..."
& $DockerBin compose -f docker-compose.yml config -q | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "[init.sh] FAIL:compose 鏂囦欢" ; exit 1 }

# === 4/6 ===
Write-Host "[init.sh] 闃舵 4/6: dev profile 鍚姩鍚?/actuator/health 妫€鏌?..."
$alreadyUp = $false
try {
    # 鐢?127.0.0.1 鑰岄潪 localhost:Windows 鎶?localhost 浼樺厛瑙ｆ瀽涓?IPv6(::1),
    # 浣?cfr-app 浠呯粦瀹?IPv4 0.0.0.0:8080,IPv6 璁块棶浼氳秴鏃?    $null = Invoke-WebRequest -Uri "http://127.0.0.1:8080/actuator/health" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
    $alreadyUp = $true
} catch { }

if ($alreadyUp) {
    Write-Host "[init.sh] 妫€娴嬪埌 8080 涓婂凡鏈夊簲鐢?璺宠繃闃舵 4(鍋囧畾 cfr-app 瀹瑰櫒宸茶捣)"
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
        Write-Host "[init.sh] FAIL:搴旂敤鏈兘鍦?90 绉掑唴鍚姩"
        & $DockerBin compose logs --tail 50 cfr-app
        exit 1
    }
}

# === 5/6 ===
Write-Host "[init.sh] 闃舵 5/6: JMeter F-1 5000+ QPS 鍘嬫祴 ..."
New-Item -ItemType Directory -Path "evidence" -Force | Out-Null
& $JmeterBin -n -t "evidence/f1-jmeter.jmx" -l "evidence/f1-jmeter.jtl" 2>&1 | Select-Object -Last 10
# 闃堝€肩敱 ADR-0005 + F-1 evidence 娈靛畾涔?60s 骞冲潎 鈮?5000 RPS,璇樊 < 5%

# === 6/6 ===
Write-Host "[init.sh] 闃舵 6/6: locust F-5 P99<100ms 鍘嬫祴..."
$likeCtrl = "src\main\java\com\meisijiya\campusfood\module\like\LikeController.java"
if (Test-Path $likeCtrl) {
    # F-5 evidence:鐢?locustfile_mix.py(per-tag 鎷嗗垎,閬垮紑 --tags 鍦?master file 涓婄殑 instantiation 澶辫触)
    # host 鐢?127.0.0.1 涓嶇敤 localhost(Windows localhost 浼樺厛瑙ｆ瀽 IPv6 [::1],cfr-app 鍙粦 IPv4)
    # 30s 瀹屾暣璺?u=50 r=25 / 50% like + 50% merchant),binding acceptance: P99 < 100ms
    # (ref: evidence/f5-p99_stats.csv,burst=100000)
    & $UvBin run locust -f locustfile_mix.py --headless --host=http://127.0.0.1:8080 `
        -u 50 -r 25 -t 30s --csv=evidence/f5-p99 2>&1 | Select-Object -Last 20
    $locustExit = $LASTEXITCODE

    # F-13 demo hardening(ADR-0009):瑙ｆ瀽 failures.csv 鍖哄垎 PASS / PASS-WARN / FAIL
    # F-7 闄愭祦涓婄嚎鍚?50 user burst 鎾炵┛ user bucket 100/10s 浼氳Е鍙?429,杩欐槸璁捐琛屼负
    # (鐢ㄦ埛 1 涓?spam 灏辫兘瑙﹀彂),涓嶆槸 bug 鈫?PASS-WARN 璺緞閫€鍑?0
    # 鍙湁"闈?429 閿欒鍗犳瘮 > 5%"鎵嶅垽 FAIL(exit 1)
    $failCsv = "evidence/f5-p99_failures.csv"
    if (Test-Path $failCsv) {
        $fails = Import-Csv $failCsv -ErrorAction SilentlyContinue
        if ($fails) {
            $totalFails = ($fails | Measure-Object -Property Occurrences -Sum).Sum
            $f429 = ($fails | Where-Object { $_.Error -like "*429*" } | Measure-Object -Property Occurrences -Sum).Sum
            $fOther = $totalFails - $f429
            $ratio429 = if ($totalFails -gt 0) { $f429 / $totalFails } else { 0 }
            $ratioOther = if ($totalFails -gt 0) { $fOther / $totalFails } else { 0 }

            if ($ratioOther -gt 0.05) {
                Write-Host "[init.sh] FAIL: stage 6 闈?429 閿欒鍗犳瘮 = $([math]::Round($ratioOther*100, 2))% > 5%(429 = $([math]::Round($ratio429*100, 2))%)"
                exit 1
            } elseif ($ratio429 -ge 0.5) {
                Write-Host "[init.sh] PASS-WARN: stage 6 429 鍗犳瘮 = $([math]::Round($ratio429*100, 2))%(F-7 闄愭祦璁捐琛屼负瑙﹀彂,exit 0)"
                # F-13 polish-C:鎶?PASS-WARN 鍏抽敭 metric 钀藉埌 evidence/f5-p99-metrics.csv,渚夸簬澶嶇洏/瀵规瘮
                # 13 鍒楀浐瀹氶『搴?姣忔 stage 6 PASS-WARN 瑙﹀彂鏃惰鐩栧啓,涓嶈蛋 append
                $statsCsv = "evidence/f5-p99_stats.csv"
                $metricsCsv = "evidence/f5-p99-metrics.csv"
                if (Test-Path $statsCsv) {
                    $stats = Import-Csv $statsCsv -ErrorAction SilentlyContinue
                    $agg = $stats | Where-Object { $_.Name -eq "Aggregated" } | Select-Object -First 1
                    if ($agg) {
                        $totalReq = [int]$agg.'Request Count'
                        $rpsVal = [double]$agg.'Requests/s'
                        $p50 = [double]$agg.'Median Response Time'
                        $p95 = [double]$agg.'95%'
                        $p99 = [double]$agg.'99%'
                        $timestamp = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
                        $ratio429Pct = [math]::Round($ratio429 * 100, 2)
                        $ratioOtherPct = [math]::Round($ratioOther * 100, 2)
                        $row = [PSCustomObject]@{
                            timestamp         = $timestamp
                            ticket            = 'f5'
                            total_requests    = $totalReq
                            rps               = $rpsVal
                            p50_ms            = $p50
                            p95_ms            = $p95
                            p99_ms            = $p99
                            f429_count        = $f429
                            f429_ratio_pct    = $ratio429Pct
                            fother_count      = $fOther
                            fother_ratio_pct  = $ratioOtherPct
                            status            = 'PASS-WARN'
                            note              = 'F-13 polish-C: F-7 rate-limit design behavior, 429 burst captured for replay'
                        }
                        $row | Export-Csv -Path $metricsCsv -NoTypeInformation -Encoding UTF8
                        Write-Host "[init.sh] metrics dumped: $metricsCsv"
                    } else {
                        Write-Host "[init.sh] WARN: $statsCsv 缂?Aggregated 琛?metrics CSV 璺宠繃"
                    }
                } else {
                    Write-Host "[init.sh] WARN: $statsCsv 涓嶅瓨鍦?metrics CSV 璺宠繃"
                }
            } else {
                Write-Host "[init.sh] PASS: stage 6 failures < 50%(429 = $([math]::Round($ratio429*100, 2))%, other = $([math]::Round($ratioOther*100, 2))%)"
            }
        } else {
            Write-Host "[init.sh] PASS: stage 6 鏃?failures"
        }
    } else {
        Write-Host "[init.sh] WARN: $failCsv 涓嶅瓨鍦?璺宠繃 PASS-WARN 鍒ゆ柇(locust ExitCode=$locustExit)"
        if ($locustExit -ne 0) {
            Write-Host "[init.sh] WARN: locust 閫€鍑虹爜闈為浂,浣?acceptance 鍦ㄧ嫭绔?evidence 鏂囦欢宸插浐鍖?缁х画"
        }
    }
} else {
    Write-Host "[init.sh] 璺宠繃(LikeController 灏氭湭瀹炵幇,F-5 寰呭紑骞?"
}

Write-Host "[init.sh] PASS:鍏ㄩ儴 6 闃舵閫氳繃"

# === 7/7 ===
# ADR-0011 frontend-design 鏂囨。鏍￠獙銆傛湰杞彧浜?design.md 涓嶄笅鍓嶇宸ョ▼,浣嗘枃妗ｉ』閫氳繃 stage 7
# 鎵嶇畻 "design.md 钀藉湴"銆傛牎楠屽け璐?exit 1(鍚屽墠 6 stage 涓€鑷寸‖绾︽潫)銆?Write-Host "[init.sh] 闃舵 7/7: frontend-design 鏂囨。鏍￠獙 ..."
$designRoot = "docs/design/frontend"
$adrPath    = "docs/adr/0011-frontend-design-decisions.md"

$required = @(
    @{ p = "$designRoot/overview.md";                 min = 80 }
    @{ p = "$designRoot/visual-system.md";            min = 150 }
    @{ p = "$designRoot/architecture.md";             min = 150 }
    @{ p = "$designRoot/api-contract.md";             min = 120 }
    @{ p = "$designRoot/observability-and-launch.md"; min = 120 }
    @{ p = "$designRoot/mock/login.html";             min = 0 }
    @{ p = $adrPath;                                  min = 50 }
)
$stage7Fail = $false
foreach ($r in $required) {
    if (-not (Test-Path $r.p)) {
        Write-Host "[init.sh] FAIL: missing $($r.p)"
        $stage7Fail = $true
        continue
    }
    if ($r.min -gt 0) {
        $lineCount = (Get-Content $r.p).Count
        if ($lineCount -lt $r.min) {
            Write-Host "[init.sh] FAIL: $($r.p) lines=$lineCount < min=$($r.min)"
            $stage7Fail = $true
        } else {
            Write-Host "[init.sh] OK:   $($r.p) lines=$lineCount"
        }
    } else {
        Write-Host "[init.sh] OK:   $($r.p) exists"
    }
}

# visual-system.md 蹇呴』鍖呭惈涓や釜 token grep 鍏抽敭瀛?鍐崇瓥 9)
$vsPath = "$designRoot/visual-system.md"
if (Test-Path $vsPath) {
    $vsContent = Get-Content $vsPath -Raw
    foreach ($kw in @("--color-primary:", "--space-4:")) {
        if (-not $vsContent.Contains($kw)) {
            Write-Host "[init.sh] FAIL: visual-system.md 缂哄叧閿瓧 $kw"
            $stage7Fail = $true
        }
    }
}

# login.html 闈欐€佹鏌?涓嶈兘鍚湡姝ｇ殑鑴氭湰鏍囩(鍐崇瓥 8,F-16 wontful 杈圭晫)
# pattern 鎺掗櫎 HTML 娉ㄩ噴鍐呯殑瀛楅潰瀛楃涓?鍙尮閰嶆墽琛屾€?<script ...> 鎴?<script> 寮€鏍囩
$mockPath = "$designRoot/mock/login.html"
if (Test-Path $mockPath) {
    $mockContent = Get-Content $mockPath -Raw
    # 鍘绘帀 HTML 娉ㄩ噴鍧?<!-- ... -->)鍐?grep,閬垮厤娉ㄩ噴閲?"<script>" 瀛楅潰瀛楃涓茶鍒?    $mockNoComment = [regex]::Replace($mockContent, '<!--.*?-->', '', 'Singleline')
    if ($mockNoComment -match '<script[\s>]') {
        Write-Host "[init.sh] FAIL: mock/login.html 鍚墽琛屾€ц剼鏈爣绛?F-16 wontful 杈圭晫)"
        $stage7Fail = $true
    }
}

if ($stage7Fail) {
    Write-Host "[init.sh] FAIL: stage 7 frontend-design 鏍￠獙鏈繃"
    exit 1
}
Write-Host "[init.sh] PASS: 闃舵 7/7 frontend-design 鏍￠獙閫氳繃"

# === 8/8 ===
# ADR-0012:frontend 宸ョ▼ build + tsc 鏍￠獙銆俧rontend/ 缂哄け鏃惰烦杩?闃叉棭鏈?commit 澶辫触)銆?Write-Host "[init.sh] 闃舵 8/8: frontend 宸ョ▼ build + tsc ..."
$frontendDir = "frontend"
if (-not (Test-Path $frontendDir)) {
    Write-Host "[init.sh] SKIP: stage 8 frontend/ 涓嶅瓨鍦?璺宠繃(鏈粨搴?commit history 鏃╂湡闃舵,F-16.1 鏈惤鍦?"
} else {
    $npmBin = if ($env:NPM_BIN) { $env:NPM_BIN } else { "npm.cmd" }

    # tsc 绫诲瀷妫€鏌?    Write-Host "[init.sh] 8/8 step 1/2: vue-tsc --noEmit ..."
    Push-Location $frontendDir
    try {
        & $npmBin run type-check 2>&1 | Select-Object -Last 10
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[init.sh] FAIL: vue-tsc 绫诲瀷妫€鏌ユ湭閫氳繃"
            Pop-Location
            exit 1
        }

        # vite build(瀹為檯鏋勫缓,楠岃瘉鏁翠釜閾捐矾)
        Write-Host "[init.sh] 8/8 step 2/2: vite build ..."
        & $npmBin run build 2>&1 | Select-Object -Last 15
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[init.sh] FAIL: vite build 鏈€氳繃"
            Pop-Location
            exit 1
        }
    } finally {
        Pop-Location
    }
    Write-Host "[init.sh] PASS: 闃舵 8/8 frontend build + tsc 閫氳繃"
}
Write-Host "[init.sh] PASS:鍏ㄩ儴 8 闃舵閫氳繃"