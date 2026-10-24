package dev.sidebike.app.ui

import dev.sidebike.app.ui.theme.ThemeMode
import dev.sidebike.app.ui.theme.resolveDarkTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResolveTest {

    @Test
    fun `explicit theme modes override system`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test
    fun `system mode follows system flag`() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun `unknown or empty mode falls back to system`() {
        assertTrue(resolveDarkTheme(null, systemInDarkTheme = true))
        assertTrue(resolveDarkTheme("", systemInDarkTheme = true))
        assertTrue(resolveDarkTheme("sepia", systemInDarkTheme = true))
    }
}
