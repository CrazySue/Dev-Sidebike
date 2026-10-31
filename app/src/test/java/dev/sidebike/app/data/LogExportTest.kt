package dev.sidebike.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.TrafficStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Tests for the 0.1.1-alpha log export feature (user issue 1): readable log text + ZIP debug bundle
 * must always be produced, include all required sections and redact header secrets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogExportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun redactRemovesHeaderSecretsButKeepsStructure() {
        val text = "GET https://x\nAuthorization: Bearer super-secret-token\nCookie: sid=abc123\nok"
        val redacted = LogExport.redact(text)
        assertFalse(redacted.contains("super-secret-token"))
        assertFalse(redacted.contains("sid=abc123"))
        assertTrue(redacted.contains("Authorization:"))
        assertTrue(redacted.contains(LogExportTest.REDACTED))
        assertTrue(redacted.contains("ok"))
    }

    @Test
    fun formatLogIncludesLevelTagAndThrowable() {
        val entry = LogEntry(
            timeMs = 1_700_000_000_000,
            level = LogLevel.ERROR,
            tag = "proxy",
            message = "boom",
            throwable = IllegalStateException("bad state"),
        )
        val text = LogExport.formatEntry(entry)
        assertTrue(text.contains("[ERROR]"))
        assertTrue(text.contains("proxy: boom"))
        assertTrue(text.contains("IllegalStateException: bad state"))
    }

    @Test
    fun buildLogTextContainsEnvironmentDiagnosticsAndLogs() {
        val deviceInfo = LogExport.buildDeviceInfo(context, sampleState(), SidebikeConfig())
        val logs = listOf(log(LogLevel.INFO, "controller", "started"), log(LogLevel.WARN, "vpn", "warn"))
        val text = LogExport.buildLogText(deviceInfo, logs, "=== 诊断信息 ===\nfake-diagnostics")

        assertTrue(text.contains("Dev-Sidebike 环境信息"))
        assertTrue(text.contains("Android:"))
        assertTrue(text.contains("应用: "))
        assertTrue(text.contains("=== 诊断信息 ==="))
        assertTrue(text.contains("fake-diagnostics"))
        assertTrue(text.contains("=== 运行日志 (2) ==="))
        assertTrue(text.contains("controller: started"))
        assertTrue(text.contains("vpn: warn"))
    }

    @Test
    fun buildLogTextHandlesEmptyLogsAndMissingDiagnostics() {
        val deviceInfo = LogExport.buildDeviceInfo(context, SidebikeUiState(), SidebikeConfig())
        val text = LogExport.buildLogText(deviceInfo, emptyList(), "")
        assertTrue(text.contains("=== 运行日志 (0) ==="))
        assertTrue(text.contains("暂无日志"))
    }

    @Test
    fun buildDebugBundleIsZipWithAllRequiredEntries() {
        val state = sampleState()
        val deviceInfo = LogExport.buildDeviceInfo(context, state, SidebikeConfig())
        val logs = listOf(log(LogLevel.INFO, "proxy", "Authorization: Bearer leak-me"))
        val bytes = LogExport.buildDebugBundle(
            deviceInfo = deviceInfo,
            diagnostics = "diag-content",
            logs = logs,
            configJson = "{\"version\":1}",
            stateText = LogExport.buildStateText(state, logs.size),
        )
        assertTrue("bundle must not be empty", bytes.isNotEmpty())

        val entries = readZip(bytes)
        val expected = setOf(
            "README.txt",
            "device-info.txt",
            "diagnostics.txt",
            "logs.txt",
            "config.json",
            "state.txt",
        )
        assertEquals(expected, entries.keys)
        assertTrue(entries.getValue("device-info.txt").contains("Dev-Sidebike 环境信息"))
        assertEquals("diag-content", entries.getValue("diagnostics.txt"))
        assertEquals("{\"version\":1}", entries.getValue("config.json"))
        assertTrue(entries.getValue("state.txt").contains("status=RUNNING"))
        // Secrets must be redacted inside the bundle too.
        assertFalse(entries.getValue("logs.txt").contains("leak-me"))
        assertTrue(entries.getValue("logs.txt").contains("[REDACTED]"))
    }

    @Test
    fun buildDebugBundleNeverThrowsOnLongOrWeirdContent() {
        val bytes = LogExport.buildDebugBundle(
            deviceInfo = "x".repeat(100_000),
            diagnostics = "\u0000\u0001weird",
            logs = listOf(log(LogLevel.DEBUG, "tag", "line\nwith\rnewlines")),
            configJson = "{}",
            stateText = "",
        )
        assertTrue(bytes.isNotEmpty())
        assertNotNull(readZip(bytes)["device-info.txt"])
    }

    @Test
    fun stateTextCarriesCoreFields() {
        val text = LogExport.buildStateText(sampleState(), 7)
        assertTrue(text.contains("status=RUNNING"))
        assertTrue(text.contains("vpnActive=true"))
        assertTrue(text.contains("effectiveProxyPort=31181"))
        assertTrue(text.contains("logEntries=7"))
    }

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun sampleState(): SidebikeUiState = SidebikeUiState(
        status = RunStatus.RUNNING,
        statusMessage = "运行中",
        vpnActive = true,
        proxyRunning = true,
        mitmActive = false,
        caInstalled = true,
        stats = TrafficStats(),
        uptimeMs = 1234,
    )

    private fun log(level: LogLevel, tag: String, message: String): LogEntry = LogEntry(
        timeMs = System.currentTimeMillis(),
        level = level,
        tag = tag,
        message = message,
    )

    private companion object {
        const val REDACTED = "[REDACTED]"
    }
}
