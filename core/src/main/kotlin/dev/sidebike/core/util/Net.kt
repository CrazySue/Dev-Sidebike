package dev.sidebike.core.util

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Dial target parsed from a proxy request (`host:port`, `[v6]:port`, bare host, absolute URI). */
data class HostPort(val host: String, val port: Int) {
    /** `host:port`, bracketing IPv6 literals the way the HTTP authority form requires. */
    fun authority(): String = if (host.indexOf(':') >= 0) "[$host]:$port" else "$host:$port"

    /** `host:port` for the Host header (omit default port like browsers do). */
    fun hostHeader(): String = when {
        port == 80 || port == 443 -> if (host.indexOf(':') >= 0) "[$host]" else host
        else -> authority()
    }
}

/** Absolute-form request target split into its parts (plain HTTP proxy requests, `proxy` action URLs). */
data class AbsoluteUri(val scheme: String, val host: String, val port: Int, val pathAndQuery: String)

/**
 * Networking helpers. Everything here is deliberately Android-compatible: no `java.net.http`, no `sun.*`,
 * no third-party HTTP client.
 */
object NetUtils {

    private val IPV4_RE = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    fun isIpv4Literal(host: String): Boolean = IPV4_RE.matches(host)

    fun isIpv6Literal(host: String): Boolean {
        if (host.indexOf(':') < 0) return false
        val h = host.removePrefix("[").removeSuffix("]")
        // Conservative check: hex digits, ':' and optional zone id. A literal must contain at least two ':'.
        if (!Regex("^[0-9a-fA-F:.%]+$").matches(h)) return false
        return h.count { it == ':' } >= 2 || h.startsWith("::")
    }

    fun isIpLiteral(host: String): Boolean = isIpv4Literal(host) || isIpv6Literal(host)

    /**
     * Parses an HTTP authority (`example.com`, `example.com:443`, `[::1]:8443`, `[::1]`).
     * Returns null for malformed input (empty host, bad port, junk after `]`).
     */
    fun parseAuthority(authority: CharSequence, defaultPort: Int): HostPort? {
        if (authority.isEmpty()) return null
        val s = authority.toString().trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val host = s.substring(1, close)
            if (host.isEmpty()) return null
            val rest = s.substring(close + 1)
            if (rest.isEmpty()) return HostPort(host, defaultPort)
            if (!rest.startsWith(":")) return null
            val port = rest.substring(1).toIntOrNull() ?: return null
            return if (port in 1..65535) HostPort(host, port) else null
        }
        val colon = s.indexOf(':')
        if (colon < 0) return HostPort(s, defaultPort)
        // More than one ':' without brackets is an un-bracketed IPv6 literal: it has no port.
        if (s.indexOf(':', colon + 1) >= 0) return HostPort(s, defaultPort)
        val host = s.substring(0, colon)
        if (host.isEmpty()) return null
        val port = s.substring(colon + 1).toIntOrNull() ?: return null
        return if (port in 1..65535) HostPort(host, port) else null
    }

    /** Parses an absolute-form URI (`http://host[:port]/path?query`). Returns null when malformed. */
    fun parseAbsoluteUri(target: String): AbsoluteUri? {
        val schemeIdx = target.indexOf("://")
        if (schemeIdx <= 0) return null
        val scheme = target.substring(0, schemeIdx).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = target.substring(schemeIdx + 3)
        var authorityEnd = rest.length
        for (i in rest.indices) {
            val c = rest[i]
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i
                break
            }
        }
        val authority = rest.substring(0, authorityEnd)
        if (authority.isEmpty() || '@' in authority) return null
        val hostPort = parseAuthority(authority, if (scheme == "https") 443 else 80) ?: return null
        var path = rest.substring(authorityEnd)
        if (!path.startsWith("/")) path = "/$path"
        // Fragments must never be forwarded upstream.
        val hash = path.indexOf('#')
        if (hash >= 0) path = path.substring(0, hash)
        if (path.isEmpty()) path = "/"
        return AbsoluteUri(scheme, hostPort.host, hostPort.port, path)
    }

    /**
     * Splits an origin-form request target into path+query, keeping the query string. Anything that is not
     * origin-form (authority / asterisk form) is normalised defensively.
     */
    fun normalizePathAndQuery(target: String): String {
        if (target.isEmpty()) return "/"
        if (target == "*") return target
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return parseAbsoluteUri(target)?.pathAndQuery ?: "/"
        }
        return if (target.startsWith("/")) target else "/$target"
    }

    fun connectTcp(ipOrHost: String, port: Int, connectTimeoutMs: Int): Socket {
        val socket = Socket()
        try {
            if (connectTimeoutMs > 0) {
                socket.connect(InetSocketAddress(ipOrHost, port), connectTimeoutMs)
            } else {
                socket.connect(InetSocketAddress(ipOrHost, port))
            }
            socket.tcpNoDelay = true
            return socket
        } catch (e: IOException) {
            closeQuietly(socket)
            throw e
        }
    }

    fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
            // ignored on purpose: closing is best-effort
        }
    }

    fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
            // ignored on purpose
        }
    }
}

/** {@link java.io.Closeable} adapter for sockets so `use {}` works on them. */
class SocketCloseable(private val socket: Socket) : Closeable {
    override fun close() = NetUtils.closeQuietly(socket)
}
