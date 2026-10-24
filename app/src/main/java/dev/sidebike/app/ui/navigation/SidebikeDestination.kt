package dev.sidebike.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import dev.sidebike.app.R

/**
 * 应用内的 5 个顶级目的地（MD3 NavigationBar 建议 3–5 个）。
 * 使用「selected = filled icon、unselected = outlined icon」的 MD3 图标规则。
 */
enum class SidebikeDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        route = "home",
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    RULES(
        route = "rules",
        labelRes = R.string.nav_rules,
        selectedIcon = Icons.Filled.Rule,
        unselectedIcon = Icons.Outlined.Rule,
    ),
    LOGS(
        route = "logs",
        labelRes = R.string.nav_logs,
        selectedIcon = Icons.Filled.Article,
        unselectedIcon = Icons.Outlined.Article,
    ),
    SETTINGS(
        route = "settings",
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
    CERT_GUIDE(
        route = "cert",
        labelRes = R.string.nav_cert,
        selectedIcon = Icons.Filled.VerifiedUser,
        unselectedIcon = Icons.Outlined.VerifiedUser,
    ),
    ;

    companion object {
        val bottomBarDestinations: List<SidebikeDestination> = entries
        fun fromRoute(route: String?): SidebikeDestination = entries.firstOrNull { it.route == route } ?: HOME
    }
}
