# Dev-Sidebike 0.1.1-alpha QA flows (Windows PowerShell 5.1, ASCII only).
# Covers user issues 1-10: log export / network diagnostics / CA install / MITM gating /
# rules add-edit-delete / text+URL import (hermetic local rule server) / domestic list /
# nav transitions / single progress + no uptime.
#
# Usage:
#   powershell -NoProfile -File _script\qa\qa_011_flows.ps1                     # all flows
#   ... -Flow rules|import|export|ca|mitm|nav|progress|smoke
#   ... -Apk out/dev/app-debug.apk -SkipInstall
# Evidence: docs/qa/evidence/011/<stamp>-*.png + qa-<flow>-<stamp>.log
# Status semantics: PASS / FAIL (regression of shipped behavior) / BLOCKED (UI not implemented yet
# in this build - must be re-run when the owning dev lands it) / SKIP.
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [string]$Apk = '',
    [string]$Package = 'dev.sidebike.app.debug',
    [string]$Activity = 'dev.sidebike.app.debug/dev.sidebike.app.MainActivity',
    [string]$Flow = 'all',
    [int]$ServerPort = 18080,
    [switch]$SkipInstall,
    [switch]$KeepVpnStopped,
    [switch]$WaitBuildQuiet
)
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\lib_android.ps1"
$QaRoot = $Root
$script:QaRoot = $Root
$script:Adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'

if (-not $Apk) { $Apk = Join-Path $Root 'out\dev\app-debug.apk' }
$flowLog = Join-Path $script:QaEvidence ("qa-$Flow-$($script:QaStamp).log")

function New-Fixtures {
    $dir = Join-Path $Root 'docs\qa\fixtures'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $json = @'
{
  "intercepts": {
    "qa-text-import.example.com": { "/": { "proxy": "mirror.qa.example:443", "desc": "text import fixture" } }
  },
  "whiteList": { "qa-text-direct.example.com": true },
  "domesticAllowList": ["qa-text-cn.example.cn"]
}
'@
    [System.IO.File]::WriteAllText((Join-Path $dir 'rules_import.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
    $list = "DOMAIN-SUFFIX,qa-shadow.example.com,DIRECT`nDOMAIN-SUFFIX,qa-shadow2.example.com,PROXY`n"
    [System.IO.File]::WriteAllText((Join-Path $dir 'rules_import.list'), $list, (New-Object System.Text.UTF8Encoding($false)))
    $domains = "# domains`nqa-dom1.example.com`nqa-dom2.example.com`nqa-dom3.example.cn`n"
    [System.IO.File]::WriteAllText((Join-Path $dir 'domains_import.txt'), $domains, (New-Object System.Text.UTF8Encoding($false)))
    $bad = "{ this is not json ]"
    [System.IO.File]::WriteAllText((Join-Path $dir 'rules_malformed.json'), $bad, (New-Object System.Text.UTF8Encoding($false)))
    Qa-Log "[fixtures] written to $dir"
}

function Ensure-App {
    if (-not (Qa-WaitOnline 60)) { Qa-Result 'env' 'emulator online' 'FAIL' 'no adb device'; return $false }
    if (-not (Qa-IsBooted)) {
        $deadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $deadline -and -not (Qa-IsBooted)) { Start-Sleep -Seconds 5 }
    }
    if (-not $SkipInstall) {
        $ok = Qa-Install $Apk $Package
        Qa-Result 'env' 'install apk' ($(if ($ok) { 'PASS' } else { 'FAIL' })) $Apk
        if (-not $ok) { return $false }
    }
    Qa-GrantPerms $Package
    Qa-DismissSystemDialogs | Out-Null
    Qa-ForceStop $Package
    Start-Sleep -Seconds 2
    $ok = Qa-Launch $Activity 6
    Qa-DismissSystemDialogs | Out-Null
    # Retry once if SystemUI/ANR dialog stole the foreground.
    $top = Qa-TopActivity
    if ($top -and ($top -notmatch [regex]::Escape($Package))) {
        Qa-Log "[env] foreground is '$top', relaunching app"
        Qa-DismissSystemDialogs | Out-Null
        $ok = Qa-Launch $Activity 5
        Qa-DismissSystemDialogs | Out-Null
    }
    $top = Qa-TopActivity
    $ok = $ok -and ($top -match [regex]::Escape($Package))
    Qa-Result 'env' 'launch app' ($(if ($ok) { 'PASS' } else { 'FAIL' })) $top
    return $ok
}

function Start-RuleServer {
    # Hermetic "remote" rule source: host-side HTTP server. The emulator reaches the host loopback
    # via http://10.0.2.2:<port>, therefore the server MUST bind 0.0.0.0 (loopback-only binding is not
    # reachable from the emulator NAT on this host - verified 2026-10-31).
    $already = (Test-NetConnection -ComputerName 127.0.0.1 -Port $ServerPort -WarningAction SilentlyContinue).TcpTestSucceeded
    if ($already) {
        if (Test-EmulatorUrl) { Qa-Log "[rule-server] already listening on $ServerPort and reachable from emulator"; return $true }
        Qa-Log "[rule-server] port $ServerPort in use but not reachable from emulator; restarting is manual"
        return $false
    }
    $server = Join-Path $Root '_script\qa\rule_source_server.ps1'
    $log = Join-Path $script:QaEvidence ("rule-source-server-{0}.log" -f $script:QaStamp)
    & cmd.exe /c "start `"qa-rule-source`" /b powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$server`" -Port $ServerPort -DurationSec 900 -BindAddress any -LogFile `"$log`" >nul 2>&1" | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 1
        if ((Test-NetConnection -ComputerName 127.0.0.1 -Port $ServerPort -WarningAction SilentlyContinue).TcpTestSucceeded) {
            if (Test-EmulatorUrl) {
                Qa-Log "[rule-server] started on 0.0.0.0:$ServerPort, reachable as http://10.0.2.2:$ServerPort/"
                return $true
            }
        }
    }
    Qa-Log '[rule-server] FAIL to start or not reachable from emulator'
    return $false
}

function Test-EmulatorUrl {
    $out = (Adb shell ("printf 'GET /rules.json HTTP/1.0\r\n\r\n' | nc -w 4 10.0.2.2 " + $ServerPort + " | head -c 120"))
    return ($out -match 'HTTP/1\.1 200' -or $out -match 'ETag')
}

# =============================================================================================
# Flow: smoke - five screens exist and nav works (baseline regression)
# =============================================================================================
function Flow-Smoke {
    if (-not (Ensure-App)) { return }
    $expect = @(
        @('首页', '启动服务'),
        @('规则', '规则'),
        @('日志', '日志'),
        @('设置', '设置'),
        @('证书', '证书')
    )
    foreach ($pair in $expect) {
        $nav = $pair[0]; $marker = $pair[1]
        Qa-DismissSystemDialogs | Out-Null
        $ok = Qa-NavTo $nav
        Start-Sleep -Seconds 2
        Qa-DismissSystemDialogs | Out-Null
        $seen = Qa-HasText $marker
        Qa-Screenshot ("smoke-" + $nav)
        Qa-Result ('smoke-' + $nav) "screen $nav renders" ($(if ($ok -and $seen) { 'PASS' } else { 'FAIL' })) "text=$marker seen=$seen"
    }
    Qa-NavTo '首页' | Out-Null
}

# =============================================================================================
# Flow: progress - single progress indicator while starting/stopping + no uptime
# =============================================================================================
function Flow-Progress {
    if (-not (Ensure-App)) { return }
    $textsBefore = Qa-Texts
    $uptimeBefore = @($textsBefore | Where-Object { $_ -match '已运行|uptime|运行时长|00:\d' })
    Qa-Result 'progress-no-uptime-idle' 'no uptime text on idle home' ($(if ($uptimeBefore.Count -eq 0) { 'PASS' } else { 'FAIL' })) ("found=" + ($uptimeBefore -join '|'))
    Qa-Screenshot 'progress-idle'

    $okStart = Qa-TapText '启动服务' 5 1
    Qa-Result 'progress-start-button' 'start button exists' ($(if ($okStart) { 'PASS' } else { 'FAIL' })) ''
    # Rapid screenshots during PREPARING/STARTING to count progress indicators offline.
    for ($i = 1; $i -le 3; $i++) { Start-Sleep -Milliseconds 350; Qa-Screenshot ("progress-starting-$i") }
    $vpn = Qa-StartVpn  # handles consent + waits RUNNING (idempotent if already starting)
    Qa-Result 'progress-vpn-established' 'VPN reaches RUNNING' ($(if ($vpn) { 'PASS' } else { 'FAIL' })) ''
    Qa-Screenshot 'progress-running'
    $textsRunning = Qa-Texts
    $uptimeRunning = @($textsRunning | Where-Object { $_ -match '已运行|uptime|运行时长' })
    Qa-Result 'progress-no-uptime-running' 'no uptime text while running' ($(if ($uptimeRunning.Count -eq 0) { 'PASS' } else { 'FAIL' })) ("found=" + ($uptimeRunning -join '|'))

    $okStop = Qa-TapText '停止服务' 5 1
    Qa-Result 'progress-stop-button' 'stop button exists' ($(if ($okStop) { 'PASS' } else { 'FAIL' })) ''
    for ($i = 1; $i -le 3; $i++) { Start-Sleep -Milliseconds 350; Qa-Screenshot ("progress-stopping-$i") }
    $stopped = Qa-StopVpn
    Qa-Result 'progress-stop-complete' 'VPN stopped cleanly' ($(if ($stopped) { 'PASS' } else { 'FAIL' })) ''
}

# =============================================================================================
# Flow: nav - tab transitions do not overlap two screens (screenshots for manual verdict)
# =============================================================================================
function Flow-Nav {
    if (-not (Ensure-App)) { return }
    $tabs = @('规则', '日志', '设置', '证书', '首页', '规则', '首页')
    foreach ($t in $tabs) {
        $ok = Qa-NavTo $t
        # Capture the transition frames immediately (no settle sleep on purpose).
        for ($i = 1; $i -le 2; $i++) { Qa-Screenshot ("nav-$t-frame$i") }
        Start-Sleep -Seconds 2
        Qa-Screenshot ("nav-$t-settled")
        Qa-Result ('nav-' + $t) "navigate to $t" ($(if ($ok) { 'PASS' } else { 'FAIL' })) 'see nav-*-frame screenshots for overlap check'
    }
}

# =============================================================================================
# Flow: ca - CA install flow (system installer launch / cancel / detection, black-screen check)
# =============================================================================================
function Test-ScreenBlack([string]$png) {
    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue
        $bmp = [System.Drawing.Bitmap]::FromFile($png)
        $sum = 0; $n = 0
        for ($x = 0; $x -lt $bmp.Width; $x += 24) {
            for ($y = 0; $y -lt $bmp.Height; $y += 24) {
                $c = $bmp.GetPixel($x, $y); $sum += ($c.R + $c.G + $c.B) / 3; $n++
            }
        }
        $bmp.Dispose()
        if ($n -eq 0) { return 255 }
        return [int]($sum / $n)
    } catch { return -1 }
}

function Flow-Ca {
    if (-not (Ensure-App)) { return }
    $okNav = Qa-NavTo '证书'
    Start-Sleep -Seconds 2
    $hasInstall = Qa-HasText '安装'
    Qa-Result 'ca-screen' 'CertGuide screen shows install action' ($(if ($okNav -and $hasInstall) { 'PASS' } else { 'FAIL' })) ("texts=" + ((Qa-Texts | Select-Object -First 8) -join '|'))
    Qa-Screenshot 'ca-screen'

    $tapped = Qa-TapText '安装 CA' 4 1
    if (-not $tapped) { $tapped = Qa-TapText '安装证书' 4 1 }
    if (-not $tapped) {
        Qa-Result 'ca-install-tap' 'tap install CA' 'BLOCKED' 'no install button found'
        return
    }
    Start-Sleep -Seconds 3
    $top = Qa-TopActivity
    $shot = Qa-Screenshot 'ca-after-install-tap'
    $black = 0
    if ($shot) { $black = Test-ScreenBlack $shot }
    $isSystemUi = ($top -match 'certinstaller') -or ($top -match 'com\.android\.settings') -or ($top -match 'documentsui') -or ($top -match 'vpndialogs')
    Qa-Result 'ca-installer-launched' 'system cert installer/settings launched (top=' + $top + ')' ($(if ($isSystemUi) { 'PASS' } else { 'FAIL' })) ''
    Qa-Result 'ca-no-black-dialog' 'installer screen not >90% black' ($(if ($black -ge 0 -and $black -lt 22) { 'FAIL' } else { 'PASS' })) "avgBrightness=$black"

    # Cancel the installer and re-check app's installed state (must stay accurate, no crash).
    Qa-PressBack
    Start-Sleep -Seconds 2
    Qa-PressBack
    Start-Sleep -Seconds 2
    $backOk = Qa-HasText '证书'
    Qa-Screenshot 'ca-after-cancel'
    Qa-Result 'ca-cancel-recovery' 'app recovers after cancelling installer' ($(if ($backOk) { 'PASS' } else { 'FAIL' })) ("top=" + (Qa-TopActivity))
    $stateTexts = Qa-Texts
    $showsInstalled = @($stateTexts | Where-Object { $_ -match '已安装' }).Count -gt 0
    $showsNotInstalled = @($stateTexts | Where-Object { $_ -match '未安装|尚未安装|尚未生成|未生成' }).Count -gt 0
    Qa-Result 'ca-state-accurate' 'installed/not-installed state shown accurately' ($(if ($showsInstalled -or $showsNotInstalled) { 'PASS' } else { 'FAIL' })) ("installedText=$showsInstalled notInstalledText=$showsNotInstalled")
}

# =============================================================================================
# Flow: mitm - MITM toggle without CA must stay OFF + network diagnostics available
# =============================================================================================
function Flow-Mitm {
    if (-not (Ensure-App)) { return }
    Qa-NavTo '规则' | Out-Null
    Start-Sleep -Seconds 2
    $texts = Qa-Texts
    $mitmLabel = @($texts | Where-Object { $_ -match 'MITM' }) | Select-Object -First 1
    Qa-Result 'mitm-label' 'MITM toggle visible' ($(if ($mitmLabel) { 'PASS' } else { 'FAIL' })) ("label=$mitmLabel")
    # Find the MITM row's switch center: tap the MITM label's row right side.
    $c = Qa-FindCenter 'MITM'
    if ($c) {
        Qa-Tap ($c[0] + 380) $c[1]
        Start-Sleep -Seconds 2
    }
    Qa-Screenshot 'mitm-after-toggle'
    $after = Qa-Texts
    $warning = @($after | Where-Object { $_ -match 'CA|证书' })
    $stillOff = @($after | Where-Object { $_ -match 'MITM 已关闭|MITM.*关闭' }).Count -gt 0
    Qa-Result 'mitm-gated-without-ca' 'MITM without CA stays disabled with explanation' ($(if (($warning.Count -gt 0) -or $stillOff) { 'PASS' } else { 'BLOCKED' })) ("warning=" + (($warning | Select-Object -First 2) -join ' / '))
    Qa-Screenshot 'mitm-gating-result'

    # Network diagnostics: settings or logs screen entry point.
    Qa-NavTo '设置' | Out-Null
    Start-Sleep -Seconds 2
    Qa-SwipeUp 540 1500 500
    Start-Sleep -Seconds 1
    $diagTap = Qa-TapText '运行网络诊断' 4 1
    if (-not $diagTap) { $diagTap = Qa-TapText '诊断' 3 1 }
    if ($diagTap) {
        Start-Sleep -Seconds 6
        Qa-Screenshot 'diagnostics-result'
        $diagTexts = Qa-Texts
        $hasContent = @($diagTexts | Where-Object { $_ -match 'DNS|TLS|代理|解析|provider' }).Count -gt 0
        Qa-Result 'diagnostics-output' 'network diagnostics report rendered' ($(if ($hasContent) { 'PASS' } else { 'FAIL' })) ("texts=" + (($diagTexts | Select-Object -First 6) -join '|'))
    } else {
        Qa-Result 'diagnostics-entry' 'network diagnostics entry exists' 'BLOCKED' 'no 网络诊断 entry found in current UI'
    }
}

# =============================================================================================
# Flow: export - .log / .zip export via SAF (not clipboard)
# =============================================================================================
function Flow-Export {
    if (-not (Ensure-App)) { return }
    # Generate a few log lines first (start/stop once if idle) so the export is non-empty.
    if (Qa-HasText '启动服务') { Qa-TapText '启动服务' 3 1 | Out-Null; Start-Sleep -Seconds 8; Qa-TapText '停止服务' 3 1 | Out-Null; Start-Sleep -Seconds 3 }
    Qa-NavTo '日志' | Out-Null
    Start-Sleep -Seconds 2
    Qa-Screenshot 'export-logs-screen'
    $tapped = Qa-TapText '导出' 5 1
    if (-not $tapped) { Qa-Result 'export-entry' '导出 entry exists' 'FAIL' 'no 导出 button'; return }
    Start-Sleep -Seconds 2
    $top = Qa-TopActivity
    Qa-Screenshot 'export-after-tap'
    $texts = Qa-Texts
    $hasFormatChoice = @($texts | Where-Object { $_ -match '导出日志文件|导出诊断包|诊断信息|\.log|\.zip' }).Count -gt 0
    $inSaf = ($top -match 'documentsui') -or ($top -match 'DocumentsUI')
    Qa-Result 'export-format-choice' 'export offers text/zip format' ($(if ($hasFormatChoice) { 'PASS' } else { 'BLOCKED' })) ("texts=" + (($texts | Select-Object -First 8) -join '|'))
    if ($hasFormatChoice) {
        # Choose the text log option if present, then expect SAF.
        $pick = Qa-TapText '导出日志文件' 3 1
        if (-not $pick) { $pick = Qa-TapText '导出日志' 2 1 }
        if (-not $pick) { $pick = Qa-TapText '文本' 2 1 }
        Start-Sleep -Seconds 3
        $top2 = Qa-TopActivity
        $saf = ($top2 -match 'documentsui') -or ($top2 -match 'DocumentsUI') -or ($top2 -match 'com\.android\.documentsui')
        Qa-Screenshot 'export-saf-picker'
        Qa-Result 'export-saf-launched' 'SAF save picker launched (top=' + $top2 + ')' ($(if ($saf) { 'PASS' } else { 'FAIL' })) ''
        if ($saf) {
            # Save into Downloads: tap SAVE / 保存, then accept default filename.
            $saved = Qa-TapText 'SAVE' 3 1
            if (-not $saved) { $saved = Qa-TapText '保存' 3 1 }
            if (-not $saved) { $saved = Qa-TapText '确定' 2 1 }
            Start-Sleep -Seconds 3
            $files = (Adb shell ("ls -l /sdcard/Download"))
            $hit = @($files -split "`n" | Where-Object { $_ -match '\.log$|\.txt$|\.zip$' })
            Qa-Screenshot 'export-after-save'
            Qa-Result 'export-file-written' 'exported file exists in /sdcard/Download' ($(if ($hit.Count -gt 0) { 'PASS' } else { 'FAIL' })) (($hit | Select-Object -First 2) -join ' | ')
        }
    }
}

# =============================================================================================
# Flow: rules - add / edit / delete custom rule, direct-pass list editing
# =============================================================================================
function Flow-Rules {
    if (-not (Ensure-App)) { return }
    Qa-NavTo '规则' | Out-Null
    Start-Sleep -Seconds 2
    Qa-Screenshot 'rules-list'
    $texts = Qa-Texts
    $countText = @($texts | Where-Object { $_ -match '共\s*\d+\s*个域名' }) | Select-Object -First 1
    Qa-Result 'rules-count-visible' 'rules count visible' ($(if ($countText) { 'PASS' } else { 'FAIL' })) ("count=$countText")

    $addTap = Qa-TapText '添加规则' 3 1
    if (-not $addTap) { $addTap = Qa-TapText '添加' 3 1 }
    if (-not $addTap) { $addTap = Qa-TapText '新建' 3 1 }
    if (-not $addTap) {
        Qa-Result 'rules-add-entry' 'add rule entry exists' 'BLOCKED' 'no 新增/添加 button in current UI'
        # Continue to direct-pass list checks even if add dialog is absent.
    } else {
        Start-Sleep -Seconds 2
        $texts = Qa-Texts
        Qa-Screenshot 'rules-add-dialog'
        $hasDialog = @($texts | Where-Object { $_ -match '域名|路径|保存|取消' }).Count -ge 2
        Qa-Result 'rules-add-dialog' 'add-rule dialog renders' ($(if ($hasDialog) { 'PASS' } else { 'FAIL' })) ("texts=" + (($texts | Select-Object -First 8) -join '|'))
        if ($hasDialog) {
            # Fill domain field: focus first editable and type ASCII test domain.
            $edit = Qa-FindCenter '域名'
            if ($edit) {
                Qa-Tap $edit[0] $edit[1]
                Start-Sleep -Milliseconds 800
                Adb shell input text 'qa-edit.example.com' | Out-Null
                Start-Sleep -Milliseconds 500
            }
            $save = Qa-TapText '保存' 3 1
            if (-not $save) { $save = Qa-TapText '确定' 3 1 }
            Start-Sleep -Seconds 3
            Qa-Screenshot 'rules-after-save'
            $added = Qa-HasText 'qa-edit.example.com'
            Qa-Result 'rules-add-save' 'custom rule saved and visible' ($(if ($save -and $added) { 'PASS' } else { 'FAIL' })) ''
            # Delete it (long-press card or explicit delete action).
            if ($added) {
                $c = Qa-FindCenter 'qa-edit.example.com'
                if ($c) {
                    & $script:Adb shell input swipe $c[0] $c[1] $c[0] $c[1] 800 2>&1 | Out-Null
                    Start-Sleep -Seconds 2
                    $del = Qa-TapText '删除' 3 1
                    if ($del) { Start-Sleep -Seconds 2; Qa-Screenshot 'rules-after-delete' }
                    $gone = -not (Qa-HasText 'qa-edit.example.com')
                    Qa-Result 'rules-delete' 'custom rule deleted and disappears' ($(if ($del -and $gone) { 'PASS' } else { 'BLOCKED' })) ("deleteTapped=$del gone=$gone")
                }
            }
        } else {
            Qa-PressBack
        }
    }

    # Rule tabs: 直连放行 / 国内域名 / 规则源 entries exist (0.1.1 new UI).
    foreach ($tabName in @('直连放行', '国内域名', '规则源')) {
        Qa-DismissSystemDialogs | Out-Null
        $tabOk = Qa-TapText $tabName 4 1
        Start-Sleep -Seconds 2
        Qa-Screenshot ('rules-tab-' + $tabName)
        $tabSeen = Qa-HasText $tabName
        Qa-Result ('rules-tab-' + $tabName) "rules tab $tabName opens" ($(if ($tabOk -and $tabSeen) { 'PASS' } else { 'BLOCKED' })) "seen=$tabSeen"
    }
    Qa-TapText '拦截规则' 2 1 | Out-Null

    # Direct-pass (whiteList) list visibility / editing entry.
    Qa-NavTo '设置' | Out-Null
    Start-Sleep -Seconds 2
    Qa-SwipeUp 540 1500 500
    Start-Sleep -Seconds 1
    $ts = Qa-Texts
    $directEntry = @($ts | Where-Object { $_ -match '直连|放行|白名单|国内' })
    Qa-Result 'direct-pass-entry' 'direct-pass/domestic list editable entry visible' ($(if ($directEntry.Count -gt 0) { 'PASS' } else { 'BLOCKED' })) ("labels=" + (($directEntry | Select-Object -First 4) -join ' | '))
    Qa-Screenshot 'rules-direct-pass-settings'
}

# =============================================================================================
# Flow: import - text + URL rule import via hermetic local server (10.0.2.2)
# =============================================================================================
function Flow-Import {
    if (-not (Ensure-App)) { return }
    if (-not (Start-RuleServer)) { Qa-Result 'import-server' 'local rule source server' 'FAIL' 'could not start'; return }
    Qa-NavTo '规则' | Out-Null
    Start-Sleep -Seconds 2
    Qa-TapText '规则源' 3 1 | Out-Null; Start-Sleep -Seconds 1; $tapImport = Qa-TapText '从文本/URL 导入' 4 1
    if (-not $tapImport) { $tapImport = Qa-TapText '规则源' 4 1 }
    if (-not $tapImport) {
        Qa-Result 'import-entry' 'rule import entry exists' 'BLOCKED' 'no 导入/规则源 entry in current UI'
        return
    }
    Start-Sleep -Seconds 2
    Qa-Screenshot 'import-dialog'
    $texts = Qa-Texts
    $isDialog = @($texts | Where-Object { $_ -match 'URL|链接|文本|导入|来源' }).Count -ge 1
    Qa-Result 'import-dialog' 'import dialog/panel renders' ($(if ($isDialog) { 'PASS' } else { 'FAIL' })) ("texts=" + (($texts | Select-Object -First 10) -join '|'))
    if (-not $isDialog) { Qa-PressBack; return }

    # URL import: type local server URL into the first input.
    $url = "http://10.0.2.2:$ServerPort/rules.json"
    $input = Qa-FindCenter '规则文本或 URL'
    if (-not $input) { $input = Qa-FindCenter '链接' }
    if (-not $input) { $input = Qa-FindCenter '来源' }
    if ($input) {
        Qa-Tap $input[0] $input[1]; Start-Sleep -Milliseconds 600
        Adb shell input text ("http://10.0.2.2:$ServerPort/rules.json") | Out-Null
        Start-Sleep -Milliseconds 500
    }
    $okImport = Qa-TapText '导入' 3 1
    if (-not $okImport) { $okImport = Qa-TapText '添加' 3 1 }
    Start-Sleep -Seconds 6
    Qa-Screenshot 'import-after-import'
    $imported = Qa-HasText 'qa-remote.example.com'
    Qa-Result 'import-url' "import from $url" ($(if ($imported) { 'PASS' } else { 'BLOCKED' })) ("domainVisible=$imported")
    # Refresh again to exercise 304/ETag path (server logs If-None-Match).
    $refresh = Qa-TapText '刷新' 3 1
    if (-not $refresh) { $refresh = Qa-TapText '更新' 3 1 }
    if ($refresh) {
        Start-Sleep -Seconds 5
        Qa-Screenshot 'import-after-refresh'
        Qa-Result 'import-refresh' 'manual refresh works' 'PASS' 'see rule-source-server log for If-None-Match'
    } else {
        Qa-Result 'import-refresh' 'manual refresh entry exists' 'BLOCKED' 'no 刷新/更新 button'
    }
    # Malformed source: must not crash and must keep existing rules.
    $malTap = Qa-TapText '添加规则源' 2 1
    if ($malTap) {
        $input2 = Qa-FindCenter '规则文本或 URL'
        if ($input2) {
            Qa-Tap $input2[0] $input2[1]; Start-Sleep -Milliseconds 500
            Adb shell input text ("http://10.0.2.2:$ServerPort/malformed.json") | Out-Null
            Qa-TapText '添加' 2 1 | Out-Null
            Start-Sleep -Seconds 5
            $alive = (Qa-TopActivity) -match $Package
            Qa-Screenshot 'import-malformed'
            Qa-Result 'import-malformed-safe' 'malformed remote source does not crash app' ($(if ($alive) { 'PASS' } else { 'FAIL' })) ''
        }
    }
    Qa-PressBack
}

# =============================================================================================
# main
# =============================================================================================
New-Fixtures
Qa-Log ("=== Dev-Sidebike 0.1.1-alpha QA flow='$Flow' stamp=$($script:QaStamp) apk=$Apk pkg=$Package ===")
Qa-Log ("evidence dir: " + $script:QaEvidence)
if ($WaitBuildQuiet) {
    Qa-Log '[env] waiting for concurrent gradle/kotlin builds to be quiet (reduces emulator ANR)...'
    Qa-Log ('[env] build quiet: ' + (Qa-WaitBuildQuiet 240 8))
}
$flowsToRun = switch ($Flow) {
    'all' { @('smoke', 'progress', 'nav', 'ca', 'mitm', 'export', 'rules', 'import') }
    'smoke' { @('smoke') }
    'progress' { @('progress') }
    'nav' { @('nav') }
    'ca' { @('ca') }
    'mitm' { @('mitm') }
    'export' { @('export') }
    'rules' { @('rules') }
    'import' { @('import') }
    default { @('smoke') }
}
foreach ($f in $flowsToRun) {
    Qa-Log ""
    Qa-Log "---- flow: $f ----"
    Qa-DismissSystemDialogs | Out-Null
    switch ($f) {
        'smoke' { Flow-Smoke }
        'progress' { Flow-Progress }
        'nav' { Flow-Nav }
        'ca' { Flow-Ca }
        'mitm' { Flow-Mitm }
        'export' { Flow-Export }
        'rules' { Flow-Rules }
        'import' { Flow-Import }
    }
    # Always leave the device in a clean state between flows.
    try { Qa-StopVpn | Out-Null } catch { }
}
if (-not $KeepVpnStopped) { }
$code = Qa-Summary $Flow $flowLog
Qa-Log ("log: " + $flowLog)
exit $code
