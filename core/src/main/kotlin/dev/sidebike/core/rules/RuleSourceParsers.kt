package dev.sidebike.core.rules

import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.RuleSourceFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Parsers for all supported remote rule-source formats (pure functions, no I/O).
 *
 * Format contracts (kept in sync with `docs/` and reported to the app/UI workstream):
 *
 * ### SIDEBIKE_JSON
 * Accepted shapes (lenient JSON, unknown keys ignored):
 * ```json
 * {
 *   "intercepts": { "example.com": { ".*": { "abort": true } } },
 *   "whiteList": { "*.internal.example.com": true },
 *   "domesticAllowList": ["example.cn"],
 *   "preSetIpList": { "example.com": { "1.2.3.4": true } }
 * }
 * ```
 * Toleration: a `{"data": {...}}` / `{"config": {...}}` wrapper is unwrapped; a top-level `{"rules": {...}}`
 * object is treated as the `intercepts` map; `{"rules": [ {domain,path,action}, ... ]}` (or a top-level JSON
 * array of such objects) is accepted and expanded into intercepts. An action may be a full
 * [InterceptAction] object, or the bare literal `true` (shorthand for `{"abort": true}`).
 *
 * ### SHADOWROCKET_LIST
 * Surge/Shadowrocket rule lines: `TYPE,value[,action[,options...]]`, `#`/`;`/`//` comments and
 * `[Section]` headers. Only the `[Rule]` section is parsed when headers are present. Supported types:
 * `DOMAIN`, `DOMAIN-SUFFIX`, `DOMAIN-KEYWORD`. Action mapping:
 * `REJECT*` -> domain-wide intercept with `abort = true`; `DIRECT` -> direct-pass (whitelist) pattern;
 * `PROXY`/other -> warning + skip. `IP-CIDR`/`IP-CIDR6`/`GEOIP`/`USER-AGENT`/... cannot be expressed as
 * domain rules in the frozen contract -> warning + skip. Keyword rules are capped (see [MAX_KEYWORD_RULES]).
 *
 * ### DOMAIN_LIST / HOSTS
 * - plain domain lines -> `directPassDomains`
 * - hosts lines `<ip> <domain...>`: `0.0.0.0`/`127.0.0.1`/`::1`/`::` (ad-block style) -> domain-wide
 *   `abort = true` intercepts; any other IP -> `preSetIpList[domain][ip] = true` (hosts override)
 * - adblock-style `@@||domain^` -> direct-pass, `||domain^` -> abort intercept
 * - comments: `#`, `;`, `//` (leading and inline for hosts lines)
 */
internal object RuleSourceParsers {

    private const val MAX_IMPORTED_INTERCEPT_RULES = 20_000
    private const val MAX_DIRECT_PASS_DOMAINS = 20_000
    private const val MAX_KEYWORD_RULES = 50
    private const val MAX_WARNINGS = 100

    private val lenientJson = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val HOST_LABEL_RE = Regex("^[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?(\\.[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)*$")
    private val IPV4_RE = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val IPV6_RE = Regex("^[0-9a-fA-F:]{2,45}$")
    private val BLOCKING_HOST_IPS = setOf(
        "0.0.0.0", "127.0.0.1", "::", "::1", "::0", "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0",
    )

    // ------------------------------------------------------------------------------------------
    // entry point
    // ------------------------------------------------------------------------------------------

    fun parse(content: String, format: RuleSourceFormat, name: String, url: String): ImportedRuleSet {
        val effectiveFormat = if (format == RuleSourceFormat.AUTO) detectFormat(url, content) else format
        return try {
            when (effectiveFormat) {
                RuleSourceFormat.AUTO -> parseDomainList(content, name) // unreachable; defensive
                RuleSourceFormat.SIDEBIKE_JSON -> parseSidebikeJson(content, name)
                RuleSourceFormat.SHADOWROCKET_LIST -> parseShadowrocket(content, name)
                RuleSourceFormat.DOMAIN_LIST -> parseDomainList(content, name)
            }
        } catch (e: Exception) {
            // Parsers must never throw; this is the final safety net for hostile content.
            ImportedRuleSet(warnings = listOf("解析失败（${name.ifBlank { "规则源" }}）: ${e.message}"))
        }
    }

    /** AUTO format detection: URL suffix first, then content sniffing (task contract). */
    fun detectFormat(url: String, content: String): RuleSourceFormat {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        when {
            path.endsWith(".json") -> return RuleSourceFormat.SIDEBIKE_JSON
            path.endsWith(".list") -> return RuleSourceFormat.SHADOWROCKET_LIST
            path.endsWith(".hosts") || path.endsWith(".txt") || path.endsWith(".domains") -> return RuleSourceFormat.DOMAIN_LIST
        }
        val trimmed = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        // Surge/Shadowrocket `.conf` files are detected by their section headers (analyst spec).
        if (trimmed.contains("[Rule]", ignoreCase = true) || trimmed.contains("[MITM]", ignoreCase = true)) {
            return RuleSourceFormat.SHADOWROCKET_LIST
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return RuleSourceFormat.SIDEBIKE_JSON
        if (trimmed.contains("DOMAIN-SUFFIX,", ignoreCase = true) ||
            trimmed.contains("DOMAIN-KEYWORD,", ignoreCase = true) ||
            trimmed.contains("DOMAIN,") && trimmed.contains("REJECT", ignoreCase = true)
        ) {
            return RuleSourceFormat.SHADOWROCKET_LIST
        }
        return RuleSourceFormat.DOMAIN_LIST
    }

    // ------------------------------------------------------------------------------------------
    // SIDEBIKE_JSON
    // ------------------------------------------------------------------------------------------

    fun parseSidebikeJson(content: String, sourceName: String): ImportedRuleSet {
        val warnings = Warnings()
        if (content.isBlank()) return ImportedRuleSet(warnings = listOf("规则内容为空（${sourceName.ifBlank { "未命名源" }}）"))
        val rootElement = try {
            lenientJson.parseToJsonElement(content.trimStart('\uFEFF'))
        } catch (e: Exception) {
            return ImportedRuleSet(warnings = listOf("JSON 解析失败: ${e.message}"))
        }

        val intercepts = LinkedHashMap<String, LinkedHashMap<String, InterceptAction>>()
        val directPass = LinkedHashSet<String>()
        val domestic = LinkedHashSet<String>()
        val preset = LinkedHashMap<String, LinkedHashMap<String, Boolean>>()

        val root = unwrapRoot(rootElement, warnings)
        if (root != null) {
            parseIntercepts(root["intercepts"] ?: root["rules"]?.takeIf { it is JsonObject }, intercepts, warnings)
            parseWhiteList(root["whiteList"], directPass, warnings)
            parseStringArray(root["domesticAllowList"], domestic, "domesticAllowList", warnings)
            parsePresetIps(root["preSetIpList"], preset, warnings)
        }
        parseRuleArray(rootElement, intercepts, warnings)
        // `{"data": ...}` / `{"config": ...}` wrappers around a rules array.
        if (root == null) {
            for (key in listOf("data", "config", "result")) {
                (rootElement as? JsonObject)?.get(key)?.let { nested ->
                    val nestedRoot = unwrapRoot(nested, warnings)
                    if (nestedRoot != null) {
                        parseIntercepts(nestedRoot["intercepts"], intercepts, warnings)
                        parseWhiteList(nestedRoot["whiteList"], directPass, warnings)
                        parseStringArray(nestedRoot["domesticAllowList"], domestic, "domesticAllowList", warnings)
                        parsePresetIps(nestedRoot["preSetIpList"], preset, warnings)
                        parseRuleArray(nested, intercepts, warnings)
                    } else {
                        parseRuleArray(nested, intercepts, warnings)
                    }
                }
            }
        }

        if (intercepts.isEmpty() && directPass.isEmpty() && domestic.isEmpty() && preset.isEmpty() && warnings.none()) {
            warnings.add("未发现可识别的规则字段（intercepts/whiteList/domesticAllowList/preSetIpList）")
        }
        return ImportedRuleSet(
            intercepts = intercepts.mapValues { (_, v) -> v.toMap() },
            directPassDomains = directPass.toList(),
            domesticDomains = domestic.toList(),
            preSetIpList = preset.mapValues { (_, v) -> v.toMap() },
            warnings = warnings.finish(sourceName),
        )
    }

    /** Returns the object that carries the known rule fields, unwrapping common envelopes. */
    private fun unwrapRoot(element: JsonElement, warnings: Warnings, depth: Int = 0): JsonObject? {
        if (depth > 4) return null
        val obj = element as? JsonObject ?: return null
        val known = listOf("intercepts", "whiteList", "domesticAllowList", "preSetIpList")
        if (known.any { obj.containsKey(it) }) return obj
        if (obj.containsKey("rules") && obj["rules"] is JsonObject) {
            val rulesObj = obj["rules"] as JsonObject
            if (known.any { rulesObj.containsKey(it) }) return rulesObj
            warnings.add("将 `rules` 对象按 `intercepts` 规则表处理")
            return JsonObject(mapOf("intercepts" to rulesObj))
        }
        for (key in listOf("data", "config", "sidebike", "devSidebike")) {
            val nested = obj[key] as? JsonObject ?: continue
            val unwrapped = unwrapRoot(nested, warnings, depth + 1)
            if (unwrapped != null) return unwrapped
        }
        return null
    }

    /** Expands `{"rules":[{domain,path,action}]}` / top-level arrays into intercepts (tolerant import). */
    private fun parseRuleArray(
        element: JsonElement,
        intercepts: MutableMap<String, LinkedHashMap<String, InterceptAction>>,
        warnings: Warnings,
    ) {
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> element["rules"] as? JsonArray ?: return
            else -> return
        }
        if (array.isEmpty()) return
        var imported = 0
        ruleLoop@ for ((index, item) in array.withIndex()) {
            val obj = item as? JsonObject
            if (obj == null) {
                warnings.add("rules[$index] 不是对象，已跳过")
                continue@ruleLoop
            }
            val domain = firstString(obj, "domain", "domainPattern", "host", "pattern", "name")
            if (domain.isNullOrBlank()) {
                warnings.add("rules[$index] 缺少 domain/pattern 字段，已跳过")
                continue@ruleLoop
            }
            val path = firstString(obj, "path", "pathRegex", "pathPattern") ?: ".*"
            val actionElement = obj["action"] ?: obj.filterKeys {
                it != "domain" && it != "domainPattern" && it != "host" && it != "pattern" && it != "path" && it != "pathRegex"
            }.let { JsonObject(it) }
            val action = decodeAction(actionElement, "rules[$index].action", warnings)
            if (action == null) continue@ruleLoop
            val paths = intercepts.getOrPut(domain) { LinkedHashMap() }
            if (paths.size >= 200) {
                warnings.add("域名 $domain 的路径规则过多（>200），已停止导入")
                continue@ruleLoop
            }
            paths[path] = action
            imported++
        }
        if (imported > 0) warnings.add("以 rules 数组格式导入 $imported 条规则")
    }

    private fun parseIntercepts(
        element: JsonElement?,
        out: MutableMap<String, LinkedHashMap<String, InterceptAction>>,
        warnings: Warnings,
    ) {
        val obj = element as? JsonObject ?: run {
            if (element != null && element !is JsonNull) warnings.add("`intercepts` 不是对象，已跳过")
            return
        }
        for ((domainPattern, pathValue) in obj) {
            if (domainPattern.isBlank()) {
                warnings.add("intercepts 中存在空域模式，已跳过")
                continue
            }
            if (out.size >= MAX_IMPORTED_INTERCEPT_RULES) {
                warnings.add("intercept 规则超过上限 $MAX_IMPORTED_INTERCEPT_RULES，其余已忽略")
                return
            }
            val pathMap = pathValue as? JsonObject ?: run {
                val shorthand = decodeAction(pathValue, "intercepts[$domainPattern]", warnings)
                if (shorthand != null) {
                    out.getOrPut(domainPattern) { LinkedHashMap() }[".*"] = shorthand
                }
                return@run null
            } ?: continue
            if (pathMap.isEmpty()) {
                warnings.add("intercepts[$domainPattern] 为空对象，已跳过")
                continue
            }
            val target = LinkedHashMap<String, InterceptAction>()
            for ((pathRegex, actionValue) in pathMap) {
                val action = decodeAction(actionValue, "intercepts[$domainPattern][$pathRegex]", warnings) ?: continue
                target[pathRegex.ifBlank { ".*" }] = action
            }
            if (target.isNotEmpty()) out.getOrPut(domainPattern) { LinkedHashMap() }.putAll(target)
        }
    }

    /** Decodes one action value: object, `true` (shorthand abort), `false`/null (skip). */
    private fun decodeAction(element: JsonElement, where: String, warnings: Warnings): InterceptAction? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.booleanOrNull == true -> InterceptAction(abort = true, desc = "imported: $where")
                element.booleanOrNull == false -> null
                else -> {
                    warnings.add("$where 不是合法的 action 对象，已跳过")
                    null
                }
            }
            is JsonObject -> try {
                lenientJson.decodeFromJsonElement(InterceptAction.serializer(), element)
            } catch (e: Exception) {
                warnings.add("$where 动作字段无效: ${e.message}")
                null
            }
            else -> {
                warnings.add("$where 不是合法的 action 对象，已跳过")
                null
            }
        }
    }

    private fun parseWhiteList(element: JsonElement?, out: MutableSet<String>, warnings: Warnings) {
        when (element) {
            null, is JsonNull -> return
            is JsonObject -> {
                for ((pattern, value) in element) {
                    val truthy = when (value) {
                        is JsonNull -> false
                        is JsonPrimitive -> value.booleanOrNull ?: (value.contentOrNull?.lowercase() != "false")
                        is JsonObject -> true
                        else -> false
                    }
                    if (truthy && pattern.isNotBlank()) out.add(pattern)
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val value = (item as? JsonPrimitive)?.contentOrNull ?: continue
                    if (value.isNotBlank()) out.add(value)
                }
            }
            else -> warnings.add("`whiteList` 格式无法识别，已跳过")
        }
    }

    private fun parseStringArray(element: JsonElement?, out: MutableSet<String>, field: String, warnings: Warnings) {
        when (element) {
            null, is JsonNull -> return
            is JsonArray -> for (item in element) {
                val value = (item as? JsonPrimitive)?.contentOrNull ?: continue
                if (value.isNotBlank()) out.add(value.trim())
            }
            is JsonObject -> for ((key, value) in element) {
                val truthy = (value as? JsonPrimitive)?.booleanOrNull != false
                if (truthy && key.isNotBlank()) out.add(key)
            }
            else -> warnings.add("`$field` 格式无法识别，已跳过")
        }
    }

    private fun parsePresetIps(
        element: JsonElement?,
        out: MutableMap<String, LinkedHashMap<String, Boolean>>,
        warnings: Warnings,
    ) {
        val obj = element as? JsonObject ?: return
        for ((domain, ipsElement) in obj) {
            val ipsObj = ipsElement as? JsonObject ?: run {
                warnings.add("preSetIpList[$domain] 不是对象，已跳过")
                return@run null
            } ?: continue
            val ips = LinkedHashMap<String, Boolean>()
            for ((ip, valueElement) in ipsObj) {
                if (ip.isBlank()) continue
                val enabled = when (valueElement) {
                    is JsonNull -> false
                    is JsonPrimitive -> valueElement.booleanOrNull ?: (valueElement.contentOrNull?.lowercase() != "false")
                    else -> true // object form in upstream config means enabled
                }
                if (enabled) ips[ip.trim()] = true
            }
            if (ips.isNotEmpty()) out.getOrPut(domain) { LinkedHashMap() }.putAll(ips)
        }
    }

    // ------------------------------------------------------------------------------------------
    // SHADOWROCKET_LIST
    // ------------------------------------------------------------------------------------------

    fun parseShadowrocket(content: String, sourceName: String): ImportedRuleSet {
        val warnings = Warnings()
        val intercepts = LinkedHashMap<String, LinkedHashMap<String, InterceptAction>>()
        val directPass = LinkedHashSet<String>()
        var keywordRules = 0
        var rejectRules = 0
        var currentSection: String? = null
        var importedRuleCount = 0
        var unsupportedTypeWarnings = 0

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimStart('\uFEFF').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                // Surge-style section header: only the [Rule] section carries rules.
                currentSection = line.lowercase()
                continue
            }
            if (currentSection != null && currentSection != "[rule]") continue
            if (line.contains(",").not()) {
                warnings.add("无法识别的行（缺少逗号）: ${line.take(120)}")
                continue
            }

            val fields = line.split(',').map { it.trim() }
            if (fields.size < 2) {
                warnings.add("无法识别的行: ${line.take(120)}")
                continue
            }
            val type = fields[0].uppercase()
            val value = fields[1]
            val action = if (fields.size >= 3 && fields[2].isNotEmpty()) fields[2].uppercase() else ""

            when (type) {
                "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-WILDCARD", "DOMAIN-KEYWORD" -> {
                    val pattern = when (type) {
                        "DOMAIN" -> value.trim().lowercase().ifEmpty { "" }
                        // Analyst spec: suffix/wildcard are imported as matcher wildcard patterns
                        // ("example.com" / "*.example.com"), not as raw regexes.
                        "DOMAIN-SUFFIX" -> shadowrocketWildcardPattern(value, suffix = true)
                        "DOMAIN-WILDCARD" -> shadowrocketWildcardPattern(value, suffix = false)
                        else -> {
                            if (keywordRules >= MAX_KEYWORD_RULES) {
                                warnings.add("DOMAIN-KEYWORD 规则超过上限 $MAX_KEYWORD_RULES，跳过: $value")
                                continue
                            }
                            keywordRules++
                            "^.*${Regex.escape(value)}.*$"
                        }
                    }
                    if (pattern.isBlank()) {
                        warnings.add("空域/关键词，已跳过: ${line.take(120)}")
                        continue
                    }
                    if (pattern.contains(' ') || pattern.isEmpty()) {
                        warnings.add("非法域名/模式，已跳过: ${line.take(120)}")
                        continue
                    }
                    when {
                        action.startsWith("REJECT") -> {
                            // "later rule for the same domain overrides earlier" (analyst spec)
                            directPass.remove(pattern)
                            if (!addAbortIntercept(intercepts, pattern, sourceName, warnings)) continue
                            rejectRules++
                            importedRuleCount++
                        }
                        action == "DIRECT" -> {
                            // A later DIRECT rule removes an earlier REJECT for the same pattern.
                            intercepts.remove(pattern)
                            if (directPass.size >= MAX_DIRECT_PASS_DOMAINS) {
                                warnings.add("DIRECT 规则超过上限 $MAX_DIRECT_PASS_DOMAINS，跳过: $value")
                                continue
                            }
                            directPass.add(pattern)
                            importedRuleCount++
                        }
                        action.isEmpty() -> warnings.add("规则缺少动作字段，已跳过: ${line.take(120)}")
                        else -> warnings.add("规则动作 '$action' 暂不支持，已跳过: ${line.take(120)}")
                    }
                }
                "IP-CIDR", "IP-CIDR6", "GEOIP", "IP-ASN", "PROCESS-NAME", "USER-AGENT", "URL-REGEX",
                "FINAL", "RULE-SET", "DOMAIN-SET", "AND", "OR", "NOT", "SRC-IP", "DST-PORT", "SRC-PORT",
                -> {
                    if (unsupportedTypeWarnings < 5) {
                        warnings.add("规则类型 $type 暂不支持（无法映射为域名规则），已跳过: ${line.take(120)}")
                    }
                    unsupportedTypeWarnings++
                }
                else -> {
                    if (unsupportedTypeWarnings < 5) {
                        warnings.add("未知规则类型 $type，已跳过: ${line.take(120)}")
                    }
                    unsupportedTypeWarnings++
                }
            }
        }
        if (unsupportedTypeWarnings > 5) warnings.add("另有 ${unsupportedTypeWarnings - 5} 条不支持的规则类型被跳过")
        if (intercepts.isEmpty() && directPass.isEmpty()) {
            warnings.add("未解析出任何 Shadowrocket 规则（${sourceName.ifBlank { "未命名源" }}）")
        }
        if (rejectRules > 0) warnings.add("导入 REJECT 拦截规则 $rejectRules 条")
        return ImportedRuleSet(
            intercepts = intercepts.mapValues { (_, v) -> v.toMap() },
            directPassDomains = directPass.toList(),
            warnings = warnings.finish(sourceName),
        )
    }

    /**
     * Surge/Shadowrocket DOMAIN-SUFFIX (`example.com` -> `*.example.com`) and DOMAIN-WILDCARD
     * (`*.example.com` / `ads.*.com`, `*` kept verbatim). Returns "" for invalid values so the caller
     * can emit a warning and skip the line.
     */
    private fun shadowrocketWildcardPattern(value: String, suffix: Boolean): String {
        var pattern = value.trim().lowercase().removeSuffix(".")
        if (pattern.isEmpty()) return ""
        if (suffix) {
            pattern = pattern.removePrefix("*.")
            if (pattern.isEmpty() || pattern.contains('*') || pattern.contains(' ')) return ""
            if (pattern.split('.').any { it.isEmpty() || it.length > 63 }) return ""
            return "*.$pattern"
        }
        if (pattern.count { it == '*' } > 8) return ""
        val starless = pattern.replace("*", "a")
        if (starless.contains(' ') || starless.split('.').any { it.isEmpty() || it.length > 63 }) return ""
        return pattern
    }

    private fun addAbortIntercept(
        intercepts: MutableMap<String, LinkedHashMap<String, InterceptAction>>,
        pattern: String,
        sourceName: String,
        warnings: Warnings,
    ): Boolean {
        if (intercepts.size >= MAX_IMPORTED_INTERCEPT_RULES) {
            warnings.add("intercept 规则超过上限 $MAX_IMPORTED_INTERCEPT_RULES，其余已忽略")
            return false
        }
        val action = InterceptAction(abort = true, desc = "imported REJECT from ${sourceName.ifBlank { "rule source" }}")
        intercepts.getOrPut(pattern) { LinkedHashMap() }[".*"] = action
        return true
    }

    // ------------------------------------------------------------------------------------------
    // DOMAIN_LIST / HOSTS
    // ------------------------------------------------------------------------------------------

    fun parseDomainList(content: String, sourceName: String): ImportedRuleSet {
        val warnings = Warnings()
        val intercepts = LinkedHashMap<String, LinkedHashMap<String, InterceptAction>>()
        val directPass = LinkedHashSet<String>()
        val preset = LinkedHashMap<String, LinkedHashMap<String, Boolean>>()
        var blocked = 0
        var invalid = 0

        for (rawLine in content.lineSequence()) {
            var line = rawLine.trimStart('\uFEFF').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue
            // Inline comments: hosts files use '#'; some lists use ' // '.
            val hash = line.indexOf('#')
            if (hash > 0) line = line.substring(0, hash).trim()
            val comment = line.indexOf(" // ")
            if (comment > 0) line = line.substring(0, comment).trim()
            if (line.isEmpty()) continue

            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            val first = tokens[0]

            // hosts form: "<ip> <domain> [domain...]"
            if (isIpLiteral(first) && tokens.size >= 2) {
                for (domainToken in tokens.drop(1)) {
                    val domain = normalizeDomain(domainToken) ?: run {
                        invalid++
                        null
                    } ?: continue
                    if (first in BLOCKING_HOST_IPS) {
                        if (!addAbortIntercept(intercepts, domain, sourceName, warnings)) return finishList(intercepts, directPass, preset, warnings, sourceName, blocked, invalid)
                        blocked++
                    } else {
                        preset.getOrPut(domain) { LinkedHashMap() }[first] = true
                    }
                }
                continue
            }

            // adblock syntax: "@@||domain^" (allow) / "||domain^" (block)
            var token = first
            var policy: String? = null
            if (token.startsWith("@@")) {
                policy = "DIRECT"
                token = token.removePrefix("@@")
            }
            if (token.startsWith("||")) {
                token = token.removePrefix("||").removeSuffix("^")
                val pattern = adBlockDomainPattern(token)
                if (pattern == null) {
                    invalid++
                    continue
                }
                if (policy == "DIRECT") {
                    directPass.add(pattern)
                } else {
                    if (!addAbortIntercept(intercepts, pattern, sourceName, warnings)) {
                        return finishList(intercepts, directPass, preset, warnings, sourceName, blocked, invalid)
                    }
                    blocked++
                }
                continue
            }

            // plain domain / wildcard line
            val normalized = normalizeDomain(token) ?: run {
                invalid++
                null
            } ?: continue
            if (directPass.size >= MAX_DIRECT_PASS_DOMAINS) {
                warnings.add("直连域名超过上限 $MAX_DIRECT_PASS_DOMAINS，其余已忽略")
                break
            }
            directPass.add(normalized)
        }
        if (intercepts.isEmpty() && directPass.isEmpty() && preset.isEmpty()) {
            warnings.add("未解析出任何域名（${sourceName.ifBlank { "未命名源" }}）")
        }
        if (blocked > 0) warnings.add("导入 hosts/广告屏蔽规则 $blocked 条（abort=true）")
        if (invalid > 0) warnings.add("跳过 $invalid 条无效域名行")
        return finishList(intercepts, directPass, preset, warnings, sourceName, blocked, invalid)
    }

    private fun finishList(
        intercepts: Map<String, LinkedHashMap<String, InterceptAction>>,
        directPass: Set<String>,
        preset: Map<String, LinkedHashMap<String, Boolean>>,
        warnings: Warnings,
        sourceName: String,
        @Suppress("UNUSED_PARAMETER") blocked: Int,
        @Suppress("UNUSED_PARAMETER") invalid: Int,
    ): ImportedRuleSet = ImportedRuleSet(
        intercepts = intercepts.mapValues { (_, v) -> v.toMap() },
        directPassDomains = directPass.toList(),
        preSetIpList = preset.mapValues { (_, v) -> v.toMap() },
        warnings = warnings.finish(sourceName),
    )

    /** Returns a domain rule pattern for a normalized domain or `*.domain` wildcard, else null. */
    private fun normalizeDomain(token: String): String? {
        var value = token.trim().removeSuffix(".").lowercase()
        if (value.isEmpty()) return null
        var wildcard = false
        if (value.startsWith("*.")) {
            wildcard = true
            value = value.removePrefix("*.")
        }
        if (value.isEmpty() || value.contains('/') || value.contains(',') || value.contains(' ')) return null
        if (value.length > 253) return null
        if (value.startsWith("^") || value.contains('*') || value.contains('\\')) return null // regex-ish: not a plain domain
        if (!value.contains('.')) {
            // single-label host (e.g. "localhost") is a valid domain list entry
            return if (HOST_LABEL_RE.matches(value)) value else null
        }
        if (!HOST_LABEL_RE.matches(value)) return null
        if (value.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return if (wildcard) "*.$value" else value
    }

    /** `||domain^` -> suffix regex covering the bare domain and all subdomains. */
    private fun adBlockDomainPattern(token: String): String? {
        val domain = token.trim().lowercase().removeSuffix("^").removeSuffix(".").removePrefix(".")
        if (domain.isEmpty() || !domain.contains('.')) return null
        if (!HOST_LABEL_RE.matches(domain)) return null
        return "^(.+\\.)?${Regex.escape(domain)}$"
    }

    private fun isIpLiteral(value: String): Boolean =
        IPV4_RE.matches(value) || (value.contains(':') && IPV6_RE.matches(value))

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val primitive = obj[key] as? JsonPrimitive ?: continue
            val content = primitive.contentOrNull ?: continue
            if (content.isNotBlank()) return content
        }
        return null
    }

    /** Warning collector: caps the number of surfaced warnings so hostile inputs cannot bloat configs. */
    private class Warnings {
        private val list = ArrayList<String>()
        private var truncated = false

        fun add(message: String) {
            if (list.size < MAX_WARNINGS) list.add(message) else truncated = true
        }

        fun none(): Boolean = list.isEmpty()

        fun finish(sourceName: String): List<String> {
            if (list.isEmpty()) return emptyList()
            val prefix = if (sourceName.isBlank()) "" else "[$sourceName] "
            val result = list.map { prefix + it }.toMutableList()
            if (truncated) result.add("${prefix}…其余警告已省略（上限 $MAX_WARNINGS 条）")
            return result
        }
    }
}
