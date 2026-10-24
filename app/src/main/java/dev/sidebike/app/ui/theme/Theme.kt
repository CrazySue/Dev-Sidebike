package dev.sidebike.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * MD3 theme entry point.
 *
 * - `darkTheme`: resolved from the user's 主题模式 setting (跟随系统 / 浅色 / 深色) in [SidebikeRoot].
 * - `dynamicColor`: Android 12+ Material You dynamic scheme; falls back to the MD3 baseline scheme.
 * - Typography and shape scales come from [SidebikeTypography] / [SidebikeShapes] (pure MD3 tokens).
 */
@Composable
fun SidebikeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> SidebikeDarkColors
        else -> SidebikeLightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SidebikeTypography,
        shapes = SidebikeShapes,
        content = content,
    )
}

/** 主题模式配置值（持久化在 [dev.sidebike.core.api.AppSettings.themeMode]）。 */
object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    val options: List<String> = listOf(SYSTEM, LIGHT, DARK)
}

/**
 * Pure resolver so it can be unit-tested without composition:
 * config `themeMode` + system dark flag -> effective `darkTheme`.
 */
fun resolveDarkTheme(themeMode: String?, systemInDarkTheme: Boolean): Boolean = when (themeMode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    else -> systemInDarkTheme
}
