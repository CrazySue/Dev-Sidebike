package dev.sidebike.app.ui.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 可导出的文件类型（决定 MIME、扩展名与安全文件名的前缀）。 */
enum class ExportFileKind(val extension: String, val mimeType: String) {
    LOG("log", "text/plain"),
    DEBUG_BUNDLE("zip", "application/zip"),
    CA_CRT("crt", "application/x-x509-ca-cert"),
    TEXT("txt", "text/plain"),
}

/** 导出文件名：`dev-sidebike-yyyyMMdd-HHmmss.<ext>`（时间来自注入的固定 clock，便于测试）。 */
fun exportFileName(kind: ExportFileKind, nowMs: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
    return "dev-sidebike-$stamp.${kind.extension}"
}

/**
 * 把文本/字节写入 SAF URI 的辅助（真正的 IO 在 root 的 ContentResolver 回调里调用）。
 * 单独抽出来便于 Robolectric 单测覆盖「内容转换」逻辑；IO 本身由系统 provider 负责。
 */
object ExportPayloads {
    fun logText(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun pem(pem: String): ByteArray = pem.toByteArray(Charsets.UTF_8)

    fun text(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)
}
