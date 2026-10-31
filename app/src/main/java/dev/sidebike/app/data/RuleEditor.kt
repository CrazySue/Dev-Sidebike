package dev.sidebike.app.data

import dev.sidebike.app.controller.RuleDraft
import dev.sidebike.app.controller.ruleKey
import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.config.BundledConfig

/**
 * Pure rule editing/merging logic shared by `DefaultSidebikeController` rule APIs.
 *
 * All functions return a new [SidebikeConfig] and never touch persistence/engine — the controller
 * saves and hot-updates once, which keeps the operations atomic and easy to unit test.
 *
 * Merge semantics (matches `core/api/RuleSources.kt` contract):
 *  - imported `intercepts` overlay existing rules (per domain/path),
 *  - imported direct-pass domains become `whiteList[domain] = true`,
 *  - imported domestic domains are appended to `domesticAllowList` (deduplicated),
 *  - imported `preSetIpList` is merged per domain.
 */
internal object RuleEditor {

    /** Domains that were added through the "直连放行" editor (tracked separately for clean removal). */
    private const val MAX_DOMAIN_LENGTH = 253

    data class MergeOutcome(
        val config: SidebikeConfig,
        val addedIntercepts: Int,
        val addedDirectPassDomains: Int,
        val addedDomesticDomains: Int,
    )

    /** Adds/replaces one rule and clears its "disabled" marker (re-adding a built-in rule re-enables it). */
    fun applyRule(config: SidebikeConfig, draft: RuleDraft): SidebikeConfig {
        val domain = normalizeInterceptDomain(draft.domainPattern)
            ?: error("规则域名不能为空")
        val path = draft.pathRegex.trim().ifEmpty { ".*" }
        val action = draft.toAction()
        val domainRules = LinkedHashMap(config.intercepts[domain] ?: emptyMap())
        domainRules[path] = action
        val intercepts = LinkedHashMap(config.intercepts)
        intercepts[domain] = domainRules
        val disabled = config.disabledIntercepts.filterNot { it == ruleKey(domain, path) }
        return config.copy(intercepts = intercepts, disabledIntercepts = disabled)
    }

    /**
     * Removes a rule. Built-in (bundled snapshot) rules are additionally recorded in
     * `disabledIntercepts` so a future merge/import with the same rule cannot silently re-enable them.
     */
    fun deleteRule(config: SidebikeConfig, domainPattern: String, pathRegex: String): SidebikeConfig {
        val domain = normalizeInterceptDomain(domainPattern) ?: return config
        val path = pathRegex.trim().ifEmpty { ".*" }
        val intercepts = LinkedHashMap(config.intercepts)
        val domainRules = LinkedHashMap(intercepts[domain] ?: emptyMap())
        domainRules.remove(path)
        if (domainRules.isEmpty()) intercepts.remove(domain) else intercepts[domain] = domainRules

        val isBuiltIn = runCatching {
            BundledConfig.load().intercepts[domain]?.containsKey(path) == true
        }.getOrDefault(false)
        val disabled = LinkedHashSet(config.disabledIntercepts)
        if (isBuiltIn) disabled += ruleKey(domain, path)
        return config.copy(intercepts = intercepts, disabledIntercepts = disabled.toList())
    }

    /**
     * Replaces the user-managed direct-pass domain set. [previouslyManaged] entries are removed from
     * `whiteList` first (so the editor can un-check them) while all other whitelist entries — e.g.
     * bundled `*.icloud.com` exemptions — are preserved.
     */
    fun setDirectPassDomains(
        config: SidebikeConfig,
        domains: List<String>,
        previouslyManaged: Collection<String>,
    ): SidebikeConfig {
        val normalized = normalizeDomains(domains)
        val whiteList = LinkedHashMap(config.whiteList)
        previouslyManaged.forEach { managed ->
            if (whiteList[managed] == true) whiteList.remove(managed)
        }
        normalized.forEach { whiteList[it] = true }
        return config.copy(whiteList = whiteList)
    }

    /** Normalizes and de-duplicates a user-provided domain list (order preserved). */
    fun normalizeDomains(domains: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (raw in domains) {
            normalizeDomain(raw)?.let { result += it }
        }
        return result.toList()
    }

    /** Alias used by the domestic-allow-list editor (same normalization as direct-pass). */
    fun setDomesticAllowList(config: SidebikeConfig, domains: List<String>): SidebikeConfig =
        config.copy(domesticAllowList = normalizeDomains(domains))

    /**
     * Idempotent merge of an [ImportedRuleSet] into [config]. Counts only *newly added* entries, so a
     * second import of the same content reports 0/0/0 and does not change the config.
     */
    fun mergeImported(config: SidebikeConfig, imported: ImportedRuleSet): MergeOutcome {
        var addedIntercepts = 0
        val intercepts = LinkedHashMap(config.intercepts)
        for ((domainRaw, paths) in imported.intercepts) {
            val domain = normalizeInterceptDomain(domainRaw) ?: continue
            val existing = LinkedHashMap(intercepts[domain] ?: emptyMap())
            for ((pathRaw, action) in paths) {
                val path = pathRaw.trim().ifEmpty { ".*" }
                if (!existing.containsKey(path)) addedIntercepts++
                existing[path] = action
            }
            intercepts[domain] = existing
        }

        var addedDirectPass = 0
        val whiteList = LinkedHashMap(config.whiteList)
        for (domainRaw in imported.directPassDomains) {
            val domain = normalizeDomain(domainRaw) ?: continue
            if (whiteList[domain] != true) {
                whiteList[domain] = true
                addedDirectPass++
            }
        }

        var addedDomestic = 0
        val domestic = LinkedHashSet(config.domesticAllowList)
        for (domainRaw in imported.domesticDomains) {
            val domain = normalizeDomain(domainRaw) ?: continue
            if (domestic.add(domain)) addedDomestic++
        }

        val preSetIpList = LinkedHashMap(config.preSetIpList)
        for ((domainRaw, entries) in imported.preSetIpList) {
            val domain = normalizeInterceptDomain(domainRaw) ?: continue
            val existing = LinkedHashMap(preSetIpList[domain] ?: emptyMap())
            existing.putAll(entries)
            preSetIpList[domain] = existing
        }

        val updated = config.copy(
            intercepts = intercepts,
            whiteList = whiteList,
            domesticAllowList = domestic.toList(),
            preSetIpList = preSetIpList,
        )
        return MergeOutcome(updated, addedIntercepts, addedDirectPass, addedDomestic)
    }

    /** True when [config] already contains an intercept rule for [domainPattern]/[pathRegex]. */
    fun containsRule(config: SidebikeConfig, domainPattern: String, pathRegex: String): Boolean {
        val domain = normalizeInterceptDomain(domainPattern) ?: return false
        val path = pathRegex.trim().ifEmpty { ".*" }
        return config.intercepts[domain]?.containsKey(path) == true
    }

    /** All intercept rules in a stable, UI-friendly order (domain, then path). */
    fun flattenRules(config: SidebikeConfig): List<Triple<String, String, InterceptAction>> = buildList {
        config.intercepts.toSortedMap().forEach { (domain, paths) ->
            paths.toSortedMap().forEach { (path, action) -> add(Triple(domain, path, action)) }
        }
    }

    /**
     * Accepts concrete domains, `*.wildcards` and `^regex$` patterns. Returns null for blank input.
     * Lower-cases ASCII domains (regex patterns keep their case-sensitive bodies untouched).
     */
    fun normalizeInterceptDomain(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Regex-style patterns are stored verbatim (case matters for regex bodies).
        if (trimmed.startsWith("^")) return trimmed
        return trimmed.lowercase().trimEnd('.')
    }

    /** Accepts plain domain / wildcard entries; rejects blanks and obviously invalid input. */
    fun normalizeDomain(raw: String): String? {
        var value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        if (value.startsWith("#") || value.startsWith(";") || value.startsWith("//")) return null
        // Tolerate pasted URLs: https://example.com/path -> example.com
        if (value.contains("://")) {
            value = value.substringAfter("://").substringBefore('/')
        }
        value = value.substringBefore('/').substringBefore(':').trimEnd('.')
        if (value.isEmpty() || value.length > MAX_DOMAIN_LENGTH) return null
        if (value.contains('\n') || value.contains('\r') || value.contains(' ')) return null
        return value
    }
}
