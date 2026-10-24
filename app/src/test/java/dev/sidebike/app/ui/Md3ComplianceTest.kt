package dev.sidebike.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 静态 MD3 审计（自动化的「QA 静态审计脚本期望」，与 docs/design/03-motion-spec.md 对应）：
 * 1. UI 源码禁止 Material 2 import（只允许 material3 / material icons）
 * 2. 禁止任何线性缓动（LinearEasing）
 * 3. theme 文件之外禁止硬编码颜色（Color(0x...)）
 * 4. 禁止自定义字体（FontFamily(...)）
 * 5. 每个屏幕都必须出现在 @Preview 中（浅色+深色由 repeatable @Preview 提供）
 * 6. 主题必须显式使用 MD3 typography / shape token
 */
class Md3ComplianceTest {

    private fun uiRoot(): File {
        val candidates = listOf(
            File("src/main/java/dev/sidebike/app/ui"),
            File("app/src/main/java/dev/sidebike/app/ui"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 UI 源码目录，cwd=${File(".").absolutePath}")
    }

    private fun uiFiles(): List<File> =
        uiRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun appMainRoot(): File {
        val candidates = listOf(
            File("src/main/java/dev/sidebike/app"),
            File("app/src/main/java/dev/sidebike/app"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 app 源码目录，cwd=${File(".").absolutePath}")
    }

    @Test
    fun uiSourcesUseOnlyMaterial3Imports() {
        val allowedPrefixes = listOf(
            "androidx.compose.material3.",
            "androidx.compose.material.icons.",
        )
        uiFiles().forEach { file ->
            file.readLines()
                .filter { it.trimStart().startsWith("import ") }
                .forEach { line ->
                    if (line.contains("androidx.compose.material.")) {
                        assertTrue(
                            "${file.name}: 不允许 Material 2 import -> $line",
                            allowedPrefixes.any { line.contains(it) },
                        )
                    }
                }
        }
    }

    @Test
    fun mainActivityUsesOnlyMaterial3() {
        val files = listOf(
            File(appMainRoot(), "MainActivity.kt"),
            File(appMainRoot(), "ui/SidebikeRoot.kt"),
        ).filter { it.isFile }
        assertTrue("MainActivity/SidebikeRoot 应该存在", files.isNotEmpty())
        files.forEach { file ->
            file.readLines()
                .filter { it.trimStart().startsWith("import ") && it.contains("androidx.compose.material.") }
                .forEach { line ->
                    assertTrue(
                        "${file.name}: 不允许 Material 2 import -> $line",
                        line.contains("androidx.compose.material3.") || line.contains("androidx.compose.material.icons."),
                    )
                }
        }
    }

    @Test
    fun noLinearEasingAnywhere() {
        uiFiles().forEach { file ->
            assertFalse(
                "${file.name}: 禁止线性缓动，必须使用 MD3 motion token",
                file.readText().contains("LinearEasing"),
            )
        }
    }

    @Test
    fun noHardcodedColorsOutsideThemeFiles() {
        uiFiles().filterNot { it.path.contains("ui/theme") || it.path.contains("ui\\theme") }.forEach { file ->
            val text = file.readText()
            assertFalse(
                "${file.name}: theme 文件之外禁止硬编码颜色（请使用 MaterialTheme.colorScheme）",
                text.contains("Color(0x"),
            )
        }
    }

    @Test
    fun noCustomFonts() {
        uiFiles().forEach { file ->
            assertFalse(
                "${file.name}: 严格 MD3 禁止自定义字体（使用默认 Roboto/系统字体）",
                file.readText().contains("FontFamily("),
            )
        }
    }

    @Test
    fun everyScreenHasLightAndDarkPreviews() {
        val previewFile = File(uiRoot(), "preview/ScreenPreviews.kt")
        assertTrue("ScreenPreviews.kt 必须存在", previewFile.isFile)
        val text = previewFile.readText()
        listOf(
            "HomeScreen(",
            "RulesScreen(",
            "LogsScreen(",
            "SettingsScreen(",
            "CertGuideScreen(",
            "SidebikeAppContent(",
        ).forEach { screen ->
            assertTrue("$screen 缺少 @Preview", text.contains(screen))
        }
        val previewCount = Regex("@Preview\\(").findAll(text).count()
        assertTrue("每个屏幕需浅色+深色，@Preview 数量应 >= 10（实际 $previewCount）", previewCount >= 10)
        assertTrue("必须提供深色模式 preview（UI_MODE_NIGHT_YES）", text.contains("UI_MODE_NIGHT_YES"))
    }

    @Test
    fun themeUsesMd3TypographyShapeAndColorTokens() {
        val theme = File(uiRoot(), "theme/Theme.kt").readText()
        assertTrue(theme.contains("typography = SidebikeTypography"))
        assertTrue(theme.contains("shapes = SidebikeShapes"))
        assertTrue(theme.contains("dynamicLightColorScheme"))
        assertTrue(theme.contains("dynamicDarkColorScheme"))
        assertTrue(theme.contains("isSystemInDarkTheme"))

        val type = File(uiRoot(), "theme/Type.kt").readText()
        assertTrue("Type.kt 必须提供 MD3 Typography token", type.contains("Typography()"))

        val shape = File(uiRoot(), "theme/Shape.kt").readText()
        assertTrue("Shape.kt 必须提供 MD3 shape scale", shape.contains("Shapes("))
        assertTrue(shape.contains("extraLarge"))
        assertTrue(shape.contains("RoundedCornerShape(28.dp)"))
    }

    @Test
    fun motionSpecFileDocumentsEventToAnimationMapping() {
        val candidates = listOf(
            File("../docs/design/03-motion-spec.md"),
            File("docs/design/03-motion-spec.md"),
            File("../docs/design/03-motion-spec.md"),
        )
        val spec = candidates.firstOrNull { it.isFile }
        assertTrue("docs/design/03-motion-spec.md 必须存在（事件 -> 动画 -> easing -> duration 表）", spec != null)
        val text = spec!!.readText()
        assertTrue("motion spec 必须列出 easing token", text.contains("EmphasizedDecelerate"))
        assertTrue("motion spec 必须列出 duration token", text.contains("Medium2"))
    }
}
