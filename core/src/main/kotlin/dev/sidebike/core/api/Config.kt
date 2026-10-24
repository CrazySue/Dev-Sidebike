package dev.sidebike.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Dev-Sidebike 全量配置。字段布局尽量与上游 Dev-Sidecar `running.json` 兼容：
 * 顶层 `intercepts` / `whiteList` / `preSetIpList` / `excludeIpList` / `dns.*` 语义一致。
 */
@Serializable
data class SidebikeConfig(
    val version: Int = CURRENT_VERSION,
    val app: AppSettings = AppSettings(),
    val proxy: ProxySettings = ProxySettings(),
    val dns: DnsSettings = DnsSettings(),
    val mitm: MitmSettings = MitmSettings(),
    val ui: UiSettings = UiSettings(),
    val intercepts: Map<String, Map<String, InterceptAction>> = emptyMap(),
    val whiteList: Map<String, Boolean?> = emptyMap(),
    val preSetIpList: Map<String, Map<String, Boolean>> = emptyMap(),
    val excludeIpList: Map<String, Boolean?> = emptyMap(),
    val domesticAllowList: List<String> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class AppSettings(
    val autoStartOnBoot: Boolean = false,
    val startOnLaunch: Boolean = false,
    val logLevel: String = "info",
    val logMaxEntries: Int = 500,
    val themeMode: String = "system",
    val dynamicColor: Boolean = true,
)

@Serializable
data class ProxySettings(
    val enabled: Boolean = true,
    /** 单端口同时服务 HTTP 与 HTTPS(CONNECT)，与上游默认 31181 对齐。 */
    val port: Int = 31181,
    /** HTTP 层改写（sni/镜像/脚本/替换）需要 MITM；默认关闭，开启需用户安装 CA。 */
    val mitmEnabled: Boolean = false,
    /** 国内域名直接直连，不进入代理链路。 */
    val bypassDomestic: Boolean = true,
    val setSystemProxy: Boolean = true,
    val maxConnections: Int = 512,
    val connectTimeoutMs: Int = 12_000,
    val idleTimeoutMs: Int = 60_000,
    val readTimeoutMs: Int = 30_000,
)

enum class DnsMode {
    /** 按 mapping 分流：默认 UDP/国内 Provider，海外域走 DoH/DoT。 */
    SMART,

    /** 所有查询优先 DoH。 */
    DOH_ONLY,

    /** 只使用配置中的第一个可用 Provider（调试用）。 */
    SINGLE,
}

enum class DnsProtocol { AUTO, UDP, TCP, DOT, DOH }

@Serializable
data class DnsProvider(
    /** "223.5.5.5" | "udp://223.5.5.5" | "tls://1.1.1.1" | "https://doh.pub/dns-query" */
    val server: String,
    val protocol: DnsProtocol = DnsProtocol.AUTO,
    /** 连接上游时使用的伪装 SNI（对齐上游 cf-DoT 的 sni: baidu.com）。 */
    val sni: String? = null,
    /**
     * 连接上游前用于自举的 IP 列表（DoH/DoT 的 URL/TLS 主机名不能走系统 DNS，
     * 否则在 VPN 内会形成自举环或再次被污染）。为空时使用 [server] 中的字面 IP。
     */
    val bootstrapIps: List<String> = emptyList(),
    /** 仅用于 SNI 查询（对齐上游 forSNI）。 */
    val forSni: Boolean = false,
    val enabled: Boolean = true,
    val weight: Int = 1,
    /** 伪装 SNI 场景下上游证书不可信，默认跳过校验。 */
    val trustAllCerts: Boolean = true,
)

/** 默认 DoH/DoT provider 的自举 IP（均为国内可直连的公共 DNS 出口 IP）。 */
object DnsBootstraps {
    const val ALIYUN_DOH_IP: String = "223.5.5.5"
    const val DOHPUB_IP: String = "120.53.53.53"
    const val DOHPUB_BACKUP_IP: String = "1.12.12.12"
    const val CLOUDFLARE_IP: String = "1.1.1.1"
    const val QUAD9_IP: String = "9.9.9.9"
    const val RUBYFISH_IP: String = "203.107.6.88"
}

@Serializable
data class DnsSettings(
    val enabled: Boolean = true,
    /** VPN 内下发给应用的 DNS 地址（tun 内拦截该地址的 UDP/53）。 */
    val vpnDnsAddress: String = "10.111.0.2",
    /** tun 本机地址。 */
    val tunAddress: String = "10.111.0.1",
    val mode: DnsMode = DnsMode.SMART,
    val providers: Map<String, DnsProvider> = defaultProviders(),
    /** 域名 pattern -> provider 名。 */
    val mapping: Map<String, String> = emptyMap(),
    /** 域名 pattern -> "4" / "6"（IPv4/IPv6 偏好），对齐上游 familyMapping。 */
    val familyMapping: Map<String, String> = emptyMap(),
    /** 本地 hosts 覆盖，优先级最高。 */
    val hosts: Map<String, String> = emptyMap(),
    val cacheSize: Int = 2048,
    val minTtlSec: Int = 10,
    val maxTtlSec: Int = 3600,
    val queryTimeoutMs: Int = 5_000,
    /** 并行查询多个 provider，取最快有效结果（防污染）。 */
    val parallelProviders: Boolean = true,
    /** 顺序回退列表，前面的失败后依次尝试。 */
    val fallbackProviders: List<String> = listOf("aliyun", "doh.pub", "cf-DoH"),
    val blockIpv6: Boolean = false,
) {
    companion object {
        fun defaultProviders(): Map<String, DnsProvider> = linkedMapOf(
            "aliyun" to DnsProvider(server = "223.5.5.5", protocol = DnsProtocol.UDP),
            "doh.pub" to DnsProvider(
                server = "https://doh.pub/dns-query",
                protocol = DnsProtocol.DOH,
                bootstrapIps = listOf(DnsBootstraps.DOHPUB_IP, DnsBootstraps.DOHPUB_BACKUP_IP),
            ),
            "cf-DoT" to DnsProvider(
                server = "tls://1.1.1.1",
                protocol = DnsProtocol.DOT,
                sni = "baidu.com",
                bootstrapIps = listOf(DnsBootstraps.CLOUDFLARE_IP),
            ),
            "cf-DoH" to DnsProvider(
                server = "https://cloudflare-dns.com/dns-query",
                protocol = DnsProtocol.DOH,
                sni = "baidu.com",
                bootstrapIps = listOf(DnsBootstraps.CLOUDFLARE_IP),
            ),
            "quad9-ip" to DnsProvider(
                server = "https://9.9.9.9/dns-query",
                protocol = DnsProtocol.DOH,
                bootstrapIps = listOf(DnsBootstraps.QUAD9_IP),
            ),
            "rubyfish" to DnsProvider(
                server = "https://rubyfish.cn/dns-query",
                protocol = DnsProtocol.DOH,
                bootstrapIps = listOf(DnsBootstraps.RUBYFISH_IP),
            ),
        )
    }
}

@Serializable
data class MitmSettings(
    val enabled: Boolean = false,
    /** 上游证书不校验（对齐上游行为；仅对规则内域名生效）。 */
    val trustUpstreamAll: Boolean = true,
    val maxLeafCache: Int = 256,
    val leafValidDays: Int = 365,
    /** CA 证书/私钥在 App 私有目录中的文件名。 */
    val caCertFileName: String = "sidebike.ca.crt",
    val caKeyFileName: String = "sidebike.ca.key.pem",
)

@Serializable
data class UiSettings(
    val lastScreen: String = "home",
    val showAdvanced: Boolean = false,
)

/**
 * 对齐上游 `intercepts[domainPattern][pathRegex] = action`。
 * 未使用的字段保留为 [JsonElement]，兼容上游 `success: true` / `success: {...}` 两种形态。
 */
@Serializable
data class InterceptAction(
    val sni: String? = null,
    val proxy: String? = null,
    val success: JsonElement? = null,
    val script: String? = null,
    val tampermonkeyScript: String? = null,
    val requestReplace: JsonElement? = null,
    val responseReplace: JsonElement? = null,
    val cacheDays: Int? = null,
    val status: Int? = null,
    val desc: String? = null,
    val remark: String? = null,
)
