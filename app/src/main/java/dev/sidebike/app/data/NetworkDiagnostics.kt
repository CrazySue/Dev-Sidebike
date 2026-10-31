package dev.sidebike.app.data

import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.vpn.DnsProbe
import dev.sidebike.core.api.DnsProviderHealth
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Result of a single low-level connectivity probe. */
internal data class ProbeOutcome(
    val ok: Boolean,
    val latencyMs: Long,
    val detail: String,
)

/**
 * Pluggable low-level probes so the diagnostics report can be unit tested without any network.
 * The production implementation is [JavaConnectivityProbes].
 */
internal interface ConnectivityProbes {
    suspend fun dnsHealth(engine: SidebikeEngine): List<DnsProviderHealth>
    suspend fun resolveIps(engine: SidebikeEngine, host: String, timeoutMs: Long): List<String>
    fun directTls(host: String, port: Int, timeoutMs: Int): ProbeOutcome
    fun proxyConnect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): ProbeOutcome
    fun proxyHttpGet(proxyPort: Int, url: String, timeoutMs: Int): ProbeOutcome
}

/**
 * Formats the "网络诊断" report (user issue 2: MITM on/off both broke foreign HTTPS — the report has
 * to answer "DNS? direct TLS? proxy? system integration?" one by one).
 *
 * All checks are best-effort and never throw; each one reports ✅/❌ plus a short human reason. The
 * report is printed in Chinese because it is surfaced verbatim in the UI and shared with support.
 */
internal class NetworkDiagnosticsRunner(
    private val config: SidebikeConfig,
    private val state: SidebikeUiState,
    private val engine: SidebikeEngine?,
    private val probes: ConnectivityProbes,
) {

    suspend fun run(): String {
        val report = StringBuilder()
        val activeEngine = engine
        report.appendLine("=== Dev-Sidebike 网络诊断 ===")
        report.appendLine("时间: ${LogExport.formatDateTime(System.currentTimeMillis())}")
        report.appendLine(
            "环境: 状态=${state.status} VPN=${if (state.vpnActive) "开" else "关"} " +
                "代理端口=${state.effectiveProxyPort}(运行=${state.proxyRunning}) " +
                "MITM=${if (state.mitmActive) "开" else "关"} CA=${if (state.caInstalled) "已安装" else "未安装"}",
        )
        report.appendLine()

        // 1) Engine / DNS provider health ---------------------------------------------------
        report.appendLine("[1/5] 引擎与 DNS Provider 健康")
        if (activeEngine == null) {
            report.appendLine("  ❌ 引擎未创建（请先启动一次 VPN 或检查引擎安装）")
        } else {
            report.appendLine("  engine.isRunning=${runCatching { activeEngine.isRunning }.getOrDefault(false)}")
            val health = try {
                probes.dnsHealth(activeEngine)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report.appendLine("  ❌ dnsHealth() 失败: ${error.message ?: error.javaClass.simpleName}")
                emptyList()
            }
            if (health.isEmpty()) {
                report.appendLine("  ⚠️ 无 DNS Provider 健康数据")
            } else {
                health.forEach { item ->
                    val mark = if (item.ok) "✅" else "❌"
                    val latency = if (item.latencyMs > 0) " ${item.latencyMs}ms" else ""
                    val error = item.error?.let { " ($it)" }.orEmpty()
                    report.appendLine("  $mark ${item.provider}$latency$error")
                }
            }
        }
        report.appendLine()

        // 2) DNS resolution through the engine's anti-poisoning path -------------------------
        report.appendLine("[2/5] DNS 解析（引擎防污染通道）")
        var dnsOk = false
        if (activeEngine == null) {
            report.appendLine("  ❌ 引擎不可用，跳过")
        } else {
            for (host in listOf("github.com", "example.com")) {
                val ips = try {
                    probes.resolveIps(activeEngine, host, RESOLVE_TIMEOUT_MS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    report.appendLine("  ❌ $host 解析异常: ${error.message ?: error.javaClass.simpleName}")
                    emptyList()
                }
                if (ips.isEmpty()) {
                    report.appendLine("  ❌ $host 解析失败（超时或空结果）")
                } else {
                    dnsOk = true
                    report.appendLine("  ✅ $host -> ${ips.joinToString(", ")}")
                }
            }
        }
        report.appendLine()

        // 3) Direct TCP+TLS (bypasses proxy) ------------------------------------------------
        report.appendLine("[3/5] 直连 TLS（绕过代理，判断网络/TLS 是否可达）")
        val direct = probes.directTls("github.com", 443, SOCKET_TIMEOUT_MS)
        report.appendLine("  ${mark(direct)} github.com:443 ${direct.detail} (${direct.latencyMs}ms)")
        report.appendLine()

        // 4) Local HTTP proxy (the path apps use when the system proxy is registered) --------
        report.appendLine("[4/5] 本地 HTTP 代理（127.0.0.1:${state.effectiveProxyPort}）")
        val proxyPort = state.effectiveProxyPort
        if (proxyPort <= 0) {
            report.appendLine("  ❌ 代理端口无效（proxy.enabled=${config.proxy.enabled}）")
        } else {
            val connect = probes.proxyConnect(proxyPort, "github.com", 443, SOCKET_TIMEOUT_MS)
            report.appendLine("  ${mark(connect)} CONNECT github.com:443 ${connect.detail} (${connect.latencyMs}ms)")
            val get = probes.proxyHttpGet(proxyPort, "http://example.com/", SOCKET_TIMEOUT_MS)
            report.appendLine("  ${mark(get)} GET http://example.com/ ${get.detail} (${get.latencyMs}ms)")
        }
        report.appendLine()

        // 5) System integration ------------------------------------------------------------
        report.appendLine("[5/5] 系统集成")
        report.appendLine("  VPN 状态: ${state.status} ${state.statusMessage}")
        report.appendLine(
            "  系统代理已注册: ${state.systemProxyRegistered ?: if (android.os.Build.VERSION.SDK_INT < 29) "不支持(API<29)" else "未知"}",
        )
        report.appendLine("  Private DNS: ${if (state.privateDnsActive) "开启（会绕过 VPN DNS）" else "关闭/自动"}")
        report.appendLine("  Lockdown: ${if (state.lockdownDetected) "开启（拆分 DNS-only 会断网）" else "关闭"}")
        report.appendLine("  本地代理 MITM: ${config.mitm.enabled} CA 已安装: ${state.caInstalled}")
        val tunStats = dev.sidebike.app.vpn.VpnRuntimeStats.snapshot()
        report.appendLine(
            "  tun 丢弃包: IPv6=${tunStats.ipv6Dropped}（本版本不接管 IPv6，属预期）, " +
                "其他=${tunStats.otherDropped}, DNS 回复=${tunStats.dnsQueries}, " +
                "最近忽略原因=${tunStats.lastIgnoredReason ?: "无"}",
        )
        report.appendLine()

        // Summary --------------------------------------------------------------------------
        report.appendLine("=== 结论 ===")
        report.appendLine("  DNS 引擎: ${if (dnsOk) "✅ 可用" else "❌ 不可用（开启 VPN 会导致全设备 DNS 失败）"}")
        report.appendLine("  直连 TLS: ${if (direct.ok) "✅ 可用" else "❌ 不可用（网络/GFW/TLS 层问题）"}")
        report.appendLine(
            "  本地代理: ${if (state.proxyRunning) "✅ 运行中" else "❌ 未运行（应用设置的系统代理会连接失败）"}",
        )
        if (state.mitmActive && !state.caInstalled) {
            report.appendLine("  ⚠️ MITM 已开启但 CA 未安装：境外 HTTPS 会出现证书错误")
        }
        if (state.privateDnsActive) {
            report.appendLine("  ⚠️ Private DNS 开启会绕过 VPN DNS，请在系统设置中关闭或设为自动")
        }
        report.appendLine("  提示: 将本报告与「导出日志」一并反馈，可快速定位 DNS/代理/MITM 问题。")
        return report.toString()
    }

    private fun mark(outcome: ProbeOutcome): String = if (outcome.ok) "✅" else "❌"

    companion object {
        private const val SOCKET_TIMEOUT_MS = 4_000
        private const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}

/**
 * Production probes: real sockets. Each probe is bounded (timeouts) and reports failures as
 * [ProbeOutcome] instead of exceptions.
 */
internal class JavaConnectivityProbes : ConnectivityProbes {

    override suspend fun dnsHealth(engine: SidebikeEngine): List<DnsProviderHealth> =
        try {
            engine.dnsHealth()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }

    override suspend fun resolveIps(engine: SidebikeEngine, host: String, timeoutMs: Long): List<String> =
        try {
            val response = withTimeoutOrNull(timeoutMs) {
                engine.resolveRawDnsQuery(DnsProbe.buildQuery(host))
            }
            DnsProbe.extractIpv4Answers(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        }

    override fun directTls(host: String, port: Int, timeoutMs: Int): ProbeOutcome {
        val startedAt = System.currentTimeMillis()
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                raw.soTimeout = timeoutMs
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
                ssl.use { socket ->
                    socket.soTimeout = timeoutMs
                    socket.startHandshake()
                    val session = socket.session
                    val peer = session.peerCertificates.firstOrNull()
                    val peerInfo = if (peer is java.security.cert.X509Certificate) {
                        "peer=${peer.subjectX500Principal.name}"
                    } else {
                        "peer=${peer?.type ?: "unknown"}"
                    }
                    ProbeOutcome(
                        ok = true,
                        latencyMs = System.currentTimeMillis() - startedAt,
                        detail = "TLS ${session.protocol} ${session.cipherSuite} $peerInfo",
                    )
                }
            }
        } catch (error: Throwable) {
            ProbeOutcome(
                ok = false,
                latencyMs = System.currentTimeMillis() - startedAt,
                detail = "失败: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    override fun proxyConnect(proxyPort: Int, host: String, port: Int, timeoutMs: Int): ProbeOutcome {
        val startedAt = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, proxyPort), timeoutMs)
                socket.soTimeout = timeoutMs
                val request = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val statusLine = readLine(socket, timeoutMs)
                val ok = statusLine?.contains(" 200") == true
                ProbeOutcome(
                    ok = ok,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    detail = statusLine ?: "无响应",
                )
            }
        } catch (error: Throwable) {
            ProbeOutcome(false, System.currentTimeMillis() - startedAt, "失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun proxyHttpGet(proxyPort: Int, url: String, timeoutMs: Int): ProbeOutcome {
        val startedAt = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, proxyPort), timeoutMs)
                socket.soTimeout = timeoutMs
                val uri = java.net.URI(url)
                val request = "GET $url HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n" +
                    "User-Agent: Dev-Sidebike-Diagnostics\r\n\r\n"
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val statusLine = readLine(socket, timeoutMs)
                val ok = statusLine?.contains(" 200") == true || statusLine?.contains(" 204") == true ||
                    statusLine?.contains(" 301") == true || statusLine?.contains(" 302") == true ||
                    statusLine?.contains(" 304") == true
                ProbeOutcome(
                    ok = ok,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    detail = statusLine ?: "无响应",
                )
            }
        } catch (error: Throwable) {
            ProbeOutcome(false, System.currentTimeMillis() - startedAt, "失败: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun readLine(socket: Socket, timeoutMs: Int): String? = runCatching {
        socket.soTimeout = timeoutMs
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        reader.readLine()
    }.getOrNull()

    private companion object {
        private const val LOOPBACK = "127.0.0.1"
    }
}
