package dev.sidebike.app.ui

import dev.sidebike.app.ui.screens.isValidProxyPort
import dev.sidebike.app.ui.screens.normalizeProxyPortInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代理端口输入/校验纯函数测试（UI 的 TextField 输入路径无法在 Robolectric 稳定驱动）。 */
class PortValidationTest {

    @Test
    fun validPortRange() {
        assertTrue(isValidProxyPort(1024))
        assertTrue(isValidProxyPort(31181))
        assertTrue(isValidProxyPort(65535))
        assertFalse(isValidProxyPort(1023))
        assertFalse(isValidProxyPort(65536))
        assertFalse(isValidProxyPort(0))
        assertFalse(isValidProxyPort(null))
    }

    @Test
    fun normalizeInputKeepsDigitsOnlyAndMaxFive() {
        assertEquals("8080", normalizeProxyPortInput("80a80"))
        assertEquals("12345", normalizeProxyPortInput("123456"))
        assertEquals("", normalizeProxyPortInput("abc"))
        assertEquals("31181", normalizeProxyPortInput(" 31181 "))
    }
}
