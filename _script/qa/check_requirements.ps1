# Dev-Sidebike 仓库结构/需求一致性审计（QA 门禁）
# 校验用户明确要求的工作区结构、三环境流水线、文档、ABI 策略、参考仓库、QA 门禁脚本。
# 用法:
#   powershell -NoProfile -File _script/qa/check_requirements.ps1
#   powershell -NoProfile -File _script/qa/check_requirements.ps1 -Root E:\WorkSpace\Dev-Sidebike -AllowWip
# 退出码: 0 通过; 1 有违规（-AllowWip 时仅 WIP 项降级为警告，硬性项仍然失败）
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [switch]$AllowWip,
    [switch]$Json
)
$ErrorActionPreference = 'Stop'
$problems = New-Object System.Collections.ArrayList
$warnings = New-Object System.Collections.ArrayList
$checks = New-Object System.Collections.ArrayList

function Check([string]$id, [string]$desc, [bool]$ok, [string]$detail = '') {
    [void]$checks.Add([pscustomobject]@{ Id = $id; Desc = $desc; Ok = $ok; Detail = $detail })
    return $ok
}
function Fail([string]$id, [string]$desc, [string]$detail = '') {
    [void]$checks.Add([pscustomobject]@{ Id = $id; Desc = $desc; Ok = $false; Detail = $detail })
    [void]$problems.Add("[$id] $desc" + $(if ($detail) { " — $detail" } else { '' }))
    return $false
}
function Wip([string]$id, [string]$desc, [string]$detail = '') {
    if ($AllowWip) {
        [void]$checks.Add([pscustomobject]@{ Id = $id; Desc = "$desc [WIP-ALLOWED]"; Ok = $true; Detail = $detail })
        [void]$warnings.Add("[$id] $desc" + $(if ($detail) { " — $detail" } else { '' }))
    } else {
        [void]$problems.Add("[$id] $desc" + $(if ($detail) { " — $detail" } else { '' }))
        [void]$checks.Add([pscustomobject]@{ Id = $id; Desc = $desc; Ok = $false; Detail = $detail })
    }
    return $false
}
function Exists([string]$rel, [string]$id, [string]$desc, [bool]$wip = $false) {
    $p = Join-Path $Root $rel
    if (Test-Path $p) { return (Check $id $desc $true $rel) }
    if ($wip) { return (Wip $id $desc "缺少 $rel") }
    return (Fail $id $desc "缺少 $rel")
}
function Has-Text([string]$rel, [string]$pattern, [string]$id, [string]$desc, [bool]$wip = $false) {
    $p = Join-Path $Root $rel
    if (-not (Test-Path $p)) { if ($wip) { return (Wip $id $desc "缺少 $rel") } else { return (Fail $id $desc "缺少 $rel") } }
    $hit = (Get-Content $p -Raw) -match $pattern
    if ($hit) { return (Check $id $desc $true $rel) }
    if ($wip) { return (Wip $id $desc "未匹配 /$pattern/") }
    return (Fail $id $desc "未匹配 /$pattern/")
}
function Has-Line([string]$rel, [string]$pattern, [string]$id, [string]$desc) {
    $p = Join-Path $Root $rel
    if (-not (Test-Path $p)) { return (Fail $id $desc "缺少 $rel") }
    $lines = Get-Content $p
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $pattern) { return (Check $id $desc $true ("{0}:{1}" -f $rel, ($i + 1))) }
    }
    return (Fail $id $desc "未匹配 /$pattern/" )
}

# ---------- 1. 基础结构 ----------
$null = Exists 'settings.gradle.kts' 'R1' 'Gradle settings 存在'
$null = Exists 'build.gradle.kts' 'R2' 'Gradle 根构建脚本存在'
$null = Exists 'gradle.properties' 'R3' 'gradle.properties 存在'
$null = Exists 'app' 'R4' 'app 模块存在'
$null = Exists 'core' 'R5' ':core 模块存在'
$null = Exists 'reference\dev-sidecar\README.md' 'R6' '参考仓库 dev-sidecar 克隆存在'
$null = Exists 'tools\android-sdk\platforms' 'R7' '离线 Android SDK 存在'
$null = Exists 'docs\dev-workflow.md' 'R8' '开发工作流文档存在'

# ---------- 2. 三环境流水线（dev -> test -> prod） ----------
$null = Exists '_script\env.ps1' 'P1' '共享环境脚本 env.ps1 存在'
$null = Exists '_script\gradle.ps1' 'P2' 'Gradle 包装脚本存在'
if (-not (Test-Path (Join-Path $Root '_script\pipeline.ps1'))) { $null = Wip 'P3' '唯一入口 _script/pipeline.ps1 存在' }
else {
    $null = Has-Line '_script\pipeline.ps1' "'dev'|dev" 'P3a' 'pipeline 支持 dev 阶段'
    $null = Has-Line '_script\pipeline.ps1' "'test'|test" 'P3b' 'pipeline 支持 test 阶段'
    $null = Has-Line '_script\pipeline.ps1' "'prod'|prod" 'P3c' 'pipeline 支持 prod 阶段'
}
if (-not (Test-Path (Join-Path $Root '_script\test.ps1'))) { $null = Wip 'P4' '测试环境脚本 _script/test.ps1 存在（单元/集成/静态/lint）' } else { $null = Check 'P4' '测试环境脚本存在' $true }
if (-not (Test-Path (Join-Path $Root '_script\release.ps1'))) { $null = Wip 'P5' '生产环境脚本 _script/release.ps1 存在（release APK+manifest+sha256）' } else { $null = Check 'P5' '生产环境脚本存在' $true }

# ---------- 3. 文档 ----------
$null = Exists 'docs\architecture\01-architecture.md' 'D1' '架构总览文档存在'
$null = Exists 'docs\architecture\02-core-contracts.md' 'D2' 'core 契约文档存在'
$null = Exists 'docs\feasibility\01-dev-sidecar-analysis.md' 'D3' '上游分析文档存在'
$null = Exists 'docs\feasibility\02-android-constraints.md' 'D4' 'Android 平台约束文档存在'
$null = Exists 'docs\feasibility\03-review-checklist.md' 'D5' '对抗性评审清单存在'
$null = Exists 'docs\qa\04-test-plan.md' 'D6' 'QA 测试计划存在' $true
$null = Exists 'docs\qa\05-test-report.md' 'D7' 'QA 测试报告存在' $true
$null = Exists 'docs\design\03-motion-spec.md' 'D8' 'MD3 动效规格文档存在' $true

# ---------- 4. ABI / APK 策略 ----------
$null = Has-Text 'app\build.gradle.kts' 'abiFilters\s*\+=\s*listOf\("arm64-v8a",\s*"armeabi-v7a"\)' 'A1' 'app/build.gradle.kts 声明 ABI 过滤 arm64-v8a+armeabi-v7a'
$null = Has-Text 'docs\architecture\01-architecture.md' 'x86' 'A2' '架构文档说明 x86/x86_64 可扩展性'
$null = Has-Text 'app\build.gradle.kts' 'minSdk\s*=\s*26' 'A3' 'minSdk=26'
$null = Has-Text 'app\build.gradle.kts' 'targetSdk\s*=\s*35' 'A4' 'targetSdk=35'

# ---------- 5. QA 门禁工具 ----------
$null = Exists '_script\qa\audit_md3.ps1' 'Q1' 'MD3 静态审计脚本存在'
$null = Exists '_script\qa\check_apk.ps1' 'Q2' 'APK 校验脚本存在'
$null = Exists '_script\qa\check_requirements.ps1' 'Q3' '仓库需求审计脚本存在'
$null = Exists '_script\qa\device_verify.md' 'Q4' '真机验证清单存在' $true
$null = Exists '_script\qa\setup_emulator.ps1' 'Q5' '离线模拟器安装脚本存在' $true
$null = Exists '_script\qa\run_qa_tests.ps1' 'Q6' 'QA 总门禁脚本存在' $true

# ---------- 5b. QA 截图验收集（真 PNG） ----------
$shotDir = Join-Path $Root 'docs\qa\screenshots'
if (Test-Path $shotDir) {
    $pngs = @(Get-ChildItem $shotDir -Filter '*.png' -ErrorAction SilentlyContinue)
    if ($pngs.Count -ge 8) { $null = Check 'Q7' "QA 截图 >= 8 张（实际 $($pngs.Count)）" $true }
    else { $null = Wip 'Q7' "QA 截图 >= 8 张（实际 $($pngs.Count)）" }
    $badPng = 0
    foreach ($png in $pngs) {
        $bytes = [System.IO.File]::ReadAllBytes($png.FullName)
        if ($bytes.Length -lt 8 -or $bytes[0] -ne 0x89 -or $bytes[1] -ne 0x50 -or $bytes[2] -ne 0x4E -or $bytes[3] -ne 0x47) { $badPng++ }
    }
    if ($pngs.Count -gt 0 -and $badPng -eq 0) { $null = Check 'Q8' "所有 QA 截图均为合法 PNG（$($pngs.Count)）" $true }
    elseif ($pngs.Count -gt 0) { $null = Fail 'Q8' '存在非法 PNG 截图' "$badPng 个文件损坏" }
}

# ---------- 6. Git 基线（只读检查，不提交） ----------
$gitDir = Join-Path $Root '.git'
if (Test-Path $gitDir) {
    $null = Check 'G1' 'git 仓库存在' $true
    try {
        $branch = (& git -C $Root rev-parse --abbrev-ref HEAD 2>$null | Select-Object -First 1)
        if ($branch) { $null = Check 'G2' "git 当前分支=$branch" $true }
        $tags = (& git -C $Root tag 2>$null)
        if ($tags) { $null = Check 'G3' "git tag 数量=$(@($tags).Count)" $true } else { $null = Wip 'G3' 'git tag 存在（交付版本基线）' }
        $dirty = (& git -C $Root status --porcelain 2>$null)
        if ($dirty) { [void]$warnings.Add("[G4] 工作区有未提交改动（Lead 负责提交）: $(@($dirty).Count) 个文件") ; $null = Check 'G4' 'git 工作区状态已记录' $true }
        else { $null = Check 'G4' 'git 工作区干净' $true }
    } catch { $null = Wip 'G1b' 'git 只读检查可用' "$_" }
} else { $null = Fail 'G1' 'git 仓库存在' '缺少 .git' }

# ---------- 7. 用户需求硬性点（可追溯） ----------
$null = Has-Text 'docs\architecture\01-architecture.md' 'MITM|mitm' 'U1' '架构含 MITM 设计'
$null = Has-Text 'docs\architecture\01-architecture.md' 'DNS|dns' 'U2' '架构含 DNS 防污染设计'
$null = Has-Text 'docs\architecture\01-architecture.md' 'always-on|always on' 'U3' '架构含 always-on VPN 设计'
$null = Has-Text 'app\src\main\AndroidManifest.xml' 'BIND_VPN_SERVICE' 'U4' 'VpnService 声明 BIND_VPN_SERVICE 权限'
$null = Has-Text 'app\src\main\AndroidManifest.xml' 'android\.net\.VpnService' 'U5' 'VpnService intent-filter 声明'
$null = Has-Text 'app\src\main\AndroidManifest.xml' 'foregroundServiceType' 'U6' '前台服务声明 foregroundServiceType（Android 14+ 必须）'
$null = Has-Text 'app\src\main\res\xml\network_security_config.xml' 'certificates\s+src="user"' 'U7' 'NSC 信任用户 CA（CA 安装自检必需）'

# ---------- 输出 ----------
$passed = @($checks | Where-Object { $_.Ok }).Count
$total = $checks.Count
if ($Json) {
    $result = [pscustomobject]@{
        root = $Root; total = $total; passed = $passed; failed = $problems.Count
        problems = $problems; warnings = $warnings; checks = $checks
    }
    $result | ConvertTo-Json -Depth 6
} else {
    Write-Host ("[check_requirements] {0}/{1} 项通过" -f $passed, $total)
    foreach ($w in $warnings) { Write-Host "  WARN $w" -ForegroundColor Yellow }
    if ($problems.Count -gt 0) {
        Write-Host ("[check_requirements] FAIL: {0} 项违规" -f $problems.Count) -ForegroundColor Red
        foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
        exit 1
    }
    Write-Host "[check_requirements] PASS" -ForegroundColor Green
}
exit $(if ($problems.Count -gt 0) { 1 } else { 0 })
