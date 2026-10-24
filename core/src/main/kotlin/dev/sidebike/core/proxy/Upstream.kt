package dev.sidebike.core.proxy

import dev.sidebike.core.api.DnsResolver
import dev.sidebike.core.api.RoutePlan
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.cert.TlsSupport
import dev.sidebike.core.util.NetUtils
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A live upstream connection (plain TCP or TLS); streams are de-framed HTTP body streams' raw source. */
class UpstreamConnection(val socket: Socket, val secure: Boolean) : Closeable {
    val input: InputStream get() = socket.getInputStream()
    val output: OutputStream get() = socket.getOutputStream()

    override fun close() = NetUtils.closeQuietly(socket)
}

/** Establishes the upstream leg of a proxied request. */
interface UpstreamConnector {
    /**
     * Connects to [plan] target.
     * @param tls wrap the TCP connection in a TLS client socket (HTTPS request)
     * @param sni upstream SNI override; null = derive from target host, "" = do not send SNI
     */
    fun connect(plan: RoutePlan, tls: Boolean, sni: String?): UpstreamConnection
}

/**
 * Upstream connector with the same layered resolution order as upstream Dev-Sidecar's
 * `dnsLookup.createLookupFunc` (mitmproxy/src/lib/proxy/mitmproxy/dnsLookup.js):
 * preset IP list -> configured DnsResolver -> system resolver fallback. The IP is then dialled directly,
 * which is the whole point of the built-in anti-poisoning DNS layer (the runtime has no system DNS hook on
 * Android).
 */
class DefaultUpstreamConnector(
    private val configProvider: () -> SidebikeConfig,
    private val dnsResolver: DnsResolver,
    private val presetIpProvider: (String) -> List<String>,
    private val logger: SidebikeLogger,
) : UpstreamConnector {

    override fun connect(plan: RoutePlan, tls: Boolean, sni: String?): UpstreamConnection {
        val config = configProvider()
        val host = plan.connectHost
        val port = plan.connectPort
        if (port !in 1..65535) throw IOException("invalid upstream port $port for '$host'")

        val dialTarget = resolveDialTarget(host, config)
        val rawSocket = NetUtils.connectTcp(dialTarget, port, config.proxy.connectTimeoutMs)
        try {
            rawSocket.soTimeout = config.proxy.readTimeoutMs
            if (!tls) return UpstreamConnection(rawSocket, secure = false)

            val verifyUpstream = !config.mitm.trustUpstreamAll && sni == null
            val sslSocket = TlsSupport.wrapClientSocket(rawSocket, host, port, sni, verifyUpstream)
            sslSocket.soTimeout = config.proxy.readTimeoutMs
            try {
                sslSocket.startHandshake()
            } catch (e: IOException) {
                NetUtils.closeQuietly(sslSocket)
                throw IOException("upstream TLS handshake failed for $host:$port (sni=${sni ?: host}): ${e.message}", e)
            }
            return UpstreamConnection(sslSocket, secure = true)
        } catch (e: IOException) {
            NetUtils.closeQuietly(rawSocket)
            throw e
        }
    }

    private fun resolveDialTarget(host: String, config: SidebikeConfig): String {
        if (NetUtils.isIpLiteral(host)) return host
        // 1) preset IP list wins (upstream `DNSOverPreSetIpList`).
        val presets = try {
            presetIpProvider(host)
        } catch (e: Exception) {
            logger.debug(TAG, "presetIp lookup failed for $host: ${e.message}")
            emptyList()
        }
        if (presets.isNotEmpty()) return presets.first()
        // 2) configured DnsResolver (anti-poisoning UDP/DoH/DoT providers, cached by the resolver).
        val resolved = try {
            runBlocking {
                withTimeout(config.dns.queryTimeoutMs.toLong().coerceAtLeast(1L)) { dnsResolver.resolve(host) }
            }.ips.firstOrNull()
        } catch (e: Exception) {
            logger.debug(TAG, "dns resolve failed for $host, falling back to system resolver: ${e.message}")
            null
        }
        // 3) system resolver fallback: InetSocketAddress(host) resolves at connect time.
        return resolved ?: host
    }

    companion object {
        private const val TAG = "proxy.upstream"
    }
}
