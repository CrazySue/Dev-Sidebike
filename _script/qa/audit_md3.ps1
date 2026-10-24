# MD3 严格合规静态审计（测试环境门禁）
# 规则：
#   1. 禁止 Material2 组件：app/src/main/java/dev/sidebike/app/ui/** 中出现 androidx.compose.material. 导入
#   2. 禁止硬编码颜色：ui/**（theme 目录除外）出现 Color(0x...)、Color.Red 等
#   3. 禁止线性动画：全工程 ui/** 出现 LinearEasing
#   4. 禁止自定义字体：ui/** 出现 FontFamily(
#   5. 必须使用 MD3 非线性动画 token：ui/motion/M3Motion.kt 必须存在且包含 cubic-bezier easing 常量
#   6. 必须覆盖状态变化动画：ui 源码中 AnimatedContent/AnimatedVisibility/animate*AsState/animateContentSize 总出现次数 >= 15
# 退出码：0 通过；1 存在违规
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike'
)
$ErrorActionPreference = 'Stop'
$uiDir = Join-Path $Root 'app\src\main\java\dev\sidebike\app\ui'
if (-not (Test-Path $uiDir)) {
    Write-Host "[audit_md3] FAIL: UI 目录不存在: $uiDir" -ForegroundColor Red
    exit 1
}
$violations = New-Object System.Collections.ArrayList
$files = Get-ChildItem $uiDir -Recurse -Filter '*.kt' -ErrorAction SilentlyContinue  # include all, some codes in ui
$themeDir = Join-Path $uiDir 'theme'

function Add-Violation([string]$rule, [string]$file, [int]$line, [string]$content) {
    [void]$violations.Add([pscustomobject]@{ Rule = $rule; File = $file; Line = $line; Content = $content.Trim() })
}

foreach ($f in $files) {
    $rel = $f.FullName.Substring($Root.Length + 1)
    $lines = Get-Content $f.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match 'import\s+androidx\.compose\.material\.') {
            Add-Violation 'M2_IMPORT' $rel ($i + 1) $line
        }
        if ($f.DirectoryName -ne $themeDir -and $line -match 'Color\(0x|Color\.(Red|Blue|Green|Yellow|Magenta|Cyan|Black|White)\b') {
            Add-Violation 'HARDCODED_COLOR' $rel ($i + 1) $line
        }
        if ($line -match 'LinearEasing') {
            Add-Violation 'LINEAR_EASING' $rel ($i + 1) $line
        }
        if ($line -match 'FontFamily\s*\(') {
            Add-Violation 'CUSTOM_FONT' $rel ($i + 1) $line
        }
    }
}

# 运动 token 文件检查
$motionFile = Join-Path $uiDir 'motion\M3Motion.kt'
if (-not (Test-Path $motionFile)) {
    Add-Violation 'MISSING_MOTION_TOKENS' 'app/src/main/java/dev/sidebike/app/ui/motion/M3Motion.kt' 0 'M3Motion.kt 缺失'
}
else {
    $motionContent = Get-Content $motionFile -Raw
    foreach ($needle in @('CubicBezierEasing', 'EmphasizedDecelerate', 'EmphasizedAccelerate', 'Standard')) {
        if ($motionContent -notmatch [regex]::Escape($needle)) {
            Add-Violation 'MOTION_TOKEN_MISSING' 'app/src/main/java/dev/sidebike/app/ui/motion/M3Motion.kt' 0 "缺少 $needle"
        }
    }
}

# 动画使用密度检查
$animCount = 0
foreach ($f in $files) {
    $c = Get-Content $f.FullName -Raw
    $animCount += ([regex]::Matches($c, 'AnimatedContent|AnimatedVisibility|AnimatedFloat|animateFloatAsState|animateIntAsState|animateColorAsState|animateDpAsState|animateContentSize|AnimatedNavHost|Crossfade')).Count
}
if ($animCount -lt 15) {
    Add-Violation 'INSUFFICIENT_MOTION' 'app/src/main/java/dev/sidebike/app/ui' 0 "动画使用次数=$animCount < 15"
}

if ($violations.Count -gt 0) {
    Write-Host "[audit_md3] FAIL: 发现 $($violations.Count) 处违规" -ForegroundColor Red
    $violations | Select-Object -First 40 | ForEach-Object { Write-Host ("  - [{0}] {1}:{2}  {3}" -f $_.Rule, $_.File, $_.Line, $_.Content) }
    exit 1
}
Write-Host "[audit_md3] PASS: 无 MD3 违规（扫描 $($files.Count) 个 Kotlin 文件，动画使用 $animCount 处）"
exit 0
