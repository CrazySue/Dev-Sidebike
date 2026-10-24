package dev.sidebike.core.util

import dev.sidebike.core.api.LogLevel
import dev.sidebike.core.log.RingBufferSidebikeLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class RingBufferLoggerTest {

    private fun logger(max: Int = 10): RingBufferSidebikeLogger {
        val clock = AtomicLong(1000)
        return RingBufferSidebikeLogger(max) { clock.incrementAndGet() }
    }

    @Test
    fun `keeps only newest entries`() {
        val logger = logger(3)
        repeat(5) { logger.info("T", "m$it") }
        val history = logger.history()
        assertEquals(3, history.size)
        assertEquals(listOf("m2", "m3", "m4"), history.map { it.message })
        assertEquals(3, logger.size())
    }

    @Test
    fun `subscribe replays history then receives live entries and can unsubscribe`() {
        val logger = logger(10)
        logger.info("T", "before")
        val received = mutableListOf<String>()
        val handle = logger.subscribe { received.add(it.message) }
        logger.warn("T", "after")
        assertEquals(listOf("before", "after"), received)
        handle.close()
        logger.error("T", "after-close")
        assertEquals(2, received.size)
    }

    @Test
    fun `clear empties buffer without touching subscribers`() {
        val logger = logger(10)
        logger.info("T", "a")
        val received = mutableListOf<String>()
        val handle = logger.subscribe { received.add(it.message) }
        logger.clear()
        assertTrue(logger.history().isEmpty())
        logger.info("T", "b")
        assertEquals(listOf("a", "b"), received)
        handle.close()
    }

    @Test
    fun `levels and throwable are preserved`() {
        val logger = logger()
        val boom = IllegalStateException("boom")
        logger.log(LogLevel.ERROR, "tag", "msg", boom)
        val entry = logger.history().single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("tag", entry.tag)
        assertEquals(boom, entry.throwable)
        assertTrue(entry.timeMs > 0)
    }

    @Test
    fun `credentials are redacted`() {
        val logger = logger()
        logger.info("T", "authorization: Bearer secret-token")
        logger.info("T", "cookie: session=abcdef; other=1")
        logger.info("T", "GET https://x/y?token=abc123&a=1")
        val messages = logger.history().map { it.message }
        assertFalse(messages[0].contains("secret-token"))
        assertTrue(messages[0].contains("***"))
        assertFalse(messages[1].contains("abcdef"))
        assertFalse(messages[2].contains("abc123"))
    }

    @Test
    fun `concurrent logging does not throw or corrupt size`() {
        val logger = logger(200)
        val threads = (1..8).map { index ->
            Thread {
                repeat(100) { logger.info("T$index", "m$it") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(200, logger.size())
    }
}
