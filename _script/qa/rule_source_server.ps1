# Hermetic "remote rule source" HTTP server for QA (Windows PowerShell 5.1, ASCII only).
# Serves rule files to the Android emulator via http://10.0.2.2:<port>/... (10.0.2.2 = host loopback).
# Endpoints:
#   /rules.json      Sidebike/Dev-Sidecar style JSON (ETag "rules-v1"; supports If-None-Match -> 304)
#   /rules.list      Shadowrocket DOMAIN-SUFFIX list (ETag "list-v1")
#   /domains.txt     plain domain list (ETag "domains-v1")
#   /malformed.json  invalid JSON (import error path)
#   /slow            waits -SlowSeconds then answers (timeout path)
#   /error500        HTTP 500 (failure path)
#   /requests.json   request log (JSON array) for assertions
# Usage (run in its own window/background job while QA flow runs):
#   powershell -NoProfile -File _script\qa\rule_source_server.ps1 -Port 18080
#   ... -DurationSec 600 -LogFile docs\qa\evidence\011\rule-source-server.log
param(
    [int]$Port = 18080,
    [int]$DurationSec = 900,
    [int]$SlowSeconds = 8,
    [string]$LogFile = '',
    [ValidateSet('loopback', 'any')][string]$BindAddress = 'loopback',
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike'
)
$ErrorActionPreference = 'Stop'
$fixtures = Join-Path $Root 'docs\qa\fixtures'
New-Item -ItemType Directory -Force -Path $fixtures | Out-Null
if (-not $LogFile) { $LogFile = Join-Path $Root 'docs\qa\evidence\011\rule-source-server.log' }
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

$rulesJson = @'
{
  "intercepts": {
    "qa-remote.example.com": {
      "/": { "proxy": "mirror.qa.example:443", "desc": "QA remote rule" }
    },
    "qa-sni.example.com": {
      "/resource/*": { "sni": "cdn.qa.example" }
    }
  },
  "whiteList": { "qa-direct.example.com": true },
  "domesticAllowList": ["qa-cn.example.cn", "qa-cn2.example.cn"]
}
'@
$rulesList = @'
# QA Shadowrocket list fixture
DOMAIN-SUFFIX,qa-list.example.com,DIRECT
DOMAIN,qа-exact.example.com,DIRECT
DOMAIN-KEYWORD,qa-keyword
DOMAIN-SUFFIX,qa-mirror.example.com,PROXY
'@
$rulesList = $rulesList.Replace([char]0x0430, 'a') # guard against accidental non-ASCII
$domainsTxt = "# QA domain list`nqa-direct2.example.com`nqa-list2.example.com`nqa-cn3.example.cn`n"
$malformed = '{"intercepts": { broken json ]'

$script:requests = New-Object System.Collections.ArrayList
function Write-SrvLog([string]$line) {
    $msg = ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $line)
    Write-Host $msg
    Add-Content -Path $LogFile -Value $msg -Encoding UTF8
}
function Get-Fixture([string]$path) {
    if ($path -eq '/rules.json') { return @{ Body = $rulesJson; Etag = '"rules-v1"'; Type = 'application/json' } }
    if ($path -eq '/rules.list') { return @{ Body = $rulesList; Etag = '"list-v1"'; Type = 'text/plain' } }
    if ($path -eq '/domains.txt') { return @{ Body = $domainsTxt; Etag = '"domains-v1"'; Type = 'text/plain' } }
    if ($path -eq '/malformed.json') { return @{ Body = $malformed; Etag = '"bad-v1"'; Type = 'application/json' } }
    return $null
}

$bindIp = if ($BindAddress -eq 'any') { [System.Net.IPAddress]::Any } else { [System.Net.IPAddress]::Loopback }
$listener = New-Object System.Net.Sockets.TcpListener($bindIp, $Port)
$listener.Start()
$bindDesc = if ($BindAddress -eq 'any') { "0.0.0.0:$Port" } else { "127.0.0.1:$Port" }
Write-SrvLog ("rule-source-server listening on {0} (emulator tries http://10.0.2.2:{1}/rules.json and adb reverse http://127.0.0.1:{1}/rules.json), duration={2}s" -f $bindDesc, $Port, $DurationSec)
$stopAt = (Get-Date).AddSeconds($DurationSec)

while ((Get-Date) -lt $stopAt) {
    if (-not $listener.Pending()) { Start-Sleep -Milliseconds 150; continue }
    $client = $null
    try {
        $client = $listener.AcceptTcpClient()
        $stream = $client.GetStream()
        $stream.ReadTimeout = 5000
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
        $requestLine = $reader.ReadLine()
        if (-not $requestLine) { continue }
        $headers = @{}
        while ($true) {
            $line = $reader.ReadLine()
            if ($null -eq $line -or $line -eq '') { break }
            $idx = $line.IndexOf(':')
            if ($idx -gt 0) { $headers[$line.Substring(0, $idx).Trim().ToLowerInvariant()] = $line.Substring($idx + 1).Trim() }
        }
        $parts = $requestLine -split ' '
        $path = if ($parts.Count -ge 2) { ($parts[1] -split '\?')[0] } else { '/' }
        [void]$script:requests.Add([pscustomobject]@{ time = (Get-Date).ToString('HH:mm:ss'); path = $path; inm = $headers['if-none-match'] })
        Write-SrvLog ("GET {0} (If-None-Match={1}) from {2}" -f $path, $headers['if-none-match'], $client.Client.RemoteEndPoint)

        $status = '200 OK'; $ctype = 'text/plain'; $body = ''; $etag = $null
        if ($path -eq '/error500') { $status = '500 Internal Server Error'; $body = 'qa injected failure' }
        elseif ($path -eq '/slow') { Start-Sleep -Seconds $SlowSeconds; $body = 'slow response'; }
        elseif ($path -eq '/requests.json') {
            $ctype = 'application/json'
            $body = ($script:requests | ConvertTo-Json -Depth 4)
        }
        else {
            $fx = Get-Fixture $path
            if ($null -eq $fx) { $status = '404 Not Found'; $body = 'not found' }
            else {
                $ctype = $fx.Type; $body = $fx.Body; $etag = $fx.Etag
                if ($etag -and $headers['if-none-match'] -eq $etag) { $status = '304 Not Modified'; $body = '' }
            }
        }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
        $head = "HTTP/1.1 $status`r`nContent-Type: $ctype`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n"
        if ($etag) { $head += "ETag: $etag`r`n" }
        $head += "`r`n"
        $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
        $stream.Write($headBytes, 0, $headBytes.Length)
        if ($bytes.Length -gt 0) { $stream.Write($bytes, 0, $bytes.Length) }
        $stream.Flush()
    } catch {
        Write-SrvLog ("[warn] " + $_.Exception.Message)
    } finally {
        if ($client) { try { $client.Close() } catch { } }
    }
}
$listener.Stop()
Write-SrvLog 'rule-source-server stopped'
