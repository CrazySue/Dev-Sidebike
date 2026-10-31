package dev.sidebike.core.rules

import dev.sidebike.core.api.RuleSource
import dev.sidebike.core.api.RuleSourceFormat

/**
 * Built-in presets for remote rule sources (0.1.1 UI "规则源" page).
 *
 * Source: `Johnshall/Shadowrocket-ADBlock-Rules-Forever` (default branch `release`, rebuilt daily).
 * License: CC BY-SA 4.0 - rule FILES must never be bundled into the APK; they are fetched at runtime and the
 * app's About page must attribute the repository + license. These presets only carry URLs/metadata.
 *
 * Sizes (analyst reference): sr_cnip.conf ~16.7KB (recommended, enabled by default);
 * sr_top500_whitelist.conf ~30.8KB (optional); sr_top500_banlist.conf ~1.04MB (manual only).
 */
object RuleSourcePresets {

    const val REPO: String = "Johnshall/Shadowrocket-ADBlock-Rules-Forever"
    const val LICENSE: String = "CC BY-SA 4.0"
    const val LICENSE_URL: String = "https://creativecommons.org/licenses/by-sa/4.0/"
    const val RAW_BASE: String = "https://raw.githubusercontent.com/$REPO/release/"
    const val PAGES_BASE: String = "https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/"

    const val FILE_CNIP: String = "sr_cnip.conf"
    const val FILE_TOP500_WHITELIST: String = "sr_top500_whitelist.conf"
    const val FILE_TOP500_BANLIST: String = "sr_top500_banlist.conf"

    fun rawUrl(file: String): String = RAW_BASE + file

    /** GitHub Pages mirror of the same daily build (both support ETag/Last-Modified). */
    fun pagesUrl(file: String): String = PAGES_BASE + file

    /**
     * Default presets shown by the UI. Only the recommended `cnip` split-routing source is enabled by
     * default; the first-500 whitelist is opt-in and the 1MB banlist is manual-refresh only (mobile data).
     */
    fun defaultSources(): List<RuleSource> = listOf(
        RuleSource(
            id = "sr_cnip",
            name = "Johnshall 默认分流规则（cnip）",
            url = rawUrl(FILE_CNIP),
            format = RuleSourceFormat.SHADOWROCKET_LIST,
            enabled = true,
            autoUpdate = true,
            updateIntervalHours = 24,
            lastImported = 0,
        ),
        RuleSource(
            id = "sr_top500_whitelist",
            name = "Johnshall 500 强白名单（可选）",
            url = rawUrl(FILE_TOP500_WHITELIST),
            format = RuleSourceFormat.SHADOWROCKET_LIST,
            enabled = false,
            autoUpdate = true,
            updateIntervalHours = 24,
            lastImported = 0,
        ),
        RuleSource(
            id = "sr_top500_banlist",
            name = "Johnshall 500 强广告拦截（手动更新，约 1MB）",
            url = rawUrl(FILE_TOP500_BANLIST),
            format = RuleSourceFormat.SHADOWROCKET_LIST,
            enabled = false,
            autoUpdate = false,
            updateIntervalHours = 0,
            lastImported = 0,
        ),
    )
}
