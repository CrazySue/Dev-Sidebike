package dev.sidebike.app.data

import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.ruleKey
import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.config.BundledConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for rule editing/merging (0.1.1-alpha rule CRUD APIs).
 * No Android APIs are used: [RuleEditor] works on immutable config values only.
 */
class RuleEditorTest {

    @Test
    fun applyRuleAddsAndOverwrites() {
        val config = SidebikeConfig()
        val first = RuleEditor.applyRule(
            config,
            RuleDraft(domainPattern = " API.Example.com ", pathRegex = "^/v1/", sni = "baidu.com"),
        )
        assertEquals(
            "baidu.com",
            first.intercepts["api.example.com"]?.get("^/v1/")?.sni,
        )

        val overwritten = RuleEditor.applyRule(
            first,
            RuleDraft(domainPattern = "api.example.com", pathRegex = "^/v1/", proxy = "mirror.example.com"),
        )
        val action = overwritten.intercepts["api.example.com"]?.get("^/v1/")
        assertNull("overwrite must replace the action", action?.sni)
        assertEquals("mirror.example.com", action?.proxy)
        assertEquals(1, overwritten.intercepts["api.example.com"]?.size)
    }

    @Test
    fun applyRuleClearsDisabledMarkerForThatRule() {
        val domain = "blocked.example.com"
        val path = ".*"
        val disabled = SidebikeConfig(disabledIntercepts = listOf(ruleKey(domain, path), "other\t.*"))
        val updated = RuleEditor.applyRule(disabled, RuleDraft(domainPattern = domain, pathRegex = path, sni = "x"))
        assertFalse(updated.disabledIntercepts.contains(ruleKey(domain, path)))
        assertTrue("unrelated disabled keys are preserved", updated.disabledIntercepts.contains("other\t.*"))
    }

    @Test
    fun deleteRuleRemovesUserRuleAndDisablesBuiltInRule() {
        val builtInDomain = BundledConfig.load().intercepts.keys.first()
        val builtInPath = BundledConfig.load().intercepts.getValue(builtInDomain).keys.first()
        val config = SidebikeConfig(
            intercepts = mapOf(
                builtInDomain to mapOf(builtInPath to InterceptAction(desc = "builtin")),
                "user.example.com" to mapOf(".*" to InterceptAction(proxy = "x")),
            ),
        )

        val afterBuiltIn = RuleEditor.deleteRule(config, builtInDomain, builtInPath)
        assertFalse(afterBuiltIn.intercepts.containsKey(builtInDomain))
        assertTrue(
            "deleting a bundled rule must persist a disable marker",
            afterBuiltIn.disabledIntercepts.contains(ruleKey(builtInDomain, builtInPath)),
        )

        val afterUser = RuleEditor.deleteRule(afterBuiltIn, "user.example.com", ".*")
        assertFalse(afterUser.intercepts.containsKey("user.example.com"))
        assertEquals("user rule must not add a disable marker", 1, afterUser.disabledIntercepts.size)
    }

    @Test
    fun deleteOnlyPathKeepsOtherPathsOfSameDomain() {
        val config = SidebikeConfig(
            intercepts = mapOf(
                "multi.example.com" to mapOf(
                    ".*" to InterceptAction(proxy = "a"),
                    "^/api" to InterceptAction(proxy = "b"),
                ),
            ),
        )
        val updated = RuleEditor.deleteRule(config, "multi.example.com", "^/api")
        assertEquals(1, updated.intercepts["multi.example.com"]?.size)
        assertTrue(updated.intercepts["multi.example.com"]!!.containsKey(".*"))
    }

    @Test
    fun setDirectPassDomainsReplacesManagedEntriesAndKeepsBuiltIns() {
        val builtInWhitelistDomain = BundledConfig.load().whiteList.entries
            .firstOrNull { it.value == true }?.key
        org.junit.Assume.assumeNotNull(
            "bundled snapshot should contain at least one whitelist entry",
            builtInWhitelistDomain,
        )

        val config = SidebikeConfig(
            whiteList = linkedMapOf(
                builtInWhitelistDomain!! to true,
                "old-managed.example.com" to true,
                "explicit-false.example.com" to false,
            ),
        )
        val updated = RuleEditor.setDirectPassDomains(
            config = config,
            domains = listOf("New-Managed.example.com", "new-managed.example.com", "https://url.example.com/path"),
            previouslyManaged = listOf("old-managed.example.com"),
        )

        assertEquals(true, updated.whiteList[builtInWhitelistDomain])
        assertFalse("previously managed entry removed", updated.whiteList.containsKey("old-managed.example.com"))
        assertEquals(true, updated.whiteList["new-managed.example.com"])
        assertEquals(true, updated.whiteList["url.example.com"])
        assertTrue("explicit false entries are preserved", updated.whiteList.containsKey("explicit-false.example.com"))
        assertEquals(false, updated.whiteList["explicit-false.example.com"])
    }

    @Test
    fun setDomesticAllowListOverwritesAndDeduplicates() {
        val config = SidebikeConfig(domesticAllowList = listOf("old.cn"))
        val updated = RuleEditor.setDomesticAllowList(config, listOf("a.cn", "A.cn", " b.cn ", ""))
        assertEquals(listOf("a.cn", "b.cn"), updated.domesticAllowList)
    }

    @Test
    fun normalizeDomainHandlesUrlsCommentsAndInvalidInput() {
        assertEquals("example.com", RuleEditor.normalizeDomain("HTTPS://Example.com/path?q=1"))
        assertEquals("example.com", RuleEditor.normalizeDomain("example.com."))
        assertEquals("example.com", RuleEditor.normalizeDomain(" example.com "))
        assertNull(RuleEditor.normalizeDomain(""))
        assertNull(RuleEditor.normalizeDomain("   "))
        assertNull(RuleEditor.normalizeDomain("# comment"))
        assertNull(RuleEditor.normalizeDomain("has space.com"))
        assertEquals("*.example.com", RuleEditor.normalizeInterceptDomain("*.Example.com"))
        assertEquals("^REGEX$", RuleEditor.normalizeInterceptDomain("^REGEX$"))
    }

    @Test
    fun mergeImportedIsIdempotentAndCountsOnlyNewEntries() {
        val imported = ImportedRuleSet(
            intercepts = mapOf(
                "github.com" to mapOf(
                    ".*" to InterceptAction(sni = "baidu.com"),
                    "^/api" to InterceptAction(proxy = "gh.example"),
                ),
            ),
            directPassDomains = listOf("local.example.com", "Local2.example.com"),
            domesticDomains = listOf("baidu.com", "qq.com"),
            preSetIpList = mapOf("github.com" to mapOf("20.27.177.113" to true)),
        )
        val base = SidebikeConfig()

        val first = RuleEditor.mergeImported(base, imported)
        assertEquals(2, first.addedIntercepts)
        assertEquals(2, first.addedDirectPassDomains)
        assertEquals(2, first.addedDomesticDomains)
        assertEquals("baidu.com", first.config.intercepts["github.com"]?.get(".*")?.sni)
        assertEquals(true, first.config.whiteList["local.example.com"])
        assertEquals(listOf("baidu.com", "qq.com"), first.config.domesticAllowList)

        val second = RuleEditor.mergeImported(first.config, imported)
        assertEquals("second import must be a no-op", 0, second.addedIntercepts)
        assertEquals(0, second.addedDirectPassDomains)
        assertEquals(0, second.addedDomesticDomains)
        assertEquals(first.config, second.config)
    }

    @Test
    fun mergeImportedPreservesUnrelatedConfig() {
        val base = SidebikeConfig(proxy = SidebikeConfig().proxy.copy(port = 32222))
        val merged = RuleEditor.mergeImported(
            base,
            ImportedRuleSet(directPassDomains = listOf("pass.example.com")),
        ).config
        assertEquals(32222, merged.proxy.port)
        assertEquals(true, merged.whiteList["pass.example.com"])
    }

    @Test
    fun importedPreSetIpListMergesPerDomain() {
        val base = SidebikeConfig(preSetIpList = mapOf("a.com" to mapOf("1.1.1.1" to true)))
        val merged = RuleEditor.mergeImported(
            base,
            ImportedRuleSet(
                preSetIpList = mapOf(
                    "a.com" to mapOf("2.2.2.2" to true),
                    "b.com" to mapOf("3.3.3.3" to true),
                ),
            ),
        ).config
        assertEquals(true, merged.preSetIpList["a.com"]?.get("1.1.1.1"))
        assertEquals(true, merged.preSetIpList["a.com"]?.get("2.2.2.2"))
        assertEquals(true, merged.preSetIpList["b.com"]?.get("3.3.3.3"))
    }

    @Test
    fun containsRuleAndFlattenRulesAreStable() {
        val config = RuleEditor.applyRule(
            RuleEditor.applyRule(SidebikeConfig(), RuleDraft("b.com")),
            RuleDraft("a.com", pathRegex = "^/x"),
        )
        assertTrue(RuleEditor.containsRule(config, "b.com", ".*"))
        assertFalse(RuleEditor.containsRule(config, "b.com", "^/missing"))
        val flattened = RuleEditor.flattenRules(config)
        assertEquals(listOf("a.com", "b.com"), flattened.map { it.first })
    }
}
