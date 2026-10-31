package dev.sidebike.app.data

import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.core.api.DnsProviderHealth
import dev.sidebike.core.api.EngineStats
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.api.TrafficStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the network diagnostics report (0.1.1-alpha): the report must be readable,
 * classify DNS/direct-TLS/proxy checks independently and never throw, even when probes fail.
 */
class NetworkDiagnosticsTest {

    @Test
    fun healthyReportContainsAllSectionsAndSuccessMarks() = runBlocking {
        val runner = NetworkDiagnosticsRunner(
            config = SidebikeConfig(),
            state = runningState(),
            engine = FakeEngine(),
            probes = HealthyProbes(),
        )
        val report = runner.run()

        assertTrue(report.contains("=== Dev-Sidebike 网络诊断 ==="))
        assertTrue(report.contains("[1/5]"))
        assertTrue(report.contains("[2/5]"))
        assertTrue(report.contains("[5/5]"))
        assertTrue(report.contains("=== 结论 ==="))
        assertTrue(report.contains("✅ aliyun"))
        assertTrue(report.contains("✅ github.com -> 20.205.243.166"))
        assertTrue(report.contains("✅ github.com:443"))
        assertTrue(report.contains("✅ CONNECT github.com:443"))
        assertTrue(report.contains("✅ GET http://example.com/"))
        assertTrue(report.contains("DNS 引擎: ✅ 可用"))
        assertTrue(report.contains("直连 TLS: ✅ 可用"))
        assertTrue(report.contains("本地代理: ✅ 运行中"))
    }

    @Test
    fun failingProbesProduceActionableFailureMarksWithoutThrowing() = runBlocking {
        val runner = NetworkDiagnosticsRunner(
            config = SidebikeConfig(),
            state = runningState(),
            engine = FakeEngine(),
            probes = FailingProbes(),
        )
        val report = runner.run()

        assertTrue(report.contains("❌"))
        assertTrue(report.contains("DNS 引擎: ❌ 不可用"))
        assertTrue(report.contains("直连 TLS: ❌ 不可用"))
        assertTrue(report.contains("解析异常") || report.contains("解析失败"))
    }

    @Test
    fun missingEngineIsReportedNotCrashed() = runBlocking {
        val runner = NetworkDiagnosticsRunner(
            config = SidebikeConfig(),
            state = SidebikeUiState(status = RunStatus.IDLE),
            engine = null,
            probes = HealthyProbes(),
        )
        val report = runner.run()
        assertTrue(report.contains("引擎未创建"))
        assertTrue(report.contains("❌ 引擎不可用，跳过"))
    }

    @Test
    fun mitmWithoutCaAndPrivateDnsProduceWarnings() = runBlocking {
        val state = runningState().copy(
            mitmActive = true,
            caInstalled = false,
            privateDnsActive = true,
            effectiveProxyPort = 0,
        )
        val runner = NetworkDiagnosticsRunner(
            config = SidebikeConfig(proxy = SidebikeConfig().proxy.copy(enabled = false)),
            state = state,
            engine = FakeEngine(),
            probes = HealthyProbes(),
        )
        val report = runner.run()
        assertTrue(report.contains("MITM 已开启但 CA 未安装"))
        assertTrue(report.contains("Private DNS 开启"))
        assertTrue(report.contains("代理端口无效"))
    }

    @Test
    fun reportIncludesTunDropCountersIncludingIpv6() = runBlocking {
        dev.sidebike.app.vpn.VpnRuntimeStats.reset()
        try {
            dev.sidebike.app.vpn.VpnRuntimeStats.recordIgnored(dev.sidebike.app.vpn.VpnRuntimeStats.IPV6_REASON_PREFIX)
            dev.sidebike.app.vpn.VpnRuntimeStats.recordIgnored(dev.sidebike.app.vpn.VpnRuntimeStats.IPV6_REASON_PREFIX)
            dev.sidebike.app.vpn.VpnRuntimeStats.recordIgnored("not-udp(proto=6)")
            dev.sidebike.app.vpn.VpnRuntimeStats.recordDnsQuery()

            val runner = NetworkDiagnosticsRunner(
                config = SidebikeConfig(),
                state = runningState(),
                engine = FakeEngine(),
                probes = HealthyProbes(),
            )
            val report = runner.run()
            assertTrue("IPv6 drop counter expected in report", report.contains("tun 丢弃包: IPv6=2"))
            assertTrue(report.contains("其他=1"))
            assertTrue(report.contains("DNS 回复=1"))
            assertTrue(report.contains("not-udp(proto=6)"))
        } finally {
            dev.sidebike.app.vpn.VpnRuntimeStats.reset()
        }
    }

    private fun runningState(): SidebikeUiState = SidebikeUiState(
        status = RunStatus.RUNNING,
        statusMessage = "运行中",
        vpnActive = true,
        proxyRunning = true,
        caInstalled = true,
        effectiveProxyPort = 31181,
        stats = TrafficStats(),
    )

    private class HealthyProbes : ConnectivityProbes {
        override suspend fun dnsHealth(engine: SidebikeEngine): List<DnsProviderHealth> = listOf(
            DnsProviderHealth(provider = "aliyun", ok = true, latencyMs = 12),
            DnsProviderHealth(provider = "doh.pub", ok = false, latencyMs = 0, error = "timeout"),
        )

        override suspend fun resolveIps(engine: SidebikeEngine, host: String, timeoutMs: Long): List<String> =
            if (host == "github.com") listOf("20.205.243.166", "20.205.243.165") else listOf("93.184.216.34")

        override fun directTls(host: String, port: Int, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(true, 210, "TLS TLSv1.3 TLS_AES_256_GCM_SHA384 peer=CN=github.com")

        override fun proxyConnect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(true, 30, "HTTP/1.1 200 Connection established")

        override fun proxyHttpGet(proxyPort: Int, url: String, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(true, 25, "HTTP/1.1 200 OK")
    }

    private class FailingProbes : ConnectivityProbes {
        override suspend fun dnsHealth(engine: SidebikeEngine): List<DnsProviderHealth> =
            throw IllegalStateException("dns health exploded")

        override suspend fun resolveIps(engine: SidebikeEngine, host: String, timeoutMs: Long): List<String> =
            throw IllegalStateException("resolve exploded")

        override fun directTls(host: String, port: Int, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(false, 4000, "失败: timeout")

        override fun proxyConnect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(false, 4000, "失败: connection refused")

        override fun proxyHttpGet(proxyPort: Int, url: String, timeoutMs: Int): ProbeOutcome =
            ProbeOutcome(false, 4000, "失败: connection refused")
    }

    private class FakeEngine : SidebikeEngine {
        override fun start() = Unit
        override fun stop() = Unit
        override val isRunning: Boolean = true
        override fun updateConfig(config: SidebikeConfig) = Unit
        override fun currentConfig(): SidebikeConfig = SidebikeConfig()
        override suspend fun resolveRawDnsQuery(query: ByteArray): ByteArray? = null
        override fun stats(): EngineStats = EngineStats(proxyRunning = true)
        override fun logger(): SidebikeLogger = NoopLogger
        override fun caCertificatePem(): String? = null
        override fun ensureCaCertificate(): String? = null
        override fun close() = Unit
    }

    private object NoopLogger : SidebikeLogger {
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) = Unit
        override fun history(): List<LogEntry> = emptyList()
        override fun clear() = Unit
        override fun subscribe(listener: (LogEntry) -> Unit): AutoCloseable = AutoCloseable { }
    }
}
