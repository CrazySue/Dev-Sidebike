package dev.sidebike.core.cert

import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TLS plumbing for the MITM server and the "trust-all / decoy-SNI" upstream connections.
 *
 * ### Server side (client -> us)
 * A single [SSLContext] backed by [SniCertificateKeyManager] issues the leaf certificate on demand based
 * on the SNI the client sent. The manager is an [X509ExtendedKeyManager] as required by the task: JSSE calls
 * `chooseEngineServerAlias` during the handshake and the requested server name is read from
 * `SSLEngine#getHandshakeSession()` ([ExtendedSSLSession#getRequestedServerNames]). A ThreadLocal fallback
 * (the CONNECT authority) is used when the client omits SNI (e.g. IP literal targets).
 *
 * ### Upstream side (us -> target)
 * The upstream certificate is intentionally not validated when `mitm.trustUpstreamAll` is true (upstream
 * default behaviour, see `mitmproxy/src/lib/interceptor/impl/req/sni.js:23-27` which switches every
 * decoy-SNI rule to `unVerifySsl`). Decoy SNI is set to the requested name:
 *  - JVM/Android `SSLSocketFactory.createSocket(Socket, host, port, autoClose)` derives the SNI from
 *    `host`; we pass the *decoy* host so no `SSLParameters` trickery is needed;
 *  - additionally `SSLParameters#setServerNames` is applied before `startHandshake()` for the explicit
 *    "spoofed SNI different from the real host" case. JVM honours it; on Android (Conscrypt) the
 *    `host` argument is the reliable switch, which is why both are set. See docs: Android's
 *    `SSLSocketFactory.createSocket` implementation sets SNI from the host parameter.
 */
object TlsSupport {

    /** HTTP/1.1 only: the MITM forwards HTTP/1.1, so h2 must never be negotiated with the upstream. */
    private const val ALPN_HTTP_1_1 = "http/1.1"

    /**
     * Upstream TLS versions. Upstream Dev-Sidecar pins TLSv1.3 only (`common/util.js:64-65`). The Android
     * port negotiates 1.3 with 1.2 fallback because several decoy-SNI / Cloudflare edge cases and older
     * Android 7-9 stacks still require TLS 1.2 (Lead note, docs/feasibility/01 §3.3). Server side accepts
     * 1.3 + 1.2 for the same reason.
     */
    val ENABLED_PROTOCOLS: Array<String> = arrayOf("TLSv1.3", "TLSv1.2")

    private val SECURE_RANDOM = SecureRandom()

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val defaultTrustManagers: Array<TrustManager> by lazy {
        try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers
        } catch (_: Exception) {
            arrayOf(trustAllManager)
        }
    }

    private val trustAllClientContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SECURE_RANDOM)
        }
    }

    private val defaultClientContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, defaultTrustManagers, SECURE_RANDOM)
        }
    }

    fun clientContext(trustAll: Boolean): SSLContext = if (trustAll) trustAllClientContext else defaultClientContext

    /** Builds the terminating server context; leaf certs are selected by [SniCertificateKeyManager]. */
    fun serverTls(ca: CertificateAuthority, fallbackHostProvider: () -> String?): ServerTls {
        val keyManager = SniCertificateKeyManager(ca, fallbackHostProvider)
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), null, SECURE_RANDOM)
        }
        return ServerTls(context, keyManager)
    }

    /**
     * Wraps an accepted plain socket into a server-mode [SSLSocket]. The caller must have already written
     * `HTTP/1.1 200 Connection Established` (and must not have consumed any bytes beyond the CONNECT head).
     * The CONNECT authority is passed as the session peer host and used as the certificate fallback when
     * the ClientHello carries no SNI.
     */
    fun wrapServerSocket(context: SSLContext, plainSocket: Socket, connectHost: String, connectPort: Int): SSLSocket {
        val sslSocket = context.socketFactory.createSocket(plainSocket, connectHost, connectPort, true) as SSLSocket
        sslSocket.useClientMode = false
        sslSocket.needClientAuth = false
        sslSocket.wantClientAuth = false
        val params = sslSocket.sslParameters
        params.protocols = filterProtocols(sslSocket.supportedProtocols)
        sslSocket.sslParameters = params
        return sslSocket
    }

    /**
     * Wraps a connected raw TCP socket into a client-mode [SSLSocket] for the upstream connection.
     *
     * @param targetHost real target host (used for the session peer host / certificate verification)
     * @param sni decoy SNI override; `null` keeps [targetHost], empty string disables the SNI extension
     * @param verifyUpstream when true enables hostname verification (only used when the config does not
     *        trust all upstream certificates *and* no SNI rewrite happened)
     */
    fun wrapClientSocket(
        rawSocket: Socket,
        targetHost: String,
        port: Int,
        sni: String?,
        verifyUpstream: Boolean,
    ): SSLSocket {
        // Pass the decoy host to createSocket: on JVM and Android the SNI is derived from this argument,
        // which is the portable way to spoof the upstream SNI.
        val peerHost = when {
            sni == null -> targetHost
            sni.isEmpty() -> targetHost // SNI explicitly disabled -> cleared below
            else -> sni
        }
        val context = clientContext(trustAll = !verifyUpstream)
        val sslSocket = context.socketFactory.createSocket(rawSocket, peerHost, port, true) as SSLSocket
        sslSocket.useClientMode = true
        val params = sslSocket.sslParameters
        params.protocols = filterProtocols(sslSocket.supportedProtocols)
        if (sni != null) {
            try {
                params.serverNames = if (sni.isEmpty()) emptyList() else listOf(SNIHostName(sni))
            } catch (_: IllegalArgumentException) {
                // Not a valid DNS name for SNI (e.g. IP literal decoy): keep the auto-derived name.
            } catch (_: UnsupportedOperationException) {
                // Android/Conscrypt without SSLParameters SNI support: the peerHost argument still applies.
            }
        }
        params.endpointIdentificationAlgorithm = if (verifyUpstream) "HTTPS" else null
        try {
            // JDK 9+ API (`SSLParameters#setApplicationProtocols(String[])`); Android API 24+ has it too.
            params.applicationProtocols = arrayOf(ALPN_HTTP_1_1)
        } catch (_: Throwable) {
            // setApplicationProtocols is API 24+ on Android and not implemented by all providers; servers
            // default to HTTP/1.1 when no ALPN is offered.
        }
        sslSocket.sslParameters = params
        return sslSocket
    }

    private fun filterProtocols(supported: Array<String>): Array<String> {
        val enabled = ENABLED_PROTOCOLS.filter { p -> supported.any { it.equals(p, ignoreCase = true) } }
        return if (enabled.isEmpty()) supported else enabled.toTypedArray()
    }
}

/** Server-side TLS bundle: SSLContext plus the SNI key manager (needed for the fallback-host scope). */
class ServerTls(val context: SSLContext, val keyManager: SniCertificateKeyManager)

/**
 * SNI-aware server key manager: issues/caches a leaf certificate per requested host.
 *
 * The alias format is `leaf:<host>`; [getCertificateChain]/[getPrivateKey] lazily issue the certificate, so
 * JSSE never needs a pre-populated KeyStore (the set of hosts is unbounded at runtime - exactly why
 * upstream uses a dynamic `SNICallback` around node-forge certs).
 */
class SniCertificateKeyManager(
    private val ca: CertificateAuthority,
    private val fallbackHostProvider: () -> String?,
) : X509ExtendedKeyManager() {

    private val threadFallbackHost = ThreadLocal<String?>()

    /** Runs [block] with an explicit certificate fallback host (the CONNECT authority). */
    fun <T> withFallbackHost(host: String?, block: () -> T): T {
        val previous = threadFallbackHost.get()
        threadFallbackHost.set(host)
        try {
            return block()
        } finally {
            if (previous == null) threadFallbackHost.remove() else threadFallbackHost.set(previous)
        }
    }

    private fun requestedSni(engine: SSLEngine?): String? {
        if (engine == null) return null
        return try {
            val session = engine.handshakeSession ?: return null
            (session as? ExtendedSSLSession)
                ?.requestedServerNames
                ?.filterIsInstance<SNIHostName>()
                ?.firstOrNull()
                ?.asciiName
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveAlias(host: String?): String? {
        if (host == null || !CertificateAuthority.isIssuableHost(host)) return null
        val alias = ALIAS_PREFIX + host
        // Issue eagerly (outside JSSE's getCertificateChain call) so handshake timing stays predictable and
        // certificate-generation failures surface as a clear handshake error instead of a null chain.
        ca.leaf(host)
        return alias
    }

    private fun hostOf(alias: String?): String? {
        if (alias == null || !alias.startsWith(ALIAS_PREFIX)) return null
        return alias.substring(ALIAS_PREFIX.length)
    }

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? {
        val sni = requestedSni(engine)
        val host = sni ?: threadFallbackHost.get() ?: fallbackHostProvider()
        return resolveAlias(host)
    }

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?): String? =
        resolveAlias(threadFallbackHost.get() ?: fallbackHostProvider())

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        val host = hostOf(alias) ?: return null
        return try {
            ca.leaf(host).chain
        } catch (_: Exception) {
            null
        }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        val host = hostOf(alias) ?: return null
        return try {
            ca.leaf(host).keyPair.private
        } catch (_: Exception) {
            null
        }
    }

    // ---- client-side API: unused by the MITM server ----
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: java.net.Socket?,
    ): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    companion object {
        private const val ALIAS_PREFIX = "leaf:"
    }
}
