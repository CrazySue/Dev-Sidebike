package dev.sidebike.app.ui

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sidebike.app.data.NotificationPermission
import dev.sidebike.app.ui.theme.SidebikeTheme
import dev.sidebike.app.ui.theme.resolveDarkTheme

/**
 * 应用最外层：主题解析（跟随系统/浅色/深色 + 动态取色）+ edge-to-edge 系统栏外观 +
 * VPN 授权 / 通知权限流程 + [SidebikeApp]。
 *
 * - VPN 授权：`VpnService.prepare()` + ActivityResult（失败 → `onStartAborted`）。
 * - 通知权限（Android 13+）：首次进入非阻塞请求一次；被拒绝时首页显示可重试的提示横幅，
 *   避免用户永远看不到「VPN 正在运行」前台通知与通知栏的「停止」操作。
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

        // Android 13+ 通知权限：MainActivity 已通过 NotificationPermission.ensureRequested() 发起首次请求；
        // 这里只负责「被拒绝后的重试」与状态展示（VPN 前台通知的「停止」动作依赖该权限）。
        var notificationsGranted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            notificationsGranted = granted
        }
        val requestNotificationPermission: () -> Unit = {
            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 从系统「安装证书」界面返回时自动重新检测 CA 安装状态；同时刷新通知权限状态。
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            viewModel.refreshCaInstalled()
            notificationsGranted = NotificationPermission.isGranted(context)
        }

        SidebikeApp(
            viewModel = viewModel,
            requestStart = requestStart,
            notificationsDenied = !notificationsGranted,
            onRequestNotificationPermission = requestNotificationPermission,
        )
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
