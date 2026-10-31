package dev.sidebike.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.DnsProvider
import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.config.BundledConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Persistence for [SidebikeConfig].
 *
 * The full config is stored as one JSON blob in a dedicated SharedPreferences file. The JSON is
 * produced/consumed by kotlinx.serialization with `ignoreUnknownKeys` + `isLenient`, matching the
 * core contract (`docs/architecture/02-core-contracts.md`). Corrupt or partially migrated blobs
 * fall back to defaults instead of crashing the app.
 *
 * Persistence is intentionally SharedPreferences rather than DataStore: the VpnService and the
 * BootReceiver both need the config synchronously when starting from a background context, and the
 * blob is small (<100 KB worst case).
 */
class SidebikeConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    /**
     * Bundled defaults: `core`'s built-in defaults merged with the offline official-rules snapshot
     * (`intercepts` / `preSetIpList` / `whiteList` / `dns.providers` / `dns.mapping` …). Used whenever
     * the user has no persisted override yet, so the UI/Rules page is populated on first launch
     * instead of showing an empty config (QA-004). Loaded once and cached (the snapshot is ~40 KB).
     */
    @Volatile
    private var cachedBundledDefaults: SidebikeConfig? = null

    private fun bundledDefaults(): SidebikeConfig {
        cachedBundledDefaults?.let { return it }
        return synchronized(this) {
            cachedBundledDefaults ?: runCatching { BundledConfig.load() }
                .getOrElse { error ->
                    Log.w(TAG, "Bundled rules snapshot unavailable, falling back to API defaults", error)
                    SidebikeConfig()
                }
                .also { cachedBundledDefaults = it }
        }
    }

    /** Loads the persisted config; returns bundled defaults when nothing is stored or the blob is corrupt. */
    fun load(): SidebikeConfig {
        val raw = prefs.getString(KEY_CONFIG_JSON, null) ?: return bundledDefaults()
        return runCatching { json.decodeFromString(SidebikeConfig.serializer(), raw) }
            .getOrElse { error ->
                Log.w(TAG, "Persisted config is corrupt, falling back to bundled defaults", error)
                bundledDefaults()
            }
    }

    /** Persists [config]. Uses `commit()` so a service start immediately afterwards always sees it. */
    fun save(config: SidebikeConfig) {
        val encoded = runCatching { json.encodeToString(SidebikeConfig.serializer(), config) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to encode config", error)
                return
            }
        // commit() (not apply()) is deliberate: controller -> service hand-off is cross-component.
        check(prefs.edit().putString(KEY_CONFIG_JSON, encoded).commit()) {
            "Failed to persist SidebikeConfig"
        }
    }

    /** Full JSON export of the current stored config (diagnostics / backup). */
    fun export(): String =
        runCatching { json.encodeToString(SidebikeConfig.serializer(), load()) }
            .getOrElse { "{}" }

    /** Raw JSON export of an arbitrary config value. */
    fun encode(config: SidebikeConfig): String =
        runCatching { json.encodeToString(SidebikeConfig.serializer(), config) }.getOrElse { "{}" }

    fun resetToDefaults() {
        prefs.edit().remove(KEY_CONFIG_JSON).commit()
    }

    /** Result of a best-effort upstream import. */
    data class ImportResult(
        val config: SidebikeConfig,
        /** Sections that were imported successfully (human readable). */
        val importedSections: List<String>,
        /** Non-fatal problems the caller should surface in the UI. */
        val warnings: List<String>,
    )

    /**
     * Best-effort import of an upstream Dev-Sidecar configuration.
     *
     * Accepts either:
     *  - an upstream `running.json`/`config.json` shaped document (the interesting sections live
     *    under `server.*`; a flat document with the same section names is also accepted), or
     *  - our own [SidebikeConfig] JSON, which decodes directly.
     *
     * Upstream concepts that have no Android equivalent (system proxy scripts, plugins, Node.js
     * settings, remote-config metadata) are skipped with a warning. Nothing here throws: a bad
     * document yields defaults plus warnings.
     */
    fun importUpstream(importedJson: String): ImportResult {
        val sections = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val rootElement = runCatching { json.parseToJsonElement(importedJson) }
            .getOrElse { error ->
                return ImportResult(
                    config = bundledDefaults(),
                    importedSections = emptyList(),
                    warnings = listOf("JSON 解析失败: ${error.message}"),
                )
            }
        val root = rootElement as? JsonObject
            ?: return ImportResult(
                config = bundledDefaults(),
                importedSections = emptyList(),
                warnings = listOf("JSON 顶层必须是对象"),
            )

        val server = root["server"] as? JsonObject
        if (server == null) {
            // Assume our own native format (possibly produced by export()).
            val decoded = runCatching { json.decodeFromJsonElement(SidebikeConfig.serializer(), root) }
                .getOrElse { error ->
                    return ImportResult(
                        config = bundledDefaults(),
                        importedSections = emptyList(),
                        warnings = listOf("无法识别的配置格式: ${error.message}"),
                    )
                }
            return ImportResult(decoded, listOf("完整配置"), emptyList())
        }

        // Start from the bundled official rules so an import only *overrides* the sections the
        // upstream document actually contains, instead of wiping the built-in rules (QA-004).
        var config = bundledDefaults()

        config = config.importServerProxy(server, warnings)?.also { sections += "proxy" } ?: config
        config = config.importServerDns(server["dns"] as? JsonObject, warnings)?.also { sections += "dns" } ?: config
        config = config.importIntercepts(server["intercepts"] as? JsonObject, warnings)?.also { sections += "intercepts" } ?: config
        config = config.importBooleanMap(
            source = server["whiteList"] as? JsonObject,
            target = config.whiteList,
            warnings = warnings,
            sectionName = "whiteList",
        )?.let { parsedWhiteList -> config.copy(whiteList = parsedWhiteList) }
            ?.also { sections += "whiteList" } ?: config
        config = config.importPreSetIpList(server["preSetIpList"] as? JsonObject, warnings)
            ?.also { sections += "preSetIpList" } ?: config
        config = config.importExcludeIpList(server["excludeIpList"] as? JsonObject, warnings)
            ?.also { sections += "excludeIpList" } ?: config

        if (sections.isEmpty()) warnings += "未找到可导入的 Dev-Sidecar 配置段"
        return ImportResult(config, sections, warnings)
    }

    // ---------------------------------------------------------------- upstream mappers

    private fun SidebikeConfig.importServerProxy(server: JsonObject, warnings: MutableList<String>): SidebikeConfig? {
        val port = (server["port"] as? JsonPrimitive)?.intOrNull
        val enabled = (server["enabled"] as? JsonPrimitive)?.booleanOrNull
        val proxySettings = proxy.copy(
            port = port?.takeIf { it in 1..65535 } ?: proxy.port,
            enabled = enabled ?: proxy.enabled,
        )
        if (port == null && enabled == null) return null
        if (port != null && port !in 1..65535) warnings += "忽略非法端口: $port"
        return copy(proxy = proxySettings)
    }

    private fun SidebikeConfig.importServerDns(dnsObject: JsonObject?, warnings: MutableList<String>): SidebikeConfig? {
        if (dnsObject == null) return null
        var dns: DnsSettings = dns
        var touched = false

        (dnsObject["providers"] as? JsonObject)?.let { providers ->
            val parsed = linkedMapOf<String, DnsProvider>()
            for ((name, element) in providers) {
                val objectValue = element as? JsonObject
                if (objectValue == null) {
                    warnings += "dns.providers.$name 不是对象，已跳过"
                    continue
                }
                val server = (objectValue["server"] as? JsonPrimitive)?.contentOrNullSafe()
                if (server.isNullOrBlank()) {
                    warnings += "dns.providers.$name 缺少 server，已跳过"
                    continue
                }
                val rawType = (objectValue["type"] as? JsonPrimitive)?.contentOrNullSafe()
                    ?: (objectValue["protocol"] as? JsonPrimitive)?.contentOrNullSafe()
                val sni = (objectValue["sni"] as? JsonPrimitive)?.contentOrNullSafe()
                    ?: (objectValue["servername"] as? JsonPrimitive)?.contentOrNullSafe()
                val forSni = ((objectValue["forSNI"] ?: objectValue["forSni"]) as? JsonPrimitive)?.booleanOrNull ?: false
                val enabled = (objectValue["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                val weight = (objectValue["weight"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: 1
                // Upstream DoH/DoT bootstrap IPs are stored under different keys across versions:
                // `bootstrapIps` (ours), `ip` (single literal) or `preSetIpList` (list/map).
                val bootstrapIps = linkedSetOf<String>()
                (objectValue["bootstrapIps"] as? JsonArray)?.forEach { element ->
                    (element as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { bootstrapIps += it }
                }
                (objectValue["ip"] as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { bootstrapIps += it }
                (objectValue["preSetIpList"] as? JsonArray)?.forEach { element ->
                    (element as? JsonPrimitive)?.contentOrNullSafe()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { bootstrapIps += it }
                }
                parsed[name] = DnsProvider(
                    server = server,
                    protocol = parseDnsProtocol(server, rawType),
                    sni = sni,
                    bootstrapIps = bootstrapIps.toList(),
                    forSni = forSni,
                    enabled = enabled,
                    weight = weight,
                )
            }
            if (parsed.isNotEmpty()) {
                dns = dns.copy(providers = parsed)
                touched = true
            }
        }

        (dnsObject["mapping"] as? JsonObject)?.let { mapping ->
            val parsed = linkedMapOf<String, String>()
            for ((pattern, element) in mapping) {
                when (element) {
                    is JsonPrimitive -> element.contentOrNullSafe()?.let { parsed[pattern] = it }
                    is JsonObject -> (element["dnsName"] as? JsonPrimitive)?.contentOrNullSafe()
                        ?.let { parsed[pattern] = it }
                        ?: warnings.add("dns.mapping.$pattern 值为对象但缺少 dnsName，已跳过")
                    else -> warnings += "dns.mapping.$pattern 类型不支持，已跳过"
                }
            }
            if (parsed.isNotEmpty()) {
                dns = dns.copy(mapping = parsed)
                touched = true
            }
        }

        (dnsObject["familyMapping"] as? JsonObject)?.let { mapping ->
            val parsed = linkedMapOf<String, String>()
            for ((pattern, element) in mapping) {
                val family = (element as? JsonPrimitive)?.contentOrNullSafe()?.trim()
                if (family == "4" || family == "6") parsed[pattern] = family
                else warnings += "dns.familyMapping.$pattern 不是 4/6，已跳过"
            }
            if (parsed.isNotEmpty()) {
                dns = dns.copy(familyMapping = parsed)
                touched = true
            }
        }

        return if (touched) copy(dns = dns) else null
    }

    private fun SidebikeConfig.importIntercepts(
        interceptsObject: JsonObject?,
        warnings: MutableList<String>,
    ): SidebikeConfig? {
        if (interceptsObject == null) return null
        val parsed = linkedMapOf<String, Map<String, InterceptAction>>()
        var skipped = 0
        for ((domain, domainValue) in interceptsObject) {
            val paths = domainValue as? JsonObject
            if (paths == null) {
                skipped++
                continue
            }
            val actions = linkedMapOf<String, InterceptAction>()
            for ((pathPattern, actionElement) in paths) {
                val actionObject = actionElement as? JsonObject
                if (actionObject == null) {
                    skipped++
                    continue
                }
                val action = runCatching {
                    json.decodeFromJsonElement(InterceptAction.serializer(), actionObject)
                }.getOrElse { error ->
                    warnings += "intercepts.$domain[$pathPattern] 解析失败: ${error.message}"
                    null
                } ?: continue
                actions[pathPattern] = action
            }
            if (actions.isNotEmpty()) parsed[domain] = actions
        }
        if (skipped > 0) warnings += "intercepts 中 $skipped 条空/不支持规则已跳过（上游 null 表示禁用）"
        return if (parsed.isNotEmpty()) copy(intercepts = parsed) else null
    }

    private fun SidebikeConfig.importBooleanMap(
        source: JsonObject?,
        target: Map<String, Boolean?>,
        warnings: MutableList<String>,
        sectionName: String,
    ): Map<String, Boolean?>? {
        if (source == null) return null
        val parsed = linkedMapOf<String, Boolean?>()
        for ((key, element) in source) {
            when {
                element is JsonNull -> parsed[key] = null
                element is JsonPrimitive -> {
                    val bool = element.booleanOrNull
                    if (bool == null) {
                        warnings += "$sectionName.$key 不是布尔值，已跳过"
                    } else {
                        parsed[key] = bool
                    }
                }
                else -> warnings += "$sectionName.$key 类型不支持，已跳过"
            }
        }
        return if (parsed.isNotEmpty()) parsed else null
    }

    private fun SidebikeConfig.importPreSetIpList(
        source: JsonObject?,
        warnings: MutableList<String>,
    ): SidebikeConfig? {
        if (source == null) return null
        val parsed = linkedMapOf<String, Map<String, Boolean>>()
        for ((domain, element) in source) {
            val ips = element as? JsonObject
            if (ips == null) {
                warnings += "preSetIpList.$domain 不是对象，已跳过"
                continue
            }
            val entries = linkedMapOf<String, Boolean>()
            for ((ip, value) in ips) {
                val enabled = when (value) {
                    is JsonNull -> false
                    is JsonPrimitive -> value.booleanOrNull ?: (value.contentOrNullSafe() != "false" && value.contentOrNullSafe() != "0")
                    is JsonObject -> true // upstream allows metadata objects; presence means enabled
                    else -> false
                }
                entries[ip] = enabled
            }
            if (entries.isNotEmpty()) parsed[domain] = entries
        }
        return if (parsed.isNotEmpty()) copy(preSetIpList = parsed) else null
    }

    private fun SidebikeConfig.importExcludeIpList(
        source: JsonObject?,
        warnings: MutableList<String>,
    ): SidebikeConfig? {
        if (source == null) return null
        val parsed = linkedMapOf<String, Boolean?>()
        for ((key, element) in source) {
            when {
                element is JsonNull -> parsed[key] = null
                element is JsonPrimitive -> {
                    val bool = element.booleanOrNull
                    if (bool == null) warnings += "excludeIpList.$key 不是布尔值，已跳过" else parsed[key] = bool
                }
                else -> warnings += "excludeIpList.$key 类型不支持，已跳过"
            }
        }
        return if (parsed.isNotEmpty()) copy(excludeIpList = parsed) else null
    }

    private fun parseDnsProtocol(server: String, explicit: String?): DnsProtocol {
        val value = explicit?.trim()?.lowercase()
        return when {
            value == "udp" -> DnsProtocol.UDP
            value == "tcp" -> DnsProtocol.TCP
            value == "tls" || value == "dot" -> DnsProtocol.DOT
            value == "https" || value == "doh" -> DnsProtocol.DOH
            value != null -> DnsProtocol.AUTO
            server.startsWith("https://") || server.startsWith("http://") -> DnsProtocol.DOH
            server.startsWith("tls://") || server.startsWith("dot://") -> DnsProtocol.DOT
            server.startsWith("tcp://") -> DnsProtocol.TCP
            server.startsWith("udp://") -> DnsProtocol.UDP
            else -> DnsProtocol.AUTO
        }
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    // ---------------------------------------------------------------- managed list helpers (0.1.1)

    /**
     * Domains the user added through the "直连放行" editor. Tracked separately from
     * `SidebikeConfig.whiteList` so removing them does not delete built-in whitelist exemptions
     * (e.g. `*.icloud.com`) that the user never touched.
     */
    fun loadManagedDirectPassDomains(): List<String> =
        prefs.getStringSet(KEY_MANAGED_DIRECT_PASS_DOMAINS, emptySet())?.toList()?.sorted().orEmpty()

    fun saveManagedDirectPassDomains(domains: List<String>) {
        // commit() keeps the managed list and the config blob consistent across components.
        check(
            prefs.edit()
                .putStringSet(KEY_MANAGED_DIRECT_PASS_DOMAINS, domains.toSet())
                .commit(),
        ) { "Failed to persist managed direct-pass domains" }
    }

    companion object {
        private const val TAG = "SidebikeConfigStore"
        const val PREFS_NAME: String = "dev_sidebike_config"
        const val KEY_CONFIG_JSON: String = "config_json_v1"
        private const val KEY_MANAGED_DIRECT_PASS_DOMAINS: String = "managed_direct_pass_domains_v1"

        /** Default engine working directory below `filesDir` (CA files, future caches). */
        @Suppress("unused")
        fun engineDataDir(filesDir: File): File = File(filesDir, "engine")
    }
}
