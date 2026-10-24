# Automated emulator smoke/acceptance run for Dev-Sidebike (Windows PowerShell 5.1, ASCII only).
# Covers: install -> launch -> VPN consent -> RUNNING -> E1 (system proxy registered/connection
# counts) -> E2 (DNS resolution via ping) -> stop -> recovery, and saves evidence screenshots/logs.
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File _script/qa/emulator_smoke.ps1
#   ... -Apk out/dev/app-debug.apk -Package dev.sidebike.app.debug -EvidenceDir docs/qa/evidence
# Exit: 0 all checks pass; 1 a check failed (evidence still saved).
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [string]$Apk = '',
    [string]$Package = 'dev.sidebike.app.debug',
    [string]$Activity = 'dev.sidebike.app.debug/dev.sidebike.app.MainActivity',
    [string]$EvidenceDir = '',
    [switch]$SkipInstall,
    [int]$BootTimeoutSec = 300
)
$ErrorActionPreference = 'Continue'
$sdk = Join-Path $Root 'tools\android-sdk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $Root 'docs\qa\evidence' }
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
if (-not $Apk) {
    $cand = Join-Path $Root 'out\dev\app-debug.apk'
    if (Test-Path $cand) { $Apk = $cand }
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $EvidenceDir "smoke-$stamp.log"
$results = New-Object System.Collections.ArrayList
$shotIndex = 0

function Log([string]$msg) { Write-Host $msg; Add-Content -Path $logFile -Value $msg }
function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    [void]$results.Add([pscustomobject]@{ Check = $name; Ok = $ok; Detail = $detail })
    $tag = if ($ok) { 'PASS' } else { 'FAIL' }
    Log ("[{0}] {1}: {2}" -f $tag, $name, $detail)
}
function Sh([string]$name) {
    $script:shotIndex++
    $file = Join-Path $EvidenceDir ("{0}-{1:D2}-{2}.png" -f $stamp, $script:shotIndex, $name)
    & cmd.exe /c "`"$adb`" exec-out screencap -p > `"$file`"" | Out-Null
    if (Test-Path $file) {
        $b = [System.IO.File]::ReadAllBytes($file)
        $ok = ($b.Length -gt 5000 -and $b[0] -eq 0x89 -and $b[1] -eq 0x50)
        Log ("[shot] {0} ({1} bytes, png={2})" -f $file, $b.Length, $ok)
        return $file
    }
    return ''
}
function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$CmdArgs)
    # Accept both "Adb shell getprop x" (3 args) and "Adb @('shell getprop x')" (1 string arg):
    # flatten every element on spaces so adb always receives proper argv entries.
    $flat = @()
    foreach ($part in $CmdArgs) { $flat += ($part -split '\s+' | Where-Object { $_ -ne '' }) }
    return (& $adb $flat 2>&1 | Out-String)
}
function Wait-Boot {
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    & $adb start-server 2>&1 | Out-Null
    while ((Get-Date) -lt $deadline) {
        $boot = (Adb @('shell', 'getprop', 'sys.boot_completed')).Trim()
        $pm = (Adb @('shell', 'pm', 'path', 'android')).Trim()
        if ($boot -eq '1' -and $pm -match 'package:') { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}
function Find-Center([string]$text) {
    & $adb shell uiautomator dump /sdcard/qa_ui.xml 2>&1 | Out-Null
    $ui = (Adb @('shell', 'cat', '/sdcard/qa_ui.xml'))
    if ($ui.Length -lt 100) { return $null }
    $m = [regex]::Match($ui, ('(?:text|content-desc)="' + [regex]::Escape($text) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'))
    if (-not $m.Success) { return $null }
    $cx = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $cy = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    return @([int]$cx, [int]$cy)
}
function Tap-Text([string]$text, [int]$tries = 5) {
    for ($i = 1; $i -le $tries; $i++) {
        $c = Find-Center $text
        if ($c) { & $adb shell input tap $c[0] $c[1] 2>&1 | Out-Null; Log "[tap] '$text' at $($c[0]),$($c[1])"; return $true }
        Start-Sleep -Seconds 2
    }
    Log "[tap] NOT FOUND: '$text'"
    return $false
}
function Resolves([string]$hostName) {
    $out = Adb @('shell', ('ping -c 1 -W 3 ' + $hostName))
    if ($out -match 'PING\s+([^\s]+)\s+\(([0-9a-fA-F\.:]+)\)') { return $Matches[2] }
    return $null
}

# ---------------- run ----------------
Log ("=== Dev-Sidebike emulator smoke {0} ===" -f $stamp)
Log ("apk={0} package={1}" -f $Apk, $Package)
Add-Result 'emulator-online' (Wait-Boot) 'sys.boot_completed=1'

if (-not $SkipInstall -and $Apk -and (Test-Path $Apk)) {
    $inst = (Adb @('install', '-r', '-t', $Apk))
    Add-Result 'apk-install' ($inst -match 'Success') $inst.Trim()
} else {
    Add-Result 'apk-install' ($SkipInstall -or -not $Apk) "(skipped; apk='$Apk')"
}

# API 33+: the app requests POST_NOTIFICATIONS on first launch. Grant it from adb (shell holds
# GRANT_RUNTIME_PERMISSIONS via pm) so the system permission dialog never blocks UI automation.
$null = (Adb @('shell', 'pm', 'grant', $Package, 'android.permission.POST_NOTIFICATIONS'))
Log ('[perm] POST_NOTIFICATIONS grant attempted')
$null = (Adb @('shell', 'am', 'force-stop', $Package))
Start-Sleep -Seconds 1

$start = (Adb @('shell', 'am', 'start', '-W', '-n', $Activity))
Add-Result 'app-launch' ($start -match 'Activity' -or $start -match 'Warning') $start.Trim()
Start-Sleep -Seconds 4
$null = Sh 'launch'

if (Tap-Text '启动服务') {
    Start-Sleep -Seconds 2
    $null = Sh 'start-tapped'
    # VPN consent dialog (system UI) may appear.
    $focused = (Adb @('shell', 'dumpsys', 'activity', 'activities'))
    if ($focused -match 'vpndialogs') {
        if (Tap-Text 'OK') { Log '[vpn] consent accepted' } else { Log '[vpn] consent dialog not confirmed' }
    }
    # wait for RUNNING
    $deadline = (Get-Date).AddSeconds(40); $vpnUp = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $c = (Adb @('shell', 'dumpsys', 'connectivity'))
        if ($c -match 'VPN CONNECTED' -and $c -match 'InterfaceName: tun0') { $vpnUp = $true; break }
    }
    Add-Result 'vpn-established' $vpnUp 'dumpsys connectivity VPN CONNECTED + tun0'
    $conn = (Adb @('shell', 'dumpsys', 'connectivity'))
    $hasProxy = ($conn -match 'HttpProxy: \[127\.0\.0\.1\]')
    Add-Result 'E1-system-proxy-registered' $hasProxy 'VPN LinkProperties HttpProxy 127.0.0.1'
    $hasDns = ($conn -match 'DnsAddresses: \[ /10\.111\.0\.2 \]')
    Add-Result 'E2-vpn-dns-configured' $hasDns 'VPN DnsAddresses 10.111.0.2'
    Start-Sleep -Seconds 5
    $null = Sh 'running'
    $dnsIps = @()
    $dnsOk = $true
    foreach ($h in @('example.com', 'github.com')) {
        $ip = Resolves $h
        $dnsIps += ("$h=$ip")
        if (-not $ip) { $dnsOk = $false }
    }
    Add-Result 'E2-dns-resolution' $dnsOk ($dnsIps -join '; ')
    Log ('[dns] ' + ($dnsIps -join ' | '))
    $null = Sh 'after-dns'
    # stop
    if (Tap-Text '停止服务') {
        # Teardown is asynchronous: poll up to 20s for tun0 to disappear instead of a fixed sleep.
        $tunGone = $false
        $deadline = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 2
            $after = (Adb @('shell', 'dumpsys', 'connectivity'))
            if (-not ($after -match 'InterfaceName: tun0')) { $tunGone = $true; break }
        }
        Add-Result 'SMK-03-stop-recovers' $tunGone 'tun0 removed after stop'
        $ip = Resolves 'example.com'
        Add-Result 'SMK-03-dns-after-stop' ($null -ne $ip) "example.com=$ip"
        $null = Sh 'stopped'
    } else {
        Add-Result 'SMK-03-stop-recovers' $false 'stop button not found'
    }
} else {
    Add-Result 'vpn-start' $false 'start button not found'
}

# ---------------- summary ----------------
Log '=== SUMMARY ==='
$failed = 0
foreach ($r in $results) {
    if (-not $r.Ok) { $failed++ }
    Log ("  [{0}] {1} - {2}" -f $(if ($r.Ok) { 'PASS' } else { 'FAIL' }), $r.Check, $r.Detail)
}
Log ("evidence: {0}" -f $EvidenceDir)
Log ("result: {0} failed" -f $failed)
if ($failed -gt 0) { exit 1 }
exit 0
