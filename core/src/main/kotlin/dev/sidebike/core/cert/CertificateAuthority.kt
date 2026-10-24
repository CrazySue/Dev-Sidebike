package dev.sidebike.core.cert

import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.util.Clock
import dev.sidebike.core.util.LruCache
import dev.sidebike.core.util.NetUtils
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.encoders.Base64

/** A leaf certificate (host key + certificate + chain up to the local CA) ready for a TLS server. */
class HeldCertificate(
    val host: String,
    val keyPair: KeyPair,
    val certificate: X509Certificate,
    val chain: Array<X509Certificate>,
)

/**
 * Local MITM certificate authority (BouncyCastle, pure JVM - Android compatible).
 *
 * Behavioural parity with upstream `mitmproxy/src/lib/proxy/tls/tlsUtils.js`:
 *  - self-signed RSA-2048 CA, SHA256withRSA, `BasicConstraints(cA=true, critical)` +
 *    `KeyUsage(keyCertSign|cRLSign, critical)` + SubjectKeyIdentifier (`tlsUtils.js#createCA`);
 *  - one leaf per host: RSA-2048, `CN=host`, `subjectAltName=host`, `basicConstraints(cA=false)`,
 *    `extKeyUsage=serverAuth`, SKI + AKI (`tlsUtils.js#createFakeCertificateByDomain`) so strict
 *    clients (Python 3.13 VERIFY_X509_STRICT / openssl -x509_strict) accept it;
 *  - the private key/cert are persisted as PEM files under `dataDir` and reused across restarts
 *    (`tlsUtils.js#initCA`).
 *
 * Divergences from upstream (documented in code comments):
 *  - CA validity is 10 years (task spec) instead of 20;
 *  - leaves are cached per exact host instead of the upstream wildcard `*.domain.com` grouping (the task
 *    spec asks for a per-host cache; it costs one RSA keygen per new host, cached afterwards).
 */
class CertificateAuthority(
    val caCertificate: X509Certificate,
    private val caKeyPair: KeyPair,
    private val clock: Clock,
    private val logger: SidebikeLogger,
    maxLeafCache: Int = 256,
    private val leafValidDays: Int = 365,
) {
    private val leafCache = LruCache<String, HeldCertificate>(if (maxLeafCache > 0) maxLeafCache else 256)

    val caCertificatePem: String = pemEncode("CERTIFICATE", caCertificate.encoded)
    val caPrivateKeyPem: String = pemEncode("PRIVATE KEY", caKeyPair.private.encoded)

    fun cachedLeafCount(): Int = leafCache.size()

    fun maybeCachedLeaf(host: String): HeldCertificate? = leafCache.get(host)

    /** Returns (issuing on first use) the leaf certificate for [host]. Thread-safe. */
    fun leaf(host: String): HeldCertificate = leafCache.getOrPut(host) { issueLeaf(host) }

    private fun issueLeaf(host: String): HeldCertificate {
        require(isIssuableHost(host)) { "cannot issue a certificate for '$host'" }
        val keyPair = generateRsaKeyPair()
        val now = clock.currentTimeMillis()
        val notBefore = Date(now - DAY_MS) // tolerate client clock skew
        val leafDays = leafValidDays.coerceAtLeast(1)
        val notAfter = Date(now + leafDays * DAY_MS)
        val subject = X500Name("CN=$host, O=Dev-Sidebike, OU=MITM, C=CN")
        val serial = BigInteger(63, SECURE_RANDOM)
        val leafSubjectPublicKey = keyPair.public
        val builder = JcaX509v3CertificateBuilder(
            X500Name(caCertificate.subjectX500Principal.name),
            serial,
            notBefore,
            notAfter,
            subject,
            leafSubjectPublicKey,
        )
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            if (NetUtils.isIpv4Literal(host)) {
                GeneralNames(GeneralName(GeneralName.iPAddress, host))
            } else if (NetUtils.isIpv6Literal(host)) {
                GeneralNames(GeneralName(GeneralName.iPAddress, host.removePrefix("[").removeSuffix("]")))
            } else {
                GeneralNames(GeneralName(GeneralName.dNSName, host))
            },
        )
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(leafSubjectPublicKey))
        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extUtils.createAuthorityKeyIdentifier(caCertificate),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BC_PROVIDER).build(caKeyPair.private)
        val certificate = JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(builder.build(signer))
        certificate.checkValidity()
        certificate.verify(caCertificate.publicKey)
        logger.log(LogLevel.DEBUG, TAG, "issued leaf certificate CN=$host (serial=${serial.toString(16)})")
        return HeldCertificate(host, keyPair, certificate, arrayOf(certificate, caCertificate))
    }

    companion object {
        private const val TAG = "cert.ca"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val CA_VALID_DAYS = 3650L
        private val SECURE_RANDOM = SecureRandom()
        private val HOST_RE = Regex("^[A-Za-z0-9._:\\[\\]-]+$")

        val BC_PROVIDER: Provider by lazy { BouncyCastleProvider() }

        fun isIssuableHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            if (!HOST_RE.matches(host)) return false
            return true
        }

        fun generateRsaKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SECURE_RANDOM) }.generateKeyPair()

        fun pemEncode(label: String, der: ByteArray): String {
            val b64 = Base64.toBase64String(der)
            val sb = StringBuilder(b64.length + 64)
            sb.append("-----BEGIN ").append(label).append("-----\n")
            var i = 0
            while (i < b64.length) {
                val end = minOf(i + 64, b64.length)
                sb.append(b64, i, end).append('\n')
                i = end
            }
            sb.append("-----END ").append(label).append("-----\n")
            return sb.toString()
        }

        fun pemDecode(pem: String): ByteArray {
            val body = pem
                .replace(Regex("-----[^-]+-----"), "")
                .replace(Regex("\\s"), "")
            return Base64.decode(body)
        }

        /** Creates a fresh self-signed CA (10y) with the same X.509 shape as upstream `createCA`. */
        fun generate(clock: Clock, logger: SidebikeLogger): CertificateAuthority {
            val keyPair = generateRsaKeyPair()
            val now = clock.currentTimeMillis()
            val notBefore = Date(now - 60L * 60 * 1000)
            val notAfter = Date(now + CA_VALID_DAYS * DAY_MS)
            val name = X500Name(
                "CN=Dev-Sidebike Local CA, O=Dev-Sidebike, OU=https://github.com/docmirror/dev-sidecar, C=CN, ST=GuangDong, L=ShenZhen",
            )
            val serial = BigInteger(63, SECURE_RANDOM)
            val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
            val extUtils = JcaX509ExtensionUtils()
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            builder.addExtension(Extension.subjectKeyIdentifier, false, extUtils.createSubjectKeyIdentifier(keyPair.public))
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(BC_PROVIDER).build(keyPair.private)
            val certificate = JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(builder.build(signer))
            certificate.verify(keyPair.public)
            logger.log(LogLevel.INFO, TAG, "generated local CA certificate (RSA-2048, SHA256withRSA, valid ${CA_VALID_DAYS / 365}y)")
            return CertificateAuthority(certificate, keyPair, clock, logger)
        }

        /** Parses a PEM certificate + PEM PKCS#8 private key previously written by [CaStore]. */
        fun fromPem(
            certPem: String,
            keyPem: String,
            clock: Clock,
            logger: SidebikeLogger,
            maxLeafCache: Int = 256,
            leafValidDays: Int = 365,
        ): CertificateAuthority {
            val certFactory = CertificateFactory.getInstance("X.509")
            val cert = certFactory.generateCertificate(certPem.byteInputStream()) as X509Certificate
            val keySpec = PKCS8EncodedKeySpec(pemDecode(keyPem))
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
            val publicKey = cert.publicKey
            cert.verify(publicKey)
            return CertificateAuthority(cert, KeyPair(publicKey, privateKey), clock, logger, maxLeafCache, leafValidDays)
        }
    }
}

/**
 * Owns the on-disk CA under `dataDir` (upstream: `~/.dev-sidecar/dev-sidecar.ca.crt` + `.key.pem`).
 * Generation happens lazily on first use so an app with MITM disabled never writes the files.
 */
class CaStore(
    private val dataDir: File,
    private val clock: Clock,
    private val logger: SidebikeLogger,
    private val certFileName: String = "sidebike.ca.crt",
    private val keyFileName: String = "sidebike.ca.key.pem",
    private val maxLeafCache: Int = 256,
    private val leafValidDays: Int = 365,
) {
    @Volatile
    private var cached: CertificateAuthority? = null

    private val lock = Any()

    /** Returns the CA PEM if it already exists on disk (or is cached), without generating a new one. */
    fun peekPem(): String? {
        cached?.let { return it.caCertificatePem }
        val certFile = File(dataDir, certFileName)
        val keyFile = File(dataDir, keyFileName)
        return if (certFile.isFile && keyFile.isFile) {
            try {
                ensure().caCertificatePem
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, TAG, "failed to load CA from $certFile", e)
                null
            }
        } else {
            null
        }
    }

    /** Loads or generates the CA (idempotent, thread-safe). */
    fun ensure(): CertificateAuthority = synchronized(lock) {
        cached?.let { return it }
        val certFile = File(dataDir, certFileName)
        val keyFile = File(dataDir, keyFileName)
        var certPem: String? = null
        var keyPem: String? = null
        if (certFile.isFile && keyFile.isFile) {
            certPem = certFile.readText()
            keyPem = keyFile.readText()
        }
        val ca = try {
            if (certPem != null && keyPem != null) {
                CertificateAuthority.fromPem(certPem, keyPem, clock, logger, maxLeafCache, leafValidDays)
            } else {
                val generated = generateAndPersist(certFile, keyFile)
                generated
            }
        } catch (e: Exception) {
            logger.log(LogLevel.WARN, TAG, "existing CA is unreadable, regenerating: ${e.message}")
            generateAndPersist(certFile, keyFile)
        }
        cached = ca
        ca
    }

    fun clearCache() {
        synchronized(lock) { cached = null }
    }

    private fun generateAndPersist(certFile: File, keyFile: File): CertificateAuthority {
        val fresh = CertificateAuthority.generate(clock, logger)
        dataDir.mkdirs()
        certFile.writeText(fresh.caCertificatePem)
        keyFile.writeText(fresh.caPrivateKeyPem)
        logger.log(LogLevel.INFO, TAG, "persisted CA certificate + key to ${certFile.parentFile?.absolutePath}")
        // Rebuild so the caller's cache-size/validity settings apply.
        return CertificateAuthority.fromPem(
            fresh.caCertificatePem,
            fresh.caPrivateKeyPem,
            clock,
            logger,
            maxLeafCache,
            leafValidDays,
        )
    }

    companion object {
        private const val TAG = "cert.store"
    }
}
