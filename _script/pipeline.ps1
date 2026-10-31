# Dev-Sidebike 三环境流水线
#
#   dev  : 源码（开发环境）→ 构建预交付文件 out/dev/app-debug.apk + build-info.json
#   test : 测试环境：单元/集成测试 + 静态审计 + APK 校验 → out/test/test-report.md + status.json
#          （任何一项失败 → 退出码非 0，必须回 dev 修根因后重跑）
#   prod : 生产环境：仅当 test 通过 → 构建签名 release APK → 升为交付文件 dist/
#   all  : dev → test → prod（任一失败立即停止）
#
# 用法: powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\pipeline.ps1 dev|test|prod|all
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('dev', 'test', 'prod', 'all')]
    [string]$Stage
)
$ErrorActionPreference = 'Stop'
$root = 'E:\WorkSpace\Dev-Sidebike'
. "$root\_script\env.ps1" | Out-Null
$gradle = "$root\tools\gradle-8.9\bin\gradle.bat"
$aapt2 = "$env:ANDROID_HOME\build-tools\35.0.0\aapt2.exe"

function Write-Section([string]$text) {
    Write-Host ""
    Write-Host ("=" * 78) -ForegroundColor DarkCyan
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ("=" * 78) -ForegroundColor DarkCyan
}
function Get-GitSha { return ((git -C $root rev-parse --short HEAD) 2>$null) }
function Get-AppVersion {
    $m = Select-String -Path "$root\app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"'
    if ($m) { return $m.Matches[0].Groups[1].Value }
    return 'unknown'
}
function Invoke-Gradle([string[]]$GradleArgs, [string]$LogName) {
    $log = "$root\out\logs\$LogName.log"
    New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
    Write-Host "> gradle $($GradleArgs -join ' ')" -ForegroundColor DarkGray
    # IMPORTANT: run through the shared wrapper (cross-process mutex) instead of bare gradle.bat —
    # otherwise a pipeline build can race with an agent build and corrupt Kotlin incremental caches
    # ("Storage[...source-to-classes.tab] is already registered").
    $gradleWrapper = "$root\_script\gradle.ps1"
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $gradleWrapper @GradleArgs 2>&1 |
        Tee-Object -FilePath $log | ForEach-Object { Write-Host $_ }
    $code = $LASTEXITCODE
    if ($code -ne 0) { throw "Gradle 失败: $($GradleArgs -join ' ')（exit=$code，日志: $log）" }
}
function Get-TestCounts([string]$resultsDir) {
    $total = 0; $failures = 0; $errors = 0; $skipped = 0
    if (Test-Path $resultsDir) {
        Get-ChildItem $resultsDir -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
            try {
                [xml]$x = Get-Content $_.FullName -Raw
                if ($x.testsuite) {
                    $total += [int]$x.testsuite.tests
                    $failures += [int]$x.testsuite.failures
                    $errors += [int]$x.testsuite.errors
                    $skipped += [int]$x.testsuite.skipped
                }
            } catch { Write-Warning "无法解析测试结果 $($_.FullName): $_" }
        }
    }
    return [pscustomobject]@{ Total = $total; Failures = $failures; Errors = $errors; Skipped = $skipped }
}
function Write-Sha256([string]$file) {
    return (Get-FileHash -Algorithm SHA256 -Path $file).Hash.ToLowerInvariant()
}

# ------------------------------------------------------------------ DEV
function Invoke-DevStage {
    Write-Section "DEV 环境：构建预交付文件（debug APK）"
    Invoke-Gradle @(':app:assembleDebug') 'dev-assembleDebug'
    $apk = "$root\app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) { throw "未找到 debug APK: $apk" }
    $outDir = "$root\out\dev"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    Copy-Item $apk "$outDir\app-debug.apk" -Force
    $info = [ordered]@{
        stage      = 'dev'
        artifact   = 'out/dev/app-debug.apk'
        sha256     = (Write-Sha256 "$outDir\app-debug.apk")
        version    = (Get-AppVersion)
        gitSha     = (Get-GitSha)
        builtAt    = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
        toolchain  = "JDK17 / Gradle 8.9 / AGP 8.7.3 / compileSdk 35 / minSdk 26"
    }
    $info | ConvertTo-Json | Set-Content -Encoding UTF8 "$outDir\build-info.json"
    Write-Host "[DEV OK] 预交付文件: $outDir\app-debug.apk ($([math]::Round((Get-Item "$outDir\app-debug.apk").Length / 1MB, 1)) MB)" -ForegroundColor Green
    Write-Host "[DEV OK] 构建信息: $outDir\build-info.json" -ForegroundColor Green
}

# ------------------------------------------------------------------ TEST
function Invoke-TestStage {
    Write-Section "TEST 环境：对预交付物执行全部测试与审计"
    $outDir = "$root\out\test"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $gates = New-Object System.Collections.ArrayList

    function Add-Gate([string]$name, [bool]$passed, [string]$detail) {
        [void]$gates.Add([pscustomobject]@{ Name = $name; Passed = $passed; Detail = $detail })
        $color = if ($passed) { 'Green' } else { 'Red' }
        Write-Host ("[{0}] {1} — {2}" -f $(if ($passed) { 'PASS' } else { 'FAIL' }), $name, $detail) -ForegroundColor $color
    }

    # 0) 预交付物必须存在（否则先跑 dev）
    $devApk = "$root\out\dev\app-debug.apk"
    Add-Gate '预交付物存在(dev)' (Test-Path $devApk) $(if (Test-Path $devApk) { $devApk } else { '请先运行 pipeline.ps1 dev' })
    if (-not (Test-Path $devApk)) { throw "缺少预交付物，测试环境中止" }

    # 0b) 预交付物与 build-info 一致性：哈希必须匹配、gitSha 必须等于当前 HEAD
    #     防止“测试/交付物与源码或产物脱节”（改完源码忘记重新 build → 直接判失败）。
    $infoPath = "$root\out\dev\build-info.json"
    $infoOk = $false
    $infoDetail = 'build-info.json 缺失'
    if (Test-Path $infoPath) {
        try {
            $info = Get-Content $infoPath -Raw | ConvertFrom-Json
            $actualSha = Write-Sha256 $devApk
            $headSha = (git -C $root rev-parse --short HEAD)
            $shaOk = ($info.sha256 -eq $actualSha)
            $gitOk = ($info.gitSha -eq $headSha)
            $infoOk = ($shaOk -and $gitOk)
            $infoDetail = "sha256Match=$shaOk gitShaMatch=$gitOk (artifact=$($info.gitSha) head=$headSha)"
        } catch { $infoDetail = "解析失败: $_" }
    }
    Add-Gate '预交付物 build-info 一致性' $infoOk $infoDetail

    # 1) :core 单元/集成测试
    $coreOk = $true; $coreDetail = ''
    try { Invoke-Gradle @(':core:test') 'test-core' } catch { $coreOk = $false; $coreDetail = "$_" }
    $coreCounts = Get-TestCounts "$root\core\build\test-results\test"
    $coreDetail = "tests=$($coreCounts.Total) failures=$($coreCounts.Failures) errors=$($coreCounts.Errors) skipped=$($coreCounts.Skipped)"
    Add-Gate 'core 测试' ($coreOk -and $coreCounts.Failures -eq 0 -and $coreCounts.Errors -eq 0 -and $coreCounts.Total -gt 0) $coreDetail

    # 2) :app JVM/Robolectric 测试
    $appOk = $true; $appDetail = ''
    try { Invoke-Gradle @(':app:testDebugUnitTest') 'test-app' } catch { $appOk = $false; $appDetail = "$_" }
    $appCounts = Get-TestCounts "$root\app\build\test-results\testDebugUnitTest"
    $appDetail = "tests=$($appCounts.Total) failures=$($appCounts.Failures) errors=$($appCounts.Errors) skipped=$($appCounts.Skipped)"
    Add-Gate 'app 测试(Robolectric/JVM)' ($appOk -and $appCounts.Failures -eq 0 -and $appCounts.Errors -eq 0 -and $appCounts.Total -gt 0) $appDetail

    # 3) 严格 MD3 / 动画静态审计
    $audit = "$root\_script\qa\audit_md3.ps1"
    $auditOk = $false; $auditDetail = 'missing script'
    if (Test-Path $audit) {
        $auditOut = & $audit 2>&1 | Out-String
        $auditOk = ($LASTEXITCODE -eq 0)
        $auditDetail = if ($auditOk) { '无违规' } else { ($auditOut -split "`n" | Select-Object -First 12) -join ' | ' }
        $auditOut | Set-Content -Encoding UTF8 "$outDir\md3-audit.log"
    }
    Add-Gate 'MD3/动画静态审计' $auditOk $auditDetail

    # 4) APK 结构校验
    $checkApk = "$root\_script\qa\check_apk.ps1"
    $apkOk = $false; $apkDetail = 'missing script'
    if (Test-Path $checkApk) {
        $apkOut = & $checkApk -Apk $devApk -ExpectDebuggable 2>&1 | Out-String
        $apkOk = ($LASTEXITCODE -eq 0)
        $apkDetail = if ($apkOk) { '结构/清单校验通过' } else { ($apkOut -split "`n" | Select-Object -First 12) -join ' | ' }
        $apkOut | Set-Content -Encoding UTF8 "$outDir\apk-check.log"
    }
    Add-Gate 'APK 结构校验' $apkOk $apkDetail

    # 5) Lint（信息性，门禁只要求不致命错误）
    $lintOk = $true; $lintDetail = 'skipped'
    try { Invoke-Gradle @(':app:lintDebug') 'test-lint' ; $lintDetail = '完成（报告 app/build/reports/lint-results-debug.html）' } catch { $lintOk = $false; $lintDetail = "$_" }
    Add-Gate 'Android Lint' $lintOk $lintDetail

    # 6) 需求/交付结构校验
    $checkReq = "$root\_script\qa\check_requirements.ps1"
    $reqOk = $false; $reqDetail = 'missing script'
    if (Test-Path $checkReq) {
        $reqOut = & $checkReq 2>&1 | Out-String
        $reqOk = ($LASTEXITCODE -eq 0)
        $reqDetail = if ($reqOk) { '项目结构/三环境/ABI 配置满足要求' } else { ($reqOut -split "`n" | Select-Object -First 12) -join ' | ' }
        $reqOut | Set-Content -Encoding UTF8 "$outDir\requirements-check.log"
    }
    Add-Gate '需求结构校验' $reqOk $reqDetail

    # 报告
    $allPassed = ($gates | Where-Object { -not $_.Passed }).Count -eq 0
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('# Dev-Sidebike 测试环境报告')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine("- 预交付物: ``out/dev/app-debug.apk`` (sha256: $((Write-Sha256 $devApk)))")
    [void]$sb.AppendLine("- 版本: $(Get-AppVersion) / git: $(Get-GitSha)")
    [void]$sb.AppendLine("- 执行时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
    [void]$sb.AppendLine("- 结论: **$(if ($allPassed) { 'PASS（可升为交付文件）' } else { 'FAIL（必须回开发环境修复根因后重测）' })**")
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('| 检查项 | 结果 | 详情 |')
    [void]$sb.AppendLine('| --- | --- | --- |')
    foreach ($g in $gates) { [void]$sb.AppendLine("| $($g.Name) | $(if ($g.Passed) { 'PASS' } else { 'FAIL' }) | $($g.Detail) |") }
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine('## 测试统计')
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine("| 套件 | tests | failures | errors | skipped |")
    [void]$sb.AppendLine('| --- | --- | --- | --- | --- |')
    [void]$sb.AppendLine("| core | $($coreCounts.Total) | $($coreCounts.Failures) | $($coreCounts.Errors) | $($coreCounts.Skipped) |")
    [void]$sb.AppendLine("| app | $($appCounts.Total) | $($appCounts.Failures) | $($appCounts.Errors) | $($appCounts.Skipped) |")
    $sb.ToString() | Set-Content -Encoding UTF8 "$outDir\test-report.md"
    [ordered]@{ passed = $allPassed; timestamp = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'); gitSha = (Get-GitSha); version = (Get-AppVersion); gates = $gates } |
        ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 "$outDir\status.json"

    if (-not $allPassed) { throw "测试环境未通过，禁止升为交付文件（详见 out/test/test-report.md）" }
    Write-Host "[TEST OK] 报告: $outDir\test-report.md" -ForegroundColor Green
}

# ------------------------------------------------------------------ PROD
function Invoke-ProdStage {
    Write-Section "PROD 环境：测试通过后升为交付文件"
    $statusFile = "$root\out\test\status.json"
    if (-not (Test-Path $statusFile)) { throw "测试环境未执行，禁止生成交付文件（先运行 pipeline.ps1 test）" }
    $status = Get-Content $statusFile -Raw | ConvertFrom-Json
    if (-not $status.passed) { throw "测试环境未通过，禁止生成交付文件（详见 out/test/test-report.md）" }

    Invoke-Gradle @(':app:assembleRelease') 'prod-assembleRelease'
    $releaseApk = "$root\app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $releaseApk)) { throw "未找到 release APK: $releaseApk" }
    $version = Get-AppVersion
    $distDir = "$root\dist"
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    $releaseName = "Dev-Sidebike-$version-release.apk"
    Copy-Item $releaseApk "$distDir\$releaseName" -Force
    Copy-Item "$root\out\dev\app-debug.apk" "$distDir\Dev-Sidebike-$version-debug.apk" -Force
    Copy-Item "$root\out\test\test-report.md" "$distDir\TEST-REPORT.md" -Force

    # 签名校验 + 交付清单
    $apkCheck = "$root\_script\qa\check_apk.ps1"
    if (Test-Path $apkCheck) {
        & $apkCheck -Apk "$distDir\$releaseName" | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "release APK 校验失败" }
    }
    $hashes = @()
    Get-ChildItem $distDir -Filter '*.apk' | ForEach-Object {
        $h = Write-Sha256 $_.FullName
        $hashes += ("{0}  {1}" -f $h, $_.Name)
    }
    $hashes | Set-Content -Encoding ASCII "$distDir\SHA256SUMS.txt"
    $manifest = [ordered]@{
        project    = 'Dev-Sidebike'
        version    = $version
        gitSha     = (Get-GitSha)
        builtAt    = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')
        artifacts  = @("Dev-Sidebike-$version-release.apk", "Dev-Sidebike-$version-debug.apk")
        sha256     = @{}
        testReport = 'TEST-REPORT.md'
        upstream   = 'https://github.com/docmirror/dev-sidecar (MPL-2.0)'
        installNote = '安装 release APK（自签名，允许未知来源）；首次启动按引导授予 VPN 权限。'
    }
    Get-ChildItem $distDir -Filter '*.apk' | ForEach-Object { $manifest.sha256[$_.Name] = (Write-Sha256 $_.FullName) }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 "$distDir\delivery-manifest.json"

    @"
# Dev-Sidebike $version — 交付说明

- `Dev-Sidebike-$version-release.apk`：release 签名交付文件（推荐安装）
- `Dev-Sidebike-$version-debug.apk`：debug 预交付文件（便于对比/排障）
- `SHA256SUMS.txt`：校验和
- `TEST-REPORT.md`：测试环境报告（PASS 后才会生成本目录）

## 安装
1. 手机允许"安装未知来源应用"；
2. 安装 release APK；
3. 首次打开按引导授予 VPN 权限（可选择"始终允许"便于开机自启）；
4. 需要 GitHub/Google 等加速时在首页点击启动；默认仅 DNS 防污染 + 系统 HTTP 代理，无需安装 CA；
5. 需要 HTTP 层改写（镜像/脚本/诱饵 SNI）时，在"证书引导"页安装 CA 后开启 MITM（注意：Android 7+ 多数第三方应用不信任用户 CA，仅浏览器等部分应用可受益）。

## 已知限制（详见 docs/feasibility）
- 不遵循系统 HTTP 代理的应用仅获得 DNS 级加速；
- 系统 Private DNS（DoT）开启时会绕过 VPN DNS，请在系统设置中关闭；
- 无 root 无法静默安装系统级 CA；
- 私有网络/企业环境可能与本工具规则冲突。
"@ | Set-Content -Encoding UTF8 "$distDir\README.md"
    Write-Host "[PROD OK] 交付目录: $distDir" -ForegroundColor Green
    Get-ChildItem $distDir | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } } | Format-Table -AutoSize | Out-Host
}

switch ($Stage) {
    'dev' { Invoke-DevStage }
    'test' { Invoke-TestStage }
    'prod' { Invoke-ProdStage }
    'all' { Invoke-DevStage; Invoke-TestStage; Invoke-ProdStage }
}
Write-Host ""
Write-Host "流水线阶段 '$Stage' 完成。" -ForegroundColor Cyan
