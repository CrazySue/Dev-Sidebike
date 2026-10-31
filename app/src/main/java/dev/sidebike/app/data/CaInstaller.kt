package dev.sidebike.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.security.KeyChain
import android.util.Log
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.StringReader
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * CA installation + detection for the optional MITM feature.
 *
 * Android has no privileged way for an app to install a user CA: the platform flow is
 * `KeyChain.createInstallIntent()` which opens the system "install certificate" UI and requires the
 * user to confirm (plus device PIN). This helper builds that intent from the engine's CA PEM and
 * detects afterwards whether the user actually trusted the certificate.
 *
 * Detection uses two independent probes:
 *  1. Enumerating the `AndroidCAStore` KeyStore for a certificate byte-identical to our CA (cheap
 *     and precise, requires the platform CA store provider).
 *  2. A loopback TLS self-test: a throw-away leaf certificate is signed with the engine CA key,
 *     served from `127.0.0.1`, and the connection is validated with the app's *default* TLS trust
 *     manager. The default trust manager honours the app's network security config, which we extend
 *     with the `user` certificate source — so the handshake only succeeds when the user did install
 *     and trust our CA. This catches false positives where the certificate is present but disabled.
 *
 * Everything is defensive: on any failure detection reports "not installed" instead of throwing, and
 * the MITM feature stays opt-in.
 */
object CaInstaller {

    private const val TAG = "CaInstaller"
    private const val LOOPBACK = "127.0.0.1"
    private const val SELF_TEST_ALIAS = "sidebike-ca-selftest"
    private const val SELF_TEST_PASSWORD = "sidebike-selftest"
    private const val HANDSHAKE_TIMEOUT_MS = 4_000

    /** Display name shown by the system certificate installer UI. */
    const val DEFAULT_DISPLAY_NAME: String = "Dev-Sidebike CA"

    /** Result of [isCaInstalled]; [method] is useful for diagnostics. */
    data class Detection(val installed: Boolean, val method: String)

    /**
     * Converts a PEM certificate to DER bytes (X.509), the format expected by
     * [KeyChain.EXTRA_CERTIFICATE]. Returns null when the PEM is unparsable.
     */
    fun pemToDer(pem: String): ByteArray? = parseCertificate(pem)?.encoded

    /** Parses the first X.509 certificate from [pem]; returns null when it cannot be parsed. */
    fun parseCertificate(pem: String): X509Certificate? = runCatching {
        val base64 = extractPemBody(pem, "CERTIFICATE") ?: return null
        val der = java.util.Base64.getMimeDecoder().decode(base64)
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }.getOrNull()

    /**
     * Builds the system CA-install intent for [caPem].
     *
     * The caller must launch the returned intent from an Activity (the platform install UI needs a
     * foreground context); [launchInstall] does that for convenience from a non-UI caller.
     * Returns null when the PEM is invalid or the platform refuses to create the intent.
     */
    fun createInstallIntent(caPem: String, displayName: String = DEFAULT_DISPLAY_NAME): Intent? {
        val der = pemToDer(caPem) ?: run {
            Log.w(TAG, "Cannot build install intent: invalid CA PEM")
            return null
        }
        return runCatching {
            KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_CERTIFICATE, der)
                putExtra(KeyChain.EXTRA_NAME, displayName)
            }
        }.getOrElse { error ->
            Log.w(TAG, "KeyChain.createInstallIntent failed", error)
            null
        }
    }

    /** Launches the system CA install UI (adds NEW_TASK so it also works from the VPN service). */
    fun launchInstall(context: Context, caPem: String): Boolean {
        val intent = createInstallIntent(caPem) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Unable to launch CA install intent", it) }
            .isSuccess
    }

    /**
     * Detects whether the user CA store already trusts our CA.
     *
     * @param caCertPem CA certificate PEM (from the engine).
     * @param caKeyPem optional CA private-key PEM; when provided the loopback TLS self-test runs as
     *   a stronger second probe. Without it only the CA-store enumeration is used.
     */
    fun isCaInstalled(caCertPem: String, caKeyPem: String?): Detection {
        val cert = parseCertificate(caCertPem) ?: return Detection(false, "invalid-pem")
        if (isInPlatformCaStore(cert)) return Detection(true, "android-ca-store")
        if (caKeyPem != null && tlsSelfTest(caCertPem, caKeyPem)) return Detection(true, "tls-self-test")
        return Detection(false, "not-found")
    }

    /**
     * Data required to drive the system CA install flow (0.1.1-alpha):
     * the KeyChain install Intent, a settings fallback Intent, the PEM and display-friendly
     * certificate identity (SHA-256 fingerprint + subject DN).
     */
    data class InstallBundle(
        val intent: Intent,
        val settingsFallbackIntent: Intent,
        val pem: String,
        val sha256Fingerprint: String,
        val subject: String,
    )

    /** Uppercase SHA-256 fingerprint in colon-separated hex (e.g. `AB:CD:…`), as shown by Android. */
    fun sha256Fingerprint(cert: X509Certificate): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        digest.joinToString(":") { byte -> String.format("%02X", byte.toInt() and 0xFF) }
    }.getOrDefault("")

    /** Certificate subject DN (e.g. `CN=Dev-Sidebike CA, O=Dev-Sidebike`). */
    fun subjectDn(cert: X509Certificate): String =
        runCatching { cert.subjectX500Principal.name }.getOrDefault("")

    /**
     * Fallback entry point for the system CA installer. `KeyChain.createInstallIntent()` can be
     * intercepted/hidden by some OEM ROMs (observed: black dialog then "未安装该证书"); in that case
     * the UI offers this intent to open the system "加密与凭据" settings page so the user can import
     * the exported certificate manually.
     */
    fun buildSettingsFallbackIntent(context: Context): Intent {
        val candidates = listOf(
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent("android.settings.PRIVACY_SETTINGS"),
        )
        for (candidate in candidates) {
            val resolvable = runCatching {
                context.packageManager.resolveActivity(candidate, PackageManager.MATCH_DEFAULT_ONLY) != null
            }.getOrDefault(false)
            if (resolvable) return candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Builds everything needed by `SidebikeController.prepareCaInstall()`.
     *
     * Always usable regardless of the MITM toggle: the CA is generated by the engine
     * (`ensureCaCertificate()`) before this is called; this method only validates/serializes it.
     * Returns null when the PEM is invalid or the platform refuses to create the install intent.
     */
    fun buildInstallBundle(
        context: Context,
        caPem: String,
        displayName: String = DEFAULT_DISPLAY_NAME,
    ): InstallBundle? {
        val cert = parseCertificate(caPem) ?: run {
            Log.w(TAG, "Cannot build install bundle: invalid CA PEM")
            return null
        }
        val intent = createInstallIntent(caPem, displayName) ?: return null
        return InstallBundle(
            intent = intent,
            settingsFallbackIntent = buildSettingsFallbackIntent(context),
            pem = caPem,
            sha256Fingerprint = sha256Fingerprint(cert),
            subject = subjectDn(cert),
        )
    }

    /**
     * Detection with a short retry window: after returning from the system installer the CA store is
     * not always updated synchronously, which produced the user-visible false negative
     * "未安装该证书". Used by `refreshCaInstalled()`.
     */
    fun detectWithRetry(
        caCertPem: String,
        caKeyPem: String?,
        attempts: Int = 2,
        delayMs: Long = 600L,
    ): Detection {
        var last = Detection(false, "not-found")
        repeat(attempts.coerceAtLeast(1)) { index ->
            last = isCaInstalled(caCertPem, caKeyPem)
            if (last.installed) return last
            if (index < attempts - 1) {
                runCatching { Thread.sleep(delayMs) }
            }
        }
        return last
    }

    /** Enumerates `AndroidCAStore` (system + user CAs) looking for a byte-identical certificate. */
    internal fun isInPlatformCaStore(cert: X509Certificate): Boolean = runCatching {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val aliases = store.aliases()
        val expected = cert.encoded
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val candidate = runCatching { store.getCertificate(alias) as? X509Certificate }.getOrNull()
            if (candidate != null && candidate.encoded.contentEquals(expected)) return true
        }
        false
    }.getOrElse { error ->
        Log.d(TAG, "AndroidCAStore unavailable: ${error.message}")
        false
    }

    /**
     * Loopback TLS self-test. Returns true only when a TLS client using the app's default trust
     * manager accepts a leaf certificate signed by our CA (which implies the CA is trusted).
     *
     * @param trustContextFactory test seam: supplies the client SSLContext; production passes the
     *   platform default (which honours the network security config).
     */
    internal fun tlsSelfTest(
        caCertPem: String,
        caKeyPem: String,
        trustContextFactory: () -> SSLContext = ::defaultTrustContext,
    ): Boolean {
        val caCert = parseCertificate(caCertPem) ?: return false
        val caKey = parsePrivateKey(caKeyPem) ?: run {
            Log.d(TAG, "CA private key unparsable; TLS self-test skipped")
            return false
        }
        val leaf = createSelfTestLeaf(caCert, caKey) ?: run {
            Log.d(TAG, "Could not create self-test leaf certificate")
            return false
        }

        return runCatching {
            val password = SELF_TEST_PASSWORD.toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(SELF_TEST_ALIAS, caKey, password, arrayOf(leaf, caCert))
            }
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val serverContext = SSLContext.getInstance("TLS").apply {
                init(keyManagerFactory.keyManagers, null, null)
            }
            val serverSocket = serverContext.serverSocketFactory
                .createServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) as SSLServerSocket
            serverSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val serverFailure = AtomicReference<Throwable?>(null)
            val serverThread = Thread({
                runCatching {
                    serverSocket.accept().use { socket -> (socket as SSLSocket).startHandshake() }
                }.onFailure { serverFailure.set(it) }
            }, "ca-selftest-server").apply { isDaemon = true }

            try {
                serverThread.start()
                val clientSocket = trustContextFactory()
                    .socketFactory
                    .createSocket(LOOPBACK, serverSocket.localPort) as SSLSocket
                clientSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
                clientSocket.use { it.startHandshake() }
                true
            } finally {
                runCatching { serverSocket.close() }
                serverThread.join(HANDSHAKE_TIMEOUT_MS.toLong())
                serverFailure.get()?.let { Log.d(TAG, "Self-test server side error (expected on reject): ${it.message}") }
            }
        }.getOrElse { error ->
            Log.d(TAG, "TLS self-test rejected: ${error.message}")
            false
        }
    }

    /** The default TLS context of the app process (honours network_security_config anchors). */
    private fun defaultTrustContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, null, null)
    }

    /**
     * Creates a short-lived leaf certificate signed by the CA, reusing the CA key pair as the TLS
     * credential so no expensive key generation is needed for the probe.
     */
    private fun createSelfTestLeaf(caCert: X509Certificate, caKey: PrivateKey): X509Certificate? = runCatching {
        val now = System.currentTimeMillis()
        val issuer = X500Name.getInstance(caCert.subjectX500Principal.encoded)
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now),
            Date(now - 3_600_000L),
            Date(now + 86_400_000L),
            X500Name("CN=ca-selftest.dev-sidebike.invalid"),
            caCert.publicKey,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        val signatureAlgorithm = when (caKey.algorithm.uppercase()) {
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> "SHA256withRSA"
        }
        val signer = JcaContentSignerBuilder(signatureAlgorithm).build(caKey)
        JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }.getOrElse { error ->
        Log.d(TAG, "Leaf certificate creation failed: ${error.message}")
        null
    }

    /** Parses an unencrypted PKCS#8 (`PRIVATE KEY`) or PKCS#1 (`RSA PRIVATE KEY`) PEM block. */
    internal fun parsePrivateKey(pem: String): PrivateKey? = runCatching {
        PEMParser(StringReader(pem)).use { parser ->
            val converter = JcaPEMKeyConverter()
            when (val parsed = parser.readObject()) {
                is PrivateKeyInfo -> converter.getPrivateKey(parsed)
                is PEMKeyPair -> converter.getKeyPair(parsed).private
                else -> null
            }
        }
    }.getOrElse { error ->
        Log.d(TAG, "Private key parsing failed: ${error.message}")
        null
    }

    /** Extracts the Base64 body of the first PEM block with [type] (e.g. `CERTIFICATE`). */
    private fun extractPemBody(pem: String, type: String): String? {
        val begin = "-----BEGIN $type-----"
        val end = "-----END $type-----"
        val beginIndex = pem.indexOf(begin)
        if (beginIndex < 0) return null
        val bodyStart = beginIndex + begin.length
        val endIndex = pem.indexOf(end, bodyStart)
        if (endIndex < 0) return null
        return pem.substring(bodyStart, endIndex)
    }
}
