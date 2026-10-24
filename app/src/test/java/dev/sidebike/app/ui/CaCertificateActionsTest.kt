package dev.sidebike.app.ui

import android.content.Intent
import android.security.KeyChain
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.app.ui.cert.CaCertificateActions
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.asn1.x500.X500Name
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

/**
 * UI-local CA 安装/导出 Intent 单元测试（PEM -> DER -> KeyChain）。
 * 证书在测试里用 BouncyCastle 现场生成，不访问网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaCertificateActionsTest {

    @Test
    fun invalidPemReturnsNull() {
        assertNull(CaCertificateActions.parseDer("not a pem"))
        assertNull(CaCertificateActions.parseCertificate("not a pem"))
        assertNull(CaCertificateActions.buildInstallIntent("not a pem"))
        assertNull(CaCertificateActions.buildInstallIntent(""))
    }

    @Test
    fun parsesPemIntoDerAndCertificate() {
        val pem = generateCaPem()
        val der = CaCertificateActions.parseDer(pem)
        assertTrue(der != null && der.isNotEmpty())

        val certificate = CaCertificateActions.parseCertificate(pem)
        assertTrue(certificate != null)
        assertArrayEquals(der, certificate!!.encoded)
    }

    @Test
    fun buildsSystemInstallIntentWithCertificateExtra() {
        val pem = generateCaPem()
        val certificate = CaCertificateActions.parseCertificate(pem)!!
        val intent = CaCertificateActions.buildInstallIntent(pem)
        assertTrue(intent != null)
        assertArrayEquals(
            certificate.encoded,
            intent!!.getByteArrayExtra(KeyChain.EXTRA_CERTIFICATE),
        )
        assertEquals("Dev-Sidebike CA", intent.getStringExtra(KeyChain.EXTRA_NAME))
    }

    @Test
    fun buildsExportShareIntentWithPemText() {
        val pem = generateCaPem()
        val intent = CaCertificateActions.buildExportIntent(pem, subject = "Dev-Sidebike CA")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/x-pem-file", intent.type)
        assertEquals("Dev-Sidebike CA", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("BEGIN CERTIFICATE"))
    }

    private fun generateCaPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Dev-Sidebike Test CA")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 1_000),
            Date(now + 86_400_000L),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val encoded = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----\n"
    }
}
