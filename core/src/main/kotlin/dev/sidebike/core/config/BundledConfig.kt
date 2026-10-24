package dev.sidebike.core.config

import dev.sidebike.core.api.DnsSettings
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger

/**
 * 内置默认配置 + 官方规则快照（离线可用，运行期不需要网络）。
 *
 * ## 快照来源（provenance）
 * - 本仓库 `core/src/main/resources/dev/sidebike/core/rules/rules.json` 由 T1 分析阶段
 *   从 **本机正在运行的 Dev-Sidecar v2.3.1** 的 `C:\Users\CrazySue\.dev-sidecar\running.json`
 *   （2026-10-25 只读拷贝）+ 官方配置 `remote_config.json5` 规则表生成；
 * - 生成原则：保留 `intercepts / preSetIpList / whiteList / excludeIpList / dns.providers /
 *   dns.mapping / dns.familyMapping / domesticAllowList`，丢弃 GUI/插件/系统代理等桌面专属字段；
 * - 只保留 Dev-Sidebike MVP 支持的拦截动作字段（sni/proxy/success/script/tampermonkeyScript/
 *   requestReplace/responseReplace/cacheDays/status/desc/remark），其余动作（abort/redirect/backup）
 *   丢弃或降级，详见 `docs/feasibility/01b-config-porting.md`；
 * - 运行时不访问网络；如需更新规则，重新生成该资源即可（禁止在运行期下载未校验的规则）。
 *
 * ## 合并层次
 * `api.SidebikeConfig()`（编译期默认值）→ 本快照（规则类字段整体覆盖）→ 用户覆盖（`DefaultConfigRepository`）。
 */
object BundledConfig {

    const val RULES_RESOURCE: String = "/dev/sidebike/core/rules/rules.json"
    const val RULES_PATH: String = "dev/sidebike/core/rules/rules.json"

    /** 读取快照原文；资源缺失时返回 null（构建期异常，运行期回退默认配置并告警）。 */
    fun rulesText(): String? =
        BundledConfig::class.java.getResourceAsStream(RULES_RESOURCE)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }

    fun isSnapshotPresent(): Boolean = rulesText() != null

    /**
     * 快照元信息（`rules.json` 顶层 `_meta`）：来源、生成日期、覆盖范围。
     * 仅用于诊断/文档/测试，不参与运行配置。
     */
    fun meta(): kotlinx.serialization.json.JsonObject? {
        val text = rulesText() ?: return null
        return runCatching {
            (ConfigJson.parseObject(text)["_meta"] as? kotlinx.serialization.json.JsonObject)
        }.getOrNull()
    }

    /**
     * 构建内置完整配置：`SidebikeConfig()` 默认值 + 官方规则快照。
     * 快照字段：intercepts/whiteList/preSetIpList/excludeIpList/domesticAllowList 整体使用；
     * dns 只合并 providers/mapping/familyMapping（其余 DnsSettings 使用 api 默认值）。
     */
    fun load(logger: SidebikeLogger = NoopSidebikeLogger): SidebikeConfig {
        val base = SidebikeConfig()
        val text = rulesText() ?: run {
            logger.warn(TAG, "内置规则快照缺失（$RULES_RESOURCE），回退为纯 api 默认配置（镜像规则不可用）")
            return base
        }
        val snapshot = try {
            ConfigJson.decode(text)
        } catch (e: Exception) {
            logger.error(TAG, "内置规则快照解析失败，回退为 api 默认配置", e)
            return base
        }
        logger.info(
            TAG,
            "内置规则已加载: intercepts=${snapshot.intercepts.size} 域, presetIp=${snapshot.preSetIpList.size} 域, " +
                "whiteList=${snapshot.whiteList.size}, excludeIpList=${snapshot.excludeIpList.size}, " +
                "dnsProviders=${snapshot.dns.providers.size}, domestic=${snapshot.domesticAllowList.size} 条",
        )
        return base.copy(
            intercepts = snapshot.intercepts,
            whiteList = snapshot.whiteList,
            preSetIpList = snapshot.preSetIpList,
            excludeIpList = snapshot.excludeIpList,
            domesticAllowList = snapshot.domesticAllowList,
            dns = base.dns.copy(
                providers = snapshot.dns.providers,
                mapping = snapshot.dns.mapping,
                familyMapping = snapshot.dns.familyMapping,
            ),
        )
    }

    /** 仅取 dns.providers 快照（测试/诊断用）。 */
    fun snapshotDnsSettings(fallback: DnsSettings = DnsSettings()): DnsSettings {
        val text = rulesText() ?: return fallback
        return runCatching { ConfigJson.decode(text).dns }.getOrDefault(fallback)
    }

    private const val TAG = "BundledConfig"
}
