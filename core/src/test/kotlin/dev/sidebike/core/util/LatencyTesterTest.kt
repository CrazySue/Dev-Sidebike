package dev.sidebike.core.util

import dev.sidebike.core.speed.LatencyTester
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LatencyTesterTest {

    private lateinit var server: ServerSocket
    private val clients = mutableListOf<Thread>()

    @Before
    fun setUp() {
        server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        clients += thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().close()
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        clients.forEach { runCatching { it.join(500) } }
    }

    @Test
    fun `measures local tcp latency`() = runBlocking {
        val tester = LatencyTester(timeoutMs = 2000)
        val latency = tester.tcpLatencyMs("127.0.0.1", server.localPort)
        assertNotNull(latency)
        assert(latency!! >= 0)
    }

    @Test
    fun `closed port returns null`() = runBlocking {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = closed.localPort
        closed.close()
        val tester = LatencyTester(timeoutMs = 500)
        assertNull(tester.tcpLatencyMs("127.0.0.1", port))
    }

    @Test
    fun `rank sorts reachable targets`() = runBlocking {
        val tester = LatencyTester(timeoutMs = 1000)
        val ranked = tester.rank(
            listOf(
                "127.0.0.1" to server.localPort,
                "127.0.0.1" to 1, // 不可达
            ),
        )
        assertEquals(1, ranked.size)
        assertEquals("127.0.0.1", ranked[0].first)
    }
}
