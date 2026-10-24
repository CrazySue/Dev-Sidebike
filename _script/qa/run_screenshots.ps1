# Opt-in Robolectric screenshot capture (QA-Screenshots).
# The default `testDebugUnitTest` suite must stay deterministic: Robolectric NATIVE-graphics capture
# is flaky on Windows (ComposeTimeoutException while waiting for idle) and stays SKIPPED unless
# DSH_QA_SCREENSHOTS=1. For the beta acceptance we use the emulator screenshots captured from a real
# Android 15 system image (docs/qa/screenshots/emulator_*.png / e2_*.png).
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File _script/qa/run_screenshots.ps1
#   ... -Filter "dev.sidebike.app.qa.ScreenshotQaTest"
# Output: docs/qa/screenshots/*.png (overwrites same-named files), logs to stdout.
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [string]$Filter = 'dev.sidebike.app.qa.ScreenshotQaTest*'
)
$ErrorActionPreference = 'Continue'
$env:DSH_QA_SCREENSHOTS = '1'
$gradle = Join-Path $Root '_script\gradle.ps1'
Write-Host "[run_screenshots] DSH_QA_SCREENSHOTS=1, filter=$Filter"
# --no-daemon is intentional: the test worker must inherit the env var; a reused daemon has a stale
# environment snapshot.
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $gradle :app:testDebugUnitTest --tests $Filter --no-daemon
$code = $LASTEXITCODE
Write-Host "[run_screenshots] exit=$code"
if (Test-Path (Join-Path $Root 'docs\qa\screenshots')) {
    Get-ChildItem (Join-Path $Root 'docs\qa\screenshots') -Filter '*.png' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 5 |
        ForEach-Object { Write-Host ("  {0}  {1} bytes  {2}" -f $_.Name, $_.Length, $_.LastWriteTime) }
}
exit $code
