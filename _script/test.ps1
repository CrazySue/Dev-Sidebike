# Run the full test environment (unit/integration tests + static audits + APK checks).
# Equivalent to: pipeline.ps1 test
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\pipeline.ps1" test
exit $LASTEXITCODE
