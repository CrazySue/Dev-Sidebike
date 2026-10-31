package dev.sidebike.core.mitm

import dev.sidebike.core.api.RuleEngine
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.cert.CaStore
import dev.sidebike.core.cert.ServerTls
import dev.sidebike.core.cert.SniCapture
import dev.sidebike.core.cert.TlsSupport
import dev.sidebike.core.proxy.HttpForwarder
import dev.sidebike.core.proxy.HttpResponses
import dev.sidebike.core.util.Clock
import dev.sidebike.core.util.CountingInputStream
import dev.sidebike.core.util.CountingOutputStream
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.NetUtils
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * TLS-terminating MITM engine.
 *
 * Design notes (parity with upstream `mitmproxy/src/lib/proxy/mitmproxy/createConnectHandler.js` +
 * `FakeServersCenter`):
 *  - the upstream implementation forwards the client socket to a per-host fake TLS server listening on a
 *    random loopback port; we terminate TLS in-process with a single [javax.net.ssl.SSLContext] whose
 *    [dev.sidebike.core.cert.SniCertificateKeyManager] issues the leaf certificate for the requested SNI.
 *    That removes one TCP hop/thread pair per CONNECT while keeping the same observable behaviour.
 *  - HTTP/1.1 only: the upstream fake server is HTTP/2 capable (`allowHTTP1: true`); Android beta scope is
 *    HTTP/1.1 (see task non-goals). Clients offering h2 via ALPN simply fall back to 1.1 because the
 *    server context never negotiates h2.
 *  - after the handshake exactly one HTTP request is served, then the TLS connection is closed
 *    (`Connection: close`). Upstream keeps the decrypted connection alive for keep-alive; the huge majority
 *    of real-world traffic works with one request per CONNECT and correctness wins over the extra handshake
 *    (explicitly allowed by the task).
 */
class MitmEngine(
    private val configProvider: () -> SidebikeConfig,
    private val caStore: CaStore,
    private val forwarder: HttpForwarder,
    private val ruleEngine: RuleEngine,
    private val logger: SidebikeLogger,
    private val clock: Clock,
) {

    data class MitmResult(
        val success: Boolean,
        val bytesUp: Long,
        val bytesDown: Long,
        val error: String?,
        val status: Int? = null,
    )

    private val contextLock = Any()

    @Volatile
    private var serverTls: ServerTls? = null

    @Volatile
    private var caFailureLogged: Boolean = false

    /** CA PEM if it already exists (never generates). */
    fun caPemIfExists(): String? = caStore.peekPem()

    /** Loads or generates the CA and returns its PEM. */
    fun ensureCaPem(): String = caStore.ensure().caCertificatePem

    /**
     * Preflight for the proxy: can we actually terminate TLS right now?
     *
     * When MITM is enabled but the CA file is missing/unreadable and cannot be (re)generated (e.g. the app
     * private dir is not writable), MITM must NOT be attempted per connection: the proxy degrades every
     * affected host to a direct tunnel instead of breaking all TLS with a handshake the client cannot verify.
     * Never throws; logs the root cause once.
     */
    fun ensureReady(): Boolean {
        return try {
            serverTls()
            true
        } catch (e: Throwable) {
            if (!caFailureLogged) {
                caFailureLogged = true
                logger.error(
                    TAG,
                    "MITM 不可用（CA 缺失/无法生成），已降级为直连隧道: ${e.message ?: e.javaClass.simpleName}",
                    e,
                )
            }
            false
        }
    }

    /** True when the CA material is available and the TLS server context can be built (idempotent). */
    fun isReady(): Boolean = ensureReady()

    private fun serverTls(): ServerTls = serverTls ?: synchronized(contextLock) {
        serverTls ?: TlsSupport.serverTls(caStore.ensure()) { null }.also { serverTls = it }
    }

    /**
     * Handles one CONNECT whose MITM decision was already made by the proxy.
     * The caller must not have written the `200 Connection Established` response yet and must not have
     * buffered any bytes past the CONNECT request head.
     */
    fun handleConnect(client: Socket, targetHost: String, targetPort: Int): MitmResult {
        val config = configProvider()
        val startedAt = clock.currentTimeMillis()
        val clientOut = client.getOutputStream()
        clientOut.write("HTTP/1.1 200 Connection Established\r\nProxy-agent: dev-sidecar\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        clientOut.flush()

        var ssl: SSLSocket? = null
        var handshakeCompleted = false
        try {
            val tls = serverTls()
            // Observe the ClientHello SNI through an SNIMatcher (portable on JVM + Android); the key manager
            // issues the leaf for the SNI name and falls back to the CONNECT authority when no SNI is sent.
            val sniCapture = SniCapture()
            ssl = TlsSupport.wrapServerSocket(tls.context, client, targetHost, targetPort, sniCapture)
            ssl.soTimeout = config.proxy.readTimeoutMs
            try {
                tls.keyManager.withHandshakeContext(targetHost, sniCapture) { ssl.startHandshake() }
                handshakeCompleted = true
            } catch (e: SSLException) {
                // Most common causes: the client app does not trust the locally generated CA (expected when
                // the user has not installed it - Android 7+ apps only trust user CAs when their network
                // security config allows it), certificate pinning, or a non-TLS protocol sent to an
                // intercepted port. Nothing can be answered inside a broken TLS channel: log a concise
                // diagnosis (at WARN so users can find it) and drop the connection cleanly.
                val message = e.message ?: e.javaClass.simpleName
                val looksLikeNonTls = message.contains("unrecognized", ignoreCase = true) ||
                    message.contains("plaintext", ignoreCase = true) ||
                    message.contains("not an SSL", ignoreCase = true)
                if (looksLikeNonTls) {
                    logger.debug(TAG, "客户端在 MITM 端口发送了非 TLS 数据: $targetHost:$targetPort: $message")
                } else {
                    logger.warn(
                        TAG,
                        "MITM TLS 握手失败: $targetHost:$targetPort，可能原因：应用不信任用户 CA（请在系统设置中" +
                            "安装本机 CA）或启用了证书固定（certificate pinning）; 错误: $message",
                    )
                }
                return MitmResult(false, 0, 0, "client TLS handshake failed: $message")
            }
            logger.debug(TAG, "MITM TLS established: connect=$targetHost, sni=${sniCapture.sni ?: "(none)"}")

            val countingIn = CountingInputStream(ssl.inputStream)
            val countingOut = CountingOutputStream(ssl.outputStream)
            val request = HttpCodec.readRequestHead(countingIn)
                ?: return MitmResult(false, 0, 0, "client closed before sending a request")

            val hostHeader = request.headers.get("Host")
            val fromHeader = hostHeader?.let { NetUtils.parseAuthority(it, targetPort) }
            val host = fromHeader?.host ?: targetHost
            val port = fromHeader?.port ?: targetPort
            val path = NetUtils.normalizePathAndQuery(request.target)

            val plan = try {
                ruleEngine.plan(host, port, path)
            } catch (e: Exception) {
                logger.error(TAG, "rule engine failed for $host$path: ${e.message}", e)
                HttpResponses.writeError(countingOut, request, 500, "规则引擎异常: ${e.message}", "https://$host:$port$path")
                return MitmResult(false, countingIn.count, countingOut.count, "rule engine error: ${e.message}", 500)
            }

            val outcome = forwarder.forward(request, "https", host, port, plan, countingIn, countingOut)
            val cost = clock.currentTimeMillis() - startedAt
            logger.debug(TAG, "MITM ${request.method} https://$host:$port$path -> ${outcome.upstreamStatus} (${cost}ms)")
            return MitmResult(
                success = outcome.error == null,
                bytesUp = countingIn.count,
                bytesDown = countingOut.count,
                error = outcome.error,
                status = outcome.upstreamStatus,
            )
        } catch (e: SocketTimeoutException) {
            logger.debug(TAG, "MITM idle timeout for $targetHost:$targetPort")
            return MitmResult(false, 0, 0, "idle timeout: ${e.message}")
        } catch (e: IOException) {
            if (!handshakeCompleted) {
                // A reset before the handshake finished is almost always the client aborting because it does
                // not trust our CA (or a non-TLS peer); surface the same concise diagnosis as the SSL path so
                // users can find the CA-install hint in the log.
                val message = e.message ?: e.javaClass.simpleName
                if (message.contains("reset", ignoreCase = true) ||
                    message.contains("terminated", ignoreCase = true) ||
                    message.contains("handshake", ignoreCase = true)
                ) {
                    logger.warn(
                        TAG,
                        "MITM TLS 握手中断: $targetHost:$targetPort，可能原因：应用不信任用户 CA（请在系统设置中" +
                            "安装本机 CA）或启用了证书固定; 错误: $message",
                    )
                } else {
                    logger.debug(TAG, "MITM handshake I/O error for $targetHost:$targetPort: $message")
                }
            } else {
                logger.debug(TAG, "MITM I/O error for $targetHost:$targetPort: ${e.message}")
            }
            return MitmResult(false, 0, 0, e.message)
        } finally {
            try {
                ssl?.close()
            } catch (_: Throwable) {
                // ignored
            }
        }
    }

    companion object {
        private const val TAG = "mitm"
    }
}
