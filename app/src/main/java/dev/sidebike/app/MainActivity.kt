package dev.sidebike.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sidebike.app.ui.SidebikeRoot
import dev.sidebike.app.ui.SidebikeViewModel

/**
 * 单 Activity + Compose 入口。edge-to-edge 由 [enableEdgeToEdge] + Scaffold insets 处理，
 * 系统栏图标明暗由 [dev.sidebike.app.ui.SidebikeRoot] 根据主题设置动态调整。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: SidebikeViewModel = viewModel()
            SidebikeRoot(viewModel = viewModel)
        }
    }
}
