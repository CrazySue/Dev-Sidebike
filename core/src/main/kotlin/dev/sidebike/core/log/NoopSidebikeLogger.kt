package dev.sidebike.core.log

import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeLogger

/**
 * 空日志实现：仅用于"未注入 logger"的默认参数与单元测试。
 * 生产路径（`:app` → `DefaultSidebikeEngine`）必须注入 [RingBufferSidebikeLogger]。
 */
object NoopSidebikeLogger : SidebikeLogger {
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) = Unit
    override fun history(): List<LogEntry> = emptyList()
    override fun clear() = Unit
    override fun subscribe(listener: (LogEntry) -> Unit): AutoCloseable = AutoCloseable { }
}
