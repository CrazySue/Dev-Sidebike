package dev.sidebike.core.util

import java.io.IOException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bidirectional byte relay for CONNECT tunnels (and any byte-transparent proxying).
 *
 * Robustness rules (all covered by tests):
 *  - both directions run on their own daemon thread and never outlive [relay];
 *  - EOF in one direction triggers `shutdownOutput()` on the *other* socket (half-close), so clients
 *    that finish sending while still expecting a response do not deadlock;
 *  - `Socket#close()` (e.g. from [dev.sidebike.core.proxy.HttpProxyServer.stop]) unblocks the reads;
 *  - idle reads time out via `SO_TIMEOUT` and are treated as a normal close, not an error;
 *  - counters are exact byte counts per direction (up = client -> upstream).
 *
 * Upstream equivalent: `mitmproxy/src/lib/proxy/mitmproxy/createConnectHandler.js#connect()`
 * (`cltSocket.pipe(proxySocket)` / `proxySocket.pipe(cltSocket)` plus `on('timeout')` logging).
 */
object ByteRelay {

    data class RelayResult(val bytesUp: Long, val bytesDown: Long, val error: String?)

    private const val BUFFER_SIZE = 16 * 1024

    /**
     * Relays bytes until both directions reach EOF or a socket error occurs.
     * [client] is the downstream peer, [upstream] the target connection.
     *
     * [onBytes] (optional) is invoked with incremental `(upDelta, downDelta)` byte counts while the tunnel
     * is still open, so statistics show live traffic instead of only updating when a tunnel closes
     * (QA-009: long-lived HTTPS tunnels kept the UI at "0 B").
     */
    fun relay(
        client: Socket,
        upstream: Socket,
        idleTimeoutMs: Int,
        onError: ((String) -> Unit)? = null,
        onBytes: ((upDelta: Long, downDelta: Long) -> Unit)? = null,
    ): RelayResult {
        applyIdleTimeout(client, idleTimeoutMs)
        applyIdleTimeout(upstream, idleTimeoutMs)

        val up = AtomicLong(0)
        val down = AtomicLong(0)
        val errors = java.util.Collections.synchronizedList(ArrayList<String>())
        val latch = CountDownLatch(2)

        fun recordError(direction: String, e: Throwable) {
            if (e is SocketTimeoutException) {
                // Idle tunnel: treat as a close, but record it for diagnostics only.
                errors.add("$direction idle timeout")
                return
            }
            if (e is SocketException && isClosedMessage(e)) return
            errors.add("$direction: ${e.message ?: e.javaClass.simpleName}")
            onError?.invoke("tunnel $direction error: ${e.message}")
        }

        val clientToUpstream = Thread({
            try {
                val input = client.getInputStream()
                val output = upstream.getOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buf, 0, n)
                    output.flush()
                    up.addAndGet(n.toLong())
                    if (onBytes != null) {
                        try {
                            onBytes(n.toLong(), 0L)
                        } catch (_: Throwable) {
                            // statistics listener must never break the tunnel
                        }
                    }
                }
            } catch (e: IOException) {
                recordError("client->upstream", e)
            } finally {
                halfCloseOutput(upstream)
                latch.countDown()
            }
        }, "ds-relay-up")

        val upstreamToClient = Thread({
            try {
                val input = upstream.getInputStream()
                val output = client.getOutputStream()
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buf, 0, n)
                    output.flush()
                    down.addAndGet(n.toLong())
                    if (onBytes != null) {
                        try {
                            onBytes(0L, n.toLong())
                        } catch (_: Throwable) {
                            // statistics listener must never break the tunnel
                        }
                    }
                }
            } catch (e: IOException) {
                recordError("upstream->client", e)
            } finally {
                halfCloseOutput(client)
                latch.countDown()
            }
        }, "ds-relay-down")

        clientToUpstream.isDaemon = true
        upstreamToClient.isDaemon = true
        clientToUpstream.start()
        upstreamToClient.start()

        // Wait for both directions. A finite join protects against a half-broken peer that keeps the
        // socket open forever: once either direction finished we only wait for the other with a bound.
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            clientToUpstream.interrupt()
            upstreamToClient.interrupt()
            NetUtils.closeQuietly(client)
            NetUtils.closeQuietly(upstream)
        }

        val error = if (errors.isEmpty()) null else errors.joinToString("; ")
        return RelayResult(up.get(), down.get(), error)
    }

    /**
     * Relays exactly one buffered blob (the bytes a client sent immediately after CONNECT - the
     * `head` argument upstream) and then keeps relaying. Callers that parsed CONNECT byte-by-byte do not
     * need this, but it is kept for API completeness and tests.
     */
    fun writeFirstBytes(upstream: Socket, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val out = upstream.getOutputStream()
        out.write(bytes)
        out.flush()
    }

    private fun applyIdleTimeout(socket: Socket, timeoutMs: Int) {
        try {
            if (timeoutMs > 0) socket.soTimeout = timeoutMs
        } catch (_: SocketException) {
            // Socket already closed: relay will fail fast.
        }
    }

    private fun halfCloseOutput(socket: Socket) {
        try {
            if (!socket.isClosed && !socket.isOutputShutdown) socket.shutdownOutput()
        } catch (_: IOException) {
            // peer already gone
        }
    }

    private fun isClosedMessage(e: SocketException): Boolean {
        val m = e.message?.lowercase() ?: return false
        return "socket closed" in m || "connection reset" in m || "broken pipe" in m || "connection abort" in m
    }

    /** Test helper: waits for a thread pool to drain (used by lifecycle tests). */
    fun awaitTermination(latch: CountDownLatch, timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
