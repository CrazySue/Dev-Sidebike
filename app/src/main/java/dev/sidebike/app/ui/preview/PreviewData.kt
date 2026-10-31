package dev.sidebike.app.ui.preview

import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.MitmSettings
import dev.sidebike.core.api.ProxySettings
import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.TrafficStats
import kotlinx.serialization.json.JsonPrimitive

/**
 * Preview / Robolectric 测试共用的示例数据（无 Android 依赖，不会访问网络）。
 * `@Preview` 使用它渲染真实布局，测试用它构造空/满/错误等状态。
 */
object PreviewData {

    val config: SidebikeConfig = SidebikeConfig(
        proxy = ProxySettings(mitmEnabled = true),
        mitm = MitmSettings(enabled = true),
        intercepts = linkedMapOf(
            "github.com" to linkedMapOf(
                "/" to InterceptAction(proxy = "github.moeyy.xyz:443", desc = "GitHub 镜像代理"),
                "/api/*" to InterceptAction(success = JsonPrimitive(true)),
            ),
            "raw.githubusercontent.com" to linkedMapOf(
                "/" to InterceptAction(
                    proxy = "raw.gitmirror.com:443",
                    sni = "baidu.com",
                    cacheDays = 7,
                    desc = "Raw 文件加速",
                ),
            ),
            "api.github.com" to linkedMapOf(
                "/" to InterceptAction(script = "console.log('injected')", desc = "API 注入脚本"),
            ),
            "www.gstatic.com" to linkedMapOf(
                "/" to InterceptAction(proxy = "gstatic.loli.net:443", status = 200),
            ),
        ),
        whiteList = mapOf(
            "raw.githubusercontent.com" to true,
            "*.internal.example.com" to true,
        ),
        domesticAllowList = listOf("example.cn", "baidu.com", "qq.com", "taobao.com"),
        disabledIntercepts = listOf("www.gstatic.com\t/"),
        ruleSources = listOf(
            RuleSource(
                id = "johnshall-cnip",
                name = "国内外分流（推荐）",
                url = "https://raw.githubusercontent.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever/release/sr_cnip.conf",
                format = RuleSourceFormat.AUTO,
                enabled = true,
                autoUpdate = true,
                updateIntervalHours = 24,
                lastUpdatedMs = System.currentTimeMillis() - 3_600_000,
                lastImported = 1237,
            ),
            RuleSource(
                id = "custom-example",
                name = "自定义规则源",
                url = "https://example.com/rules.list",
                format = RuleSourceFormat.SHADOWROCKET_LIST,
                enabled = false,
                autoUpdate = false,
                updateIntervalHours = 0,
                lastError = "连接超时",
            ),
        ),
    )

    val stats: TrafficStats = TrafficStats(
        totalConnections = 128,
        activeConnections = 6,
        successfulConnections = 120,
        failedConnections = 8,
        bytesUp = 1_234_567,
        bytesDown = 9_876_543,
        dnsQueries = 4_821,
        dnsCacheHits = 3_102,
        dnsFailures = 3,
        uptimeMs = 3_724_000,
        proxyPort = 31181,
        dnsAvgLatencyMs = 23,
    )

    val logs: List<LogEntry> = listOf(
        LogEntry(
            timeMs = System.currentTimeMillis() - 42_000,
            level = LogLevel.INFO,
            tag = "engine",
            message = "SidebikeEngine 已启动：DNS 监听 10.111.0.2:53，HTTP 代理监听 127.0.0.1:31181",
        ),
        LogEntry(
            timeMs = System.currentTimeMillis() - 30_000,
            level = LogLevel.DEBUG,
            tag = "dns",
            message = "resolve github.com -> 20.205.243.166 (provider=aliyun, 18ms)",
        ),
        LogEntry(
            timeMs = System.currentTimeMillis() - 18_000,
            level = LogLevel.INFO,
            tag = "proxy",
            message = "CONNECT api.github.com:443 -> MIRROR_PROXY github.moeyy.xyz:443",
        ),
        LogEntry(
            timeMs = System.currentTimeMillis() - 9_000,
            level = LogLevel.WARN,
            tag = "dns",
            message = "provider doh.pub 超时，已切换到 aliyun",
        ),
        LogEntry(
            timeMs = System.currentTimeMillis() - 3_000,
            level = LogLevel.ERROR,
            tag = "mitm",
            message = "TLS 握手失败：客户端未信任用户 CA（www.gstatic.com）",
        ),
    )

    fun state(
        status: RunStatus = RunStatus.RUNNING,
        lastError: String? = null,
        caInstalled: Boolean = true,
        config: SidebikeConfig = this.config,
        logs: List<LogEntry> = this.logs,
        privateDnsActive: Boolean = false,
        lockdownDetected: Boolean = false,
        systemProxyRegistered: Boolean? = if (status == RunStatus.RUNNING) true else null,
        effectiveProxyPort: Int = config.proxy.port,
        ruleSourcesBusy: Boolean = false,
        diagnosticsRunning: Boolean = false,
    ): SidebikeUiState = SidebikeUiState(
        status = status,
        statusMessage = "",
        vpnActive = status == RunStatus.RUNNING,
        proxyRunning = status == RunStatus.RUNNING,
        mitmActive = status == RunStatus.RUNNING && (config.proxy.mitmEnabled || config.mitm.enabled),
        caInstalled = caInstalled,
        config = config,
        stats = when (status) {
            RunStatus.RUNNING -> this.stats.copy(proxyPort = effectiveProxyPort)
            RunStatus.ERROR -> this.stats.copy(activeConnections = 0)
            else -> TrafficStats()
        },
        recentLogs = logs.takeLast(3),
        lastError = lastError,
        uptimeMs = if (status == RunStatus.RUNNING) this.stats.uptimeMs else 0L,
        systemProxyRegistered = systemProxyRegistered,
        privateDnsActive = privateDnsActive,
        lockdownDetected = lockdownDetected,
        effectiveProxyPort = effectiveProxyPort,
        ruleSourcesBusy = ruleSourcesBusy,
        diagnosticsRunning = diagnosticsRunning,
    )

    /** 空数据（新安装 / 占位状态）——所有屏幕必须能安全渲染。 */
    val emptyConfig: SidebikeConfig = SidebikeConfig()

    val emptyState: SidebikeUiState = SidebikeUiState(status = RunStatus.IDLE, config = emptyConfig)
}
