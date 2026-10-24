# Shared Gradle wrapper for all Dev-Sidebike agents.
# - loads the common JDK/SDK/GRADLE_USER_HOME environment
# - serializes builds across ALL agents with a cross-process mutex, because several
#   teammates compile the same project concurrently and concurrent Gradle daemons
#   race on build/ incremental state (observed: Kotlin internal error / missing .class).
# Usage: powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :core:test
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1" | Out-Null
$root = 'E:\WorkSpace\Dev-Sidebike'

$mutex = New-Object System.Threading.Mutex($false, 'DevSidebikeGradleBuildLock')
$acquired = $false
$exitCode = 0
try {
    Write-Host "[gradle] 等待其他 agent 的构建完成（全局构建锁）..." -ForegroundColor DarkGray
    $acquired = $mutex.WaitOne([TimeSpan]::FromMinutes(20))
    if (-not $acquired) { throw "[gradle] 等待构建锁超时（20 分钟）" }
    Write-Host "[gradle] 获得构建锁，执行: gradle $($GradleArgs -join ' ')" -ForegroundColor DarkGray
    & "$root\tools\gradle-8.9\bin\gradle.bat" -p $root --console=plain @GradleArgs
    $exitCode = $LASTEXITCODE
}
finally {
    if ($acquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
exit $exitCode
