package dev.sidebike.core.mitm

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

/**
 * Content-Encoding handling for response bodies that interceptors have to inspect/mutate
 * (parity: `InsertScriptMiddleware.js:9-23` supports gzip/deflate/br; brotli has no JDK/Android API,
 * so `br` is passed through untouched - documented limitation).
 */
object ContentCodec {

    private val CHARSET_RE = Regex("charset\\s*=\\s*\"?([A-Za-z0-9._-]+)\"?", RegexOption.IGNORE_CASE)

    fun charsetOf(contentType: String?): Charset {
        if (contentType == null) return StandardCharsets.UTF_8
        val m = CHARSET_RE.find(contentType) ?: return StandardCharsets.UTF_8
        return try {
            Charset.forName(m.groupValues[1])
        } catch (_: Exception) {
            StandardCharsets.UTF_8
        }
    }

    fun isSupportedEncoding(encoding: String?): Boolean {
        if (encoding.isNullOrBlank()) return true
        return when (normalize(encoding)) {
            "", "identity", "gzip", "x-gzip", "deflate" -> true
            else -> false
        }
    }

    fun normalize(encoding: String?): String = encoding?.trim()?.lowercase() ?: ""

    /** Decompresses [bytes]; returns null for unsupported encodings (caller must pass through). */
    fun decode(bytes: ByteArray, encoding: String?): ByteArray? {
        return when (normalize(encoding)) {
            "", "identity" -> bytes
            "gzip", "x-gzip" -> try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            "deflate" -> try {
                InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (_: Exception) {
                try {
                    // Some servers send raw deflate (no zlib header).
                    java.util.zip.Inflater().run {
                        setInput(bytes)
                        val out = ByteArrayOutputStream(bytes.size * 4)
                        val buf = ByteArray(8192)
                        while (!finished()) {
                            val n = inflate(buf)
                            if (n == 0) {
                                if (needsInput() || needsDictionary()) break
                                continue
                            }
                            out.write(buf, 0, n)
                        }
                        end()
                        out.toByteArray()
                    }
                } catch (_: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    fun encode(bytes: ByteArray, encoding: String?): ByteArray? {
        return when (normalize(encoding)) {
            "", "identity" -> bytes
            "gzip", "x-gzip" -> ByteArrayOutputStream(bytes.size + 64).also { out ->
                GZIPOutputStream(out).use { it.write(bytes) }
            }.toByteArray()
            "deflate" -> ByteArrayOutputStream(bytes.size + 64).also { out ->
                DeflaterOutputStream(out).use { it.write(bytes) }
            }.toByteArray()
            else -> null
        }
    }
}

/**
 * Injects a `<script>` tag into an HTML document at the same positions as upstream's
 * `InsertScriptMiddleware` (`</head>` first, then `</body>`, case-insensitive, InsertScriptMiddleware.js:49-87).
 */
object ScriptInjector {

    private val HEAD_TAG = "</head>".toByteArray(StandardCharsets.US_ASCII)
    private val HEAD_TAG_UPPER = "</HEAD>".toByteArray(StandardCharsets.US_ASCII)
    private val BODY_TAG = "</body>".toByteArray(StandardCharsets.US_ASCII)
    private val BODY_TAG_UPPER = "</BODY>".toByteArray(StandardCharsets.US_ASCII)

    fun isHtml(contentType: String?): Boolean {
        if (contentType == null) return false
        val lower = contentType.lowercase()
        return lower.contains("text/html") || lower.contains("application/xhtml+xml")
    }

    /**
     * Inserts [tag] right before the *content* tag, preserving the rest of the document byte-for-byte.
     * Upstream injects into `</head>` for external scripts; the frozen `script` action here has no
     * "head/body" distinction, so we prefer `</body>` (the task guidance) and fall back to `</head>`,
     * `</html>` and finally append.
     */
    fun inject(html: ByteArray, tag: String, charset: Charset = StandardCharsets.UTF_8): ByteArray {
        val payload = ("\r\n\t$tag").toByteArray(charset)
        val index = indexOfIgnoreCase(html, BODY_TAG, BODY_TAG_UPPER)
            ?: indexOfIgnoreCase(html, HEAD_TAG, HEAD_TAG_UPPER)
            ?: html.size
        val result = ByteArray(html.size + payload.size)
        System.arraycopy(html, 0, result, 0, index)
        System.arraycopy(payload, 0, result, index, payload.size)
        System.arraycopy(html, index, result, index + payload.size, html.size - index)
        return result
    }

    private fun indexOfIgnoreCase(data: ByteArray, lower: ByteArray, upper: ByteArray): Int? {
        if (data.size < lower.size) return null
        outer@ for (i in 0..data.size - lower.size) {
            for (j in lower.indices) {
                val b = data[i + j].toInt() and 0xFF
                if (b != (lower[j].toInt() and 0xFF) && b != (upper[j].toInt() and 0xFF)) continue@outer
            }
            return i
        }
        return null
    }
}

/**
 * One buffered response body plus the encoding it was stored with. Interceptors that rewrite the body
 * need the decoded bytes; the re-encoded result is what goes on the wire.
 */
class BufferedBody(
    val bytes: ByteArray,
    val contentEncoding: String?,
)

object BodyTransforms {

    private const val MAX_MODIFY_BYTES = 8L * 1024 * 1024

    /** Result of trying to buffer a response body for mutation. */
    sealed class BufferResult {
        /** Whole body fit in memory and is safe to rewrite. */
        class Buffered(val raw: ByteArray) : BufferResult()

        /** Body exceeded the mutation budget: the already-read prefix must be streamed before [rest]. */
        class TooLarge(val prefix: ByteArray, val rest: InputStream) : BufferResult()
    }

    /**
     * Reads at most [maxBytes] (default 8 MiB) of [input]. When the body is larger, the caller still has a
     * valid, lossless stream: [BufferResult.TooLarge.prefix] + [BufferResult.TooLarge.rest].
     */
    fun bufferForModification(input: InputStream, maxBytes: Long = MAX_MODIFY_BYTES): BufferResult {
        val out = ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) {
                // Split the overflow: everything except the last chunk is the prefix.
                val overflow = (total - maxBytes).toInt()
                out.write(buf, 0, n - overflow)
                val prefix = out.toByteArray()
                val rest = java.io.SequenceInputStream(
                    ByteArrayInputStream(buf, n - overflow, overflow),
                    input,
                )
                return BufferResult.TooLarge(prefix, rest)
            }
            out.write(buf, 0, n)
        }
        return BufferResult.Buffered(out.toByteArray())
    }

    /**
     * Applies `responseReplace` body replacement, then `script` injection (if HTML). Returns the new
     * decoded body or null when nothing changed. Throws nothing; unsupported encodings are handled by the
     * caller (which checks [ContentCodec.isSupportedEncoding] first).
     */
    fun rewriteBody(
        raw: ByteArray,
        contentEncoding: String?,
        contentType: String?,
        action: dev.sidebike.core.api.InterceptAction?,
    ): ByteArray? {
        val decoded = ContentCodec.decode(raw, contentEncoding) ?: return null
        var body = decoded
        var changed = contentEncoding.isNullOrBlank() // decoded == raw only when identity
        val replacement = InterceptorActions.responseReplaceBody(action)
        if (replacement != null) {
            body = ContentCodec.decode(replacement, contentEncoding) ?: replacement
            changed = true
        }
        val spec = InterceptorActions.scriptSpec(action)
        if (spec != null && ScriptInjector.isHtml(contentType)) {
            val tag = InterceptorActions.renderScriptTags(spec)
            body = ScriptInjector.inject(body, tag, ContentCodec.charsetOf(contentType))
            changed = true
        }
        if (!changed) return null
        return if (contentEncoding.isNullOrBlank()) body else (ContentCodec.encode(body, contentEncoding) ?: return null)
    }
}
