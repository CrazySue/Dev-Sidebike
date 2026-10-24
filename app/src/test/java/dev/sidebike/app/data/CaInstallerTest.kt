package dev.sidebike.app.data

import android.security.KeyChain
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Tests for [CaInstaller]: PEM/DER conversion, install-intent construction (defensive under
 * Robolectric) and the loopback TLS self-test with an injected trust context (hermetic: no network,
 * no real user CA store needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaInstallerTest {

    private data class TestCa(
        val cert: X509Certificate,
        val certPem: String,
        val keyPem: String,
        val keyPair: KeyPair,
    )

    @Test
    fun pemToDerMatchesCertificateEncoding() {
        val ca = generateCa()
        val der = CaInstaller.pemToDer(ca.certPem)
        assertNotNull(der)
        assertArrayEquals(ca.cert.encoded, der)
    }

    @Test
    fun parseCertificateReadsSubjectAndEncodedForm() {
        val ca = generateCa()
        val parsed = CaInstaller.parseCertificate(ca.certPem)
        assertNotNull(parsed)
        assertEquals(ca.cert.subjectX500Principal, parsed!!.subjectX500Principal)
    }

    @Test
    fun invalidPemReturnsNullInsteadOfThrowing() {
        assertNull(CaInstaller.parseCertificate(""))
        assertNull(CaInstaller.parseCertificate("not a pem"))
        assertNull(CaInstaller.pemToDer("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"))
        assertNull(CaInstaller.parsePrivateKey("garbage"))
    }

    @Test
    fun parsePrivateKeyReadsPkcs8Pem() {
        val ca = generateCa()
        val key = CaInstaller.parsePrivateKey(ca.keyPem)
        assertNotNull(key)
        assertEquals("RSA", key!!.algorithm)
    }

    @Test
    fun installIntentIsNullForInvalidPem() {
        assertNull(CaInstaller.createInstallIntent("definitely-not-a-certificate"))
    }

    @Test
    fun installIntentCarriesDerCertificateWhenPlatformSupportsIt() {
        val ca = generateCa()
        val intent = CaInstaller.createInstallIntent(ca.certPem, "Dev-Sidebike Test CA")
        // Robolectric might not implement KeyChain.createInstallIntent; if it does, verify the extras.
        if (intent != null) {
            assertArrayEquals(ca.cert.encoded, intent.getByteArrayExtra(KeyChain.EXTRA_CERTIFICATE))
            assertEquals("Dev-Sidebike Test CA", intent.getStringExtra(KeyChain.EXTRA_NAME))
        }
    }

    @Test
    fun tlsSelfTestSucceedsWhenCaIsTrusted() {
        val ca = generateCa()
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("test-ca", ca.cert)
        }
        val trustManagerFactory = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }
        val trustContext: () -> SSLContext = {
            SSLContext.getInstance("TLS").apply { init(null, trustManagerFactory.trustManagers, null) }
        }

        assertTrue(
            "loopback TLS self-test must succeed when the CA is a trust anchor",
            CaInstaller.tlsSelfTest(ca.certPem, ca.keyPem, trustContext),
        )
    }

    @Test
    fun tlsSelfTestFailsWhenCaIsNotTrusted() {
        val ca = generateCa()
        val defaultTrustContext: () -> SSLContext = {
            SSLContext.getInstance("TLS").apply { init(null, null, null) }
        }
        // The JVM default trust store never contains this freshly generated random CA.
        assertFalse(
            "loopback TLS self-test must reject an untrusted CA",
            CaInstaller.tlsSelfTest(ca.certPem, ca.keyPem, defaultTrustContext),
        )
    }

    @Test
    fun isCaInstalledWithoutKeyPemIsFalseAndDoesNotCrash() {
        val ca = generateCa()
        // On the JVM the AndroidCAStore provider is unavailable; detection must degrade to false.
        val detection = CaInstaller.isCaInstalled(ca.certPem, caKeyPem = null)
        assertFalse(detection.installed)
    }

    @Test
    fun isCaInstalledReturnsFalseForInvalidPem() {
        assertFalse(CaInstaller.isCaInstalled("invalid", null).installed)
    }

    // ---------------------------------------------------------------- helpers

    private fun generateCa(): TestCa {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=Dev-Sidebike Test CA, O=Dev-Sidebike, C=CN")
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 24L * 3600 * 1000),
            name,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.digitalSignature))
        val certificate = JcaX509CertificateConverter()
            .getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)))

        return TestCa(
            cert = certificate,
            certPem = encodePem("CERTIFICATE", certificate.encoded),
            keyPem = encodePem("PRIVATE KEY", keyPair.private.encoded),
            keyPair = keyPair,
        )
    }

    private fun encodePem(type: String, der: ByteArray): String {
        val base64 = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(der)
        return "-----BEGIN $type-----\n$base64\n-----END $type-----\n"
    }
}
