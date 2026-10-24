# APK 结构与清单校验（测试/生产环境门禁）
# 用法: pwsh -NoProfile -File check_apk.ps1 -Apk <path> -ExpectDebuggable $true|$false
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [bool]$ExpectDebuggable = $false,
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike'
)
$ErrorActionPreference = 'Stop'
$aapt2 = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe'
if (-not (Test-Path $aapt2)) {
    $aapt2 = 'E:\WorkSpace\Dev-Sidebike\tools\android-sdk\build-tools\35.0.0\aapt2.exe'
}
if (-not (Test-Path $Apk)) { Write-Host "[check_apk] FAIL: APK 不存在 $Apk" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $aapt2)) { Write-Host "[check_apk] FAIL: aapt2 不存在 $aapt2" -ForegroundColor Red; exit 1 }

$badging = & $aapt2 dump badging $Apk 2>&1 | Out-String
$problems = New-Object System.Collections.ArrayList
function Expect([string]$pattern, [string]$desc, [bool]$must = $true) {
    $hit = $badging -match $pattern
    if ($must -and -not $hit) { [void]$problems.Add("缺少/不匹配: $desc") }
    if (-not $must -and $hit) { [void]$problems.Add("不应存在: $desc") }
    return $hit
}

$null = Expect "package: name='dev\.sidebike\.app" "包名 dev.sidebike.app"
$null = Expect "sdkVersion:'26'" "minSdkVersion=26"
$null = Expect "targetSdkVersion:'35'" "targetSdkVersion=35"
$null = Expect "uses-permission: name='android\.permission\.INTERNET'" "INTERNET 权限"
$null = Expect "uses-permission: name='android\.permission\.FOREGROUND_SERVICE'" "FOREGROUND_SERVICE 权限"
$null = Expect "uses-permission: name='android\.permission\.POST_NOTIFICATIONS'" "POST_NOTIFICATIONS 权限"
$null = Expect "launchable-activity: name='dev\.sidebike\.app\.MainActivity'" "MainActivity 为启动入口"
$null = Expect "application-debuggable" "debuggable 标记（期望值: $ExpectDebuggable）" (-not $ExpectDebuggable -eq $false)

$isDebuggable = ($badging -match 'application-debuggable')
if ($isDebuggable -ne $ExpectDebuggable) {
    [void]$problems.Add("debuggable=$isDebuggable，期望 $ExpectDebuggable")
}

# 版本信息
$versionLine = ($badging -split "`n" | Where-Object { $_ -match "^package:" }) -join ''
Write-Host "[check_apk] $versionLine"
Write-Host ("[check_apk] APK 大小: {0} MB" -f [math]::Round((Get-Item $Apk).Length / 1MB, 2))

if ($problems.Count -gt 0) {
    Write-Host "[check_apk] FAIL:" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "[check_apk] PASS"
exit 0
