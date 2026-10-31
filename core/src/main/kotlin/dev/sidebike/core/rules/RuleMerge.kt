package dev.sidebike.core.rules

import dev.sidebike.core.api.ImportedRuleSet
import dev.sidebike.core.api.InterceptAction
import dev.sidebike.core.api.SidebikeConfig

/**
 * Idempotent merge of an [ImportedRuleSet] into a [SidebikeConfig] - the merge policy documented in
 * `api/RuleSources.kt` (implements it once so the App Controller and tests share the same semantics):
 *  - [ImportedRuleSet.intercepts]: deep merge by `domainPattern -> pathRegex`, imported path rules override
 *    existing ones for the same path;
 *  - [ImportedRuleSet.directPassDomains]: written as `whiteList[domain] = true`;
 *  - [ImportedRuleSet.domesticDomains]: appended to `domesticAllowList` (deduplicated, order preserved);
 *  - [ImportedRuleSet.preSetIpList]: merged per domain (imported IP flags override).
 *
 * Importing the same source twice yields exactly the same config (idempotency is required by the contract
 * because refresh may run on every app start).
 */
object RuleMerge {

    fun mergeInto(config: SidebikeConfig, imported: ImportedRuleSet): SidebikeConfig {
        if (imported.intercepts.isEmpty() &&
            imported.directPassDomains.isEmpty() &&
            imported.domesticDomains.isEmpty() &&
            imported.preSetIpList.isEmpty()
        ) {
            return config
        }

        val intercepts = LinkedHashMap<String, Map<String, InterceptAction>>(config.intercepts.size + imported.intercepts.size)
        for ((domain, paths) in config.intercepts) intercepts[domain] = LinkedHashMap(paths)
        for ((domain, paths) in imported.intercepts) {
            if (domain.isBlank() || paths.isEmpty()) continue
            val mergedPaths = LinkedHashMap(intercepts[domain] ?: emptyMap())
            mergedPaths.putAll(paths)
            intercepts[domain] = mergedPaths
        }

        val whiteList = LinkedHashMap<String, Boolean?>(config.whiteList.size + imported.directPassDomains.size)
        whiteList.putAll(config.whiteList)
        for (domain in imported.directPassDomains) {
            if (domain.isNotBlank()) whiteList[domain] = true
        }

        val domestic = LinkedHashSet(config.domesticAllowList)
        for (domain in imported.domesticDomains) {
            if (domain.isNotBlank()) domestic.add(domain)
        }

        val preset = LinkedHashMap<String, Map<String, Boolean>>(config.preSetIpList.size + imported.preSetIpList.size)
        for ((domain, ips) in config.preSetIpList) preset[domain] = LinkedHashMap(ips)
        for ((domain, ips) in imported.preSetIpList) {
            if (domain.isBlank() || ips.isEmpty()) continue
            val mergedIps = LinkedHashMap(preset[domain] ?: emptyMap())
            mergedIps.putAll(ips)
            preset[domain] = mergedIps
        }

        return config.copy(
            intercepts = intercepts,
            whiteList = whiteList,
            domesticAllowList = domestic.toList(),
            preSetIpList = preset,
        )
    }
}
