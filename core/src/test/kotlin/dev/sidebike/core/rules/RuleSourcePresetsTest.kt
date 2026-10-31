package dev.sidebike.core.rules

import dev.sidebike.core.api.RuleSourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presets for the remote rule-source UI: stable ids/URLs, manual-only banlist, license/provenance constants. */
class RuleSourcePresetsTest {

    @Test
    fun defaultPresetsAreCompleteAndConsistent() {
        val sources = RuleSourcePresets.defaultSources()
        assertEquals(3, sources.size)
        assertEquals(3, sources.map { it.id }.toSet().size)

        sources.forEach { source ->
            assertEquals(RuleSourceFormat.SHADOWROCKET_LIST, source.format)
            assertTrue(source.url.startsWith(RuleSourcePresets.RAW_BASE))
            assertTrue(source.url.endsWith(".conf"))
            assertTrue(source.updateIntervalHours >= 0)
        }

        val cnip = sources.first { it.id == "sr_cnip" }
        assertTrue("recommended source must be enabled by default", cnip.enabled)
        assertTrue(cnip.autoUpdate)
        assertEquals(24, cnip.updateIntervalHours)
        assertTrue(cnip.url.endsWith(RuleSourcePresets.FILE_CNIP))

        val banlist = sources.first { it.id == "sr_top500_banlist" }
        assertFalse("1MB banlist must be manual-only", banlist.enabled)
        assertFalse(banlist.autoUpdate)
        assertEquals(0, banlist.updateIntervalHours)
        assertTrue(banlist.url.endsWith(RuleSourcePresets.FILE_TOP500_BANLIST))
    }

    @Test
    fun urlsCoverRawAndPagesMirrors() {
        assertEquals(
            "https://raw.githubusercontent.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever/release/sr_cnip.conf",
            RuleSourcePresets.rawUrl(RuleSourcePresets.FILE_CNIP),
        )
        assertEquals(
            "https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/sr_cnip.conf",
            RuleSourcePresets.pagesUrl(RuleSourcePresets.FILE_CNIP),
        )
    }

    @Test
    fun licenseAttributionConstantsArePresent() {
        assertEquals("CC BY-SA 4.0", RuleSourcePresets.LICENSE)
        assertTrue(RuleSourcePresets.LICENSE_URL.startsWith("https://"))
        assertTrue(RuleSourcePresets.REPO.contains("Shadowrocket-ADBlock-Rules"))
    }
}
