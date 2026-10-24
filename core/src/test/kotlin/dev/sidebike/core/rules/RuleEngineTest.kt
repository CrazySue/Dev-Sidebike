package dev.sidebike.core.rules

import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RouteKind
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.log.NoopSidebikeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val github = mapOf(".*" to InterceptAction(sni = "baidu.com"))
    private val fonts = mapOf(".*" to InterceptAction(proxy = "fonts.googleapis.cn"))
    private val jsdelivr = mapOf("^/.*\\.(js|css)$" to InterceptAction(proxy = "fastly.jsdelivr.net"))
    private val recaptcha = mapOf("/recaptcha/.*" to InterceptAction(proxy = "www.recaptcha.net"))
    private val collect = mapOf(
        "/github/collect" to InterceptAction(success = kotlinx.serialization.json.JsonPrimitive(true), status = 204),
        ".*" to InterceptAction(sni = "baidu.com"),
    )
    private val cacheRule = mapOf(
        ".*" to InterceptAction(sni = "baidu.com"),
        "^/cache" to InterceptAction(cacheDays = 365),
    )

    private fun engine(
        intercepts: Map<String, Map<String, InterceptAction>> = linkedMapOf(
            "github.com" to github,
            "fonts.googleapis.com" to fonts,
            "cdn.jsdelivr.net" to jsdelivr,
            "www.google.com" to recaptcha,
            "collector.github.com" to collect,
            "merge.example.com" to cacheRule,
        ),
        whiteList: Map<String, Boolean?> = linkedMapOf("*.icloud.com" to true, "localhost" to true),
        excludeIpList: Map<String, Boolean?> = linkedMapOf("*.cn" to true, "127.*.*.*" to true),
        preset: Map<String, Map<String, Boolean>> = linkedMapOf(
            "github.com" to linkedMapOf("20.27.177.113" to true, "140.82.113.3" to false, "20.205.243.166" to true),
        ),
        domestic: List<String> = listOf("*.baidu.com", "*.qq.com"),
    ): DefaultRuleEngine = DefaultRuleEngine(
        SidebikeConfig(
            intercepts = intercepts,
            whiteList = whiteList,
            excludeIpList = excludeIpList,
            preSetIpList = preset,
            domesticAllowList = domestic,
        ),
        NoopSidebikeLogger,
    )

    @Test
    fun `match sni action and plan direct with sni`() {
        val e = engine()
        assertEquals("baidu.com", e.match("github.com", "/")!!.sni)
        val plan = e.plan("github.com", 443, "/")
        assertEquals(RouteKind.DIRECT, plan.kind)
        assertEquals("baidu.com", plan.sni)
        assertEquals("sni", plan.reason)
    }

    @Test
    fun `match returns null for non intercept host`() {
        val e = engine()
        assertNull(e.match("example.org", "/"))
        val plan = e.plan("example.org", 443, "/")
        assertEquals(RouteKind.DIRECT, plan.kind)
        assertNull(plan.action)
    }

    @Test
    fun `mirror proxy plan and path prefix parsing`() {
        val e = engine()
        val plan = e.plan("fonts.googleapis.com", 443, "/css?family=Roboto")
        assertEquals(RouteKind.MIRROR_PROXY, plan.kind)
        assertEquals("fonts.googleapis.cn", plan.connectHost)
        assertEquals(443, plan.connectPort)
        assertEquals("fonts.googleapis.cn", plan.action!!.proxy)
        val target = ProxyTargetParser.parse(plan.action!!.proxy, 443)!!
        assertEquals("/css?family=Roboto", target.buildPath("/css?family=Roboto"))
    }

    @Test
    fun `path specific rule only matches its path`() {
        val e = engine()
        assertNotNull(e.match("cdn.jsdelivr.net", "/lib/x.js"))
        assertNotNull(e.match("cdn.jsdelivr.net", "/lib/x.css"))
        assertNull(e.match("cdn.jsdelivr.net", "/lib/x.png"))
        assertEquals(RouteKind.MIRROR_PROXY, e.plan("cdn.jsdelivr.net", 443, "/lib/x.js").kind)
        assertEquals(RouteKind.DIRECT, e.plan("cdn.jsdelivr.net", 443, "/lib/x.png").kind)
    }

    @Test
    fun `success action becomes synthetic with status`() {
        val e = engine()
        val plan = e.plan("collector.github.com", 443, "/github/collect")
        assertEquals(RouteKind.SYNTHETIC, plan.kind)
        assertEquals(204, plan.action!!.status)
    }

    @Test
    fun `path null returns path-rule-pending marker when any path rule exists`() {
        val e = engine()
        assertTrue(e.requiresMitm("cdn.jsdelivr.net"))
        assertTrue(e.hasIntercept("cdn.jsdelivr.net"))
        val plan = e.plan("cdn.jsdelivr.net", 443, null)
        assertEquals(DefaultRuleEngine.REASON_PATH_RULE_PENDING, plan.reason)
        assertNotNull("CONNECT 预判必须带 action 以便开启 MITM", plan.action)
        assertFalse(e.requiresMitm("example.org"))
        assertEquals(DefaultRuleEngine.REASON_NO_INTERCEPT, e.plan("example.org", 443, null).reason)
    }

    @Test
    fun `merged actions across path rules`() {
        val e = engine()
        val plan = e.plan("merge.example.com", 443, "/cache/abc")
        assertEquals("baidu.com", plan.action!!.sni)
        assertEquals(365, plan.action!!.cacheDays)
    }

    @Test
    fun `whitelist exclude domestic checks and plan reasons`() {
        val e = engine()
        assertTrue(e.isWhitelisted("x.icloud.com"))
        assertTrue(e.isWhitelisted("localhost"))
        assertFalse(e.isWhitelisted("github.com"))
        assertEquals(DefaultRuleEngine.REASON_WHITELIST, e.plan("x.icloud.com", 443, "/").reason)

        assertTrue(e.isExcluded("www.example.cn"))
        assertTrue(e.isExcluded("127.0.0.1"))
        assertEquals(DefaultRuleEngine.REASON_EXCLUDED, e.plan("www.example.cn", 443, "/").reason)

        assertTrue(e.isDomestic("www.baidu.com"))
        assertTrue(e.isDomestic("news.qq.com"))
        assertTrue(e.isDomestic("localhost"))
        assertFalse(e.isDomestic("github.com"))
        assertEquals(DefaultRuleEngine.REASON_DOMESTIC, e.plan("www.baidu.com", 443, "/").reason)
    }

    @Test
    fun `preset ips ordered enabled first and wildcard supported`() {
        val e = engine(
            preset = linkedMapOf(
                "github.com" to linkedMapOf("a" to true, "b" to false, "c" to true),
                "*.githubusercontent.com" to linkedMapOf("1.1.1.1" to true),
            ),
        )
        assertEquals(listOf("a", "c"), e.presetIps("github.com"))
        assertEquals(listOf("1.1.1.1"), e.presetIps("raw.githubusercontent.com"))
        assertTrue(e.presetIps("example.org").isEmpty())
    }

    @Test
    fun `abort marker becomes reject`() {
        val e = engine(
            intercepts = linkedMapOf(
                "abort.example.com" to mapOf(
                    ".*" to InterceptAction(success = kotlinx.serialization.json.Json.parseToJsonElement("""{"abort":true}""")),
                ),
            ),
        )
        assertEquals(RouteKind.REJECT, e.plan("abort.example.com", 443, "/").kind)
    }

    @Test
    fun `placeholder proxy target falls back to direct with warning reason`() {
        val e = engine(
            intercepts = linkedMapOf(
                "video.example.com" to mapOf(".*" to InterceptAction(proxy = "\${pre}.mirror.example")),
            ),
        )
        val plan = e.plan("video.example.com", 443, "/")
        assertEquals(RouteKind.DIRECT, plan.kind)
        assertEquals("proxy-unresolvable", plan.reason)
    }

    @Test
    fun `update config atomically changes rules`() {
        val e = engine()
        assertEquals("baidu.com", e.plan("github.com", 443, "/").sni)
        e.updateConfig(
            SidebikeConfig(
                intercepts = linkedMapOf("github.com" to mapOf(".*" to InterceptAction(sni = "www.google.cn"))),
            ),
        )
        assertEquals("www.google.cn", e.plan("github.com", 443, "/").sni)
        assertEquals(2, e.configVersion())
    }

    @Test
    fun `domain matches helper`() {
        val e = engine()
        assertTrue(e.domainMatches("*.x.com", "a.x.com"))
        assertTrue(e.domainMatches("a.com", "a.com"))
        assertFalse(e.domainMatches("*.x.com", "b.com"))
        assertTrue(e.domainMatches("^(.+\\.)?x\\.com$", "a.x.com"))
    }

    @Test
    fun `blank host is handled without crashing`() {
        val e = engine()
        assertNull(e.match("", "/"))
        assertEquals(RouteKind.DIRECT, e.plan("", 443, "/").kind)
        assertEquals("empty-host", e.plan("", 443, "/").reason)
        assertFalse(e.requiresMitm(""))
    }

    @Test
    fun `status only action becomes synthetic`() {
        val e = engine(
            intercepts = linkedMapOf(
                "status.example.com" to mapOf(".*" to InterceptAction(status = 204)),
            ),
        )
        val plan = e.plan("status.example.com", 443, "/")
        assertEquals(RouteKind.SYNTHETIC, plan.kind)
        assertEquals("status", plan.reason)
    }
}
