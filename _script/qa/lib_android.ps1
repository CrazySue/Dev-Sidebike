# Shared adb/UI automation helpers for Dev-Sidebike QA (Windows PowerShell 5.1, ASCII only).
# Dot-source from QA scripts:   . "$PSScriptRoot\lib_android.ps1"
# All functions are defensive: on timeout they return $false/nulls so flows can report BLOCKED
# instead of crashing. Screenshots are real PNGs (cmd.exe binary redirect, verified magic bytes).
param()

$script:QaRoot = 'E:\WorkSpace\Dev-Sidebike'
if (-not (Test-Path $script:QaRoot)) { $script:QaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }
$script:Adb = Join-Path $script:QaRoot 'tools\android-sdk\platform-tools\adb.exe'
$script:QaEvidence = Join-Path $script:QaRoot 'docs\qa\evidence\011'
$script:QaFixtures = Join-Path $script:QaRoot 'docs\qa\fixtures'
$script:QaResults = New-Object System.Collections.ArrayList
$script:QaShotIndex = 0
$script:QaStamp = Get-Date -Format 'yyyyMMdd-HHmmss'

New-Item -ItemType Directory -Force -Path $script:QaEvidence, $script:QaFixtures | Out-Null

function Qa-Log([string]$msg) { Write-Host $msg }

# ---------------------------------------------------------------- adb primitives
# NOTE: no param() block on purpose. With ValueFromRemainingArguments, dash tokens such as
# `am start -W -n ...` are parsed as *function parameters* (-W is ambiguous) instead of adb
# arguments. Without a param block, every token lands in $args verbatim; we then flatten arrays.
function Adb {
    $flat = @()
    foreach ($part in $args) { $flat += ($part -split '\s+' | Where-Object { $_ -ne '' }) }
    return (& $script:Adb $flat 2>&1 | Out-String)
}
function Adb-Shell([string]$cmd) {
    # Single-string remote shell command: adb receives ONE argument and executes it through the
    # device sh -c, so pipelines/quotes/redirects work. NEVER use Adb (flattened) for these.
    return (& $script:Adb shell $cmd 2>&1 | Out-String)
}function Adb-Raw {
    $flat = @()
    foreach ($part in $args) { $flat += $part }
    return (& $script:Adb $flat 2>&1 | Out-String)
}
function Qa-Devices { return (Adb devices) }
function Qa-IsOnline {
    $out = (Adb devices)
    return ($out -match 'emulator-\d+\s+device') -or ($out -match '\bdevice\b')
}
function Qa-WaitOnline([int]$timeoutSec = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Qa-IsOnline) { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}
function Qa-IsBooted {
    $b = (Adb shell getprop sys.boot_completed).Trim()
    return ($b -eq '1')
}
function Qa-PackageInstalled([string]$pkg) {
    return ((Adb shell pm list packages) -match [regex]::Escape($pkg))
}
function Qa-Install([string]$apk, [string]$pkg = '') {
    if (-not (Test-Path $apk)) { Qa-Log "[install] FAIL missing apk: $apk"; return $false }
    $out = (Adb install -r -t $apk)
    Qa-Log ("[install] " + ($out -split "`n" | Where-Object { $_ -match 'Success|Failure|Error' }) -join ' ')
    if ($pkg) { return (Qa-PackageInstalled $pkg) }
    return ($out -match 'Success')
}
function Qa-GrantPerms([string]$pkg) {
    # POST_NOTIFICATIONS is the only runtime permission we need; shell holds GRANT_RUNTIME_PERMISSIONS.
    $out = (Adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS)
    Qa-Log ("[perm] grant POST_NOTIFICATIONS: " + $out.Trim())
}
function Qa-ForceStop([string]$pkg) { & $script:Adb shell am force-stop $pkg 2>&1 | Out-Null }
function Qa-Launch([string]$component, [int]$warmWaitSec = 3) {
    $out = (Adb shell am start -W -n $component)
    Qa-Log ("[launch] " + (($out -split "`n") | Where-Object { $_ -match 'Status|WaitTime|Complete' }) -join ' ')
    Start-Sleep -Seconds $warmWaitSec
    return ($out -match 'Status: ok' -or $out -match 'Warning')
}
function Qa-TopActivity {
    $out = (Adb shell dumpsys activity activities)
    $m = [regex]::Match($out, 'topResumedActivity=ActivityRecord\{[^}]*?\s+([\w\.]+/[\w\.]+)')
    if ($m.Success) { return $m.Groups[1].Value }
    # Fallback for older dumpsys formats / transient states.
    $w = (Adb shell dumpsys window)
    $m2 = [regex]::Match($w, 'mCurrentFocus=Window\{[^}]*\s+([\w\.]+/[\w\.]+)')
    if ($m2.Success) { return $m2.Groups[1].Value }
    return ''
}

# ---------------------------------------------------------------- UI dump / find / tap
$script:QaUiCache = ''
$script:QaUiCacheTime = [datetime]::MinValue
function Qa-UiDump([switch]$Force) {
    $age = ((Get-Date) - $script:QaUiCacheTime).TotalSeconds
    if ($Force -or -not $script:QaUiCache -or $age -gt 1.5) {
        $dumped = ''
        for ($attempt = 1; $attempt -le 2; $attempt++) {
            & $script:Adb shell uiautomator dump /sdcard/qa_ui.xml 2>&1 | Out-Null
            $dumped = (Adb shell cat /sdcard/qa_ui.xml)
            if ($dumped.Length -gt 120 -and $dumped -notmatch 'ERROR:') { break }
            Start-Sleep -Milliseconds 800
        }
        $script:QaUiCache = $dumped
        $script:QaUiCacheTime = Get-Date
    }
    return $script:QaUiCache
}

# Heavy concurrent builds can make SystemUI/other processes hang on the emulator and show
# "<app> isn't responding / Close app / Wait" dialogs that steal the UI dump and screenshots.
# Tapping "Wait" keeps the emulator alive so automated flows stay meaningful.
function Qa-DismissSystemDialogs {
    for ($i = 0; $i -le 2; $i++) {
        $texts = Qa-Texts
        $anr = @($texts | Where-Object { $_ -match "isn't responding|is not responding|no responde|无响应|未响应|沒有回應" })
        if ($anr.Count -eq 0) { return $false }
        Qa-Log ("[anr] system dialog detected: " + ($anr -join '|'))
        $tapped = Qa-TapText 'Wait' 2 1
        if (-not $tapped) { $tapped = Qa-TapText '等待' 2 1 }
        if (-not $tapped) { $tapped = Qa-TapText '等待响应' 2 1 }
        if (-not $tapped) { Qa-PressBack }
        Start-Sleep -Seconds 2
    }
    return $true
}

function Qa-WaitBuildQuiet([int]$maxWaitSec = 180, [int]$quietSec = 8) {
    # Optional: wait until no gradle/kotlin build processes run for $quietSec (reduces emulator load).
    $start = Get-Date
    $lastBusy = Get-Date
    while (((Get-Date) - $start).TotalSeconds -lt $maxWaitSec) {
        $busy = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
                $_.CommandLine -match 'gradle\.bat|GradleDaemon|kotlin-daemon'
            })
        if ($busy.Count -eq 0) {
            if (((Get-Date) - $lastBusy).TotalSeconds -ge $quietSec) { return $true }
        } else {
            $lastBusy = Get-Date
        }
        Start-Sleep -Seconds 3
    }
    return $false
}
function Qa-Texts {
    $ui = Qa-UiDump
    return @([regex]::Matches($ui, '(?:text|content-desc)="([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
}
function Qa-HasText([string]$text, [switch]$Exact) {
    foreach ($t in (Qa-Texts)) {
        if ($Exact) { if ($t -eq $text) { return $true } } else { if ($t -like "*$text*") { return $true } }
    }
    return $false
}
function Qa-FindCenter([string]$text, [switch]$Exact) {
    $ui = Qa-UiDump
    $matches = [regex]::Matches($ui, '(?:text|content-desc)="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    foreach ($m in $matches) {
        $t = $m.Groups[1].Value
        $hit = if ($Exact) { $t -eq $text } else { $t -like "*$text*" }
        if (-not $hit) { continue }
        $cx = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
        $cy = ([int]$m.Groups[3].Value + [int]$m.Groups[5].Value) / 2
        return @([int]$cx, [int]$cy)
    }
    return $null
}
function Qa-Tap([int]$x, [int]$y) { & $script:Adb shell input tap $x $y 2>&1 | Out-Null }
function Qa-TapText([string]$text, [int]$tries = 6, [int]$delaySec = 1, [switch]$Exact) {
    for ($i = 1; $i -le $tries; $i++) {
        $c = Qa-FindCenter $text -Exact:$Exact
        if ($c) { Qa-Tap $c[0] $c[1]; Qa-Log "[tap] '$text' at $($c[0]),$($c[1])"; Start-Sleep -Seconds 1; return $true }
        # The UiDump cache from the previous screen can be stale right after navigation: force
        # a fresh dump on every retry (this was why the logs export FAB was missed).
        $null = Qa-UiDump -Force
        Start-Sleep -Seconds $delaySec
    }
    Qa-Log "[tap] NOT FOUND: '$text'"
    return $false
}
function Qa-TapDialog([string]$text) {
    # System dialogs (VPN consent / permission / DocumentsUI) render in another process but stay on top.
    return (Qa-TapText $text 4 1)
}
function Qa-WaitText([string]$text, [int]$timeoutSec = 15) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Qa-HasText $text) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}
function Qa-PressBack { & $script:Adb shell input keyevent KEYCODE_BACK 2>&1 | Out-Null; Start-Sleep -Seconds 1 }
function Qa-SwipeUp([int]$x = 540, [int]$from = 1500, [int]$to = 500) {
    & $script:Adb shell input swipe $x $from $x $to 300 2>&1 | Out-Null
}
function Qa-NavTo([string]$label) {
    # Bottom nav labels: 首页 / 规则 / 日志 / 设置 / 证书
    return (Qa-TapText $label 3 1)
}

# ---------------------------------------------------------------- screenshots (real PNG)
function Qa-Screenshot([string]$name) {
    $script:QaShotIndex++
    $safe = ($name -replace '[^\w\-]', '_')
    $file = Join-Path $script:QaEvidence ("{0}-{1:D2}-{2}.png" -f $script:QaStamp, $script:QaShotIndex, $safe)
    & cmd.exe /c "`"$script:Adb`" exec-out screencap -p > `"$file`"" | Out-Null
    if (-not (Test-Path $file)) { Qa-Log "[shot] FAIL $name"; return '' }
    $b = [System.IO.File]::ReadAllBytes($file)
    $ok = ($b.Length -gt 3000 -and $b[0] -eq 0x89 -and $b[1] -eq 0x50 -and $b[2] -eq 0x4E -and $b[3] -eq 0x47)
    Qa-Log ("[shot] {0} ({1} bytes, png={2})" -f $file, $b.Length, $ok)
    return $file
}

function Qa-TapClickableNearBottomRight([int]$minX = 500, [int]$minY = 1100, [int]$minW = 150, [int]$minH = 70) {
    # Compose FABs often expose a clickable node without text/content-desc in uiautomator.
    $ui = Qa-UiDump -Force
    $best = $null
    foreach ($m in [regex]::Matches($ui, '<node[^>]*>')) {
        $n = $m.Value
        if ($n -notmatch 'clickable="true"') { continue }
        $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
        $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
        if ($x1 -lt $minX -or $y1 -lt $minY -or ($x2 - $x1) -lt $minW -or ($y2 - $y1) -lt $minH) { continue }
        if (-not $best -or $y2 -gt $best[3]) { $best = @($x1, $y1, $x2, $y2) }
    }
    if (-not $best) { Qa-Log '[tap-br] no candidates'; return $false }
    $cx = [int](($best[0] + $best[2]) / 2); $cy = [int](($best[1] + $best[3]) / 2)
    Qa-Tap $cx $cy
    Qa-Log "[tap-br] at $cx,$cy bounds=[$($best[0]),$($best[1])][$($best[2]),$($best[3])]"
    Start-Sleep -Seconds 1
    return $true
}
# ---------------------------------------------------------------- results / verdicts
function Qa-Result([string]$id, [string]$desc, [string]$status, [string]$detail = '') {
    # status: PASS | FAIL | BLOCKED | SKIP
    [void]$script:QaResults.Add([pscustomobject]@{ Id = $id; Desc = $desc; Status = $status; Detail = $detail })
    Qa-Log ("[{0}] {1} {2} {3}" -f $status, $id, $desc, $detail)
}
function Qa-Summary([string]$flowName, [string]$outFile = '') {
    $pass = @($script:QaResults | Where-Object { $_.Status -eq 'PASS' }).Count
    $fail = @($script:QaResults | Where-Object { $_.Status -eq 'FAIL' }).Count
    $blocked = @($script:QaResults | Where-Object { $_.Status -eq 'BLOCKED' }).Count
    $skip = @($script:QaResults | Where-Object { $_.Status -eq 'SKIP' }).Count
    $lines = @()
    $lines += "=== QA FLOW SUMMARY: $flowName ($($script:QaStamp)) ==="
    foreach ($r in $script:QaResults) { $lines += ("  [{0}] {1} - {2} {3}" -f $r.Status, $r.Id, $r.Desc, $r.Detail) }
    $lines += ("RESULT: PASS=$pass FAIL=$fail BLOCKED=$blocked SKIP=$skip")
    $lines | ForEach-Object { Write-Host $_ }
    if ($outFile) { $lines | Out-File -FilePath $outFile -Encoding UTF8 }
    if ($fail -gt 0) { return 1 }
    return 0
}

# ---------------------------------------------------------------- VPN lifecycle helpers
function Qa-StartVpn {
    if (-not (Qa-TapText '启动服务' 6 1)) {
        # Maybe already running or button text differs.
        if (Qa-HasText '停止服务') { return $true }
        return $false
    }
    Start-Sleep -Seconds 2
    $top = Qa-TopActivity
    if ($top -match 'vpndialogs') {
        Qa-Log '[vpn] consent dialog detected'
        if (-not (Qa-TapText 'OK' 4 1)) { Qa-TapText 'Allow' 3 1 | Out-Null }
    }
    $deadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $deadline) {
        $out = (Adb shell dumpsys connectivity)
        if ($out -match 'VPN CONNECTED' -and $out -match 'InterfaceName: tun0') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}
function Qa-StopVpn {
    if (-not (Qa-TapText '停止服务' 5 1)) { return -not (Qa-HasText '停止服务') }
    $deadline = (Get-Date).AddSeconds(25)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $out = (Adb shell dumpsys connectivity)
        if (-not ($out -match 'InterfaceName: tun0')) { return $true }
    }
    return $false
}

# ---------------------------------------------------------------- misc
function Qa-PingHost([string]$hostName) {
    $out = (Adb shell ("ping -c 1 -W 3 " + $hostName))
    $m = [regex]::Match($out, 'PING\s+\S+\s+\(([0-9a-fA-F\.\:]+)\)')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}
function Qa-ClearLogs { & $script:Adb logcat -c 2>&1 | Out-Null }
function Qa-LogcatErrors { return ((& $script:Adb logcat -d 2>&1) -join "`n") }
function Qa-WriteFixture([string]$name, [string]$content) {
    $path = Join-Path $script:QaFixtures $name
    [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
    Qa-Log "[fixture] $path"
    return $path
}
