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

/**
 * 0.1.1 rule-engine enhancements:
 *  - `InterceptAction.abort = true` -> `RouteKind.REJECT`;
 *  - `SidebikeConfig.disabledIntercepts` ("domain\tpath") hides built-in rules from match/plan/requiresMitm,
 *    including the CONNECT-phase MITM decision, and hot reload via `updateConfig` takes effect atomically.
 */
class RuleEngineDisabledAbortTest {

    private fun engine(config: SidebikeConfig) = DefaultRuleEngine(config, NoopSidebikeLogger)

    private fun config(
        intercepts: Map<String, Map<String, InterceptAction>>,
        disabled: List<String> = emptyList(),
        whiteList: Map<String, Boolean?> = emptyMap(),
        domestic: List<String> = emptyList(),
        presets: Map<String, Map<String, Boolean>> = emptyMap(),
    ) = SidebikeConfig(
        intercepts = intercepts,
        disabledIntercepts = disabled,
        whiteList = whiteList,
        domesticAllowList = domestic,
        preSetIpList = presets,
    )

    // ------------------------------------------------------------------------------------------
    // abort = true
    // ------------------------------------------------------------------------------------------

    @Test
    fun abortTrueProducesRejectPlan() {
        val rule = engine(config(mapOf("ads.example.com" to mapOf(".*" to InterceptAction(abort = true)))))
        val plan = rule.plan("ads.example.com", 443, "/anything")

        assertEquals(RouteKind.REJECT, plan.kind)
        assertEquals("abort", plan.reason)
        assertEquals(true, plan.action?.abort)
        assertEquals(true, rule.match("ads.example.com")?.abort)
        assertTrue("abort rules need MITM to be evaluated inside the tunnel", rule.requiresMitm("ads.example.com"))
    }

    @Test
    fun abortOnSpecificPathOnlyRejectsThatPath() {
        val rule = engine(
            config(
                mapOf(
                    "news.example.com" to mapOf(
                        "^/ads/.*" to InterceptAction(abort = true),
                        ".*" to InterceptAction(cacheDays = 1),
                    ),
                ),
            ),
        )
        assertEquals(RouteKind.REJECT, rule.plan("news.example.com", 443, "/ads/banner.png").kind)
        assertEquals(RouteKind.DIRECT, rule.plan("news.example.com", 443, "/article/1").kind)
    }

    @Test
    fun legacyAbortMarkerStillWorks() {
        // `success: {"abort": true}` marker used by 0.1.0 configs (before InterceptAction.abort existed)
        val marker = kotlinx.serialization.json.Json.parseToJsonElement("""{"abort":true}""")
        val rule = engine(config(mapOf("old.example.com" to mapOf(".*" to InterceptAction(success = marker)))))
        assertEquals(RouteKind.REJECT, rule.plan("old.example.com", 443, "/x").kind)
    }

    // ------------------------------------------------------------------------------------------
    // disabledIntercepts
    // ------------------------------------------------------------------------------------------

    @Test
    fun disabledInterceptHidesRuleCompletely() {
        val intercepts = mapOf("ads.example.com" to mapOf(".*" to InterceptAction(abort = true)))
        val rule = engine(config(intercepts))
        assertEquals(RouteKind.REJECT, rule.plan("ads.example.com", 443, "/x").kind)
        assertTrue(rule.requiresMitm("ads.example.com"))
        assertNotNull(rule.match("ads.example.com", "/x"))

        rule.updateConfig(config(intercepts, disabled = listOf("ads.example.com\t.*")))

        val plan = rule.plan("ads.example.com", 443, "/x")
        assertEquals(RouteKind.DIRECT, plan.kind)
        assertNull(plan.action)
        assertFalse("disabled rule must not force MITM", rule.requiresMitm("ads.example.com"))
        assertNull(rule.match("ads.example.com", "/x"))
        // CONNECT-phase plan (path unknown) must also report "no intercept"
        assertEquals("no-intercept", rule.plan("ads.example.com", 443, null).reason)
    }

    @Test
    fun partiallyDisabledDomainKeepsRemainingPathRules() {
        val intercepts = mapOf(
            "site.example.com" to mapOf(
                ".*" to InterceptAction(sni = "baidu.com"),
                "^/ads/.*" to InterceptAction(abort = true),
            ),
        )
        val rule = engine(config(intercepts, disabled = listOf("site.example.com\t^/ads/.*")))

        // disabled abort path: rule no longer matches -> plain sni rule -> DIRECT
        val plan = rule.plan("site.example.com", 443, "/ads/x")
        assertEquals(RouteKind.DIRECT, plan.kind)
        assertEquals("baidu.com", plan.sni)
        assertTrue(rule.requiresMitm("site.example.com"))

        // re-enable via hot reload -> abort applies again
        rule.updateConfig(config(intercepts))
        assertEquals(RouteKind.REJECT, rule.plan("site.example.com", 443, "/ads/x").kind)
    }

    @Test
    fun disabledKeysAreExactMatchesOnly() {
        val intercepts = mapOf("site.example.com" to mapOf(".*" to InterceptAction(abort = true)))
        // a key with the wrong path must not disable the rule
        val rule = engine(config(intercepts, disabled = listOf("site.example.com\t^/other$")))
        assertEquals(RouteKind.REJECT, rule.plan("site.example.com", 443, "/x").kind)
    }

    @Test
    fun updateConfigHotReloadsRulesAtomically() {
        val rule = engine(config(emptyMap()))
        assertEquals(RouteKind.DIRECT, rule.plan("new.example.com", 443, "/x").kind)

        rule.updateConfig(config(mapOf("new.example.com" to mapOf(".*" to InterceptAction(abort = true)))))
        assertEquals(RouteKind.REJECT, rule.plan("new.example.com", 443, "/x").kind)

        rule.updateConfig(config(emptyMap()))
        assertEquals(RouteKind.DIRECT, rule.plan("new.example.com", 443, "/x").kind)
    }

    // ------------------------------------------------------------------------------------------
    // imported rules merged into config are honoured by the engine
    // ------------------------------------------------------------------------------------------

    @Test
    fun importedRulesFromRuleMergeAreEffective() {
        val imported = dev.sidebike.core.api.ImportedRuleSet(
            intercepts = mapOf("tracked.example.com" to mapOf(".*" to InterceptAction(abort = true))),
            directPassDomains = listOf("allow.example.com"),
            domesticDomains = listOf("domestic.example.com"),
            preSetIpList = mapOf("pinned.example.com" to mapOf("1.2.3.4" to true)),
        )
        val merged = RuleMerge.mergeInto(SidebikeConfig(), imported)
        val rule = engine(merged)

        assertEquals(RouteKind.REJECT, rule.plan("tracked.example.com", 443, "/x").kind)
        assertTrue(rule.isWhitelisted("allow.example.com"))
        assertTrue(rule.isDomestic("domestic.example.com"))
        assertEquals(listOf("1.2.3.4"), rule.presetIps("pinned.example.com"))
    }
}
