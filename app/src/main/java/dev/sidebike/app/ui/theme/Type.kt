package dev.sidebike.app.ui.theme

import androidx.compose.material3.Typography

/**
 * Material Design 3 type scale (display / headline / title / body / label, each large/medium/small).
 *
 * Strict-MD3 rule: no custom font files and no custom font families. [Typography] with its default
 * arguments is exactly the MD3 type scale rendered with the platform Roboto/system font, so it is
 * kept explicit here (rather than relying on an implicit default) to make the token source clear.
 */
val SidebikeTypography = Typography()
