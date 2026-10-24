package dev.sidebike.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyTargetParserTest {

    @Test
    fun `bare host uses client port default`() {
        val target = ProxyTargetParser.parse("fonts.googleapis.cn", 443)!!
        assertEquals("fonts.googleapis.cn", target.host)
        assertEquals(443, target.port)
        assertEquals("", target.pathPrefix)
        assertEquals("/css?x=1", target.buildPath("/css?x=1"))
    }

    @Test
    fun `bare host with http client port uses 80`() {
        val target = ProxyTargetParser.parse("example.com", 80)!!
        assertEquals(80, target.port)
    }

    @Test
    fun `host with explicit port`() {
        val target = ProxyTargetParser.parse("example.com:8443", 443)!!
        assertEquals("example.com", target.host)
        assertEquals(8443, target.port)
    }

    @Test
    fun `path prefix is preserved and joined`() {
        val target = ProxyTargetParser.parse("ajax.proxy.ustclug.org/base/", 443)!!
        assertEquals("ajax.proxy.ustclug.org", target.host)
        assertEquals("/base", target.pathPrefix)
        assertEquals("/base/ajax.js", target.buildPath("/ajax.js"))
        assertEquals("/base", target.buildPath(""))
    }

    @Test
    fun `absolute urls`() {
        val https = ProxyTargetParser.parse("https://www.recaptcha.net", 443)!!
        assertEquals("www.recaptcha.net", https.host)
        assertEquals(443, https.port)
        val http = ProxyTargetParser.parse("http://mirror.example/base", 443)!!
        assertEquals("mirror.example", http.host)
        assertEquals(80, http.port)
        assertEquals("/base", http.pathPrefix)
    }

    @Test
    fun `placeholders are unsupported`() {
        assertNull(ProxyTargetParser.parse("\${pre}.xn--ngstr-lra8j.com", 443))
        assertNull(ProxyTargetParser.parse("", 443))
        assertNull(ProxyTargetParser.parse(null, 443))
    }
}
