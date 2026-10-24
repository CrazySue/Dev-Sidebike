# Ensures every PowerShell script in the workspace is UTF-8 WITH BOM.
# Windows PowerShell 5.1 (the shell used by this environment) parses BOM-less .ps1 as ANSI,
# which corrupts Chinese string literals and can break the whole script.
# Idempotent. Run: powershell -NoProfile -File _script\ensure-ps1-bom.ps1
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike'
)
$ErrorActionPreference = 'Continue'
$utf8Bom = New-Object System.Text.UTF8Encoding($true)
$count = 0
Get-ChildItem $Root -Recurse -Filter '*.ps1' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\|\\\.gradle|\\reference\\|\\tools\\|\\out\\|\\dist\\' } |
    ForEach-Object {
        $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
        $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
        if (-not $hasBom) {
            $text = [System.Text.Encoding]::UTF8.GetString($bytes)
            [System.IO.File]::WriteAllText($_.FullName, $text, $utf8Bom)
            Write-Host "[bom] fixed: $($_.FullName)"
            $count++
        }
    }
Write-Host "[bom] done, fixed $count file(s)"
exit 0
