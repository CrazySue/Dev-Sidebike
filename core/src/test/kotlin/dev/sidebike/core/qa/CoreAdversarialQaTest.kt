package dev.sidebike.core.qa

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.config.Json5
import dev.sidebike.core.config.UpstreamConfigMapper
import dev.sidebike.core.rules.DefaultRuleEngine
import dev.sidebike.core.util.HttpCodec
import dev.sidebike.core.util.MalformedHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.util.Random
import kotlinx.serialization.SerializationException

/**
 * QA adversarial tests for the pure-Kotlin core: malformed HTTP head parsing, JSON5/upstream-config
 * import fuzz, and rule-engine robustness for hostile hosts / invalid regex patterns.
 *
 * Hermetic (no network, no Android APIs) and fast. Any *unexpected* exception type (OOB, NPE,
 * StackOverflow, IllegalStateException, ...) fails the test: the production proxy parses untrusted
 * bytes from the network, so only the explicit malformed-input exception types are acceptable.
 */
class CoreAdversarialQaTest {

    private val rnd = Random(0x5EED)

    // ------------------------------------------------------------------------------------------
    // 1. HTTP head parsing fuzz (untrusted client bytes)
    // ------------------------------------------------------------------------------------------

    private fun allowedHeadFailure(t: Throwable): Boolean =
        t is MalformedHttpException || t is EOFException || t is IOException

    @Test
    fun httpRequestHeadFuzzOnlyThrowsMalformedInputErrors() {
        repeat(4_000) {
            val bytes = ByteArray(rnd.nextInt(257)).also { rnd.nextBytes(it) }
            val input = ByteArrayInputStream(bytes)
            try {
                HttpCodec.readRequestHead(input)
            } catch (t: Throwable) {
                assertTrue(
                    "unexpected ${t.javaClass.name} for ${bytes.size}B input: ${t.message}",
                    allowedHeadFailure(t),
                )
            }
        }
    }

    @Test
    fun oversizedHeaderBlockIsRejected() {
        val sb = StringBuilder("GET / HTTP/1.1\r\n")
        while (sb.length < HttpCodec.MAX_HEADER_BYTES + 1024) {
            sb.append("X-Filler: ").append("a".repeat(100)).append("\r\n")
        }
        sb.append("\r\n")
        try {
            HttpCodec.readRequestHead(ByteArrayInputStream(sb.toString().toByteArray()))
            // not throwing is also acceptable only if it did not OOM/hang; but by contract it must throw
            org.junit.Assert.fail("expected MalformedHttpException for oversized header block")
        } catch (t: Throwable) {
            assertTrue("unexpected ${t.javaClass.name}", allowedHeadFailure(t))
        }
    }

    @Test
    fun obsoleteHeaderFoldingIsRejected() {
        val raw = "GET / HTTP/1.1\r\nHost: example.com\r\nX-Folded: a\r\n b\r\n\r\n"
        try {
            HttpCodec.readRequestHead(ByteArrayInputStream(raw.toByteArray()))
            org.junit.Assert.fail("expected MalformedHttpException for obsolete header folding")
        } catch (t: Throwable) {
            assertTrue("unexpected ${t.javaClass.name}", allowedHeadFailure(t))
        }
    }

    @Test
    fun badContentLengthIsRejected() {
        val raw = "POST / HTTP/1.1\r\nHost: example.com\r\nContent-Length: not-a-number\r\n\r\n"
        val head = HttpCodec.readRequestHead(ByteArrayInputStream(raw.toByteArray()))
        assertNotNull(head)
        try {
            HttpCodec.contentLength(head!!.headers)
            org.junit.Assert.fail("expected MalformedHttpException for bad Content-Length")
        } catch (t: Throwable) {
            assertTrue("unexpected ${t.javaClass.name}", allowedHeadFailure(t))
        }
    }

    @Test
    fun connectTargetsAreParsedWithoutCrashing() {
        val targets = listOf(
            "example.com:443",
            "[::1]:443",
            "bad host:443",            // spaces -> rejected by request-line regex
            "example.com:99999",
            "example.com",
            "a".repeat(20_000),
            "\u4f8b\u5b50.\u6d4b\u8bd5:443",
        )
        for (target in targets) {
            val raw = "CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n"
            try {
                val head = HttpCodec.readRequestHead(ByteArrayInputStream(raw.toByteArray()))
                if (head != null) {
                    assertEquals("CONNECT", head.method)
                    // ASCII targets round-trip exactly; non-ASCII bytes are decoded as ISO-8859-1
                    // by design (HTTP header charset), so only assert no-crash for those.
                    if (target.all { it.code < 128 }) assertEquals(target, head.target)
                }
            } catch (t: Throwable) {
                assertTrue("unexpected ${t.javaClass.name} for target '${target.take(40)}'", allowedHeadFailure(t))
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. JSON5 / upstream config import fuzz
    // ------------------------------------------------------------------------------------------

    private fun allowedJsonFailure(t: Throwable): Boolean =
        t is SerializationException || t is IllegalArgumentException || t is IllegalStateException

    @Test
    fun json5LenientFuzzOnlyThrowsParseErrors() {
        val alphabet = "{}\"':,[]0123456789.truefalsnul \\\n\r\t\u4e2d"
        repeat(2_000) {
            val sb = StringBuilder()
            repeat(rnd.nextInt(64)) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            try {
                Json5.parseLenient(sb.toString())
            } catch (t: Throwable) {
                assertTrue(
                    "unexpected ${t.javaClass.name} for json5 input '${sb.toString().take(60)}': ${t.message}",
                    allowedJsonFailure(t),
                )
            }
        }
    }

    @Test
    fun upstreamConfigImportMalformedInputsDoNotCrash() {
        val base = SidebikeConfig()
        val inputs = listOf(
            "", " ", "{}", "[]", "null", "42", "{", "\"x\"",
            "{\"intercepts\": 5}",
            "{\"intercepts\": {\"example.com\": []}, \"whiteList\": null}",
            "{\"dns\": {\"providers\": \"nope\"}}",
            "{'single': 'quotes', // comment\n}",
            "{" + "\"k\":" + "\"" + "a".repeat(200_000) + "\"}",
        )
        for (text in inputs) {
            try {
                val result = UpstreamConfigMapper.importUpstream(text, base)
                assertNotNull("result must never be null for '${text.take(40)}'", result)
            } catch (t: Throwable) {
                assertTrue(
                    "unexpected ${t.javaClass.name} for import '${text.take(40)}': ${t.message}",
                    allowedJsonFailure(t),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 3. Rule engine robustness (hostile hosts, invalid regex configs)
    // ------------------------------------------------------------------------------------------

    @Test
    fun ruleEngineWeirdHostsNeverThrow() {
        val engine = DefaultRuleEngine(SidebikeConfig())
        val hosts = listOf(
            "", " ", ".", "..", "*.", "example.com", "EXAMPLE.COM",
            "a".repeat(10_000), "\u4f8b\u5b50.\u6d4b\u8bd5", "host with spaces",
            "xn--fiqs8s", "127.0.0.1", "-leading-dash", "trailing-dot.",
        )
        for (h in hosts) {
            engine.plan(h, 443, "/")
            engine.plan(h, 443, null)
            engine.match(h, "/")
            engine.isWhitelisted(h)
            engine.isExcluded(h)
            engine.isDomestic(h)
            engine.requiresMitm(h)
        }
        // blank host must be a safe direct plan
        val plan = engine.plan("", 443, "/")
        assertNotNull(plan)
    }

    @Test
    fun invalidRegexAndWeirdInterceptPatternsDoNotCrashUpdate() {
        val config = SidebikeConfig(
            intercepts = mapOf(
                "^[a-" to mapOf("/" to InterceptAction(proxy = "mirror.example:443")),
                "***" to mapOf("/" to InterceptAction(success = kotlinx.serialization.json.JsonPrimitive(true))),
                "a".repeat(500) to mapOf("(" to InterceptAction(script = "x")),
                "" to mapOf("" to InterceptAction(status = 200)),
            ),
            whiteList = mapOf("^[bad" to true, "" to true),
            excludeIpList = mapOf("**" to true),
            domesticAllowList = listOf("", "^[", "a".repeat(500)),
        )
        val engine = DefaultRuleEngine(SidebikeConfig())
        engine.updateConfig(config) // must not throw PatternSyntaxException etc.
        for (h in listOf("example.com", "", "***", "a".repeat(600))) {
            engine.plan(h, 443, "/whatever")
            engine.match(h, "/")
        }
    }
}
