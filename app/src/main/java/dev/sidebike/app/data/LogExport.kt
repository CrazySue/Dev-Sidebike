package dev.sidebike.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import dev.sidebike.app.BuildConfig
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeConfig
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Text/ZIP export helpers for the "导出日志" feature (user issue 1: clipboard-only export was not
 * enough). Everything is best-effort: export never throws and always returns something a user can
 * attach to a bug report.
 *
 * The bundle intentionally contains no secrets: Dev-Sidebike has no accounts/tokens, and the log
 * redaction step additionally removes `Authorization`/`Proxy-Authorization`/`Cookie` header values in
 * case a future interceptor logs request headers.
 */
object LogExport {

    private const val REDACTED = "[REDACTED]"

    /** Header-value patterns that must never leak into a user-shared log. */
    private val SECRET_PATTERNS: List<Regex> = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*)([^\\r\\n]+)"),
        Regex("(?i)(proxy-authorization\\s*[:=]\\s*)([^\\r\\n]+)"),
        Regex("(?i)(set-cookie\\s*[:=]\\s*)([^\\r\\n]+)"),
        Regex("(?i)(cookie\\s*[:=]\\s*)([^\\r\\n]+)"),
        Regex("(?i)(\\btoken\\s*[:=]\\s*)([A-Za-z0-9._~+/=-]{12,})"),
    )

    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Redacts header-style secrets from [text] (used for both .txt and .zip exports). */
    fun redact(text: String): String {
        var result = text
        for (pattern in SECRET_PATTERNS) {
            result = pattern.replace(result) { match -> "${match.groupValues[1]}$REDACTED" }
        }
        return result
    }

    /** Formats one [LogEntry] including a compact stack-trace (first few frames) when present. */
    fun formatEntry(entry: LogEntry): String = buildString {
        append(synchronized(logTimeFormat) { logTimeFormat.format(Date(entry.timeMs)) })
        append(" [").append(entry.level.name).append("] ")
        append(entry.tag).append(": ").append(redact(entry.message))
        entry.throwable?.let { throwable ->
            append("\n    ! ").append(throwable.javaClass.name).append(": ").append(throwable.message)
            throwable.stackTrace.take(6).forEach { frame -> append("\n      at ").append(frame) }
        }
    }

    /** Formats a log list (deduplicated by timestamp+tag+message, preserving order). */
    fun formatLogs(entries: List<LogEntry>): String {
        val seen = HashSet<String>(entries.size * 2)
        val output = StringBuilder()
        for (entry in entries.sortedBy { it.timeMs }) {
            val key = "${entry.timeMs}|${entry.tag}|${entry.message}"
            if (!seen.add(key)) continue
            output.append(formatEntry(entry)).append('\n')
        }
        return output.toString()
    }

    /**
     * Human-readable environment/device snapshot used as the header of [buildLogText] and as
     * `device-info.txt` in [buildDebugBundle].
     */
    fun buildDeviceInfo(
        context: Context,
        state: SidebikeUiState,
        config: SidebikeConfig,
        extraEnvironmentLines: List<String> = emptyList(),
    ): String = buildString {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        appendLine("Dev-Sidebike 环境信息")
        appendLine("导出时间: ${formatDateTime(System.currentTimeMillis())}")
        appendLine("应用: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) debug=${BuildConfig.DEBUG}")
        packageInfo?.lastUpdateTime?.let { appendLine("安装/更新时间: ${formatDateTime(it)}") }
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine(
            "设备: ${Build.MANUFACTURER} ${Build.MODEL} (brand=${Build.BRAND}, device=${Build.DEVICE}, " +
                "product=${Build.PRODUCT})",
        )
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("本地化: ${Locale.getDefault()} 时区: ${TimeZone.getDefault().id}")
        appendLine("网络: ${describeActiveNetwork(context)}")
        appendLine("VPN 已授权(prepare==null): ${runCatching { VpnService.prepare(context) == null }.getOrDefault(false)}")
        appendLine("通知权限: ${NotificationPermission.isGranted(context)}")
        appendLine("应用数据目录: ${context.filesDir.absolutePath}")
        appendLine("CA 证书文件: ${describeCaFiles(context, config)}")
        appendLine()
        appendLine("=== 运行状态 ===")
        appendLine("status=${state.status} message=${state.statusMessage}")
        appendLine("lastError=${state.lastError}")
        appendLine(
            "vpnActive=${state.vpnActive} proxyRunning=${state.proxyRunning} mitmActive=${state.mitmActive} " +
                "caInstalled=${state.caInstalled}",
        )
        appendLine(
            "effectiveProxyPort=${state.effectiveProxyPort} systemProxyRegistered=${state.systemProxyRegistered} " +
                "privateDnsActive=${state.privateDnsActive} lockdownDetected=${state.lockdownDetected}",
        )
        appendLine("uptimeMs=${state.uptimeMs}")
        appendLine("stats=${state.stats}")
        appendLine(
            "config: interceptDomains=${config.intercepts.size} " +
                "interceptRules=${config.intercepts.values.sumOf { it.size }} " +
                "whiteList=${config.whiteList.size} domestic=${config.domesticAllowList.size} " +
                "dnsProviders=${config.dns.providers.size} ruleSources=${config.ruleSources.size} " +
                "disabledIntercepts=${config.disabledIntercepts.size}",
        )
        if (extraEnvironmentLines.isNotEmpty()) {
            appendLine()
            extraEnvironmentLines.forEach { appendLine(it) }
        }
    }

    /** Builds the readable `.log`/`.txt` export: environment header + diagnostics + all logs. */
    fun buildLogText(
        deviceInfo: String,
        logs: List<LogEntry>,
        diagnostics: String,
    ): String = buildString {
        appendLine(redact(deviceInfo))
        appendLine()
        appendLine("=== 诊断信息 ===")
        appendLine(redact(diagnostics))
        appendLine()
        appendLine("=== 运行日志 (${logs.size}) ===")
        append(redact(formatLogs(logs)))
        if (logs.isEmpty()) appendLine("（暂无日志）")
    }

    /**
     * Builds a `.zip` debug bundle with:
     *   device-info.txt / diagnostics.txt / logs.txt / config.json / state.txt
     *
     * Never throws: on encoding errors it returns the partial ZIP written so far (possibly empty).
     */
    fun buildDebugBundle(
        deviceInfo: String,
        diagnostics: String,
        logs: List<LogEntry>,
        configJson: String,
        stateText: String,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        return runCatching {
            ZipOutputStream(output).use { zip ->
                zip.putTextEntry("README.txt", BUILD_BUNDLE_README)
                zip.putTextEntry("device-info.txt", deviceInfo)
                zip.putTextEntry("diagnostics.txt", diagnostics)
                zip.putTextEntry("logs.txt", formatLogs(logs))
                zip.putTextEntry("config.json", configJson)
                zip.putTextEntry("state.txt", stateText)
            }
            output.toByteArray()
        }.getOrElse { error ->
            android.util.Log.e("LogExport", "Failed to build debug bundle", error)
            output.toByteArray()
        }
    }

    /** Machine/reader-friendly state snapshot for `state.txt`. */
    fun buildStateText(state: SidebikeUiState, logCount: Int): String = buildString {
        appendLine("status=${state.status}")
        appendLine("statusMessage=${state.statusMessage}")
        appendLine("lastError=${state.lastError}")
        appendLine("vpnActive=${state.vpnActive}")
        appendLine("proxyRunning=${state.proxyRunning}")
        appendLine("mitmActive=${state.mitmActive}")
        appendLine("caInstalled=${state.caInstalled}")
        appendLine("effectiveProxyPort=${state.effectiveProxyPort}")
        appendLine("systemProxyRegistered=${state.systemProxyRegistered}")
        appendLine("privateDnsActive=${state.privateDnsActive}")
        appendLine("lockdownDetected=${state.lockdownDetected}")
        appendLine("uptimeMs=${state.uptimeMs}")
        appendLine("stats=${state.stats}")
        appendLine("logEntries=$logCount")
        appendLine("nowMs=${System.currentTimeMillis()}")
        appendLine("now=${formatDateTime(System.currentTimeMillis())}")
    }

    fun formatDateTime(epochMs: Long): String = synchronized(logTimeFormat) {
        logTimeFormat.format(Date(epochMs))
    }

    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name).apply { time = System.currentTimeMillis() })
        write(redact(content).toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun describeActiveNetwork(context: Context): String = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "不可用"
        val network = manager.activeNetwork ?: return "无活动网络"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "未知"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val downKbps = capabilities.linkDownstreamBandwidthKbps
        "transports=${transports.joinToString("+").ifEmpty { "UNKNOWN" }} downKbps=$downKbps"
    }.getOrDefault("读取失败")

    private fun describeCaFiles(context: Context, config: SidebikeConfig): String = runCatching {
        val cert = java.io.File(context.filesDir, config.mitm.caCertFileName)
        val key = java.io.File(context.filesDir, config.mitm.caKeyFileName)
        "cert=${cert.name} exists=${cert.isFile} size=${if (cert.isFile) cert.length() else 0}, " +
            "key=${key.name} exists=${key.isFile}"
    }.getOrDefault("读取失败")

    /** Permission helper reused by the device info section (avoids hard dependency in tests). */
    @Suppress("unused")
    private fun notificationGranted(context: Context): Boolean = NotificationPermission.isGranted(context)

    private const val BUILD_BUNDLE_README =
        "Dev-Sidebike 调试包\n" +
            "=================\n" +
            "此压缩包由 Dev-Sidebike「一键诊断」生成，包含：\n" +
            "  device-info.txt  设备/系统/运行状态\n" +
            "  diagnostics.txt  控制器诊断信息（配置、状态、统计、日志）\n" +
            "  logs.txt         运行日志（Authorization/Cookie 等敏感头已脱敏）\n" +
            "  config.json      当前生效配置\n" +
            "  state.txt        UI 状态快照\n" +
            "无账号/密码等隐私数据；如仍介意可先检查内容再发送。\n"

    init {
        // Android's SimpleDateFormat is not thread-safe; keep exports serialized.
        logTimeFormat.timeZone = TimeZone.getDefault()
    }
}
