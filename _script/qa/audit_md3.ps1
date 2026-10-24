# MD3 strict-compliance static audit (QA gate). ASCII-only for Windows PowerShell 5.1.
# Rules:
#   1. No Material2 component imports: androidx.compose.material.<Component>; material.icons.* and
#      material.ripple are allowed (icons library is used by MD3 apps).
#   2. No hardcoded colors outside ui/theme: Color(0x..., Color.Red/Blue/... literals.
#   3. No LinearEasing anywhere in ui/**.
#   4. No custom fonts: FontFamily( outside ui/theme.
#   5. ui/motion/M3Motion.kt must exist and define non-linear cubic-bezier tokens.
#   6. Motion tokens must be actually used in ui/** (>= 8 references) and state animations >= 15.
#   7. No Material2/legacy widget libs in app/build.gradle.kts dependencies
#      (androidx.compose.material:material, com.google.android.material).
# Exit: 0 pass, 1 violations. Use -Json for pipeline consumption.
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [switch]$Json
)
$ErrorActionPreference = 'Stop'
$uiDir = Join-Path $Root 'app\src\main\java\dev\sidebike\app\ui'
$themeDir = Join-Path $uiDir 'theme'
if (-not (Test-Path $uiDir)) { Write-Host "[audit_md3] FAIL: ui dir missing: $uiDir"; exit 1 }

$violations = New-Object System.Collections.ArrayList
function Add-Violation([string]$rule, [string]$file, [int]$line, [string]$content) {
    [void]$violations.Add([pscustomobject]@{ Rule = $rule; File = $file; Line = $line; Content = $content.Trim() })
}
function Is-ThemeFile([string]$dir) { return ($dir -eq $themeDir) -or ($dir.StartsWith($themeDir + '\')) }

$files = @(Get-ChildItem $uiDir -Recurse -Filter '*.kt' -ErrorAction SilentlyContinue)
foreach ($f in $files) {
    $rel = $f.FullName.Substring($Root.Length + 1)
    $lines = @(Get-Content $f.FullName)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match 'import\s+androidx\.compose\.material\.(?!icons\.|ripple\.)') {
            Add-Violation 'M2_IMPORT' $rel ($i + 1) $line
        }
        if (-not (Is-ThemeFile $f.DirectoryName) -and $line -match 'Color\(0x|Color\.(Red|Blue|Green|Yellow|Magenta|Cyan|Black|White)\b') {
            Add-Violation 'HARDCODED_COLOR' $rel ($i + 1) $line
        }
        if ($line -match 'LinearEasing') { Add-Violation 'LINEAR_EASING' $rel ($i + 1) $line }
        if (-not (Is-ThemeFile $f.DirectoryName) -and $line -match 'FontFamily\s*\(') {
            Add-Violation 'CUSTOM_FONT' $rel ($i + 1) $line
        }
    }
}

$motionFile = Join-Path $uiDir 'motion\M3Motion.kt'
if (-not (Test-Path $motionFile)) {
    Add-Violation 'MISSING_MOTION_TOKENS' 'app/src/main/java/dev/sidebike/app/ui/motion/M3Motion.kt' 0 'M3Motion.kt missing'
} else {
    $motionContent = Get-Content $motionFile -Raw
    foreach ($needle in @('CubicBezierEasing', 'EmphasizedDecelerate', 'EmphasizedAccelerate', 'Standard')) {
        if ($motionContent -notmatch [regex]::Escape($needle)) {
            Add-Violation 'MOTION_TOKEN_MISSING' 'app/src/main/java/dev/sidebike/app/ui/motion/M3Motion.kt' 0 "missing $needle"
        }
    }
}

$animCount = 0
$tokenUsage = 0
foreach ($f in $files) {
    $c = Get-Content $f.FullName -Raw
    $animCount += ([regex]::Matches($c, 'AnimatedContent|AnimatedVisibility|AnimatedFloat|animateFloatAsState|animateIntAsState|animateColorAsState|animateDpAsState|animateContentSize|AnimatedNavHost|Crossfade|animateItem')).Count
    if (-not (Is-ThemeFile $f.DirectoryName) -and ($f.Name -ne 'M3Motion.kt')) {
        $tokenUsage += ([regex]::Matches($c, 'M3Motion\.')).Count
    }
}
if ($animCount -lt 15) { Add-Violation 'INSUFFICIENT_MOTION' 'app/src/main/java/dev/sidebike/app/ui' 0 "animation usages=$animCount < 15" }
if ($tokenUsage -lt 8) { Add-Violation 'MOTION_TOKENS_UNUSED' 'app/src/main/java/dev/sidebike/app/ui' 0 "M3Motion.* usages=$tokenUsage < 8" }

# build.gradle.kts: forbid M2/legacy widget libs in app dependencies
$gradle = Join-Path $Root 'app\build.gradle.kts'
if (Test-Path $gradle) {
    $glines = @(Get-Content $gradle)
    for ($i = 0; $i -lt $glines.Count; $i++) {
        $l = $glines[$i]
        if ($l -match 'androidx\.compose\.material:material\b' -or $l -match 'com\.google\.android\.material') {
            Add-Violation 'NON_MD3_DEPENDENCY' 'app/build.gradle.kts' ($i + 1) $l
        }
    }
}

$result = [pscustomobject]@{
    files = $files.Count; animations = $animCount; motionTokenUsages = $tokenUsage
    violations = $violations.Count; details = $violations
}
if ($Json) { $result | ConvertTo-Json -Depth 6 }
if ($violations.Count -gt 0) {
    if (-not $Json) {
        Write-Host "[audit_md3] FAIL: $($violations.Count) violation(s)" -ForegroundColor Red
        $violations | Select-Object -First 60 | ForEach-Object { Write-Host ("  - [{0}] {1}:{2}  {3}" -f $_.Rule, $_.File, $_.Line, $_.Content) }
    }
    exit 1
}
if (-not $Json) {
    Write-Host "[audit_md3] PASS: $($files.Count) kotlin files, animations=$animCount, M3Motion usages=$tokenUsage"
}
exit 0
