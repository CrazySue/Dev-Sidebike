package dev.sidebike.app.ui

import dev.sidebike.app.ui.components.UiFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `bytes formats binary units`() {
        assertEquals("0 B", UiFormat.bytes(0))
        assertEquals("512 B", UiFormat.bytes(512))
        assertEquals("1.0 KB", UiFormat.bytes(1024))
        assertEquals("1.5 KB", UiFormat.bytes(1536))
        assertEquals("1.0 MB", UiFormat.bytes(1024L * 1024L))
        assertEquals("1.0 GB", UiFormat.bytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `bytes fall back to dash when traffic unknown`() {
        assertEquals("—", UiFormat.bytesOrDash(0, hasConnections = true))
        assertEquals("0 B", UiFormat.bytesOrDash(0, hasConnections = false))
        assertEquals("1.0 KB", UiFormat.bytesOrDash(1024, hasConnections = true))
    }

    @Test
    fun `count uses grouping`() {
        assertEquals("0", UiFormat.count(0))
        assertEquals("1,234", UiFormat.count(1234))
        assertEquals("1,234,567", UiFormat.count(1_234_567))
    }

    @Test
    fun `uptime formats mm ss and hh mm ss`() {
        assertEquals("00:00", UiFormat.uptime(0))
        assertEquals("01:05", UiFormat.uptime(65_000))
        assertEquals("1:02:04", UiFormat.uptime(3_724_000))
    }

    @Test
    fun `latency formats milliseconds`() {
        assertEquals("—", UiFormat.latency(0))
        assertEquals("23 ms", UiFormat.latency(23))
    }
}
