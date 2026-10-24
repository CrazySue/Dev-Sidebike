package dev.sidebike.core.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Ordered, case-insensitive HTTP header collection.
 *
 * Duplicate header fields are preserved in insertion order (RFC 7230 §3.2.2); lookups are
 * case-insensitive as required by the HTTP/1.1 grammar. The upstream implementation relies on Node's
 * lower-cased header object (reference/dev-sidecar/vendor/ds-core/mitmproxy/src/lib/proxy/common/util.js
 * `getOptionsFromRequest`), so all interceptor helpers here work on a lower-case view when needed.
 */
class HttpHeaders {
    private val fields = ArrayList<Pair<String, String>>(16)

    fun size(): Int = fields.size
    fun isEmpty(): Boolean = fields.isEmpty()

    fun add(name: String, value: String) {
        // Sanitise CR/LF: interceptor configs (headers maps, success bodies) are user/remote supplied and
        // must never be able to inject extra header lines (response splitting).
        val safe = if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            value.replace('\r', ' ').replace('\n', ' ')
        } else {
            value
        }
        fields.add(name to safe)
    }

    fun get(name: String): String? {
        for (i in fields.indices) {
            if (fields[i].first.equals(name, ignoreCase = true)) return fields[i].second
        }
        return null
    }

    fun getAll(name: String): List<String> = fields.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun contains(name: String): Boolean = get(name) != null

    /** Replaces every existing field with [name] (case-insensitive) by a single [value]. */
    fun set(name: String, value: String) {
        remove(name)
        add(name, value)
    }

    fun remove(name: String) {
        fields.removeAll { it.first.equals(name, ignoreCase = true) }
    }

    /** Unique header names in first-seen order, original casing preserved. */
    fun names(): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(fields.size)
        for ((n, _) in fields) {
            if (seen.add(n.lowercase())) out.add(n)
        }
        return out
    }

    fun entries(): List<Pair<String, String>> = fields.toList()

    fun lowerCaseMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((n, v) in fields) out[n.lowercase()] = v
        return out
    }

    fun copy(): HttpHeaders {
        val copy = HttpHeaders()
        for ((n, v) in fields) copy.add(n, v)
        return copy
    }

    override fun toString(): String = fields.joinToString("; ") { "${it.first}: ${it.second}" }

    companion object {
        fun of(vararg pairs: Pair<String, String>): HttpHeaders {
            val h = HttpHeaders()
            for ((n, v) in pairs) h.add(n, v)
            return h
        }
    }
}

data class HttpRequestHead(
    val method: String,
    /** Request target exactly as received: origin-form for MITM/upstream requests, absolute-form for plain HTTP proxy. */
    val target: String,
    val version: String,
    val headers: HttpHeaders,
) {
    val isConnect: Boolean get() = method.equals("CONNECT", ignoreCase = true)
}

data class HttpResponseHead(
    val version: String,
    val status: Int,
    val reason: String,
    val headers: HttpHeaders,
)

/**
 * HTTP/1.1 wire codec used by both the plain-HTTP forward path and the MITM path.
 *
 * Deliberately minimal and allocation-friendly: request/response heads are parsed into immutable data
 * classes, bodies are exposed as streams so large downloads are never buffered unless an interceptor
 * needs the bytes.
 *
 * Notes on parity with upstream (`mitmproxy/src/lib/proxy/mitmproxy/createRequestHandler.js`):
 *  - response headers are limited to 64 KiB (`maxHeaderSize: 65536` in `common/util.js`),
 *  - `Connection`, `Keep-Alive`, `Proxy-Connection`, `Transfer-Encoding` and `Upgrade` are hop-by-hop
 *    headers and are stripped before forwarding (upstream strips them before `res.setHeader`).
 */
object HttpCodec {
    const val MAX_HEADER_BYTES: Int = 64 * 1024
    const val MAX_LINE_BYTES: Int = 16 * 1024

    private val TOKEN_RE = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")
    private val REQUEST_LINE_RE = Regex("^([!#$%&'*+\\-.^_`|~0-9A-Za-z]+) ([^ ]+) (HTTP/\\d\\.\\d)$")
    private val STATUS_LINE_RE = Regex("^(HTTP/\\d\\.\\d) (\\d{3})(?: (.*))?$")

    /** Hop-by-hop headers that must not be forwarded (RFC 7230 §6.1). */
    val HOP_BY_HOP: Set<String> = setOf(
        "connection",
        "keep-alive",
        "proxy-connection",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )

    /**
     * Reads one CRLF-terminated line (bare LF tolerated). Returns null on clean EOF at the very first byte.
     * Reads the underlying stream byte-by-byte so callers may pass an unbuffered socket stream without
     * consuming bytes that belong to the next protocol phase (critical for CONNECT: early TLS data).
     */
    fun readLine(input: InputStream, maxBytes: Int = MAX_LINE_BYTES): String? {
        val buf = ByteArrayOutputStream(128)
        var first = true
        var sawCr = false
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (first) return null
                throw EOFException("unexpected EOF while reading line")
            }
            first = false
            if (b == '\n'.code) {
                if (sawCr) {
                    // drop the CR
                    val arr = buf.toByteArray()
                    return String(arr, 0, maxOf(0, arr.size - 1), StandardCharsets.ISO_8859_1)
                }
                return String(buf.toByteArray(), StandardCharsets.ISO_8859_1)
            }
            sawCr = b == '\r'.code
            buf.write(b)
            if (buf.size() > maxBytes) throw MalformedHttpException("line too long (> $maxBytes bytes)")
        }
    }

    /**
     * Reads a full header block (first line + headers) without buffering anything past the final CRLF.
     * Returns `null` only when the peer closed the connection before sending any byte.
     */
    private fun readHeadLines(input: InputStream): List<String>? {
        val first = readLine(input) ?: return null
        val lines = ArrayList<String>(16)
        lines.add(first)
        var total = first.length + 2
        while (true) {
            val line = readLine(input) ?: throw EOFException("unexpected EOF in header block")
            total += line.length + 2
            if (total > MAX_HEADER_BYTES) throw MalformedHttpException("header block too large (> $MAX_HEADER_BYTES bytes)")
            if (line.isEmpty()) break
            if (line[0] == ' ' || line[0] == '\t') throw MalformedHttpException("obsolete header folding is not supported")
            lines.add(line)
        }
        return lines
    }

    private fun parseHeaderLines(lines: List<String>): HttpHeaders {
        val headers = HttpHeaders()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) throw MalformedHttpException("bad header line: '${line.take(80)}'")
            val name = line.substring(0, colon)
            if (!TOKEN_RE.matches(name)) throw MalformedHttpException("bad header name: '${name.take(80)}'")
            var value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            if (value.indexOf('\u0000') >= 0 || value.indexOf('\r') >= 0) throw MalformedHttpException("bad header value")
            headers.add(name, value)
        }
        return headers
    }

    /** Parses a request head; returns null only for a clean EOF before the request line. */
    fun readRequestHead(input: InputStream): HttpRequestHead? {
        val lines = readHeadLines(input) ?: return null
        val requestLine = lines[0]
        val m = REQUEST_LINE_RE.matchEntire(requestLine)
            ?: throw MalformedHttpException("bad request line: '${requestLine.take(120)}'")
        val target = m.groupValues[2]
        if (target.length > MAX_LINE_BYTES) throw MalformedHttpException("request target too long")
        val method = m.groupValues[1].uppercase()
        return HttpRequestHead(method, target, m.groupValues[3], parseHeaderLines(lines))
    }

    /** Parses a response head coming from an upstream server. */
    fun readResponseHead(input: InputStream): HttpResponseHead {
        val lines = readHeadLines(input) ?: throw EOFException("upstream closed before sending a response")
        val statusLine = lines[0]
        val m = STATUS_LINE_RE.matchEntire(statusLine)
            ?: throw MalformedHttpException("bad status line: '${statusLine.take(120)}'")
        val status = m.groupValues[2].toInt()
        if (status < 100 || status > 999) throw MalformedHttpException("bad status code: $status")
        return HttpResponseHead(m.groupValues[1], status, m.groupValues[3], parseHeaderLines(lines))
    }

    /** Body length advertised by a message, or null when no framing header is present. */
    fun contentLength(headers: HttpHeaders): Long? {
        val raw = headers.get("Content-Length") ?: return null
        val values = headers.getAll("Content-Length")
        val parsed = raw.trim().toLongOrNull() ?: throw MalformedHttpException("bad Content-Length: '$raw'")
        if (parsed < 0) throw MalformedHttpException("negative Content-Length: $parsed")
        // Multiple identical Content-Length fields are tolerated (RFC 7230 §3.3.2).
        for (v in values) {
            if (v.trim().toLongOrNull() != parsed) throw MalformedHttpException("conflicting Content-Length headers")
        }
        return parsed
    }

    fun isChunked(headers: HttpHeaders): Boolean {
        val te = headers.get("Transfer-Encoding") ?: return false
        val codings = te.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return codings.lastOrNull() == "chunked"
    }

    /** Returns a body stream for a client request. Requests without framing headers have no body. */
    fun requestBodyStream(head: HttpRequestHead, input: InputStream): InputStream {
        if (isChunked(head.headers)) return ChunkedInputStream(input)
        val len = contentLength(head.headers) ?: return ByteArrayInputStream(ByteArray(0))
        return FixedLengthInputStream(input, len)
    }

    /**
     * Returns a body stream for an upstream response, or null when the response has no body
     * (HEAD, 1xx, 204, 304). Unframed responses are read until the upstream closes the connection.
     */
    fun responseBodyStream(requestMethod: String, response: HttpResponseHead, input: InputStream): InputStream? {
        if (requestMethod.equals("HEAD", ignoreCase = true)) return null
        val status = response.status
        if (status in 100..199 || status == 204 || status == 304) return null
        if (isChunked(response.headers)) return ChunkedInputStream(input)
        val len = contentLength(response.headers) ?: return input
        return FixedLengthInputStream(input, len)
    }

    fun writeRequestHead(out: OutputStream, method: String, target: String, headers: HttpHeaders) {
        val sb = StringBuilder(256)
        sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        for ((name, value) in headers.entries()) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    fun writeResponseHead(out: OutputStream, status: Int, reason: String, headers: HttpHeaders) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status)
        val r = reason.ifBlank { reasonPhrase(status) }
        if (r.isNotEmpty()) sb.append(' ').append(r)
        sb.append("\r\n")
        for ((name, value) in headers.entries()) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    /** Convenience helper for locally generated responses (errors, synthetic interceptor responses). */
    fun writeResponse(
        out: OutputStream,
        status: Int,
        headers: HttpHeaders,
        body: ByteArray?,
        reason: String = "",
    ) {
        val h = headers.copy()
        if (body != null) {
            h.remove("Transfer-Encoding")
            h.set("Content-Length", body.size.toString())
        }
        writeResponseHead(out, status, reason, h)
        if (body != null) out.write(body)
        out.flush()
    }

    /** Re-encodes a decoded body stream as `Transfer-Encoding: chunked`. */
    fun writeChunkedBody(input: InputStream, out: OutputStream, bufferSize: Int = 16 * 1024) {
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            out.write((Integer.toHexString(n) + "\r\n").toByteArray(StandardCharsets.ISO_8859_1))
            out.write(buf, 0, n)
            out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        out.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
    }

    /** Strips hop-by-hop headers, optionally preserving `Transfer-Encoding: chunked` handling to the caller. */
    fun stripHopByHop(headers: HttpHeaders) {
        for (name in HOP_BY_HOP) headers.remove(name)
        // `Connection: x, y` lists additional hop-by-hop fields: strip those too.
        val connectionTokens = headers.get("Connection")?.split(',')?.map { it.trim().lowercase() } ?: emptyList()
        for (token in connectionTokens) {
            if (token.isNotEmpty()) headers.remove(token)
        }
        headers.remove("Connection")
    }

    fun reasonPhrase(status: Int): String = when (status) {
        100 -> "Continue"
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        421 -> "Misdirected Request"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }

    /** Encodes a plain text or HTML body into UTF-8. */
    fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    fun contentTypeText(html: Boolean = false): String =
        if (html) "text/html; charset=utf-8" else "text/plain; charset=utf-8"
}
