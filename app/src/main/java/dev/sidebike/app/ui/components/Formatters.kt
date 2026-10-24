package dev.sidebike.app.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 纯格式化工具（无 Compose 依赖，可被单元测试直接覆盖）。 */
object UiFormat {

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)

    /** 字节数 -> 人类可读单位（B / KB / MB / GB / TB，1024 进制）。 */
    fun bytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble() / 1024.0
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        val pattern = if (value >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.CHINA, pattern, value, units[index])
    }

    /** 整数计数：按中文习惯加千位分隔符。 */
    fun count(value: Long): String = String.format(Locale.CHINA, "%,d", value)

    /**
     * 字节数展示：已有连接但字节统计仍为 0 时显示 "—"（统计链路未就绪/未知），
     * 避免把「未知」误报成 "0 B"。
     */
    fun bytesOrDash(bytes: Long, hasConnections: Boolean): String =
        if (bytes == 0L && hasConnections) "—" else bytes(bytes)

    /** 毫秒延迟。 */
    fun latency(ms: Long): String = if (ms <= 0) "—" else "$ms ms"

    /** 运行时长 mm:ss 或 hh:mm:ss。 */
    fun uptime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
        }
    }

    /** 日志时间戳 -> HH:mm:ss。 */
    fun timeOfDay(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeFormatter)
}
