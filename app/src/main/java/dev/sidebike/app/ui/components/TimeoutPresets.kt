package dev.sidebike.app.ui.components

import dev.sidebike.app.R
import dev.sidebike.core.api.SidebikeConfig

/** 上游 timeouts 三档预设（0.1.1-alpha 设置页）。 */
data class ProxyTimeoutPreset(
    val id: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val idleTimeoutMs: Int,
    val labelRes: Int,
    val descRes: Int,
)

object TimeoutPresets {

    val all: List<ProxyTimeoutPreset> = listOf(
        ProxyTimeoutPreset(
            id = "default",
            connectTimeoutMs = 12_000,
            readTimeoutMs = 30_000,
            idleTimeoutMs = 60_000,
            labelRes = R.string.timeout_preset_default,
            descRes = R.string.timeout_preset_default_desc,
        ),
        ProxyTimeoutPreset(
            id = "fast",
            connectTimeoutMs = 6_000,
            readTimeoutMs = 15_000,
            idleTimeoutMs = 30_000,
            labelRes = R.string.timeout_preset_fast,
            descRes = R.string.timeout_preset_fast_desc,
        ),
        ProxyTimeoutPreset(
            id = "robust",
            connectTimeoutMs = 20_000,
            readTimeoutMs = 60_000,
            idleTimeoutMs = 120_000,
            labelRes = R.string.timeout_preset_robust,
            descRes = R.string.timeout_preset_robust_desc,
        ),
    )

    /** 当前配置命中的预设（三者全等才算；否则 null）。 */
    fun current(config: SidebikeConfig): ProxyTimeoutPreset? = all.firstOrNull {
        it.connectTimeoutMs == config.proxy.connectTimeoutMs &&
            it.readTimeoutMs == config.proxy.readTimeoutMs &&
            it.idleTimeoutMs == config.proxy.idleTimeoutMs
    }

    /** 应用预设到配置（仅改 proxy 三个超时字段）。 */
    fun apply(config: SidebikeConfig, preset: ProxyTimeoutPreset): SidebikeConfig = config.copy(
        proxy = config.proxy.copy(
            connectTimeoutMs = preset.connectTimeoutMs,
            readTimeoutMs = preset.readTimeoutMs,
            idleTimeoutMs = preset.idleTimeoutMs,
        ),
    )
}
