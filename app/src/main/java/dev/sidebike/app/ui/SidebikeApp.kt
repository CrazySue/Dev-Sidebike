@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sidebike.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sidebike.app.R
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.cert.CaCertificateActions
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.components.StatusVisuals
import dev.sidebike.app.ui.motion.M3Motion
import dev.sidebike.app.ui.motion.M3TopAppBarSnapSpec
import dev.sidebike.app.ui.motion.m3FadeThrough
import dev.sidebike.app.ui.motion.m3FadeThroughIn
import dev.sidebike.app.ui.motion.m3FadeThroughOut
import dev.sidebike.app.ui.motion.m3FadeThroughResize
import dev.sidebike.app.ui.navigation.SidebikeDestination
import dev.sidebike.app.ui.screens.CertGuideScreen
import dev.sidebike.app.ui.screens.HomeScreen
import dev.sidebike.app.ui.screens.LogsScreen
import dev.sidebike.app.ui.screens.RulesScreen
import dev.sidebike.app.ui.screens.SettingsScreen
import dev.sidebike.core.api.LogEntry
import dev.sidebike.core.api.SidebikeConfig
import kotlinx.coroutines.launch

/**
 * App 根：收集 controller 的 state/logs StateFlow，把无状态的 Screen 组合起来。
 *
 * 导航：5 个 MD3 NavigationBar destination；内容切换使用 MD3 **fade-through**
 * （AnimatedContent，EmphasizedDecelerate/Accelerate）。
 */
@Composable
fun SidebikeApp(
    viewModel: SidebikeViewModel,
    requestStart: () -> Unit = viewModel::start,
    modifier: Modifier = Modifier,
    notificationsDenied: Boolean = false,
    onRequestNotificationPermission: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    SidebikeAppContent(
        state = state,
        logs = logs,
        requestStart = requestStart,
        onStop = viewModel::stop,
        onConfigChange = { config -> viewModel.updateConfig(config) },
        onReloadConfig = { viewModel.reloadConfig() },
        onClearLogs = viewModel::clearLogs,
        onRefreshCaInstalled = viewModel::refreshCaInstalled,
        onExportDiagnostics = { viewModel.exportDiagnostics() },
        onLoadCaPem = { viewModel.caCertificatePem() },
        modifier = modifier,
        notificationsDenied = notificationsDenied,
        onRequestNotificationPermission = onRequestNotificationPermission,
    )
}

/**
 * 无 ViewModel 依赖的根内容（便于 Robolectric 直接测试五屏与导航）。
 */
@Composable
fun SidebikeAppContent(
    state: SidebikeUiState,
    logs: List<LogEntry>,
    requestStart: () -> Unit,
    onStop: () -> Unit,
    onConfigChange: suspend (SidebikeConfig) -> Unit,
    onClearLogs: () -> Unit,
    onRefreshCaInstalled: () -> Unit,
    onExportDiagnostics: suspend () -> String,
    onLoadCaPem: suspend () -> String?,
    modifier: Modifier = Modifier,
    onReloadConfig: suspend () -> Unit = {},
    notificationsDenied: Boolean = false,
    onRequestNotificationPermission: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var currentRoute by rememberSaveable { mutableStateOf(SidebikeDestination.HOME.route) }
    val currentDestination = SidebikeDestination.fromRoute(currentRoute)
    var exportText by remember { mutableStateOf<String?>(null) }

    val showSnackbar: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
    val onConfigChangeUi: (SidebikeConfig) -> Unit = { config ->
        scope.launch { onConfigChange(config) }
    }
    val onReloadConfigUi: () -> Unit = {
        scope.launch { onReloadConfig() }
    }
    val onExportDiagnosticsUi: () -> Unit = {
        scope.launch { exportText = onExportDiagnostics() }
    }
    val onInstallCa: () -> Unit = {
        scope.launch {
            val pem = onLoadCaPem()
            val intent = pem?.let { CaCertificateActions.buildInstallIntent(it) }
            if (intent == null) {
                showSnackbar(context.getString(R.string.cert_install_failed))
            } else {
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    showSnackbar(context.getString(R.string.cert_install_failed))
                }
            }
        }
    }
    val onExportCa: () -> Unit = {
        scope.launch {
            val pem = onLoadCaPem()
            if (pem == null) {
                showSnackbar(context.getString(R.string.cert_export_failed))
            } else {
                val chooser = android.content.Intent.createChooser(
                    CaCertificateActions.buildExportIntent(
                        pem = pem,
                        subject = context.getString(R.string.cert_share_subject),
                    ),
                    context.getString(R.string.cert_export_chooser),
                )
                try {
                    context.startActivity(chooser)
                } catch (_: ActivityNotFoundException) {
                    showSnackbar(context.getString(R.string.cert_export_failed))
                }
            }
        }
    }

    BackHandler(enabled = currentDestination != SidebikeDestination.HOME) {
        currentRoute = SidebikeDestination.HOME.route
    }

    val topBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        state = rememberTopAppBarState(),
        snapAnimationSpec = M3TopAppBarSnapSpec,
    )

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        topBar = {
            SidebikeTopBar(
                destination = currentDestination,
                scrollBehavior = topBarScrollBehavior,
                onOpenLogs = { currentRoute = SidebikeDestination.LOGS.route },
                onOpenCertGuide = { currentRoute = SidebikeDestination.CERT_GUIDE.route },
            )
        },
        bottomBar = {
            SidebikeNavigationBar(
                current = currentDestination,
                onSelect = { destination -> currentRoute = destination.route },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = currentDestination == SidebikeDestination.LOGS,
                enter = m3FadeThroughIn(),
                exit = m3FadeThroughOut(),
            ) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.logs_export)) },
                    icon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                    onClick = onExportDiagnosticsUi,
                    modifier = Modifier.testTag("logs-export-fab"),
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentDestination,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { m3FadeThrough() },
            label = "destinationContent",
        ) { destination ->
            when (destination) {
                SidebikeDestination.HOME -> HomeScreen(
                    state = state,
                    recentLogs = state.recentLogs.ifEmpty { logs },
                    contentPadding = innerPadding,
                    onStart = requestStart,
                    onStop = onStop,
                    onOpenRules = { currentRoute = SidebikeDestination.RULES.route },
                    onOpenLogs = { currentRoute = SidebikeDestination.LOGS.route },
                    onOpenCertGuide = { currentRoute = SidebikeDestination.CERT_GUIDE.route },
                    onToggleMitm = { enabled -> onConfigChangeUi(state.config.withMitmEnabled(enabled)) },
                    onRefreshCa = onRefreshCaInstalled,
                    notificationsDenied = notificationsDenied,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                )

                SidebikeDestination.RULES -> RulesScreen(
                    config = state.config,
                    contentPadding = innerPadding,
                    onConfigChange = onConfigChangeUi,
                    onReloadConfig = onReloadConfigUi,
                )

                SidebikeDestination.LOGS -> LogsScreen(
                    logs = logs,
                    contentPadding = innerPadding,
                    onClearLogs = {
                        onClearLogs()
                        showSnackbar(context.getString(R.string.logs_cleared))
                    },
                )

                SidebikeDestination.SETTINGS -> SettingsScreen(
                    config = state.config,
                    caInstalled = state.caInstalled,
                    contentPadding = innerPadding,
                    onConfigChange = onConfigChangeUi,
                    onOpenCertGuide = { currentRoute = SidebikeDestination.CERT_GUIDE.route },
                    onRefreshCaInstalled = onRefreshCaInstalled,
                    onExportCa = onExportCa,
                )

                SidebikeDestination.CERT_GUIDE -> CertGuideScreen(
                    state = state,
                    contentPadding = innerPadding,
                    onInstallCa = onInstallCa,
                    onExportCa = onExportCa,
                    onRefreshCaInstalled = onRefreshCaInstalled,
                    onOpenSettings = { currentRoute = SidebikeDestination.SETTINGS.route },
                )
            }
        }
    }

    val sheetText = exportText
    if (sheetText != null) {
        ExportDiagnosticsSheet(
            text = sheetText,
            onCopy = {
                clipboard.setText(AnnotatedString(sheetText))
                exportText = null
                showSnackbar(context.getString(R.string.logs_copied))
            },
            onDismiss = { exportText = null },
        )
    }
}

/** MITM 开关同时写入两个契约字段：`mitm.enabled`（引擎）与 `proxy.mitmEnabled`（代理门控）。 */
private fun SidebikeConfig.withMitmEnabled(enabled: Boolean): SidebikeConfig = copy(
    proxy = proxy.copy(mitmEnabled = enabled),
    mitm = mitm.copy(enabled = enabled),
)

@Composable
private fun SidebikeTopBar(
    destination: SidebikeDestination,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onOpenLogs: () -> Unit,
    onOpenCertGuide: () -> Unit,
) {
    if (destination == SidebikeDestination.HOME) {
        LargeTopAppBar(
            title = {
                Text(text = stringResource(R.string.app_name))
            },
            actions = {
                IconButton(onClick = onOpenLogs) {
                    Icon(
                        imageVector = Icons.Outlined.Article,
                        contentDescription = stringResource(R.string.nav_logs),
                    )
                }
                IconButton(onClick = onOpenCertGuide) {
                    Icon(
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = stringResource(R.string.nav_cert),
                    )
                }
            },
            scrollBehavior = scrollBehavior,
        )
    } else {
        TopAppBar(
            title = { Text(text = stringResource(destination.labelRes)) },
            actions = {
                if (destination != SidebikeDestination.LOGS) {
                    IconButton(onClick = onOpenLogs) {
                        Icon(
                            imageVector = Icons.Outlined.Article,
                            contentDescription = stringResource(R.string.nav_logs),
                        )
                    }
                }
                if (destination != SidebikeDestination.CERT_GUIDE) {
                    IconButton(onClick = onOpenCertGuide) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            contentDescription = stringResource(R.string.nav_cert),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SidebikeNavigationBar(
    current: SidebikeDestination,
    onSelect: (SidebikeDestination) -> Unit,
) {
    NavigationBar {
        SidebikeDestination.bottomBarDestinations.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                modifier = Modifier.testTag("nav:${destination.route}"),
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}

@Composable
private fun ExportDiagnosticsSheet(
    text: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(title = stringResource(R.string.logs_export_sheet_title))
            Text(
                text = stringResource(R.string.logs_export_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
                Button(onClick = onCopy) {
                    Text(stringResource(R.string.logs_copy))
                }
            }
        }
    }
}
