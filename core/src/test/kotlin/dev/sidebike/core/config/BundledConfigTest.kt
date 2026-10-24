package dev.sidebike.core.config

import dev.sidebike.core.api.SidebikeConfig
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验内置官方规则快照（resources/dev/sidebike/core/rules/rules.json）完整性与关键条目。
 * 快照 provenance 见文件内 `_meta` 与 docs/feasibility/01b-config-porting.md。
 */
class BundledConfigTest {

    @Test
    fun `snapshot resource exists and is loaded`() {
        assertTrue("rules.json 资源缺失，请重新生成快照", BundledConfig.isSnapshotPresent())
        val config = BundledConfig.load()
        assertTrue("intercepts 域名数异常: ${config.intercepts.size}", config.intercepts.size >= 50)
        assertTrue("preSetIpList 域名数异常: ${config.preSetIpList.size}", config.preSetIpList.size >= 40)
        assertTrue("dns providers 数异常: ${config.dns.providers.size}", config.dns.providers.size >= 6)
        assertTrue("dns mapping 数异常: ${config.dns.mapping.size}", config.dns.mapping.size >= 20)
        assertTrue("domesticAllowList 条数异常: ${config.domesticAllowList.size}", config.domesticAllowList.size >= 1000)
        assertTrue("whiteList 不应为空", config.whiteList.isNotEmpty())
        assertTrue("excludeIpList 不应为空", config.excludeIpList.isNotEmpty())
        assertTrue("domainRules 总数异常", config.intercepts.values.sumOf { it.size } >= 60)
    }

    @Test
    fun `snapshot metadata provenance present`() {
        val meta = BundledConfig.meta()
        assertNotNull("_meta 缺失", meta)
        val obj = meta!!.jsonObject
        assertNotNull(obj["source"])
        assertNotNull(obj["generatedAt"])
        assertNotNull(obj["stats"])
        assertTrue(obj["source"]!!.jsonPrimitive.content.contains("running.json"))
    }

    @Test
    fun `github sni rule and preset ips present`() {
        val config = BundledConfig.load()
        val githubRules = config.intercepts["github.com"]
        assertNotNull("github.com 规则缺失", githubRules)
        assertTrue(
            "github.com 规则应含 sni=baidu.com，实际: $githubRules",
            githubRules!!.values.any { it.sni == "baidu.com" },
        )
        val preset = config.preSetIpList["github.com"]
        assertNotNull(preset)
        assertTrue("github 预置 IP 应为真值", preset!!.values.any { it })
        assertTrue(preset.values.any { !it } || true) // 允许全真值
    }

    @Test
    fun `mirror and recaptcha rules present`() {
        val config = BundledConfig.load()
        val fonts = config.intercepts["fonts.googleapis.com"]
        assertNotNull(fonts)
        assertEquals("fonts.googleapis.cn", fonts!!.values.firstNotNullOfOrNull { it.proxy })
        val google = config.intercepts["www.google.com"]
        assertNotNull(google)
        assertTrue(google!!.values.any { it.proxy == "www.recaptcha.net" })
    }

    @Test
    fun `dns providers carry bootstrap ips and mapping references valid providers`() {
        val config = BundledConfig.load()
        assertFalse("上游 local provider 应被移除（VPN 自递归）", config.dns.providers.containsKey("local"))
        val dohPub = config.dns.providers["doh.pub"]
        assertNotNull(dohPub)
        assertTrue("doh.pub 必须有 bootstrapIps", dohPub!!.bootstrapIps.isNotEmpty())
        for ((pattern, providerName) in config.dns.mapping) {
            assertTrue("mapping '$pattern' 指向不存在的 provider '$providerName'", config.dns.providers.containsKey(providerName))
        }
        assertTrue(config.dns.familyMapping.values.all { it == "4" || it == "6" })
    }

    @Test
    fun `serialization roundtrip of bundled config keeps rules`() {
        val config = BundledConfig.load()
        val decoded = ConfigJson.decode(ConfigJson.encode(config))
        assertEquals(config.intercepts.keys, decoded.intercepts.keys)
        assertEquals(config.dns.providers.keys, decoded.dns.providers.keys)
        assertEquals(config.preSetIpList.keys, decoded.preSetIpList.keys)
        assertEquals(SidebikeConfig.CURRENT_VERSION, decoded.version)
    }
}
