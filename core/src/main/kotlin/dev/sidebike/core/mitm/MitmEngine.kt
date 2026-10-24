package dev.sidebike.core.mitm

import dev.sidebike.core.api.RuleEngine
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.cert.CaStore
import dev.sidebike.core.cert.ServerTls
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

    /** CA PEM if it already exists (never generates). */
    fun caPemIfExists(): String? = caStore.peekPem()

    /** Loads or generates the CA and returns its PEM. */
    fun ensureCaPem(): String = caStore.ensure().caCertificatePem

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
        try {
            val tls = serverTls()
            ssl = TlsSupport.wrapServerSocket(tls.context, client, targetHost, targetPort)
            ssl.soTimeout = config.proxy.readTimeoutMs
            try {
                tls.keyManager.withFallbackHost(targetHost) { ssl.startHandshake() }
            } catch (e: SSLException) {
                // Most common cause: the client does not trust the local CA (expected when the user has not
                // installed it) or a non-TLS protocol was sent to an intercepted port. Nothing can be
                // answered inside a broken TLS channel; log and drop.
                logger.debug(TAG, "TLS handshake with client failed for $targetHost:$targetPort: ${e.message}")
                return MitmResult(false, 0, 0, "client TLS handshake failed: ${e.message}")
            }

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
            logger.debug(TAG, "MITM I/O error for $targetHost:$targetPort: ${e.message}")
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
