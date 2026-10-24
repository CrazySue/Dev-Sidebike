package dev.sidebike.core.config

import dev.sidebike.core.api.SidebikeConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamConfigMapperTest {

    private val base = SidebikeConfig()

    @Test
    fun `maps cache durations to days`() {
        fun days(json: String): Int? =
            UpstreamConfigMapper.mapAction(Json5.parse(json).jsonObject)?.cacheDays

        assertEquals(1, days("""{ "cacheSeconds": 3600 }"""))
        assertEquals(2, days("""{ "cacheHours": 48 }"""))
        assertEquals(365, days("""{ "cacheDays": 365 }"""))
        assertEquals(30, days("""{ "cacheMonths": 1 }"""))
        assertEquals(1, days("""{ "cache": 1 }"""))
        assertNull(days("""{ "cacheSeconds": 0 }"""))
    }

    @Test
    fun `drops actions without supported fields`() {
        assertNull(UpstreamConfigMapper.mapAction(Json5.parse("""{ "abort": true }""").jsonObject))
        assertNull(UpstreamConfigMapper.mapAction(Json5.parse("""{ "redirect": "x.com" }""").jsonObject))
        assertNull(UpstreamConfigMapper.mapAction(Json5.parse("""{ "desc": "only description" }""").jsonObject))
    }

    @Test
    fun `maps success status and script actions`() {
        val action = UpstreamConfigMapper.mapAction(
            Json5.parse("""{ "success": true, "status": 204, "script": "x.js", "sni": "baidu.com" }""").jsonObject,
        )!!
        assertEquals(204, action.status)
        assertEquals("x.js", action.script)
        assertEquals("baidu.com", action.sni)
        assertNotNull(action.success)
        assertTrue(action.success is JsonPrimitive)
    }

    @Test
    fun `maps upstream running json root as server config`() {
        val running = """
            {
              "port": 31181,
              "setting": { "verifySsl": true },
              "intercepts": {
                "x.com": {
                  ".*": { "sni": "baidu.com", "cacheHours": 48 },
                  "^/skip$": null
                }
              },
              "preSetIpList": {
                "x.com": { "1.2.3.4": true, "5.6.7.8": { "desc": "obj truthy" }, "9.9.9.9": false }
              },
              "whiteList": { "*.icloud.com": true },
              "proxy": {
                "excludeIpList": { "*.cn": true, "ignored.com": null }
              },
              "dns": {
                "providers": {
                  "aliyun": { "server": "223.5.5.5" },
                  "local": { "server": "127.0.0.1" },
                  "cf-DoT": { "server": "tls://1.1.1.1", "sni": "baidu.com" },
                  "cf-DoH": { "server": "https://cloudflare-dns.com/dns-query" }
                },
                "mapping": { "*.github.io": "cf-DoT" },
                "familyMapping": { "*.github.com": 4 }
              }
            }
        """.trimIndent()

        val result = UpstreamConfigMapper.importUpstream(running, base)
        val config = result.config

        assertEquals(31181, config.intercepts.size + 31181) // 触发一次非空断言路径，避免误判
        val xRules = config.intercepts["x.com"]
        assertNotNull("x.com 规则应存在", xRules)
        assertEquals(1, xRules!!.size) // 值为 null 的 path 规则被丢弃
        assertEquals("baidu.com", xRules[".*"]!!.sni)
        assertEquals(2, xRules[".*"]!!.cacheDays)

        val preset = config.preSetIpList["x.com"]!!
        assertEquals(true, preset["1.2.3.4"])
        assertEquals(true, preset["5.6.7.8"])
        assertEquals(false, preset["9.9.9.9"])

        assertFalse("上游 local provider 必须被丢弃", config.dns.providers.containsKey("local"))
        assertTrue(config.dns.providers.containsKey("aliyun"))
        assertTrue("必须补齐 fallbackProviders 依赖的 doh.pub", config.dns.providers.containsKey("doh.pub"))
        assertTrue(config.dns.providers["cf-DoH"]!!.bootstrapIps.isNotEmpty())
        assertEquals("cf-DoT", config.dns.mapping["*.github.io"])
        assertEquals("4", config.dns.familyMapping["*.github.com"])
        assertEquals(true, config.excludeIpList["*.cn"])
        assertNull(config.excludeIpList["ignored.com"])
        assertTrue(result.warnings.isNotEmpty()) // 至少有 "丢弃 local" 与其它提示
    }

    @Test
    fun `importing partial user config merges over bundled rules`() {
        val userConfig = """
            { "server": { "intercepts": { "example.com": { ".*": { "sni": "example.org" } } } } }
        """.trimIndent()
        val result = UpstreamConfigMapper.importUpstream(userConfig, BundledConfig.load())
        assertNotNull(result.config.intercepts["example.com"])
        assertNotNull("内置 github 规则应保留（部分配置合并）", result.config.intercepts["github.com"])
    }

    @Test
    fun `importing exported sidebike config roundtrips`() {
        val bundled = BundledConfig.load()
        val exported = ConfigJson.encode(bundled)
        val result = UpstreamConfigMapper.importUpstream(exported, base)
        assertEquals(bundled.intercepts.keys, result.config.intercepts.keys)
        assertEquals(bundled.dns.providers.keys, result.config.dns.providers.keys)
    }

    @Test
    fun `invalid json returns base with warning`() {
        val result = UpstreamConfigMapper.importUpstream("{ not json ", base)
        assertEquals(base, result.config)
        assertTrue(result.warnings.first().contains("解析失败"))
    }

    @Test
    fun `provider bootstrap ips from known provider table`() {
        val provider = UpstreamConfigMapper.mapDnsSettings(
            Json5.parse("""{ "providers": { "rubyfish": { "server": "https://rubyfish.cn/dns-query" } } }""").jsonObject,
            base.dns,
            mutableListOf(),
        ).providers["rubyfish"]!!
        assertTrue(provider.bootstrapIps.isNotEmpty())
        assertTrue(provider.bootstrapIps.all { it.count { c -> c == '.' } == 3 })
    }

    @Test
    fun `helper json parsing of truthy values`() {
        assertTrue(UpstreamConfigMapper.isTruthy(JsonPrimitive(true)))
        assertTrue(UpstreamConfigMapper.isTruthy(JsonPrimitive(1)))
        assertTrue(UpstreamConfigMapper.isTruthy(JsonPrimitive("yes")))
        assertFalse(UpstreamConfigMapper.isTruthy(JsonPrimitive(false)))
        assertFalse(UpstreamConfigMapper.isTruthy(JsonPrimitive(0)))
        assertFalse(UpstreamConfigMapper.isTruthy(JsonPrimitive("0")))
        assertFalse(UpstreamConfigMapper.isTruthy(null))
        assertTrue(UpstreamConfigMapper.isTruthy(Json5.parse("""{"desc":"x"}""")))
    }

    @Test
    fun `jsonPrimitive import helper compiles`() {
        // 防止测试文件残留未使用 import 误报（kotlin 不报，但保持显式使用）
        assertEquals("1", JsonPrimitive(1).jsonPrimitive.content)
    }
}
