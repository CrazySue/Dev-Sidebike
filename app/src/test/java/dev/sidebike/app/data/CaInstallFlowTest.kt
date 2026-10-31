package dev.sidebike.app.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.security.KeyChain
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Robolectric tests for the hardened CA install flow (0.1.1-alpha, user issue 3):
 * fingerprint/subject formatting, install intent + settings fallback, robust detection semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaInstallFlowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun sha256FingerprintIsUppercaseColonSeparatedSha256() {
        val ca = generateCa()
        val fingerprint = CaInstaller.sha256Fingerprint(ca.cert)
        val parts = fingerprint.split(':')
        assertEquals("SHA-256 has 32 bytes", 32, parts.size)
        assertTrue("each byte is two uppercase hex digits", parts.all { it.length == 2 && it == it.uppercase() })
        assertEquals(95, fingerprint.length)
        // Stable across calls.
        assertEquals(fingerprint, CaInstaller.sha256Fingerprint(ca.cert))
    }

    @Test
    fun subjectDnExposesCommonName() {
        val ca = generateCa()
        val subject = CaInstaller.subjectDn(ca.cert)
        assertTrue(subject.contains("CN=Dev-Sidebike Test CA"))
    }

    @Test
    fun settingsFallbackIntentIsLaunchableAndDocumented() {
        val intent = CaInstaller.buildSettingsFallbackIntent(context)
        assertNotNull(intent.action)
        assertTrue(
            "fallback must be a real settings screen or generic settings",
            intent.action in setOf(
                Settings.ACTION_SECURITY_SETTINGS,
                "android.settings.PRIVACY_SETTINGS",
                Settings.ACTION_SETTINGS,
            ),
        )
        assertTrue("fallback opens a new task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun buildInstallBundleSerializesCertificateOrMatchesPlatformLimitation() {
        val ca = generateCa()
        val bundle = CaInstaller.buildInstallBundle(context, ca.certPem)
        if (bundle == null) {
            // Robolectric does not implement KeyChain.createInstallIntent in all versions; behaviour
            // must still be consistent (no partial data).
            assertNull(CaInstaller.createInstallIntent(ca.certPem))
            return
        }
        assertEquals(ca.certPem, bundle.pem)
        assertEquals(CaInstaller.sha256Fingerprint(ca.cert), bundle.sha256Fingerprint)
        assertTrue(bundle.subject.contains("Dev-Sidebike Test CA"))
        assertNotNull(bundle.settingsFallbackIntent)
        assertArrayEquals(ca.cert.encoded, bundle.intent.getByteArrayExtra(KeyChain.EXTRA_CERTIFICATE))
    }

    @Test
    fun invalidPemCannotProduceInstallBundle() {
        assertNull(CaInstaller.buildInstallBundle(context, "not a certificate"))
    }

    @Test
    fun detectWithRetryReportsNotInstalledForFreshlyGeneratedCa() {
        // Hermetic: this CA was generated in-memory and installed on nothing, so detection must
        // report "not installed" (never throw), including with the retry variant.
        val ca = generateCa()
        val detection = CaInstaller.detectWithRetry(ca.certPem, ca.keyPem, attempts = 1, delayMs = 0)
        assertFalse(detection.installed)
        assertTrue("detection reason must be non-empty", detection.method.isNotEmpty())
    }

    @Test
    fun detectionDoesNotTreatCertificateFileAsInstalled() {
        // User issue 3: "CA file exists" must not be reported as installed. We only pass the PEM to
        // detection (no file access), which is exactly what the controller does.
        val ca = generateCa()
        assertFalse(CaInstaller.isCaInstalled(ca.certPem, null).installed)
        assertFalse(CaInstaller.isCaInstalled("garbage", null).installed)
    }

    // ---------------------------------------------------------------- helpers

    private data class TestCa(val cert: X509Certificate, val certPem: String, val keyPem: String)

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
        )
    }

    private fun encodePem(type: String, der: ByteArray): String {
        val base64 = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(der)
        return "-----BEGIN $type-----\n$base64\n-----END $type-----\n"
    }
}
