package dev.sidebike.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sidebike.app.data.NotificationPermission
import dev.sidebike.app.ui.SidebikeRoot
import dev.sidebike.app.ui.SidebikeViewModel

/**
 * 单 Activity + Compose 入口。edge-to-edge 由 [enableEdgeToEdge] + Scaffold insets 处理，
 * 系统栏图标明暗由 [dev.sidebike.app.ui.SidebikeRoot] 根据主题设置动态调整。
 *
 * Android 13+ 的 POST_NOTIFICATIONS 通过 [NotificationPermission.ensureRequested] 请求：
 * VPN 前台通知（含通知栏「停止」动作）依赖该权限，否则用户无法在通知栏停止 VPN（QA-002）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NotificationPermission.ensureRequested(this)
        setContent {
            val viewModel: SidebikeViewModel = viewModel()
            SidebikeRoot(viewModel = viewModel)
        }
    }
}
