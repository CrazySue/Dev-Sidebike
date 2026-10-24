package dev.sidebike.core.speed

import dev.sidebike.core.api.SidebikeLogger
import dev.sidebike.core.log.NoopSidebikeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * 轻量 TCP 握手测速（对齐上游 `mitmproxy/src/lib/speed/SpeedTester.js:345-380`）：
 * **只测 TCP connect 耗时**，不做 TLS/HTTP/ICMP；超时 5s（可配置）。
 *
 * 用途（beta 精简版）：
 * - DNS 预置 IP / 多 provider 结果的排序辅助；
 * - 诊断页"延迟"展示；
 * - 后续可作为 IP 优选的数据来源（上游完整版含 alive/probing 状态机，beta 不启用）。
 *
 * 所有网络操作都在 [Dispatchers.IO]；单 IP 失败返回 null，不抛异常。
 */
class LatencyTester(
    private val timeoutMs: Int = 3_000,
    private val logger: SidebikeLogger = NoopSidebikeLogger,
) {

    /** 返回 TCP 建连耗时（ms）；失败/超时返回 null。 */
    suspend fun tcpLatencyMs(host: String, port: Int): Long? = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            (System.nanoTime() - start) / 1_000_000
        } catch (e: SocketException) {
            logger.debug(TAG, "tcp latency failed: $host:$port, ${e.message}")
            null
        } catch (e: Exception) {
            logger.debug(TAG, "tcp latency failed: $host:$port, ${e.message}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 并发测量多个 `(host, port)`，按耗时升序返回（失败的条目被过滤）。
     * 并发上限由调用方/协程调度器控制（beta 默认全并发）。
     */
    suspend fun rank(targets: List<Pair<String, Int>>): List<Triple<String, Int, Long>> = coroutineScope {
        targets.map { (host, port) ->
            async { tcpLatencyMs(host, port)?.let { Triple(host, port, it) } }
        }.awaitAll()
            .filterNotNull()
            .sortedBy { it.third }
    }

    /** 选择最快的目标；全部失败返回 null。 */
    suspend fun fastest(targets: List<Pair<String, Int>>): Triple<String, Int, Long>? = rank(targets).firstOrNull()

    private companion object {
        const val TAG = "LatencyTester"
    }
}
