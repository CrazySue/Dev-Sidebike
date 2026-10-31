# QA 0.1.1-alphpa follow-up: U6 edit/delete/import, U3 lock-screen, U5 ripple (inspection + actions)
# Usage: powershell -NoProfile -File _script\qa\qa_u6_u3_u5.ps1 -Phase inspect
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [string]$Package = 'dev.sidebike.app.debug',
    [string]$Activity = 'dev.sidebike.app.debug/dev.sidebike.app.MainActivity',
    [string]$Phase = 'inspect'
)
$ErrorActionPreference = 'Continue'
. "$Root\_script\qa\lib_android.ps1"
$script:QaRoot = $Root
$script:Adb = Join-Path $Root 'tools\android-sdk\platform-tools\adb.exe'
$script:QaEvidence = Join-Path $Root 'docs\qa\evidence\011'
$script:QaStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
function Log($m) { Write-Host $m }

function Show-Screen([string]$name) {
    $ui = Qa-UiDump -Force
    $xmlPath = Join-Path $script:QaEvidence ("$name-$($script:QaStamp).xml")
    Set-Content -Path $xmlPath -Value $ui -Encoding UTF8
    Qa-Screenshot $name | Out-Null
    Log "[shot] $name -> $xmlPath (uiLen=$($ui.Length))"
    return $ui
}
function DumpTexts([string]$ui) { return @([regex]::Matches($ui, '(?:text|content-desc)="([^"]+)"') | ForEach-Object { $_.Groups[1].Value }) }
function FindNodeBounds([string]$ui, [string]$pattern, [int]$index = 0) {
    $ms = [regex]::Matches($ui, '<node[^>]*>')
    $hits = @()
    foreach ($m in $ms) {
        $n = $m.Value
        $t = [regex]::Match($n, '(?:text|content-desc)="([^"]*)"')
        if ($t.Success -and $t.Groups[1].Value -match $pattern) {
            $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if ($b.Success) {
                $hits += , @([int]$b.Groups[1].Value, [int]$b.Groups[2].Value, [int]$b.Groups[3].Value, [int]$b.Groups[4].Value, $t.Groups[1].Value)
            }
        }
    }
    if ($hits.Count -gt $index) { return $hits[$index] }
    return $null
}
function ClickableNodes([string]$ui) {
    $out = @()
    foreach ($m in [regex]::Matches($ui, '<node[^>]*>')) {
        $n = $m.Value
        if ($n -notmatch 'clickable="true"') { continue }
        $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $t = [regex]::Match($n, '(?:text|content-desc)="([^"]*)"')
        $cd = ''
        if ($t.Success) { $cd = $t.Groups[1].Value }
        $out += [pscustomobject]@{ x1 = [int]$b.Groups[1].Value; y1 = [int]$b.Groups[2].Value; x2 = [int]$b.Groups[3].Value; y2 = [int]$b.Groups[4].Value; label = $cd }
    }
    return $out
}

function Start-App {
    & $script:Adb shell am force-stop $Package 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    & $script:Adb shell am start -W -n $Activity 2>&1 | Out-Null
    Start-Sleep -Seconds 4
    Qa-DismissSystemDialogs | Out-Null
}
function GoRules {
    Qa-NavTo '规则' | Out-Null
    Start-Sleep -Seconds 2
    Qa-DismissSystemDialogs | Out-Null
}

switch ($Phase) {
    'inspect' {
        Start-App
        Qa-Screenshot 'u6-home' | Out-Null
        GoRules
        $ui = Show-Screen 'u6-rules-list'
        Log ('texts: ' + ((DumpTexts $ui | Select-Object -First 25) -join ' | '))
        # search for our custom rule to reduce list
        $search = ClickableNodes $ui | Where-Object { $_.label -match '搜索域名' }
        if (-not $search) {
            # fall back to any EditText node
            $m = [regex]::Match($ui, '<node[^>]*class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if ($m.Success) { $search = [pscustomobject]@{ x1 = [int]$m.Groups[1].Value; y1 = [int]$m.Groups[2].Value; x2 = [int]$m.Groups[3].Value; y2 = [int]$m.Groups[4].Value; label = 'EditText' } }
        }
        if ($search) {
            $cx = [int](($search.x1 + $search.x2) / 2); $cy = [int](($search.y1 + $search.y2) / 2)
            & $script:Adb shell input tap $cx $cy 2>&1 | Out-Null
            Start-Sleep -Milliseconds 600
            & $script:Adb shell input text 'qa-edit.example.com' 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            $ui2 = Show-Screen 'u6-rules-search'
            Log ('search texts: ' + ((DumpTexts $ui2 | Select-Object -First 25) -join ' | '))
            $click = ClickableNodes $ui2
            Log ('clickables: ' + (($click | ForEach-Object { "($($_.x1),$($_.y1))[$($_.x2),$($_.y2)]:'$($_.label)'" }) -join ' '))
        } else { Log 'search box not found' }
        Qa-PressBack | Out-Null
    }
    default { Log "unknown phase $Phase" }
}
