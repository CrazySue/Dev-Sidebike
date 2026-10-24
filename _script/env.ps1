# Shared dev environment for all Dev-Sidebike agents/workstreams.
# Usage:  . E:\WorkSpace\Dev-Sidebike\_script\env.ps1
# After dot-sourcing:  gradle ...  |  adb ...  |  $env:ANDROID_HOME is set
$root = 'E:\WorkSpace\Dev-Sidebike'

$env:JAVA_HOME = 'D:\Java\zulu17.68.17-ca-jdk17.0.20-win_x64'
$env:ANDROID_HOME = "$root\tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$root\.gradle-home"
$env:DEV_SIDebike_ROOT = $root

$env:PATH = "$env:JAVA_HOME\bin;$root\tools\gradle-8.9\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:PATH"

# Keep Gradle builds off the (small) system drive.
if (-not (Test-Path $env:GRADLE_USER_HOME)) { New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null }

Write-Host "[env] JAVA_HOME=$env:JAVA_HOME"
Write-Host "[env] ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "[env] GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
