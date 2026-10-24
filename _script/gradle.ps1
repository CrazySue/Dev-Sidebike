# Thin wrapper so every agent uses the same JDK/SDK/Gradle setup.
# Usage: pwsh -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 assembleDebug
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArgs)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1"
$root = 'E:\WorkSpace\Dev-Sidebike'
& "$root\tools\gradle-8.9\bin\gradle.bat" -p $root @GradleArgs
exit $LASTEXITCODE
