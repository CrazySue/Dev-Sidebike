package dev.sidebike.core.api

/** 日志级别，语义与 log4js 对齐（trace 归入 DEBUG）。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
)

/**
 * 线程安全的环形缓冲日志。UI 通过 [subscribe] 获取实时日志；[history] 返回快照。
 * 实现必须保证：subscribe 后不会丢日志（先补历史再回调新增）。
 */
interface SidebikeLogger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)

    fun debug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)

    fun history(): List<LogEntry>
    fun clear()

    /** 返回取消订阅句柄。 */
    fun subscribe(listener: (LogEntry) -> Unit): AutoCloseable
}
