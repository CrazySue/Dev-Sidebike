package dev.sidebike.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.config.BundledConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [SidebikeConfigStore]: persistence roundtrip, corrupt-blob fallback and the
 * best-effort upstream Dev-Sidecar `running.json` importer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SidebikeConfigStoreTest {

    private lateinit var context: Context
    private lateinit var store: SidebikeConfigStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = SidebikeConfigStore(context)
        store.resetToDefaults()
    }

    @Test
    fun loadWithoutPersistedConfigReturnsBundledDefaults() {
        val config = store.load()
        // QA-004: first launch must show the bundled official rules, not an empty config.
        assertTrue("bundled intercept rules must be present", config.intercepts.isNotEmpty())
        assertTrue("bundled whitelist must be present", config.whiteList.isNotEmpty())
        assertTrue("bundled DNS providers must be present", config.dns.providers.isNotEmpty())
        assertEquals(31181, config.proxy.port)
        assertEquals("10.111.0.2", config.dns.vpnDnsAddress)
        // Must be exactly what `core` bundles (so the Rules page shows the expected ~69 domains).
        assertEquals(BundledConfig.load(), config)
    }

    @Test
    fun saveThenLoadRoundtripsFullConfig() {
        val custom = SidebikeConfig(
            app = SidebikeConfig().app.copy(autoStartOnBoot = true, logLevel = "debug"),
            proxy = SidebikeConfig().proxy.copy(port = 32222, mitmEnabled = true),
            dns = SidebikeConfig().dns.copy(vpnDnsAddress = "10.222.0.2", tunAddress = "10.222.0.1"),
            intercepts = mapOf(
                "github.com" to mapOf(
                    ".*" to InterceptAction(sni = "baidu.com", proxy = "ghproxy.example"),
                ),
            ),
            whiteList = mapOf("*.icloud.com" to true, "cn.*" to null),
            preSetIpList = mapOf("github.com" to mapOf("20.27.177.113" to true)),
            excludeIpList = mapOf("192.168.*" to true),
        )
        store.save(custom)

        // A fresh store instance reads the same SharedPreferences file.
        val reloaded = SidebikeConfigStore(context).load()
        assertEquals(custom, reloaded)
        assertEquals("baidu.com", reloaded.intercepts["github.com"]?.get(".*")?.sni)
        assertTrue(reloaded.app.autoStartOnBoot)
    }

    @Test
    fun corruptBlobFallsBackToBundledDefaultsInsteadOfCrashing() {
        context.getSharedPreferences(SidebikeConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SidebikeConfigStore.KEY_CONFIG_JSON, "{ this is not json")
            .commit()

        val config = store.load()
        assertTrue("corrupt blob must fall back to bundled rules", config.intercepts.isNotEmpty())
        assertEquals(BundledConfig.load(), config)
    }

    @Test
    fun exportRememberedAfterSave() {
        store.save(SidebikeConfig(proxy = SidebikeConfig().proxy.copy(port = 30000)))
        val exported = store.export()
        assertTrue(exported.contains("30000"))
        assertTrue(exported.contains("\"proxy\""))
    }

    @Test
    fun importUpstreamRunningJsonSubset() {
        val upstream = """
            {
              "server": {
                "enabled": true,
                "port": 31181,
                "intercepts": {
                  "github.com": {
                    ".*": { "sni": "baidu.com", "cacheDays": 7 }
                  },
                  "fonts.googleapis.com": {
                    ".*": { "proxy": "fonts.googleapis.cn" }
                  },
                  "disabled.example.com": null
                },
                "whiteList": { "*.icloud.com": true, "cn.*": null },
                "preSetIpList": {
                  "github.com": { "20.27.177.113": true, "140.82.112.3": false }
                },
                "dns": {
                  "providers": {
                    "aliyun": { "server": "223.5.5.5", "type": "udp" },
                    "cf-DoT": { "server": "tls://1.1.1.1", "type": "tls", "sni": "baidu.com" },
                    "doh.pub": { "server": "https://doh.pub/dns-query", "type": "https" }
                  },
                  "mapping": { "google.com": "cf-DoT" },
                  "familyMapping": { "youtube.com": "4" }
                },
                "clientId": 123,
                "fakeServerMaxLength": 100
              },
              "app": { "remoteConfig": { "enabled": true } }
            }
        """.trimIndent()

        val result = store.importUpstream(upstream)
        val config = result.config

        assertTrue(result.importedSections.contains("proxy"))
        assertTrue(result.importedSections.contains("intercepts"))
        assertTrue(result.importedSections.contains("dns"))
        assertEquals(31181, config.proxy.port)
        // Intercept actions are decoded into the frozen core model.
        assertEquals("baidu.com", config.intercepts["github.com"]?.get(".*")?.sni)
        assertEquals(7, config.intercepts["github.com"]?.get(".*")?.cacheDays)
        assertEquals("fonts.googleapis.cn", config.intercepts["fonts.googleapis.com"]?.get(".*")?.proxy)
        assertFalse(config.intercepts.containsKey("disabled.example.com"))
        // DNS providers: type strings mapped onto the DnsProtocol enum; settings preserved.
        assertEquals(DnsProtocol.UDP, config.dns.providers["aliyun"]?.protocol)
        assertEquals(DnsProtocol.DOT, config.dns.providers["cf-DoT"]?.protocol)
        assertEquals("baidu.com", config.dns.providers["cf-DoT"]?.sni)
        assertEquals(DnsProtocol.DOH, config.dns.providers["doh.pub"]?.protocol)
        assertEquals("cf-DoT", config.dns.mapping["google.com"])
        assertEquals("4", config.dns.familyMapping["youtube.com"])
        // whiteList null values survive (upstream uses null to disable a default entry).
        assertEquals(true, config.whiteList["*.icloud.com"])
        assertTrue(config.whiteList.containsKey("cn.*"))
        assertNull(config.whiteList["cn.*"])
        // preSetIpList truthiness: true enabled, false disabled.
        assertEquals(true, config.preSetIpList["github.com"]?.get("20.27.177.113"))
        assertEquals(false, config.preSetIpList["github.com"]?.get("140.82.112.3"))
        // Unknown sections/fields are ignored without warnings.
        assertTrue(result.warnings.none { it.contains("app") })
    }

    @Test
    fun importUpstreamProviderBootstrapIps() {
        val upstream = """
            {
              "server": {
                "dns": {
                  "providers": {
                    "cf-DoH": {
                      "server": "https://cloudflare-dns.com/dns-query",
                      "type": "https",
                      "ip": "1.1.1.1",
                      "preSetIpList": ["1.1.1.1", "1.0.0.1"]
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val provider = store.importUpstream(upstream).config.dns.providers["cf-DoH"]
        assertNotNull(provider)
        assertTrue(provider!!.bootstrapIps.contains("1.1.1.1"))
        assertTrue(provider.bootstrapIps.contains("1.0.0.1"))
    }

    @Test
    fun importOwnExportedJsonRoundtrips() {
        val custom = SidebikeConfig(proxy = SidebikeConfig().proxy.copy(port = 34567))
        store.save(custom)
        val result = store.importUpstream(store.export())
        assertEquals(custom.proxy.port, result.config.proxy.port)
    }

    @Test
    fun importGarbageReportsWarningAndBundledDefaults() {
        val result = store.importUpstream("not-a-json-document")
        assertTrue(result.warnings.isNotEmpty())
        assertTrue("import failure must not wipe the bundled rules", result.config.intercepts.isNotEmpty())
    }
}
