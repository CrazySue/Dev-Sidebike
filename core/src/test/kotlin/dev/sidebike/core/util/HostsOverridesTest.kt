package dev.sidebike.core.util

import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.hosts.HostsOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsOverridesTest {

    @Test
    fun `parses hosts file and skips invalid lines`() {
        val hosts = HostsOverrides.parseHostsFile(
            """
            # comment
            127.0.0.1 localhost localhost.localdomain
            1.2.3.4 example.com
            not-an-ip bad.example.com
                10.0.0.1   spaced.example.com   # trailing comment
            """.trimIndent(),
        )
        assertEquals(listOf("127.0.0.1"), hosts["localhost"])
        assertEquals(listOf("127.0.0.1"), hosts["localhost.localdomain"])
        assertEquals(listOf("1.2.3.4"), hosts["example.com"])
        assertNull(hosts["bad.example.com"])
        assertEquals(listOf("10.0.0.1"), hosts["spaced.example.com"])
    }

    @Test
    fun `config hosts override wins with comma separated ips and wildcards`() {
        val config = SidebikeConfig(
            dns = DnsSettings(
                hosts = linkedMapOf(
                    "exact.example.com" to "1.1.1.1, 2.2.2.2",
                    "*.wild.example.com" to "9.9.9.9",
                    "bad" to "not-an-ip",
                ),
            ),
        )
        val overrides = HostsOverrides.fromConfig(config)
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), overrides.resolve("exact.example.com"))
        assertEquals(listOf("9.9.9.9"), overrides.resolve("a.wild.example.com"))
        assertNull(overrides.resolve("wild.example.com"))
        assertNull(overrides.resolve("bad"))
    }

    @Test
    fun `extra hosts file lower priority than config`() {
        val config = SidebikeConfig(dns = DnsSettings(hosts = linkedMapOf("a.com" to "3.3.3.3")))
        val overrides = HostsOverrides.fromConfig(config, extraHostsText = "1.1.1.1 a.com")
        assertEquals(listOf("3.3.3.3"), overrides.resolve("a.com"))
    }

    @Test
    fun `empty overrides`() {
        val overrides = HostsOverrides.fromConfig(SidebikeConfig())
        assertTrue(overrides.isEmpty())
        assertNull(overrides.resolve("x.com"))
        assertNotNull(overrides.entries())
    }
}
