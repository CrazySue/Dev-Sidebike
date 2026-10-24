# Promote the tested pre-delivery artifact into signed delivery files (production environment).
# Equivalent to: pipeline.ps1 prod
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\pipeline.ps1" prod
exit $LASTEXITCODE
