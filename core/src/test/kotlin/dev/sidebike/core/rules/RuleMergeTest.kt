package dev.sidebike.core.rules

import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merge semantics for [RuleMerge] (contract in `api/RuleSources.kt`): rules from a remote source must be
 * applied idempotently and never mutate/erase unrelated user configuration.
 */
class RuleMergeTest {

    @Test
    fun emptyImportReturnsSameConfig() {
        val config = SidebikeConfig(whiteList = mapOf("keep.example.com" to true))
        assertSame(config, RuleMerge.mergeInto(config, ImportedRuleSet.EMPTY))
    }

    @Test
    fun interceptsAreDeepMergedPerPathAndImportedWins() {
        val config = SidebikeConfig(
            intercepts = mapOf(
                "site.example.com" to mapOf(
                    ".*" to InterceptAction(sni = "baidu.com"),
                    "^/keep/.*" to InterceptAction(cacheDays = 3),
                ),
                "other.example.com" to mapOf(".*" to InterceptAction(abort = true)),
            ),
        )
        val imported = ImportedRuleSet(
            intercepts = mapOf(
                // same domain: adds one path, overrides ".*"
                "site.example.com" to mapOf(
                    ".*" to InterceptAction(abort = true),
                    "^/new/.*" to InterceptAction(cacheDays = 7),
                ),
                "added.example.com" to mapOf(".*" to InterceptAction(sni = "g.cn")),
            ),
        )

        val merged = RuleMerge.mergeInto(config, imported)

        assertEquals(true, merged.intercepts["site.example.com"]!![".*"]!!.abort)
        assertEquals(3, merged.intercepts["site.example.com"]!!["^/keep/.*"]!!.cacheDays)
        assertEquals(7, merged.intercepts["site.example.com"]!!["^/new/.*"]!!.cacheDays)
        assertEquals(true, merged.intercepts["other.example.com"]!![".*"]!!.abort)
        assertEquals("g.cn", merged.intercepts["added.example.com"]!![".*"]!!.sni)
        // original config untouched
        assertEquals("baidu.com", config.intercepts["site.example.com"]!![".*"]!!.sni)
    }

    @Test
    fun whiteListDomesticAndPresetAreMerged() {
        val config = SidebikeConfig(
            whiteList = mapOf("existing.example.com" to true),
            domesticAllowList = listOf("existing.cn"),
            preSetIpList = mapOf("existing.example.com" to mapOf("9.9.9.9" to true)),
        )
        val imported = ImportedRuleSet(
            directPassDomains = listOf("allow.example.com", "existing.example.com"),
            domesticDomains = listOf("new.cn", "existing.cn"),
            preSetIpList = mapOf(
                "existing.example.com" to mapOf("1.2.3.4" to true),
                "added.example.com" to mapOf("5.6.7.8" to true),
            ),
        )

        val merged = RuleMerge.mergeInto(config, imported)

        assertEquals(true, merged.whiteList["allow.example.com"])
        assertEquals(true, merged.whiteList["existing.example.com"])
        assertEquals(listOf("existing.cn", "new.cn"), merged.domesticAllowList)
        assertEquals(
            mapOf("9.9.9.9" to true, "1.2.3.4" to true),
            merged.preSetIpList["existing.example.com"],
        )
        assertEquals(mapOf("5.6.7.8" to true), merged.preSetIpList["added.example.com"])
        // everything else preserved
        assertEquals(config.proxy, merged.proxy)
        assertEquals(config.dns, merged.dns)
    }

    @Test
    fun mergeIsIdempotent() {
        val config = SidebikeConfig()
        val imported = ImportedRuleSet(
            intercepts = mapOf("a.example.com" to mapOf(".*" to InterceptAction(abort = true))),
            directPassDomains = listOf("b.example.com"),
            domesticDomains = listOf("c.cn"),
            preSetIpList = mapOf("d.example.com" to mapOf("1.1.1.1" to true)),
        )
        val once = RuleMerge.mergeInto(config, imported)
        val twice = RuleMerge.mergeInto(once, imported)
        assertEquals(once, twice)
        assertTrue(once.intercepts.size == 1)
    }
}
