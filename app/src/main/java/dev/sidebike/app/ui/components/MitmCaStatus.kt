package dev.sidebike.app.ui.components

import dev.sidebike.app.R

/**
 * MITM / CA 的三态语义：UI 文案必须由**当前实际状态**决定，不能出现「MITM 未开却提示安装证书」、
 * 或「已 RUNNING 还提示先去启动服务」这类前后矛盾的文案。
 */
enum class MitmCaStatus {
    /** MITM 关闭（默认）：MITM 只是可选增强，不提示安装证书。 */
    MITM_DISABLED,

    /** MITM 已开启但 CA 未安装：需要引导安装证书。 */
    NEED_CA,

    /** MITM 已开启且 CA 已安装：证书已就绪。 */
    READY,
}

fun mitmCaStatusOf(mitmEnabled: Boolean, caInstalled: Boolean): MitmCaStatus = when {
    !mitmEnabled -> MitmCaStatus.MITM_DISABLED
    caInstalled -> MitmCaStatus.READY
    else -> MitmCaStatus.NEED_CA
}

/** 首页/快捷操作使用的状态文案。 */
fun mitmCaHomeLabelRes(status: MitmCaStatus): Int = when (status) {
    MitmCaStatus.MITM_DISABLED -> R.string.home_mitm_disabled
    MitmCaStatus.NEED_CA -> R.string.home_mitm_need_ca
    MitmCaStatus.READY -> R.string.home_mitm_ready
}

/** 设置页「证书状态」使用的状态文案。 */
fun mitmCaSettingsLabelRes(status: MitmCaStatus): Int = when (status) {
    MitmCaStatus.MITM_DISABLED -> R.string.settings_ca_mitm_off
    MitmCaStatus.NEED_CA -> R.string.settings_ca_need_install
    MitmCaStatus.READY -> R.string.settings_ca_installed
}
