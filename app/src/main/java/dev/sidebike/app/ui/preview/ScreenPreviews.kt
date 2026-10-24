package dev.sidebike.app.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sidebike.app.state.RunStatus
import dev.sidebike.app.ui.SidebikeAppContent
import dev.sidebike.app.ui.screens.CertGuideScreen
import dev.sidebike.app.ui.screens.HomeScreen
import dev.sidebike.app.ui.screens.LogsScreen
import dev.sidebike.app.ui.screens.RulesScreen
import dev.sidebike.app.ui.screens.SettingsScreen
import dev.sidebike.app.ui.theme.SidebikeTheme

/**
 * 所有屏幕的 MD3 @Preview：每个屏幕都提供 浅色/深色 两套（uiMode = NIGHT_YES）。
 * Preview 数据来自 [PreviewData]，callbacks 为空操作，不访问网络、不依赖真实 controller。
 */

private val NoPadding = PaddingValues(0.dp)

@Composable
private fun PreviewCanvas(content: @Composable () -> Unit) {
    SidebikeTheme(dynamicColor = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------------------------

@Preview(name = "首页 · 运行中 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "首页 · 运行中 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenPreview() {
    PreviewCanvas {
        HomeScreen(
            state = PreviewData.state(),
            recentLogs = PreviewData.logs.takeLast(3),
            contentPadding = NoPadding,
            onStart = {},
            onStop = {},
            onOpenRules = {},
            onOpenLogs = {},
            onOpenCertGuide = {},
            onToggleMitm = {},
            onRefreshCa = {},
        )
    }
}

@Preview(name = "首页 · 空闲 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "首页 · 空闲 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenIdlePreview() {
    PreviewCanvas {
        HomeScreen(
            state = PreviewData.emptyState,
            recentLogs = emptyList(),
            contentPadding = NoPadding,
            onStart = {},
            onStop = {},
            onOpenRules = {},
            onOpenLogs = {},
            onOpenCertGuide = {},
            onToggleMitm = {},
            onRefreshCa = {},
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Rules
// ---------------------------------------------------------------------------------------------

@Preview(name = "规则 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "规则 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun RulesScreenPreview() {
    PreviewCanvas {
        RulesScreen(
            config = PreviewData.config,
            contentPadding = NoPadding,
            onConfigChange = {},
        )
    }
}

@Preview(name = "规则 · 空 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "规则 · 空 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun RulesScreenEmptyPreview() {
    PreviewCanvas {
        RulesScreen(
            config = PreviewData.emptyConfig,
            contentPadding = NoPadding,
            onConfigChange = {},
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Logs
// ---------------------------------------------------------------------------------------------

@Preview(name = "日志 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "日志 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LogsScreenPreview() {
    PreviewCanvas {
        LogsScreen(
            logs = PreviewData.logs,
            contentPadding = NoPadding,
            onClearLogs = {},
        )
    }
}

@Preview(name = "日志 · 空 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "日志 · 空 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LogsScreenEmptyPreview() {
    PreviewCanvas {
        LogsScreen(
            logs = emptyList(),
            contentPadding = NoPadding,
            onClearLogs = {},
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Preview(name = "设置 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "设置 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SettingsScreenPreview() {
    PreviewCanvas {
        SettingsScreen(
            config = PreviewData.config,
            caInstalled = true,
            contentPadding = NoPadding,
            onConfigChange = {},
            onOpenCertGuide = {},
            onRefreshCaInstalled = {},
            onExportCa = {},
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Certificate guide
// ---------------------------------------------------------------------------------------------

@Preview(name = "证书 · 未安装 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "证书 · 未安装 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CertGuideScreenPreview() {
    PreviewCanvas {
        CertGuideScreen(
            state = PreviewData.state(RunStatus.RUNNING, caInstalled = false),
            contentPadding = NoPadding,
            onInstallCa = {},
            onExportCa = {},
            onRefreshCaInstalled = {},
            onOpenSettings = {},
        )
    }
}

@Preview(name = "证书 · 未生成 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "证书 · 未生成 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CertGuideScreenNotGeneratedPreview() {
    PreviewCanvas {
        CertGuideScreen(
            state = PreviewData.emptyState,
            contentPadding = NoPadding,
            onInstallCa = {},
            onExportCa = {},
            onRefreshCaInstalled = {},
            onOpenSettings = {},
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Full app shell (top bar + bottom navigation + snackbar host + FAB)
// ---------------------------------------------------------------------------------------------

@Preview(name = "应用 · 浅色", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(
    name = "应用 · 深色",
    showBackground = true,
    widthDp = 412,
    heightDp = 915,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SidebikeAppPreview() {
    PreviewCanvas {
        SidebikeAppContent(
            state = PreviewData.state(),
            logs = PreviewData.logs,
            requestStart = {},
            onStop = {},
            onConfigChange = {},
            onClearLogs = {},
            onRefreshCaInstalled = {},
            onExportDiagnostics = { "" },
            onLoadCaPem = { null },
        )
    }
}
