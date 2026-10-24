package dev.sidebike.core.mitm

import dev.sidebike.core.cert.CaStore
import dev.sidebike.core.cert.CertificateAuthority
import dev.sidebike.core.log.NoopSidebikeLogger
import dev.sidebike.core.proxy.TestCAs
import dev.sidebike.core.util.FakeClock
import java.io.File
import java.nio.file.Files
import java.security.cert.X509Certificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Certificate-authority unit tests: X.509 shape (matching upstream node-forge `createCA` /
 * `createFakeCertificateByDomain`), PEM persistence under the app data dir, per-host leaf cache and LRU
 * eviction. These are pure in-memory/JVM tests (RSA keygen ~100ms each, kept to a handful of leaves).
 */
class CertificateAuthorityTest {

    private val tempDirs = ArrayList<File>()
    private val clock = FakeClock()

    @After
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun newTempDir(): File {
        val dir = Files.createTempDirectory("ds-ca-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    @Test
    fun caCertificateHasRequiredX509Extensions() {
        val ca = TestCAs.newCa(clock)
        val cert = ca.caCertificate

        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertTrue("CA must have basicConstraints CA:true", cert.basicConstraints >= 0)
        val keyUsage = cert.keyUsage
        assertNotNull("CA must carry keyUsage", keyUsage)
        assertTrue("CA must allow keyCertSign", keyUsage[5])
        assertTrue("CA must allow cRLSign", keyUsage[6])

        val validityDays = (cert.notAfter.time - cert.notBefore.time) / (24L * 3600 * 1000)
        assertTrue("CA validity should be ~10y, was $validityDays days", validityDays in 3600..3700)
        assertTrue(cert.subjectX500Principal.name.contains("Dev-Sidebike"))

        val pem = ca.caCertificatePem
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))
        val parsed = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate
        assertEquals(cert, parsed)

        val keyPem = ca.caPrivateKeyPem
        assertTrue(keyPem.startsWith("-----BEGIN PRIVATE KEY-----"))
    }

    @Test
    fun leafCertificateIsSignedByCaAndValidForHost() {
        val ca = TestCAs.newCa(clock)
        val host = "github.example.test"
        val leaf = ca.leaf(host)
        val cert = leaf.certificate

        cert.verify(ca.caCertificate.publicKey)
        assertEquals(ca.caCertificate.subjectX500Principal, cert.issuerX500Principal)
        assertTrue(cert.subjectX500Principal.name.contains("CN=$host"))
        assertTrue("leaf must not be a CA", cert.basicConstraints < 0)

        val san = cert.subjectAlternativeNames
        assertNotNull("leaf must have SAN", san)
        assertTrue(
            "SAN should contain dNSName=$host, was $san",
            san.any { it.size >= 2 && it[0] == 2 && it[1] == host },
        )
        assertTrue(
            "leaf must have serverAuth EKU",
            cert.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"),
        )
        val leafValidityDays = (cert.notAfter.time - cert.notBefore.time) / (24L * 3600 * 1000)
        assertTrue("leaf validity ~1y, was $leafValidityDays days", leafValidityDays in 360..370)
        assertEquals(2, leaf.chain.size)
        assertEquals(ca.caCertificate, leaf.chain[1])
    }

    @Test
    fun leafCacheReusesCertificatePerHost() {
        val ca = TestCAs.newCa(clock)
        val first = ca.leaf("a.example.test")
        val second = ca.leaf("a.example.test")
        assertSame("same host must reuse the cached leaf", first, second)
        assertNotSame(ca.leaf("b.example.test"), first)
        assertEquals(2, ca.cachedLeafCount())
    }

    @Test
    fun leafCacheEvictsByLruCapacity() {
        val store = CaStore(newTempDir(), clock, NoopSidebikeLogger, maxLeafCache = 2)
        val ca = store.ensure()
        ca.leaf("one.test")
        ca.leaf("two.test")
        assertEquals(2, ca.cachedLeafCount())
        ca.leaf("three.test")
        assertEquals("LRU must evict down to maxLeafCache", 2, ca.cachedLeafCount())
    }

    @Test
    fun caStorePersistsAndReloadsSamePem() {
        val dir = newTempDir()
        val store1 = CaStore(dir, clock, NoopSidebikeLogger)
        val pem1 = store1.ensure().caCertificatePem

        val certFile = File(dir, "sidebike.ca.crt")
        val keyFile = File(dir, "sidebike.ca.key.pem")
        assertTrue(certFile.isFile)
        assertTrue(keyFile.isFile)
        assertTrue(keyFile.readText().startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue("peekPem must not regenerate", store1.peekPem() == pem1)

        val store2 = CaStore(dir, clock, NoopSidebikeLogger)
        assertEquals("reloading must reuse the persisted CA", pem1, store2.ensure().caCertificatePem)
    }

    @Test
    fun caStoreRegeneratesCorruptFiles() {
        val dir = newTempDir()
        File(dir, "sidebike.ca.crt").writeText("not a pem")
        File(dir, "sidebike.ca.key.pem").writeText("definitely not a key")
        val ca = CaStore(dir, clock, NoopSidebikeLogger).ensure()
        assertNotNull(ca.caCertificate)
        ca.caCertificate.verify(ca.caCertificate.publicKey)
        assertFalse(File(dir, "sidebike.ca.crt").readText().contains("not a pem"))
    }

    @Test
    fun peekingBeforeGenerationReturnsNull() {
        val store = CaStore(newTempDir(), clock, NoopSidebikeLogger)
        assertEquals(null, store.peekPem())
        assertNotNull(store.ensure())
        assertNotNull(store.peekPem())
    }

    @Test
    fun ipHostsGetIpAddressSan() {
        val ca = TestCAs.newCa(clock)
        val leaf = ca.leaf("127.0.0.1")
        val san = leaf.certificate.subjectAlternativeNames
        assertNotNull(san)
        assertTrue("IP SAN expected, was $san", san.any { it.size >= 2 && it[0] == 7 })
        assertNotEquals(0, leaf.chain.size)
    }
}
