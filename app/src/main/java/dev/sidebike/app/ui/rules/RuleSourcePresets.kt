package dev.sidebike.app.ui.rules

import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat
import dev.sidebike.core.api.SidebikeConfig

/**
 * 推荐规则源预设：Johnshall/Shadowrocket-ADBlock-Rules-Forever（release 分支，每日重建）。
 *
 * - 数据来源：https://github.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever
 * - 许可：CC BY-SA 4.0（运行期按需拉取，不随 APK 分发/内嵌）
 * - 解析语义：DIRECT -> 直连放行；REJECT -> 拦截(abort)；PROXY/复杂类型会被跳过（无远端节点）
 *
 * URL 集中于此常量，后续若上游改名只需改这里；备用镜像把 raw.githubusercontent.com 换成
 * johnshall.github.io 即可。
 */
object RuleSourcePresets {

    const val REPO_URL: String = "https://github.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever"
    const val LICENSE: String = "CC BY-SA 4.0"
    private const val RAW_BASE =
        "https://raw.githubusercontent.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever/release"
    private const val MIRROR_BASE =
        "https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/release"

    val presets: List<RuleSource> = listOf(
        RuleSource(
            id = "johnshall-cnip",
            name = "国内外分流（推荐）",
            url = "$RAW_BASE/sr_cnip.conf",
            format = RuleSourceFormat.AUTO,
            enabled = true,
            autoUpdate = true,
            updateIntervalHours = 24,
        ),
        RuleSource(
            id = "johnshall-top500-whitelist",
            name = "Top500 直连白名单",
            url = "$RAW_BASE/sr_top500_whitelist.conf",
            format = RuleSourceFormat.AUTO,
            enabled = false,
            autoUpdate = true,
            updateIntervalHours = 24,
        ),
        RuleSource(
            id = "johnshall-top500-banlist",
            name = "Top500 广告/跟踪拦截（约 1MB，建议仅手动）",
            url = "$RAW_BASE/sr_top500_banlist.conf",
            format = RuleSourceFormat.AUTO,
            enabled = false,
            autoUpdate = false,
            updateIntervalHours = 0,
        ),
    )

    /** 备用镜像 URL（raw.githubusercontent.com 不可达时给用户的提示）。 */
    fun mirrorUrl(rawUrl: String): String = rawUrl.replace(RAW_BASE, MIRROR_BASE)

    /** 合并预设到现有规则源（按 id 去重，已存在的不覆盖用户修改）。 */
    fun mergeInto(existing: List<RuleSource>): List<RuleSource> {
        val existingIds = existing.map { it.id }.toSet()
        return existing + presets.filterNot { it.id in existingIds }
    }

    /** config 里是否已至少添加一个推荐源。 */
    fun hasAnyPreset(config: SidebikeConfig): Boolean =
        config.ruleSources.any { source -> presets.any { it.id == source.id } }
}
