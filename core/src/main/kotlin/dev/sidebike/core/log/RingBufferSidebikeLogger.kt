package dev.sidebike.core.log

import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.api.SidebikeLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 线程安全的环形缓冲日志（对齐上游 `core/src/utils/util.log.*` 的"文件+控制台"职责，
 * 但 Android 端只需内存环形缓冲 + 订阅回调）。
 *
 * 契约（见 `api/Logging.kt`）：
 * - [subscribe] 先补历史再回调新增，不丢日志；
 * - [history] 返回快照，调用方不可修改内部状态；
 * - [clear] 只清缓冲，不影响已注册订阅者。
 *
 * 日志内容在入缓冲前做基础脱敏（Authorization/Cookie/token 等），对齐上游 `util.redact*.js` 的思路：
 * 上游在 `logDetail=false`（默认）时也不会把完整凭据写日志。
 */
class RingBufferSidebikeLogger(
    private val maxEntries: Int = 500,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SidebikeLogger {

    init {
        require(maxEntries > 0) { "maxEntries must be > 0, was $maxEntries" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(minOf(maxEntries, 1024))
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        val entry = LogEntry(
            timeMs = clock(),
            level = level,
            tag = tag,
            message = redact(message),
            throwable = throwable,
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > maxEntries) {
                buffer.removeFirst()
            }
        }
        // 回调不持锁，避免慢订阅者阻塞日志写入
        for (listener in listeners) {
            runCatching { listener(entry) }
        }
    }

    override fun history(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    override fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    override fun subscribe(listener: (LogEntry) -> Unit): AutoCloseable {
        val snapshot: List<LogEntry>
        synchronized(lock) {
            snapshot = buffer.toList()
            listeners.add(listener)
        }
        // 先补历史（不持锁，避免回调里再次写日志造成死锁）
        for (entry in snapshot) {
            runCatching { listener(entry) }
        }
        return AutoCloseable { listeners.remove(listener) }
    }

    /** 供测试/诊断：当前缓冲条数。 */
    fun size(): Int = synchronized(lock) { buffer.size }

    companion object {
        private val HEADER_VALUE = Regex(
            """(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key)\s*[:=]\s*[^\s,;]+""",
        )
        private val TOKEN_PARAM = Regex("""(?i)\b(token|access_token|password|secret|key)=[^&\s]+""")

        /**
         * 基础脱敏：Authorization/Cookie/常见 token 参数。返回值仍是日志文本，但凭据被替换为 `***`。
         * 注意：这里只做"防误写盘/误上报"的兜底，代理错误处理不应把完整头传给日志（调用方仍需自律）。
         */
        internal fun redact(message: String): String {
            var out = HEADER_VALUE.replace(message) { m -> "${m.groupValues[1]}: ***" }
            out = TOKEN_PARAM.replace(out) { m -> "${m.groupValues[1]}=***" }
            return out
        }
    }
}
