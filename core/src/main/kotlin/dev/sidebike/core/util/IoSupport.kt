package dev.sidebike.core.util

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Raised when an incoming HTTP message violates the protocol (bad request line, oversized headers,
 * broken chunked framing...). Proxy code maps it to `400 Bad Request`.
 */
class MalformedHttpException(message: String) : IOException(message)

/** InputStream limited to exactly [limit] bytes; hitting EOF early is a protocol error. */
class FixedLengthInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var remaining = limit

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b < 0) throw EOFException("unexpected EOF: $remaining bytes remaining")
        remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val n = super.read(b, off, toRead)
        if (n < 0) throw EOFException("unexpected EOF: $remaining bytes remaining")
        remaining -= n
        return n
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()

    override fun skip(n: Long): Long {
        val skipped = super.skip(minOf(n, remaining))
        remaining -= skipped
        return skipped
    }

    val bytesRemaining: Long get() = remaining
}

/** Decodes HTTP/1.1 `Transfer-Encoding: chunked`. EOF before the terminating chunk is an error. */
class ChunkedInputStream(input: InputStream) : FilterInputStream(input) {
    private var chunkRemaining = 0L
    private var finished = false
    private var started = false

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (finished) return -1
        if (len == 0) return 0
        if (!started || chunkRemaining == 0L) {
            if (started) {
                readCrLf()
            }
            started = true
            chunkRemaining = readChunkSize()
            if (chunkRemaining == 0L) {
                readTrailers()
                finished = true
                return -1
            }
        }
        val toRead = minOf(len.toLong(), chunkRemaining).toInt()
        val n = super.read(b, off, toRead)
        if (n < 0) throw EOFException("unexpected EOF inside chunked body")
        chunkRemaining -= n
        return n
    }

    private fun readChunkSize(): Long {
        val line = HttpCodec.readLine(`in`) ?: throw EOFException("unexpected EOF before chunk size")
        val sizePart = line.substringBefore(';').trim()
        if (sizePart.isEmpty()) throw MalformedHttpException("empty chunk size")
        val size = try {
            java.lang.Long.parseLong(sizePart, 16)
        } catch (_: NumberFormatException) {
            throw MalformedHttpException("bad chunk size: '$sizePart'")
        }
        if (size < 0 || size > MAX_CHUNK_SIZE) throw MalformedHttpException("chunk size out of range: $size")
        return size
    }

    private fun readCrLf() {
        val a = super.read()
        val b = super.read()
        if (a < 0 || b < 0) throw EOFException("unexpected EOF after chunk data")
        if (a != '\r'.code || b != '\n'.code) throw MalformedHttpException("missing CRLF after chunk data")
    }

    private fun readTrailers() {
        while (true) {
            val line = HttpCodec.readLine(`in`) ?: throw EOFException("unexpected EOF in chunked trailers")
            if (line.isEmpty()) return
        }
    }

    companion object {
        private const val MAX_CHUNK_SIZE = 64L * 1024 * 1024
    }
}

/** Input stream that fails once more than [limit] bytes are read (used for bounded body buffering). */
class LimitExceededException(val limit: Long) : IOException("body exceeds limit of $limit bytes")

object IoSupport {
    const val DEFAULT_BUFFER_SIZE = 16 * 1024

    fun copy(input: InputStream, output: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
        val buf = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

    fun copyAtMost(input: InputStream, output: OutputStream, maxBytes: Long, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
        val buf = ByteArray(bufferSize)
        var total = 0L
        while (total < maxBytes) {
            val want = minOf(buf.size.toLong(), maxBytes - total).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            if (n == 0) continue
            output.write(buf, 0, n)
            total += n
        }
        return total
    }

    fun readAll(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(256, input.available().coerceAtMost(64 * 1024)))
        copy(input, out)
        return out.toByteArray()
    }

    /** Reads at most [maxBytes]; throws [LimitExceededException] when the stream is longer. */
    fun readAllLimited(input: InputStream, maxBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024).toInt())
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) throw LimitExceededException(maxBytes)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Reads at most [maxBytes] but never throws; returns the prefix read (stream may still have data). */
    fun readPrefix(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (n < 0) break
            total += n
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
