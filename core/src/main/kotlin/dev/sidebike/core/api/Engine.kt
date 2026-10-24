package dev.sidebike.core.api

import java.io.File

/**
 * Dev-Sidebike 后端唯一门面。Android 侧（VpnService/Controller）只依赖此接口。
 */
interface SidebikeEngine {

    /** 启动 DNS 解析器与本地代理（若已启动则幂等）。 */
    fun start()

    /** 停止所有子系统（幂等）。 */
    fun stop()

    val isRunning: Boolean

    fun updateConfig(config: SidebikeConfig)

    fun currentConfig(): SidebikeConfig

    /**
     * 处理 tun 内截获的 DNS wire query（UDP payload）。
     * 返回 wire-format 响应；无法解析时返回 null（调用方可直接丢弃）。
     */
    suspend fun resolveRawDnsQuery(query: ByteArray): ByteArray?

    fun stats(): EngineStats

    fun logger(): SidebikeLogger

    /** 返回 CA 证书 PEM（若已启用/生成），供安装引导页导出；未启用 MITM 时返回 null。 */
    fun caCertificatePem(): String?

    /** 确保 CA 已生成并返回 PEM（MITM 开启时由 Controller 调用）。 */
    fun ensureCaCertificate(): String?

    fun close()
}

/**
 * 引擎工厂：由 `engine/DefaultSidebikeEngine` 实现并注册。
 * app 通过 [create] 获取实例，不直接引用实现类。
 */
object SidebikeEngineFactory {
    @Volatile
    private var creator: ((File, SidebikeConfig, SidebikeLogger?) -> SidebikeEngine)? = null

    fun install(factory: (File, SidebikeConfig, SidebikeLogger?) -> SidebikeEngine) {
        creator = factory
    }

    fun create(dataDir: File, config: SidebikeConfig, logger: SidebikeLogger? = null): SidebikeEngine {
        val f = creator ?: error("SidebikeEngineFactory 未安装实现（engine/DefaultSidebikeEngine 未注册）")
        return f(dataDir, config, logger)
    }
}
