package dev.sidebike.app.ui

import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.ui.components.TimeoutPresets
import dev.sidebike.app.ui.export.ExportFileKind
import dev.sidebike.app.ui.export.exportFileName
import dev.sidebike.app.ui.rules.RuleActionType
import dev.sidebike.app.ui.rules.directPassDomains
import dev.sidebike.app.ui.rules.domesticDomains
import dev.sidebike.app.ui.rules.filterDomainsByQuery
import dev.sidebike.app.ui.rules.filterInterceptEntries
import dev.sidebike.app.ui.rules.isPlausibleDomainPattern
import dev.sidebike.app.ui.rules.isPlausiblePathRegex
import dev.sidebike.app.ui.rules.withDirectPassDomains
import dev.sidebike.app.ui.rules.withDomesticDomains
import dev.sidebike.app.ui.rules.withRule
import dev.sidebike.app.ui.rules.withoutRule
import dev.sidebike.app.ui.screens.isValidHostDomain
import dev.sidebike.app.ui.screens.isValidIpAddress
import dev.sidebike.app.ui.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.1.1-alpha 规则/设置/导出纯函数逻辑测试（无需 Robolectric）。 */
class RulesLogicTest {

    @Test
    fun domainPatternValidation() {
        assertTrue(isPlausibleDomainPattern("github.com"))
        assertTrue(isPlausibleDomainPattern("*.example.com"))
        assertTrue(isPlausibleDomainPattern("^ads\\..*$"))
        assertFalse(isPlausibleDomainPattern(""))
        assertFalse(isPlausibleDomainPattern("has space.com"))
        assertFalse(isPlausibleDomainPattern("nodot"))
    }

    @Test
    fun pathRegexValidation() {
        assertTrue(isPlausiblePathRegex(".*"))
        assertTrue(isPlausiblePathRegex("^/ads/.*"))
        assertTrue(isPlausiblePathRegex(""))
        assertFalse(isPlausiblePathRegex("([a-z"))
    }

    @Test
    fun withRuleAbortSetsAbortActionAndClearsDisabled() {
        val base = PreviewData.config.copy(disabledIntercepts = listOf("example.com\t.*"))
        val updated = base.withRule(
            RuleDraft(domainPattern = "example.com", pathRegex = ".*", desc = "ad block"),
            RuleActionType.ABORT,
        )
        assertEquals(true, updated.intercepts["example.com"]?.get(".*")?.abort)
        assertTrue(updated.disabledIntercepts.none { it.contains("example.com") })
    }

    @Test
    fun withRuleSniSetsSni() {
        val updated = PreviewData.config.withRule(
            RuleDraft(domainPattern = "news.example.com", pathRegex = "^/api/.*", sni = "baidu.com"),
            RuleActionType.SNI,
        )
        assertEquals("baidu.com", updated.intercepts["news.example.com"]?.get("^/api/.*")?.sni)
    }

    @Test
    fun withoutRuleRemovesPathAndMarksDisabled() {
        val base = PreviewData.config
        val updated = base.withoutRule("github.com", "/")
        assertEquals("/", base.intercepts["github.com"]?.keys?.firstOrNull())
        assertTrue(updated.disabledIntercepts.contains("github.com\t/"))
    }

    @Test
    fun directPassHelpers() {
        assertEquals(listOf("raw.githubusercontent.com", "*.internal.example.com").sorted(), directPassDomains(PreviewData.config))
        val updated = PreviewData.config.withDirectPassDomains(listOf("a.com", "b.com"))
        assertEquals(listOf("a.com", "b.com"), directPassDomains(updated))
    }

    @Test
    fun domesticHelpers() {
        assertEquals(listOf("baidu.com", "example.cn", "qq.com", "taobao.com"), domesticDomains(PreviewData.config))
        val updated = PreviewData.config.withDomesticDomains(listOf("x.cn", "x.cn", " "))
        assertEquals(listOf("x.cn"), domesticDomains(updated))
    }

    @Test
    fun filteringHelpers() {
        assertEquals(
            listOf("example.com", "test.example.com"),
            filterDomainsByQuery(listOf("example.com", "test.example.com", "other.cn"), "example"),
        )
        val entries = listOf("github.com" to PreviewData.config.intercepts["github.com"].orEmpty())
        assertTrue(filterInterceptEntries(entries, "github").isNotEmpty())
        assertTrue(filterInterceptEntries(entries, "mirror-proxy").isEmpty())
        assertTrue(filterInterceptEntries(entries, "github.moeyy").isNotEmpty())
    }

    @Test
    fun timeoutPresetsApplyExpectedValues() {
        val config = TimeoutPresets.apply(PreviewData.config, TimeoutPresets.all[1])
        assertEquals(6_000, config.proxy.connectTimeoutMs)
        assertEquals(15_000, config.proxy.readTimeoutMs)
        assertEquals(30_000, config.proxy.idleTimeoutMs)
        assertEquals("fast", TimeoutPresets.current(config)?.id)
        assertEquals("default", TimeoutPresets.current(PreviewData.config)?.id)
    }

    @Test
    fun hostValidation() {
        assertTrue(isValidHostDomain("example.com"))
        assertFalse(isValidHostDomain("example"))
        assertFalse(isValidHostDomain("bad host.com"))
        assertTrue(isValidIpAddress("10.0.0.1"))
        assertFalse(isValidIpAddress("10.0.0.256"))
        assertFalse(isValidIpAddress("not-an-ip"))
        assertTrue(isValidIpAddress("2001:db8::1"))
    }

    @Test
    fun exportFileNamesAreDeterministicAndSafe() {
        val now = 1_700_000_000_000L
        val name = exportFileName(ExportFileKind.LOG, now)
        assertTrue(name.startsWith("dev-sidebike-"))
        assertTrue(name.endsWith(".log"))
        assertEquals(name, exportFileName(ExportFileKind.LOG, now))
        assertTrue(exportFileName(ExportFileKind.DEBUG_BUNDLE).endsWith(".zip"))
        assertTrue(exportFileName(ExportFileKind.CA_CRT).endsWith(".crt"))
    }
}
