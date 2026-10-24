package dev.sidebike.core.config

import dev.sidebike.core.api.ConfigRepository
import dev.sidebike.core.api.SidebikeConfig
import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 默认配置仓库（纯 Kotlin/JVM，Android 可用）。
 *
 * ## 层次（对齐上游 `local-config-loader.js:234-259` 的 4 层合并，Android 精简为 3 层）
 * 1. 内置默认值：[dev.sidebike.core.api.SidebikeConfig] 编译期默认（api/Config.kt）
 * 2. 官方规则快照：[BundledConfig.load]（resources 中的 rules.json，离线可用）
 * 3. 用户覆盖：`<dataDir>/config.json`（本 App 自己写出的 JSON；也接受上游 JSON5 片段）
 *
 * 用户文件只依赖"部分配置 + 深合并"语义：缺失字段继承内置值，数组整体替换，JSON null 删除键。
 * 运行期**不访问网络**（远程规则更新属于后续能力，见 `docs/feasibility/01b-config-porting.md`）。
 *
 * ## 线程模型
 * [load]/[save]/[importUpstream] 用同一把锁串行化；[load] 结果缓存，[defaults] 不缓存。
 */
class DefaultConfigRepository(
    private val dataDir: File,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
    private val bundledProvider: () -> SidebikeConfig = { BundledConfig.load(logger) },
) : ConfigRepository {

    private val lock = Any()
    private val configFile: File = File(dataDir, "config.json")

    @Volatile
    private var cached: SidebikeConfig? = null

    /** 最近一次 load/import 结果（未加载时触发 load）。 */
    fun current(): SidebikeConfig = cached ?: load()

    /** 用户覆盖文件路径（便于 UI 展示/导出）。 */
    fun userConfigFile(): File = configFile

    override fun defaults(): SidebikeConfig = bundledProvider()

    override fun load(): SidebikeConfig = synchronized(lock) {
        val base = bundledProvider()
        val userJson = readUserJsonOrNull()
        val merged = if (userJson == null) {
            base
        } else {
            try {
                ConfigJson.mergeConfig(base, userJson)
            } catch (e: Exception) {
                logger.error(TAG, "用户配置合并失败，回退内置配置（文件保留）: ${configFile.absolutePath}", e)
                base
            }
        }
        val migrated = ConfigJson.migrate(merged)
        if (migrated.version > SidebikeConfig.CURRENT_VERSION) {
            logger.warn(TAG, "配置版本 ${migrated.version} 高于当前内核 ${SidebikeConfig.CURRENT_VERSION}，按兼容模式加载")
        }
        cached = migrated
        migrated
    }

    override fun save(config: SidebikeConfig) {
        synchronized(lock) {
            configFile.parentFile?.mkdirs()
            val text = ConfigJson.encodePretty(config)
            val tmp = File(configFile.parentFile, configFile.name + ".tmp")
            try {
                tmp.writeText(text, Charsets.UTF_8)
                try {
                    Files.move(
                        tmp.toPath(),
                        configFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                cached = config
                logger.info(TAG, "配置已保存: ${configFile.absolutePath} (${text.length} chars)")
            } catch (e: Exception) {
                logger.error(TAG, "配置保存失败: ${configFile.absolutePath}", e)
                throw e
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    /**
     * 导入上游 Dev-Sidecar 配置（running.json / remote_config.json5 / config.json）。
     *
     * 语义：解析 + 映射为 [SidebikeConfig] 并更新内存缓存；**不自动落盘**（由 Controller 决定是否 [save]，
     * 避免误覆盖用户配置）。输入为非法 JSON5 时返回内置配置并记录告警。
     */
    override fun importUpstream(json: String): SidebikeConfig = synchronized(lock) {
        val result = UpstreamConfigMapper.importUpstream(json, bundledProvider())
        for (warning in result.warnings) {
            logger.warn(TAG, "导入上游配置: $warning")
        }
        val migrated = ConfigJson.migrate(result.config)
        cached = migrated
        logger.info(
            TAG,
            "已导入上游配置: intercepts=${migrated.intercepts.size} 域, presetIp=${migrated.preSetIpList.size} 域, " +
                "dnsProviders=${migrated.dns.providers.size}, warnings=${result.warnings.size}",
        )
        migrated
    }

    /** 导出当前生效配置（含内置快照，便于诊断/迁移）。 */
    override fun export(): String = ConfigJson.encodePretty(current())

    /** 清除内存缓存（测试/配置回滚用）。 */
    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    private fun readUserJsonOrNull(): kotlinx.serialization.json.JsonObject? {
        if (!configFile.isFile) return null
        val text = try {
            configFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            logger.warn(TAG, "读取用户配置失败: ${configFile.absolutePath}, ${e.message}")
            return null
        }
        if (text.isBlank()) return null
        return try {
            ConfigJson.parseObject(text)
        } catch (e: Exception) {
            logger.error(TAG, "用户配置 JSON5 解析失败: ${configFile.absolutePath}", e)
            null
        }
    }

    private companion object {
        const val TAG = "ConfigRepository"
    }
}
