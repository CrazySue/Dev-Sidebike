package dev.sidebike.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 shape scale. Values match the official MD3 shape tokens:
 * extra-small 4dp, small 8dp, medium 12dp, large 16dp, extra-large 28dp.
 *
 * Components must consume `MaterialTheme.shapes.*`; arbitrary per-widget corner radii are not
 * allowed by the strict-MD3 rule of this project.
 */
val SidebikeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
