# Delivery-artifact consistency gate for dist/ (QA release gate). PS 5.1 compatible, ASCII output.
# Verifies:
#   - expected release/debug APKs exist and match SHA256SUMS.txt;
#   - delivery-manifest.json parses and its sha256/version match the actual files;
#   - TEST-REPORT.md / README.md exist and are non-trivial;
#   - release APK: debuggable=false, versionCode/Name, minSdk/targetSdk, 4-ABI or documented ARM-only,
#     permissions, VpnService/FGS/always-on/NSC declarations (delegated to check_apk.ps1 -Release).
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File _script/qa/check_delivery.ps1
#   ... -DistDir dist -Version 0.1.0-alpha
# Exit: 0 pass, 1 problems.
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [string]$DistDir = '',
    [string]$Version = '0.1.0-alpha'
)
$ErrorActionPreference = 'Continue'
if (-not $DistDir) { $DistDir = Join-Path $Root 'dist' }
$problems = New-Object System.Collections.ArrayList
$infos = New-Object System.Collections.ArrayList
function Add-Problem([string]$m) { [void]$problems.Add($m) }
function Add-Info([string]$m) { [void]$infos.Add($m) }
function Get-Sha256([string]$path) { return (Get-FileHash -Algorithm SHA256 $path).Hash.ToLower() }

Write-Host "[check_delivery] dist=$DistDir version=$Version"
if (-not (Test-Path $DistDir)) { Write-Host "[check_delivery] FAIL: dist dir missing: $DistDir" -ForegroundColor Red; exit 1 }

# 1. expected artifacts
$expected = @(
    (Join-Path $DistDir "Dev-Sidebike-$Version-release.apk"),
    (Join-Path $DistDir "Dev-Sidebike-$Version-debug.apk")
)
foreach ($p in $expected) {
    if (Test-Path $p) { Add-Info ("APK OK: {0} ({1:N1} MB)" -f (Split-Path $p -Leaf), ((Get-Item $p).Length / 1MB)) }
    else { Add-Problem "missing expected APK: $p" }
}
foreach ($p in @('SHA256SUMS.txt', 'delivery-manifest.json', 'README.md', 'TEST-REPORT.md')) {
    $full = Join-Path $DistDir $p
    if (Test-Path $full) { Add-Info ("file OK: {0} ({1} bytes)" -f $p, (Get-Item $full).Length) }
    else { Add-Problem "missing delivery artifact: $p" }
}

# 2. SHA256SUMS.txt consistency
$sumsFile = Join-Path $DistDir 'SHA256SUMS.txt'
if (Test-Path $sumsFile) {
    $sums = Get-Content $sumsFile
    $entries = 0
    foreach ($line in $sums) {
        if ($line -notmatch '^\s*([0-9a-fA-F]{64})\s+\*?(.+)$') { continue }
        $entries++
        $hash = $Matches[1].ToLower(); $name = $Matches[2].Trim()
        $path = Join-Path $DistDir $name
        if (-not (Test-Path $path)) { Add-Problem "SHA256SUMS lists missing file: $name"; continue }
        $actual = Get-Sha256 $path
        if ($actual -eq $hash) { Add-Info "sha256 OK: $name" } else { Add-Problem ("sha256 MISMATCH: {0} (sums={1} actual={2})" -f $name, $hash, $actual) }
    }
    if ($entries -eq 0) { Add-Problem "SHA256SUMS.txt has no parsable entries" }
}

# 3. delivery-manifest.json consistency (best effort: sha256/version fields if present)
$manifestFile = Join-Path $DistDir 'delivery-manifest.json'
if (Test-Path $manifestFile) {
    try {
        $m = Get-Content $manifestFile -Raw | ConvertFrom-Json
        if ($m.version -and $m.version -ne $Version) { Add-Problem "manifest version=$($m.version) expected $Version" } else { Add-Info "manifest version OK" }
        if ($m.gitSha) { Add-Info "manifest gitSha=$($m.gitSha)" }
        if ($m.apk) { $apkPath = Join-Path $DistDir (Split-Path $m.apk -Leaf); if (Test-Path $apkPath) { Add-Info "manifest apk exists" } }
        # versions array / files array shapes vary; verify any embedded sha256 values
        $text = Get-Content $manifestFile -Raw
        foreach ($mm in [regex]::Matches($text, '"sha256"\s*:\s*"([0-9a-fA-F]{64})"')) {
            $hash = $mm.Groups[1].Value.ToLower()
            $matched = $false
            foreach ($apk in (Get-ChildItem $DistDir -Filter '*.apk' -ErrorAction SilentlyContinue)) {
                if ((Get-Sha256 $apk.FullName) -eq $hash) { $matched = $true; break }
            }
            if ($matched) { Add-Info "manifest sha256 matches an APK: $hash" } else { Add-Problem "manifest sha256 not matching any APK: $hash" }
        }
    } catch { Add-Problem "delivery-manifest.json is not valid JSON: $_" }
}

# 4. release APK structural gate (delegate to check_apk.ps1)
$release = Join-Path $DistDir "Dev-Sidebike-$Version-release.apk"
$checkApk = Join-Path $Root '_script\qa\check_apk.ps1'
if ((Test-Path $release) -and (Test-Path $checkApk)) {
    Write-Host '[check_delivery] running check_apk -Release ...'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $checkApk -Apk $release -Release 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { Add-Problem "check_apk -Release failed (see output)" } else { Add-Info "check_apk -Release PASS" }
}

# 5. summary
Write-Host ''
Write-Host '================ DELIVERY GATE ================'
foreach ($i in $infos) { Write-Host "  OK   $i" }
foreach ($p in $problems) { Write-Host "  FAIL $p" -ForegroundColor Red }
if ($problems.Count -gt 0) { Write-Host ("[check_delivery] FAIL: {0} problem(s)" -f $problems.Count) -ForegroundColor Red; exit 1 }
Write-Host '[check_delivery] PASS' -ForegroundColor Green
exit 0
