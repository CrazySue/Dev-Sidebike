package dev.sidebike.app.qa

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.ComposeTestBase
import dev.sidebike.app.ui.preview.PreviewData
import dev.sidebike.app.ui.screens.CertGuideScreen
import dev.sidebike.app.ui.screens.HomeScreen
import dev.sidebike.app.ui.screens.LogsScreen
import dev.sidebike.app.ui.screens.RulesScreen
import dev.sidebike.app.ui.screens.SettingsScreen
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * QA Robolectric screenshot capture: renders every screen/state to PNG under
 * `docs/qa/screenshots/` WITHOUT a device, so a human can inspect layout/clipping/MD3 compliance
 * and diffs are reviewable in the repo. Uses the same fixed 411x891dp viewport as the UI tests.
 *
 * OPT-IN: Robolectric native-graphics capture is flaky on Windows CI (ComposeTimeoutException while
 * waiting for idle when animations are paused) and must never block the default `testDebugUnitTest`
 * suite. Run explicitly with `-Ddsh.qa.screenshots=true` (or use the emulator screenshots under
 * docs/qa/screenshots/ which are captured from a real Android 15 system image).
 */
private fun assumeScreenshotsEnabled() {
    val enabled = System.getenv("DSH_QA_SCREENSHOTS") == "1" ||
        System.getProperty("dsh.qa.screenshots") == "true"
    Assume.assumeTrue(
        "Robolectric screenshot capture is opt-in: run _script/qa/run_screenshots.ps1 " +
            "(sets DSH_QA_SCREENSHOTS=1). Device screenshots live in docs/qa/screenshots/.",
        enabled,
    )
}
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotQaTest : ComposeTestBase() {

    private val noPadding = PaddingValues(0.dp)

    @Test
    fun captureAllScreensLight() {
        assumeScreenshotsEnabled()
        capture("home_running_light") {
            HomeScreen(
                state = PreviewData.state(),
                recentLogs = PreviewData.logs.takeLast(3),
                contentPadding = noPadding,
                onStart = {}, onStop = {}, onOpenRules = {}, onOpenLogs = {},
                onOpenCertGuide = {}, onToggleMitm = {}, onRefreshCa = {},
            )
        }
        capture("home_idle_light") {
            HomeScreen(
                state = PreviewData.emptyState,
                recentLogs = emptyList(),
                contentPadding = noPadding,
                onStart = {}, onStop = {}, onOpenRules = {}, onOpenLogs = {},
                onOpenCertGuide = {}, onToggleMitm = {}, onRefreshCa = {},
            )
        }
        capture("home_error_light") {
            HomeScreen(
                state = PreviewData.state(
                    status = RunStatus.ERROR,
                    lastError = "VPN 启动失败：代理端口 31181 被占用",
                ),
                recentLogs = PreviewData.logs.takeLast(3),
                contentPadding = noPadding,
                onStart = {}, onStop = {}, onOpenRules = {}, onOpenLogs = {},
                onOpenCertGuide = {}, onToggleMitm = {}, onRefreshCa = {},
            )
        }
        capture("home_warnings_light") {
            HomeScreen(
                state = PreviewData.state(
                    privateDnsActive = true,
                    lockdownDetected = true,
                    systemProxyRegistered = false,
                ),
                recentLogs = PreviewData.logs.takeLast(3),
                contentPadding = noPadding,
                onStart = {}, onStop = {}, onOpenRules = {}, onOpenLogs = {},
                onOpenCertGuide = {}, onToggleMitm = {}, onRefreshCa = {},
            )
        }
        capture("rules_light") {
            RulesScreen(config = PreviewData.config, contentPadding = noPadding, onConfigChange = {})
        }
        capture("logs_light") {
            LogsScreen(logs = PreviewData.logs, contentPadding = noPadding, onClearLogs = {})
        }
        capture("settings_light") {
            SettingsScreen(
                config = PreviewData.config,
                caInstalled = true,
                contentPadding = noPadding,
                onConfigChange = {}, onOpenCertGuide = {}, onRefreshCaInstalled = {}, onExportCa = {},
            )
        }
        capture("cert_guide_light") {
            CertGuideScreen(
                state = PreviewData.state(status = RunStatus.IDLE, caInstalled = false),
                contentPadding = noPadding,
                onInstallCa = {}, onExportCa = {}, onRefreshCaInstalled = {}, onOpenSettings = {},
            )
        }
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        setScreen { content() }
        settle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = screenshotDir()
        assertTrue("cannot create screenshot dir: $dir", dir.isDirectory || dir.mkdirs())
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            assertTrue("PNG encode failed for $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        assertTrue("screenshot too small: $file", file.length() > 5_000)
        println("[screenshot] ${file.absolutePath} (${file.length()} bytes, ${bitmap.width}x${bitmap.height})")
    }

    private fun screenshotDir(): File {
        val candidates = listOf(
            File("../docs/qa/screenshots"),
            File("../../docs/qa/screenshots"),
            File("docs/qa/screenshots"),
            File("E:/WorkSpace/Dev-Sidebike/docs/qa/screenshots"),
        )
        for (c in candidates) {
            val parent = c.parentFile
            if (c.exists() || (parent != null && parent.exists())) return c
        }
        return File("E:/WorkSpace/Dev-Sidebike/docs/qa/screenshots")
    }
}

/** Dark-theme variant: same capture set (MD3 dark palette + dynamic-color off). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
class ScreenshotQaTestDark : ComposeTestBase() {

    private val noPadding = PaddingValues(0.dp)

    @Test
    fun captureKeyScreensDark() {
        assumeScreenshotsEnabled()
        capture("home_running_dark") {
            HomeScreen(
                state = PreviewData.state(),
                recentLogs = PreviewData.logs.takeLast(3),
                contentPadding = noPadding,
                onStart = {}, onStop = {}, onOpenRules = {}, onOpenLogs = {},
                onOpenCertGuide = {}, onToggleMitm = {}, onRefreshCa = {},
            )
        }
        capture("home_idle_dark") {
            HomeScreen(
                state = PreviewData.emptyState,
                recentLogs = emptyList(),
                contentPadding = noPadding,
                onStart = {}, onStop = {}, onOpenRules = {}, onOpenLogs = {},
                onOpenCertGuide = {}, onToggleMitm = {}, onRefreshCa = {},
            )
        }
        capture("settings_dark") {
            SettingsScreen(
                config = PreviewData.config,
                caInstalled = true,
                contentPadding = noPadding,
                onConfigChange = {}, onOpenCertGuide = {}, onRefreshCaInstalled = {}, onExportCa = {},
            )
        }
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        setScreen { content() }
        settle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = screenshotDir()
        assertTrue(dir.isDirectory || dir.mkdirs())
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        assertTrue(file.length() > 5_000)
        println("[screenshot] ${file.absolutePath} (${file.length()} bytes)")
    }

    private fun screenshotDir(): File {
        for (c in listOf(File("../docs/qa/screenshots"), File("../../docs/qa/screenshots"), File("E:/WorkSpace/Dev-Sidebike/docs/qa/screenshots"))) {
            val parent = c.parentFile
            if (c.exists() || (parent != null && parent.exists())) return c
        }
        return File("E:/WorkSpace/Dev-Sidebike/docs/qa/screenshots")
    }
}
