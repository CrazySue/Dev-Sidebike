package dev.sidebike.core.rules

import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.RuleSourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table/behaviour tests for the rule-source parsers (pure, no I/O). Covers all three formats, AUTO
 * detection, BOM/encoding tolerance, hostile input and the analyst-provided Shadowrocket `.conf` contract
 * (`[General] [Rule] [Host] [URL Rewrite] [MITM]` sections; only `[Rule]` is parsed).
 */
class RuleSourceParsersTest {

    private fun parse(content: String, format: RuleSourceFormat = RuleSourceFormat.AUTO, url: String = ""): ImportedRuleSet =
        RuleSourceParsers.parse(content, format, "test-source", url)

    private fun abortFor(set: ImportedRuleSet, pattern: String): Boolean? =
        set.intercepts[pattern]?.get(".*")?.abort

    // ------------------------------------------------------------------------------------------
    // AUTO detection
    // ------------------------------------------------------------------------------------------

    @Test
    fun autoDetectionByUrlSuffixAndContent() {
        assertEquals(
            RuleSourceFormat.SIDEBIKE_JSON,
            RuleSourceParsers.detectFormat("https://example.com/rules.json", "DOMAIN,x.com,REJECT"),
        )
        assertEquals(
            RuleSourceFormat.SHADOWROCKET_LIST,
            RuleSourceParsers.detectFormat("https://example.com/rules.list", "{\"x\":1}"),
        )
        // content section headers => Shadowrocket (analyst spec)
        assertEquals(
            RuleSourceFormat.SHADOWROCKET_LIST,
            RuleSourceParsers.detectFormat("https://example.com/rules.conf", "[General]\n[Rule]\nDOMAIN,x.com,REJECT"),
        )
        // JSON content
        assertEquals(
            RuleSourceFormat.SIDEBIKE_JSON,
            RuleSourceParsers.detectFormat("https://example.com/download", "{\"intercepts\":{}}"),
        )
        // Shadowrocket lines without section headers / suffix
        assertEquals(
            RuleSourceFormat.SHADOWROCKET_LIST,
            RuleSourceParsers.detectFormat("https://example.com/download", "DOMAIN-SUFFIX,ads.com,REJECT"),
        )
        // fallback: plain domain list
        assertEquals(
            RuleSourceFormat.DOMAIN_LIST,
            RuleSourceParsers.detectFormat("https://example.com/download", "example.com\nexample.org\n"),
        )
    }

    // ------------------------------------------------------------------------------------------
    // SIDEBIKE_JSON
    // ------------------------------------------------------------------------------------------

    @Test
    fun sidebikeJsonStandardShape() {
        val content = """
            {
              "intercepts": {
                "ads.example.com": { ".*": { "abort": true } },
                "github.com": { ".*": { "sni": "baidu.com" }, "^/raw/.*": { "cacheDays": 365 } }
              },
              "whiteList": { "*.internal.example.com": true, "disabled.example.com": false },
              "domesticAllowList": ["example.cn", "example.cn"],
              "preSetIpList": { "github.com": { "20.205.243.166": true, "140.82.121.3": false } }
            }
        """.trimIndent()
        val set = parse(content, RuleSourceFormat.SIDEBIKE_JSON)

        assertEquals(true, abortFor(set, "ads.example.com"))
        assertEquals("baidu.com", set.intercepts["github.com"]?.get(".*")?.sni)
        assertEquals(365, set.intercepts["github.com"]?.get("^/raw/.*")?.cacheDays)
        assertEquals(listOf("*.internal.example.com"), set.directPassDomains)
        assertFalse(set.directPassDomains.contains("disabled.example.com"))
        assertEquals(listOf("example.cn"), set.domesticDomains)
        assertEquals(mapOf("20.205.243.166" to true), set.preSetIpList["github.com"])
        assertTrue(set.warnings.isEmpty())
    }

    @Test
    fun sidebikeJsonBareTrueActionMeansAbort() {
        val set = parse("""{"intercepts":{"tracker.example":true}}""", RuleSourceFormat.SIDEBIKE_JSON)
        assertEquals(true, abortFor(set, "tracker.example"))
    }

    @Test
    fun sidebikeJsonRulesArrayAndWrapperShapes() {
        val arrayShape = """
            {"data":{"rules":[
              {"domain":"a.example.com","path":".*","action":{"abort":true}},
              {"pattern":"b.example.com","sni":"baidu.com"},
              {"domain":"c.example.com","action":{"cacheDays":7}}
            ]}}
        """.trimIndent()
        val fromArray = parse(arrayShape)
        assertEquals(true, abortFor(fromArray, "a.example.com"))
        assertEquals("baidu.com", fromArray.intercepts["b.example.com"]?.get(".*")?.sni)
        assertEquals(7, fromArray.intercepts["c.example.com"]?.get(".*")?.cacheDays)

        // {"rules": {...}} object form is treated as the intercepts table
        val rulesObjectShape = """{"rules":{"d.example.com":{".*":{"abort":true}}}}"""
        val fromRulesObject = parse(rulesObjectShape)
        assertEquals(true, abortFor(fromRulesObject, "d.example.com"))
    }

    @Test
    fun sidebikeJsonMasculineInputYieldsWarningsNotExceptions() {
        val brokenJson = parse("{not a valid json")
        assertTrue(brokenJson.warnings.isNotEmpty())
        assertEquals(0, brokenJson.ruleCount)

        val partlyInvalid = parse(
            """
            {"intercepts":{
               "ok.example.com":{".*":{"abort":true}},
               "bad.example.com":{"path":["not","an","action"]},
               "worse.example.com":"not-an-object"
            }}
            """.trimIndent(),
        )
        assertEquals(true, abortFor(partlyInvalid, "ok.example.com"))
        assertTrue("invalid entries must produce warnings: ${partlyInvalid.warnings}", partlyInvalid.warnings.isNotEmpty())
    }

    @Test
    fun sidebikeJsonEmptyInput() {
        val set = parse("   ", RuleSourceFormat.SIDEBIKE_JSON)
        assertTrue(set.warnings.isNotEmpty())
        assertEquals(0, set.ruleCount)
    }

    // ------------------------------------------------------------------------------------------
    // SHADOWROCKET_LIST (analyst .conf contract)
    // ------------------------------------------------------------------------------------------

    private val shadowrocketConf = """
        [General]
        bypass-system = true
        skip-proxy = 127.0.0.1

        # Only this section must be parsed
        [Rule]
        DOMAIN-SUFFIX,cn,DIRECT
        DOMAIN-SUFFIX,ads.example.com,REJECT
        DOMAIN,exact.example.com,REJECT-DROP
        DOMAIN-WILDCARD,*.wild.example.com,DIRECT
        DOMAIN-KEYWORD,telemetry,REJECT
        IP-CIDR,1.2.3.0/24,DIRECT,no-resolve
        USER-AGENT,MicroMessenger*,DIRECT
        PROXY-POLICY,some.example.com,PROXY
        FINAL,DIRECT

        [Host]
        0.0.0.0 host-section.example.com
        127.0.0.1 another-host.example.com

        [URL Rewrite]
        ^https?://example\.com/rewrite - reject
    """.trimIndent()

    @Test
    fun shadowrocketParsesOnlyRuleSectionAndMapsPolicies() {
        val set = parse(shadowrocketConf, RuleSourceFormat.SHADOWROCKET_LIST)

        // DIRECT -> directPassDomains, DOMAIN-SUFFIX is imported as "*.domain"
        assertTrue(set.directPassDomains.contains("*.cn"))
        assertTrue(set.directPassDomains.contains("*.wild.example.com"))
        // REJECT / REJECT-DROP -> domain-wide abort intercepts
        assertEquals(true, abortFor(set, "*.ads.example.com"))
        assertEquals(true, abortFor(set, "exact.example.com"))
        // DOMAIN-KEYWORD -> regex intercept (capped)
        assertTrue(set.intercepts.keys.any { it.contains("telemetry") })
        // Other sections are ignored
        assertNull(set.intercepts["host-section.example.com"])
        assertFalse(set.directPassDomains.contains("host-section.example.com"))
        // Unsupported types produce warnings
        assertTrue("IP-CIDR/FINAL/USER-AGENT warnings expected: ${set.warnings}", set.warnings.size >= 3)
        assertTrue(set.warnings.any { it.contains("IP-CIDR") })
    }

    @Test
    fun shadowrocketLaterRuleOverridesEarlier() {
        val set = parse(
            """
            [Rule]
            DOMAIN-SUFFIX,flip.example.com,DIRECT
            DOMAIN-SUFFIX,flip.example.com,REJECT
            DOMAIN,flip2.example.com,REJECT
            DOMAIN,flip2.example.com,DIRECT
            """.trimIndent(),
            RuleSourceFormat.SHADOWROCKET_LIST,
        )
        assertEquals(true, abortFor(set, "*.flip.example.com"))
        assertFalse(set.directPassDomains.contains("*.flip.example.com"))
        assertTrue(set.directPassDomains.contains("flip2.example.com"))
        assertNull(set.intercepts["flip2.example.com"])
    }

    @Test
    fun shadowrocketPolicyIsCaseInsensitive() {
        val set = parse(
            """
            [Rule]
            DOMAIN-SUFFIX,upper.example.com,reject
            DOMAIN,lower.example.com,direct
            """.trimIndent(),
            RuleSourceFormat.SHADOWROCKET_LIST,
        )
        assertEquals(true, abortFor(set, "*.upper.example.com"))
        assertTrue(set.directPassDomains.contains("lower.example.com"))
    }

    @Test
    fun shadowrocketEmptyInputWarnsAndReturnsEmpty() {
        val set = parse("# only comments\n\n", RuleSourceFormat.SHADOWROCKET_LIST)
        assertEquals(0, set.ruleCount)
        assertTrue(set.warnings.isNotEmpty())
    }

    @Test
    fun shadowrocketWarningCountIsCapped() {
        val content = buildString {
            appendLine("[Rule]")
            repeat(200) { appendLine("IP-CIDR,10.0.$it.0/24,DIRECT") }
        }
        val set = parse(content, RuleSourceFormat.SHADOWROCKET_LIST)
        assertTrue("warnings should be capped, was ${set.warnings.size}", set.warnings.size <= 102)
        assertTrue(set.warnings.any { it.contains("其余警告已省略") } || set.warnings.size < 110)
    }

    // ------------------------------------------------------------------------------------------
    // DOMAIN_LIST / HOSTS
    // ------------------------------------------------------------------------------------------

    @Test
    fun domainListParsesPlainDomainsAndComments() {
        val content = """
            # comment
            ; another comment
            // slash comment
            example.com
            *.wild.example.com
            1.1.1.1 plain.example.com
            bad domain line
            ""
        """.trimIndent()
        val set = parse(content, RuleSourceFormat.DOMAIN_LIST)
        assertTrue(set.directPassDomains.contains("example.com"))
        assertTrue(set.directPassDomains.contains("*.wild.example.com"))
        assertFalse(set.directPassDomains.any { it.contains(' ') })
        assertTrue(set.warnings.isNotEmpty()) // invalid line surfaced
    }

    @Test
    fun hostsFormatMapsBlockingIpsToAbortAndOtherIpsToPreset() {
        val content = """
            0.0.0.0 ads.example.com tracker.example.com
            127.0.0.1 local-block.example.com
            ::1 v6-block.example.com
            1.2.3.4 pinned.example.com
        """.trimIndent()
        val set = parse(content, RuleSourceFormat.DOMAIN_LIST)
        assertEquals(true, abortFor(set, "ads.example.com"))
        assertEquals(true, abortFor(set, "tracker.example.com"))
        assertEquals(true, abortFor(set, "local-block.example.com"))
        assertEquals(true, abortFor(set, "v6-block.example.com"))
        assertEquals(mapOf("1.2.3.4" to true), set.preSetIpList["pinned.example.com"])
        assertTrue(set.warnings.any { it.contains("hosts") })
    }

    @Test
    fun adblockSyntaxIsMapped() {
        val content = """
            ||blocked.example.com^
            @@||allowed.example.com^
        """.trimIndent()
        val set = parse(content, RuleSourceFormat.DOMAIN_LIST)
        assertTrue(set.intercepts.keys.any { it.contains("blocked.example.com") })
        assertTrue(set.directPassDomains.any { it.contains("allowed.example.com") })
    }

    @Test
    fun bomAndWindowsLineEndingsAreTolerated() {
        val content = "\uFEFF[Rule]\r\nDOMAIN-SUFFIX,bom.example.com,REJECT\r\n"
        val set = parse(content, RuleSourceFormat.SHADOWROCKET_LIST)
        assertEquals(true, abortFor(set, "*.bom.example.com"))
    }

    @Test
    fun unknownFormatNeverThrows() {
        val set = parse("\u0000\u0001binary junk that is not a rule list", RuleSourceFormat.DOMAIN_LIST)
        assertNotNull(set)
        assertEquals(0, set.ruleCount)
    }
}
