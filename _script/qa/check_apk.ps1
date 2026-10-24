# APK structure / manifest verification (QA gate). ASCII-only for PowerShell 5.1.
# Verifies: package id, min/target SDK, versionCode/Name, permissions, launchable activity,
# debuggable flag, native ABI story, VpnService declaration + FGS type + always-on meta-data,
# and user-CA trust in network_security_config when present.
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File _script/qa/check_apk.ps1
#   ... -Apk out/dev/app-debug.apk -ExpectDebuggable
#   ... -Apk out/prod/app-release.apk -ExpectNoNative -Json
# Exit: 0 pass, 1 problems found.
param(
    [string]$Apk = '',
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [switch]$ExpectDebuggable,
    [switch]$ExpectNoNative,
    [switch]$Json
)
$ErrorActionPreference = 'Stop'
$problems = New-Object System.Collections.ArrayList
$info = New-Object System.Collections.ArrayList

function Add-Problem([string]$msg) { [void]$problems.Add($msg) }
function Add-Info([string]$msg) { [void]$info.Add($msg) }
function Expect-Match([string]$text, [string]$pattern, [string]$desc) {
    if ($text -match $pattern) { Add-Info "OK: $desc"; return $true }
    Add-Problem "MISSING: $desc (pattern /$pattern/)"; return $false
}
function Expect-NoMatch([string]$text, [string]$pattern, [string]$desc) {
    if ($text -match $pattern) { Add-Problem "UNEXPECTED: $desc"; return $false }
    Add-Info "OK(absent): $desc"; return $true
}

# ---- locate aapt2 ----
$aapt2 = ''
$sdk = Join-Path $Root 'tools\android-sdk'
if ($env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
$bt = Join-Path $sdk 'build-tools'
if (Test-Path $bt) {
    $cand = Get-ChildItem $bt -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($cand) { $aapt2 = $cand }
}
if (-not $aapt2) { Add-Problem "aapt2.exe not found under $bt"; }

# ---- locate APK ----
if (-not $Apk) {
    $candidates = @(
        (Join-Path $Root 'out\dev\app-debug.apk'),
        (Join-Path $Root 'out\prod\app-release.apk'),
        (Join-Path $Root 'dist\app-release.apk')
    ) | Where-Object { Test-Path $_ }
    $built = @()
    foreach ($d in @('debug', 'release')) {
        $p = Join-Path $Root "app\build\outputs\apk\$d"
        if (Test-Path $p) { $built += (Get-ChildItem $p -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1) }
    }
    $all = @()
    foreach ($c in $candidates) { $all += (Get-Item $c) }
    foreach ($b in $built) { if ($b) { $all += $b } }
    if ($all.Count -eq 0) { Write-Host "[check_apk] FAIL: no APK found (build first)" -ForegroundColor Red; exit 1 }
    $Apk = ($all | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not (Test-Path $Apk)) { Write-Host "[check_apk] FAIL: APK missing: $Apk" -ForegroundColor Red; exit 1 }
if (-not $aapt2) { $problems | ForEach-Object { Write-Host "  - $_" }; exit 1 }

$badging = (& $aapt2 dump badging $Apk 2>&1 | Out-String)
$manifestXml = $null
$nscXml = $null
try { $manifestXml = (& $aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1 | Out-String) } catch { }
try { $nscXml = (& $aapt2 dump xmltree --file res/xml/network_security_config.xml $Apk 2>&1 | Out-String) } catch { }

if ($badging.Trim().Length -eq 0) { Add-Problem "aapt2 badging returned empty for $Apk" }

# ---- package/version/sdk ----
$pkgLine = (($badging -split "`n") | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
if ($pkgLine) { Add-Info "package: $($pkgLine.Trim())" } else { Add-Problem "no package line in badging" }
$null = Expect-Match $badging "package: name='dev\.sidebike\.app" "package id dev.sidebike.app"
if ($badging -notmatch "versionCode='\d+'") { Add-Problem "versionCode missing" } else { Add-Info "versionCode present" }
if ($badging -notmatch "versionName='[^']+'") { Add-Problem "versionName missing" } else { Add-Info "versionName present" }
$null = Expect-Match $badging "sdkVersion:'26'" "minSdkVersion=26"
$null = Expect-Match $badging "targetSdkVersion:'(3[4-9])'" "targetSdkVersion >= 34 (FGS types enforced)"

# ---- permissions ----
foreach ($p in @('android\.permission\.INTERNET', 'android\.permission\.FOREGROUND_SERVICE', 'android\.permission\.POST_NOTIFICATIONS', 'android\.permission\.RECEIVE_BOOT_COMPLETED')) {
    $null = Expect-Match $badging $p "permission $p"
}
$null = Expect-NoMatch $badging "WRITE_SECURE_SETTINGS" "WRITE_SECURE_SETTINGS permission (not allowed in beta)"

# ---- activity / label / icon ----
$null = Expect-Match $badging "launchable-activity: name='dev\.sidebike\.app\.MainActivity'" "launchable MainActivity"
if ($badging -match "application-label:'([^']*)'") { Add-Info "label=$($Matches[1])" } else { Add-Problem "application label missing" }
if ($badging -match "application-icon-\d+:'[^']+'" -or $badging -match "application-icon:'[^']+'") { Add-Info "icon present" } else { Add-Problem "application icon missing" }

# ---- debuggable ----
$isDebuggable = ($badging -match 'application-debuggable')
if ($ExpectDebuggable -and -not $isDebuggable) { Add-Problem "expected debuggable=true but APK is not debuggable" }
if (-not $ExpectDebuggable -and $isDebuggable) { Add-Problem "expected debuggable=false but APK is debuggable" }
Add-Info "debuggable=$isDebuggable (expected=$($ExpectDebuggable.IsPresent))"

# ---- ABI story ----
$native = [regex]::Match($badging, "native-code:\s*'([^']*)'")
$hasNative = $native.Success -and $native.Groups[1].Value.Trim().Length -gt 0
if ($ExpectNoNative -and $hasNative) {
    Add-Problem "expected pure-JVM APK (no .so) but native-code=$($native.Groups[1].Value)"
} else {
    if ($hasNative) { Add-Info "native-code=$($native.Groups[1].Value)" } else { Add-Info "no native libs (ABI-neutral, all ABIs supported)" }
}

# ---- manifest structure (VpnService / FGS / always-on) ----
if ($manifestXml -and $manifestXml.Trim().Length -gt 0) {
    $null = Expect-Match $manifestXml 'BIND_VPN_SERVICE' "manifest: BIND_VPN_SERVICE permission"
    $null = Expect-Match $manifestXml 'android\.net\.VpnService' "manifest: VpnService intent action"
    $null = Expect-Match $manifestXml 'foregroundServiceType' "manifest: foregroundServiceType present"
    $null = Expect-Match $manifestXml 'SUPPORTS_ALWAYS_ON' "manifest: always-on VPN meta-data"
    $null = Expect-Match $manifestXml 'network_security_config' "manifest: networkSecurityConfig attribute"
} else {
    Add-Problem "manifest xmltree dump failed (cannot verify VpnService/FGS declarations)"
}

# ---- network security config: user CA trusted ----
if ($nscXml -and $nscXml.Trim().Length -gt 0) {
    if ($nscXml -match 'user') { Add-Info "OK: network_security_config trusts user CA" }
    else { Add-Problem "network_security_config does not trust user CA (CA install self-test will fail)" }
} else {
    Add-Problem "network_security_config.xml not found in APK (CA install self-test will fail)"
}

# ---- summary ----
$result = [pscustomobject]@{
    apk = $Apk; sizeBytes = (Get-Item $Apk).Length; aapt2 = $aapt2
    problems = $problems.Count; details = $problems; info = $info
}
if ($Json) { $result | ConvertTo-Json -Depth 6 }
if (-not $Json) {
    Write-Host ("[check_apk] {0} ({1} MB)" -f $Apk, [math]::Round((Get-Item $Apk).Length / 1MB, 2))
    foreach ($i in $info) { Write-Host "  . $i" }
    if ($problems.Count -gt 0) {
        Write-Host ("[check_apk] FAIL: {0} problem(s)" -f $problems.Count) -ForegroundColor Red
        foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
        exit 1
    }
    Write-Host "[check_apk] PASS" -ForegroundColor Green
}
exit $(if ($problems.Count -gt 0) { 1 } else { 0 })
