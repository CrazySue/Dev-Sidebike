# QA orchestrator: runs every static/unit QA gate and prints one summary. PS 5.1 compatible.
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File _script/qa/run_qa_tests.ps1
#   ... -WithUnitTests          (also runs :app:testDebugUnitTest + :core:test via gradle wrapper)
#   ... -Apk out/prod/app-release.apk -Release   (run check_apk against a release APK)
# Exit: 0 all gates pass; 1 any gate failed.
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [string]$Apk = '',
    [switch]$Release,
    [switch]$WithUnitTests
)
$ErrorActionPreference = 'Continue'
$results = New-Object System.Collections.ArrayList
function Run-Gate([string]$name, [string]$exe, [string[]]$argv) {
    Write-Host ""
    Write-Host ("=== QA gate: {0} ===" -f $name)
    & $exe @argv 2>&1 | ForEach-Object { Write-Host $_ }
    $code = $LASTEXITCODE
    [void]$results.Add([pscustomobject]@{ Gate = $name; Exit = $code; Ok = ($code -eq 0) })
    Write-Host ("--- {0}: exit={1}" -f $name, $code)
}

$ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$qa = Join-Path $Root '_script\qa'
$md3 = Join-Path $qa 'audit_md3.ps1'
$apkCheck = Join-Path $qa 'check_apk.ps1'
$req = Join-Path $qa 'check_requirements.ps1'
$gradle = Join-Path $Root '_script\gradle.ps1'

Run-Gate 'requirements' $ps @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $req)
Run-Gate 'md3-audit' $ps @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $md3)
if ($Apk) {
    $apkArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $apkCheck, '-Apk', $Apk)
    if (-not $Release) { $apkArgs += '-ExpectDebuggable' }
    else { $apkArgs += '-ExpectArmOnly' }  # beta delivery stance: release is ARM-only by design
    Run-Gate 'apk-check' $ps $apkArgs
}
if ($WithUnitTests) {
    Run-Gate 'core-tests' $ps @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $gradle, ':core:test')
    Run-Gate 'app-tests' $ps @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $gradle, ':app:testDebugUnitTest')
}

Write-Host ""
Write-Host "================ QA SUMMARY ================"
$failed = 0
foreach ($r in $results) {
    $status = if ($r.Ok) { 'PASS' } else { 'FAIL' }
    if (-not $r.Ok) { $failed++ }
    Write-Host ("  [{0}] {1}" -f $status, $r.Gate)
}
Write-Host ("  {0} gate(s) failed" -f $failed)
if ($failed -gt 0) { exit 1 }
Write-Host "ALL QA GATES PASS"
exit 0
