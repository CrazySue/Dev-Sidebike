package dev.sidebike.core.api

object ProxyDefaults {
    /** 单端口：HTTP 代理 + HTTPS CONNECT 共用，与上游保持一致。 */
    const val DEFAULT_PORT: Int = 31181
}

/** 代理处理一个请求后的统计回执。 */
data class ProxyResult(
    val success: Boolean,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val error: String? = null,
)

/**
 * HTTP/HTTPS 代理服务（监听 127.0.0.1）。
 * 路由/接管决策由内部 RoutePlanner 完成，外部只关心生命周期与统计回调。
 */
interface ProxyServer {
    fun start(port: Int)
    fun stop()
    val isRunning: Boolean
    val port: Int

    /** 每次请求结束时回调（IO 线程），用于驱动 UI 统计。 */
    fun setResultListener(listener: (host: String, result: ProxyResult) -> Unit)

    /** 更新配置（如 MITM 开关、规则），可在运行中调用。 */
    fun updateConfig(config: SidebikeConfig)
}
