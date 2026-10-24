package dev.sidebike.app.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sidebike.app.ui.theme.SidebikeTheme
import dev.sidebike.app.ui.theme.resolveDarkTheme

/**
 * 应用最外层：主题解析（跟随系统/浅色/深色 + 动态取色）+ edge-to-edge 系统栏外观 +
 * VpnService 授权流程 + [SidebikeApp]。
 *
 * 主题设置来自冻结契约 `SidebikeConfig.app.themeMode/dynamicColor`，用户改动后立即热切换；
 * 主题切换本身由 `MaterialTheme` 重组 + 各组件 MD3 内建颜色动画完成。
 */
@Composable
fun SidebikeRoot(viewModel: SidebikeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val darkTheme = resolveDarkTheme(state.config.app.themeMode, isSystemInDarkTheme())
    SidebikeTheme(darkTheme = darkTheme, dynamicColor = state.config.app.dynamicColor) {
        SystemBarsAppearance(darkTheme = darkTheme)

        val context = LocalContext.current
        val vpnPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.start()
            } else {
                viewModel.onStartAborted("用户拒绝了 VPN 授权")
            }
        }
        val requestStart: () -> Unit = {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent == null) {
                viewModel.start()
            } else {
                vpnPermissionLauncher.launch(prepareIntent)
            }
        }

        // 从系统「安装证书」界面返回时自动重新检测 CA 安装状态。
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.refreshCaInstalled()
        }

        SidebikeApp(viewModel = viewModel, requestStart = requestStart)
    }
}

@Composable
private fun SystemBarsAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val activity = view.context as? Activity ?: return
    val window = activity.window
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
