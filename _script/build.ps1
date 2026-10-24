# Build the pre-delivery artifact (dev environment).
# Equivalent to: pipeline.ps1 dev
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\pipeline.ps1" dev
exit $LASTEXITCODE
