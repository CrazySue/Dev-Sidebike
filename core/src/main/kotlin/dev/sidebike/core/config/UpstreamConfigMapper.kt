package dev.sidebike.core.config

import dev.sidebike.core.api.DnsBootstraps
import dev.sidebike.core.api.DnsProtocol
import dev.sidebike.core.api.DnsProvider
import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上游 Dev-Sidecar 配置 → [SidebikeConfig] 的尽力兼容映射（`ConfigRepository.importUpstream`）。
 *
 * 支持两种输入：
 * 1. **运行配置**（`running.json` / 官方 `remote_config.json5`）：根节点含 `server`，且 `server` 含
 *    `port/host/setting` 等完整字段 → 按"整体替换规则"映射；
 * 2. **用户覆盖配置**（`~/.dev-sidecar/config.json`）：根节点含 `server`/`proxy` 等部分字段，
 *    或直接是 SidebikeConfig 形状 → 按"合并到 base"映射。
 *
 * 已知降级（必须同步到 `docs/feasibility/01b-config-porting.md`）：
 * - 不支持：`abort`（丢弃）、`redirect`（丢弃）、`proxy.backup`（丢弃，仅保留主 proxy 目标）、
 *   `verifyHost`/`unVerifySsl`/`options`/`exclusions`/`order`（Api 未建模；MVP 由 MITM 默认策略处理）；
 * - `cacheSeconds/cacheMinutes/...` 统一折算为 `cacheDays`（向上取整，最小 1 天）；
 * - 域名 `${...}` 占位符 proxy 目标保留原文，由 RuleEngine.plan 判定为不可解析并回退 DIRECT；
 * - 上游内置 `local`（127.0.0.1）DNS provider 被丢弃，避免 VPN tun DNS 自递归；
 * - 为满足 `DnsSettings.fallbackProviders` 默认值（aliyun/doh.pub/cf-DoH），快照/导入时会补齐缺失的 provider。
 */
object UpstreamConfigMapper {

    data class Result(val config: SidebikeConfig, val warnings: List<String>)

    /** 快照/导入时强制补齐的 provider（保证 fallbackProviders 可解析；属性取自 `DnsSettings.defaultProviders()`）。 */
    private val REQUIRED_PROVIDER_NAMES = listOf("aliyun", "doh.pub", "cf-DoH")

    fun importUpstream(text: String, base: SidebikeConfig): Result {
        val root = try {
            ConfigJson.parseObject(text)
        } catch (e: Exception) {
            return Result(base, listOf("配置 JSON5 解析失败，保持原配置: ${e.message}"))
        }
        return mapUpstreamObject(root, base)
    }

    fun mapUpstreamObject(root: JsonObject, base: SidebikeConfig): Result {
        val warnings = mutableListOf<String>()
        if (root["version"] != null && root["server"] == null) {
            // 本 App 导出的 SidebikeConfig：直接深合并
            val merged = ConfigJson.mergeConfig(base, root)
            return Result(ConfigJson.migrate(merged), warnings)
        }

        val server: JsonObject? = when {
            root["server"] is JsonObject -> root["server"] as JsonObject
            // 上游 `running.json` 的根节点就是 serverConfig（server 模块写入的是 allConfig.server + app/plugin/proxy），
            // 没有 "server" 包装层，需要按特征识别。
            root["setting"] != null || root["fakeServerMaxLength"] != null ||
                (root["intercepts"] != null && root["dns"] != null) -> root

            else -> null
        }
        if (server == null) {
            // 用户 config.json 可能只有 dns/proxy 等顶层字段（直接按 SidebikeConfig 合并）
            val merged = ConfigJson.mergeConfig(base, root)
            return Result(ConfigJson.migrate(merged), warnings + "输入不含 server 节点，按 SidebikeConfig 部分配置合并")
        }

        val isFullRuntimeConfig = server["port"] != null || server["setting"] != null || server["host"] != null
        val mappedIntercepts = mapIntercepts(server["intercepts"], warnings)
        if (mappedIntercepts.isEmpty()) warnings += "intercepts 为空或无可映射动作"
        val mappedPreset = mapPreSetIpList(server["preSetIpList"])
        val mappedWhiteList = mapBooleanMap(server["whiteList"])
        val mappedDns = mapDnsSettings(server["dns"] as? JsonObject, base.dns, warnings)
        val proxyNode = root["proxy"] as? JsonObject
        val mappedExclude = mapBooleanMap(proxyNode?.get("excludeIpList") ?: root["excludeIpList"])

        val intercepts = if (isFullRuntimeConfig) {
            mappedIntercepts
        } else {
            mergeNested(base.intercepts, mappedIntercepts)
        }
        val preset = if (isFullRuntimeConfig) {
            mappedPreset
        } else {
            mergeNested(base.preSetIpList, mappedPreset)
        }
        val whiteList = if (isFullRuntimeConfig) mappedWhiteList else mergeFlat(base.whiteList, mappedWhiteList)
        val excludeIpList = if (isFullRuntimeConfig) mappedExclude else mergeFlat(base.excludeIpList, mappedExclude)

        val config = base.copy(
            version = SidebikeConfig.CURRENT_VERSION,
            intercepts = intercepts,
            preSetIpList = preset,
            whiteList = whiteList,
            excludeIpList = excludeIpList,
            dns = base.dns.copy(
                providers = mappedDns.providers,
                mapping = mappedDns.mapping,
                familyMapping = mappedDns.familyMapping,
            ),
        )
        return Result(config, warnings)
    }

    // ------------------------------------------------------------------ intercepts

    fun mapIntercepts(element: JsonElement?, warnings: MutableList<String>): Map<String, Map<String, InterceptAction>> {
        val obj = element as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, Map<String, InterceptAction>>()
        var dropped = 0
        for ((domain, pathMapElement) in obj) {
            val pathMap = pathMapElement as? JsonObject ?: continue
            val actions = LinkedHashMap<String, InterceptAction>()
            for ((pathPattern, actionElement) in pathMap) {
                val actionObj = actionElement as? JsonObject ?: continue // null 值 = 上游删除/禁用规则
                val action = mapAction(actionObj)
                if (action == null) {
                    dropped++
                    continue
                }
                actions[pathPattern] = action
            }
            if (actions.isNotEmpty()) result[domain] = actions
        }
        if (dropped > 0) warnings += "丢弃 $dropped 条 MVP 不支持的拦截动作（abort/redirect/仅 desc 等）"
        return result
    }

    fun mapAction(obj: JsonObject): InterceptAction? {
        val sni = obj.stringOrNull("sni")
        val proxy = obj.stringOrNull("proxy")
        val success = obj["success"]?.takeIf { it !is JsonNull }
        val script = obj.stringOrNull("script")
        val tampermonkey = obj.stringOrNull("tampermonkeyScript")
        val requestReplace = obj["requestReplace"]?.takeIf { it is JsonObject }
        val responseReplace = obj["responseReplace"]?.takeIf { it is JsonObject }
        val cacheDays = mapCacheDays(obj)
        val status = obj.primitiveIntOrNull("status")
        val desc = obj.stringOrNull("desc")
        val remark = obj.stringOrNull("remark")

        val hasAction = sni != null || proxy != null || success != null || script != null ||
            tampermonkey != null || requestReplace != null || responseReplace != null ||
            cacheDays != null || status != null
        if (!hasAction) return null // 仅 desc/remark 的条目没有行为，视为无动作

        return InterceptAction(
            sni = sni,
            proxy = proxy,
            success = success,
            script = script,
            tampermonkeyScript = tampermonkey,
            requestReplace = requestReplace,
            responseReplace = responseReplace,
            cacheDays = cacheDays,
            status = status,
            desc = desc,
            remark = remark,
        )
    }

    /**
     * 上游缓存的 10 种时长键折算为 `cacheDays`（天，向上取整，最小 1）。
     * 对齐上游 `impl/req/cacheRequest.js:4-35 getMaxAge` 的取值顺序与单位。
     */
    fun mapCacheDays(obj: JsonObject): Int? {
        val seconds = when {
            obj.doubleOrNull("cacheSeconds") != null -> obj.doubleOrNull("cacheSeconds")!!
            obj.doubleOrNull("cacheMaxAge") != null -> obj.doubleOrNull("cacheMaxAge")!!
            obj.doubleOrNull("cache") != null -> obj.doubleOrNull("cache")!!
            obj.doubleOrNull("cacheMinutes") != null -> obj.doubleOrNull("cacheMinutes")!! * 60
            obj.doubleOrNull("cacheHours") != null -> obj.doubleOrNull("cacheHours")!! * 3600
            obj.doubleOrNull("cacheDays") != null -> obj.doubleOrNull("cacheDays")!! * 86400
            obj.doubleOrNull("cacheWeeks") != null -> obj.doubleOrNull("cacheWeeks")!! * 604800
            obj.doubleOrNull("cacheMonths") != null -> obj.doubleOrNull("cacheMonths")!! * 2592000
            obj.doubleOrNull("cacheYears") != null -> obj.doubleOrNull("cacheYears")!! * 31536000
            else -> return null
        }
        if (seconds <= 0) return null
        return maxOf(1, kotlin.math.ceil(seconds / 86400.0).toInt())
    }

    // ------------------------------------------------------------------ maps

    fun mapPreSetIpList(element: JsonElement?): Map<String, Map<String, Boolean>> {
        val obj = element as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, Map<String, Boolean>>()
        for ((domain, ipsElement) in obj) {
            val ips = ipsElement as? JsonObject ?: continue
            val mapped = LinkedHashMap<String, Boolean>()
            for ((ip, enabled) in ips) {
                mapped[ip] = isTruthy(enabled)
            }
            if (mapped.isNotEmpty()) result[domain] = mapped
        }
        return result
    }

    fun mapBooleanMap(element: JsonElement?): Map<String, Boolean?> {
        val obj = element as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, Boolean?>()
        for ((key, value) in obj) {
            result[key] = if (value is JsonNull) null else isTruthy(value)
        }
        return result
    }

    // ------------------------------------------------------------------ dns

    fun mapDnsSettings(dnsObj: JsonObject?, base: DnsSettings, warnings: MutableList<String>): DnsSettings {
        if (dnsObj == null) return base

        val providers = LinkedHashMap<String, DnsProvider>()
        (dnsObj["providers"] as? JsonObject)?.forEach { (name, value) ->
            val conf = value as? JsonObject ?: return@forEach
            if (name == "local") {
                warnings += "丢弃上游 DNS provider 'local'（127.0.0.1，Android VPN 下会自递归）"
                return@forEach
            }
            val provider = mapProvider(name, conf, warnings) ?: return@forEach
            providers[name] = provider
        }
        // 补齐 fallbackProviders 默认值依赖的 provider（来自 api 默认表；缺失时打警告）
        val apiDefaults = DnsSettings.defaultProviders()
        for (required in REQUIRED_PROVIDER_NAMES) {
            if (providers[required] == null) {
                providers[required] = apiDefaults.getValue(required)
                warnings += "补齐缺失 DNS provider '$required'（来自内置默认表）"
            }
        }

        val mapping = LinkedHashMap<String, String>()
        (dnsObj["mapping"] as? JsonObject)?.forEach { (pattern, value) ->
            val providerName = when (value) {
                is JsonPrimitive -> value.content
                is JsonObject -> value.stringOrNull("dnsName")
                else -> null
            }
            when {
                providerName == null -> warnings += "DNS mapping '$pattern' 配置无效，已忽略"
                providers[providerName] == null -> warnings += "DNS mapping '$pattern' 指向不存在的 provider '$providerName'，已忽略"
                else -> mapping[pattern] = providerName
            }
        }

        val familyMapping = LinkedHashMap<String, String>()
        (dnsObj["familyMapping"] as? JsonObject)?.forEach { (pattern, value) ->
            val family = when (value) {
                is JsonPrimitive -> value.content
                else -> null
            }
            if (family == "4" || family == "6") familyMapping[pattern] = family
        }

        return base.copy(providers = providers, mapping = mapping, familyMapping = familyMapping)
    }

    private fun mapProvider(name: String, conf: JsonObject, warnings: MutableList<String>): DnsProvider? {
        val server = conf.stringOrNull("server") ?: conf.stringOrNull("host") ?: return null
        val explicitType = conf.stringOrNull("type")?.lowercase()
        val protocol = when (explicitType) {
            "https", "doh", "dns-over-https" -> DnsProtocol.DOH
            "tls", "dot", "dns-over-tls" -> DnsProtocol.DOT
            "tcp" -> DnsProtocol.TCP
            "udp" -> DnsProtocol.UDP
            else -> protocolFromServer(server)
        }
        val sni = conf.stringOrNull("sni") ?: conf.stringOrNull("servername")
        val forSni = isTruthy(conf["forSNI"] ?: conf["forSni"])
        val enabled = (conf["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val weight = (conf["weight"] as? JsonPrimitive)?.intOrNull ?: 1
        val bootstrapIps = parseBootstrapIps(conf, name, server)
        return DnsProvider(
            server = server,
            protocol = protocol,
            sni = sni,
            bootstrapIps = bootstrapIps,
            forSni = forSni,
            enabled = enabled,
            weight = weight,
            trustAllCerts = true,
        )
    }

    /**
     * DoH/DoT 自举 IP（**禁止用系统 DNS 解析 provider 主机名**，见 api/Config.kt:75-96）：
     * 1. 配置里显式 `bootstrapIps` 数组；
     * 2. server 本身是字面 IP；
     * 3. 已知公共 provider 的内置自举 IP（与 `DnsBootstraps` 对齐）。
     */
    private fun parseBootstrapIps(conf: JsonObject, name: String, server: String): List<String> {
        val explicit = (conf["bootstrapIps"] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }?.filter { it.isNotBlank() }
        if (!explicit.isNullOrEmpty()) return explicit

        val host = hostOfServer(server)
        if (host != null && isLiteralIp(host)) return listOf(host)

        return when (name) {
            "doh.pub" -> listOf(DnsBootstraps.DOHPUB_IP, DnsBootstraps.DOHPUB_BACKUP_IP)
            "cf-DoH", "cf-DoT" -> listOf(DnsBootstraps.CLOUDFLARE_IP)
            "rubyfish" -> listOf(DnsBootstraps.RUBYFISH_IP)
            "quad9-ip" -> listOf(DnsBootstraps.QUAD9_IP)
            "aliyun" -> listOf(DnsBootstraps.ALIYUN_DOH_IP)
            else -> emptyList()
        }
    }

    /** 从 `server` 配置串提取主机名（去掉 scheme/端口/path）。 */
    fun hostOfServer(server: String): String? {
        var value = server.trim()
        if (value.contains("://")) value = value.substringAfter("://")
        value = value.substringBefore('/')
        if (value.startsWith("[")) { // IPv6 字面量
            return value.substring(1, value.indexOf(']').takeIf { it > 0 } ?: value.length)
        }
        if (value.count { it == ':' } == 1) value = value.substringBefore(':')
        return value.ifBlank { null }
    }

    fun isLiteralIp(value: String): Boolean {
        var seenColon = false
        for (ch in value) {
            when {
                ch.isDigit() || ch == '.' -> Unit
                ch == ':' -> seenColon = true
                ch in 'a'..'f' || ch in 'A'..'F' -> seenColon = true
                else -> return false
            }
        }
        return value.count { it == '.' } == 3 || seenColon
    }

    fun protocolFromServer(server: String): DnsProtocol = when {
        server.startsWith("https://") -> DnsProtocol.DOH
        server.startsWith("http://") -> DnsProtocol.DOH // 上游允许 HTTP DoH（仅测试/内网）；TLS 由 endpoint 判定
        server.startsWith("tls://") || server.startsWith("dot://") -> DnsProtocol.DOT
        server.startsWith("tcp://") -> DnsProtocol.TCP
        else -> DnsProtocol.UDP
    }

    // ------------------------------------------------------------------ helpers

    /** 上游真值语义（对齐 `mapToList`）：false/null/0/"false"/"0" 为假，其余（含对象/非空串）为真。 */
    fun isTruthy(element: JsonElement?): Boolean = when (element) {
        null, is JsonNull -> false
        is JsonPrimitive -> when {
            element.isString -> element.content != "" && element.content != "false" && element.content != "0"
            else -> element.booleanOrNull ?: (element.doubleOrNull?.let { it != 0.0 } ?: true)
        }

        is JsonObject, is JsonArray -> true
    }

    private fun mergeFlat(
        base: Map<String, Boolean?>,
        extra: Map<String, Boolean?>,
    ): Map<String, Boolean?> {
        val result = LinkedHashMap<String, Boolean?>(base)
        result.putAll(extra)
        return result
    }

    private fun <T> mergeNested(
        base: Map<String, Map<String, T>>,
        extra: Map<String, Map<String, T>>,
    ): Map<String, Map<String, T>> {
        val result = LinkedHashMap<String, Map<String, T>>(base)
        for ((domain, paths) in extra) {
            val old = result[domain]
            result[domain] = if (old == null) paths else LinkedHashMap(old).apply { putAll(paths) }
        }
        return result
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun JsonObject.primitiveIntOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull ?: (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
