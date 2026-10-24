package dev.sidebike.core.config

import dev.sidebike.core.api.SidebikeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): DefaultConfigRepository = DefaultConfigRepository(tmp.newFolder("cfg"))

    @Test
    fun `load without user file equals bundled config`() {
        val repo = repo()
        val loaded = repo.load()
        val bundled = BundledConfig.load()
        assertEquals(bundled.intercepts.keys, loaded.intercepts.keys)
        assertEquals(bundled.dns.providers.keys, loaded.dns.providers.keys)
        assertEquals(SidebikeConfig.CURRENT_VERSION, loaded.version)
    }

    @Test
    fun `save then reload persists override while keeping bundled rules`() {
        val dataDir = tmp.newFolder("persist")
        val repo = DefaultConfigRepository(dataDir)
        val base = repo.load()
        val modified = base.copy(proxy = base.proxy.copy(port = 23456))
        repo.save(modified)

        val reloaded = DefaultConfigRepository(dataDir).load()
        assertEquals(23456, reloaded.proxy.port)
        assertEquals(base.intercepts.keys, reloaded.intercepts.keys)
        assertEquals(base.preSetIpList.keys, reloaded.preSetIpList.keys)
    }

    @Test
    fun `user config file is JSON5 tolerant and partial`() {
        val dataDir = tmp.newFolder("json5")
        File(dataDir, "config.json").writeText(
            """
            {
              // user override
              proxy: { port: 34567, mitmEnabled: true, },
            }
            """.trimIndent(),
        )
        val loaded = DefaultConfigRepository(dataDir).load()
        assertEquals(34567, loaded.proxy.port)
        assertTrue(loaded.proxy.mitmEnabled)
        assertTrue("内置规则应保留", loaded.intercepts.size > 50)
    }

    @Test
    fun `corrupted user file falls back to bundled config`() {
        val dataDir = tmp.newFolder("broken")
        File(dataDir, "config.json").writeText("{ this is not json")
        val loaded = DefaultConfigRepository(dataDir).load()
        assertTrue(loaded.intercepts.size > 50)
    }

    @Test
    fun `import upstream then export roundtrip`() {
        val repo = repo()
        val imported = repo.importUpstream(
            """
            {
              "port": 31181,
              "setting": {},
              "intercepts": { "only.example.com": { ".*": { "sni": "baidu.com" } } }
            }
            """.trimIndent(),
        )
        assertEquals(1, imported.intercepts.size)
        val exported = repo.export()
        assertTrue(exported.contains("only.example.com"))
        assertTrue(exported.contains("\"version\""))
        assertTrue(exported.contains("intercepts"))
    }

    @Test
    fun `defaults does not include user overrides`() {
        val dataDir = tmp.newFolder("defaults")
        val repo = DefaultConfigRepository(dataDir)
        repo.save(repo.load().copy(proxy = repo.load().proxy.copy(port = 45678)))
        assertEquals(31181, repo.defaults().proxy.port)
        assertEquals(45678, repo.load().proxy.port)
    }

    @Test
    fun `invalidate forces reload`() {
        val dataDir = tmp.newFolder("invalidate")
        val repo = DefaultConfigRepository(dataDir)
        val first = repo.load()
        File(dataDir, "config.json").writeText("""{"proxy":{"port":11111}}""")
        repo.invalidate()
        val second = repo.load()
        assertEquals(11111, second.proxy.port)
        assertNotEquals(first.proxy.port, second.proxy.port)
    }
}
