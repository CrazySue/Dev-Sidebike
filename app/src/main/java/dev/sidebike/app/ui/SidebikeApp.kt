@file:OptIn(ExperimentalMaterial3Api::class)

package dev.sidebike.app.ui

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sidebike.app.R
import dev.sidebike.app.state.SidebikeUiState
import dev.sidebike.app.ui.components.SectionHeader
import dev.sidebike.app.ui.export.ExportFileKind
import dev.sidebike.app.ui.export.ExportPayloads
import dev.sidebike.app.ui.export.exportFileName
import dev.sidebike.app.ui.motion.M3TopAppBarSnapSpec
import dev.sidebike.app.ui.motion.m3FadeThroughSequential
import dev.sidebike.app.ui.navigation.SidebikeDestination
import dev.sidebike.app.ui.screens.CertGuideScreen
import dev.sidebike.app.ui.screens.HomeScreen
import dev.sidebike.app.ui.screens.LogsScreen
import dev.sidebike.app.ui.screens.RulesScreen
import dev.sidebike.app.ui.screens.SettingsScreen
import dev.sidebike.core.api.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 根：收集 controller 的 state/logs StateFlow，构造 [SidebikeActions]（ViewModel 转发 +
 * Android 系统能力：VPN 授权、CA 安装、SAF 文件导出、剪贴板），再把无状态屏幕组合起来。
 *
 * 导航：5 个 MD3 NavigationBar destination；内容切换使用**顺序版** MD3 fade-through
 * （0.1.1-alpha item 8：旧页面先淡出，避免新旧页面重叠）。
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
    val snackbarHostState = remember { SnackbarHostState() }
    val actions = rememberSidebikeActions(
        viewModel = viewModel,
        requestStart = requestStart,
        snackbarHostState = snackbarHostState,
    )
    SidebikeAppContent(
        state = state,
        logs = logs,
        actions = actions,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        notificationsDenied = notificationsDenied,
        onRequestNotificationPermission = onRequestNotificationPermission,
    )
}

/**
 * 无 ViewModel 依赖的根内容（Robolectric 测试可直接注入 fake [SidebikeActions]）。
 */
@Composable
fun SidebikeAppContent(
    state: SidebikeUiState,
    logs: List<LogEntry>,
    actions: SidebikeActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    notificationsDenied: Boolean = false,
    onRequestNotificationPermission: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = snackbarHostState ?: remember { SnackbarHostState() }
    var currentRoute by rememberSaveable { mutableStateOf(SidebikeDestination.HOME.route) }
    val currentDestination = SidebikeDestination.fromRoute(currentRoute)
    var rulesTabRequest by remember { mutableStateOf<Int?>(null) }
    var showLogExportSheet by remember { mutableStateOf(false) }
    // Explicit accessibility label: the FAB's visible "导出" label was not exposed as text/content-desc
    // to TalkBack/uiautomator (QA-011-01), so provide a stable semantic label.
    val logsExportDescription = stringResource(R.string.logs_export_entry)

    val showMessage: (String) -> Unit = { message ->
        if (message.isNotBlank()) scope.launch { snackbarHost.showSnackbar(message) }
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
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.logs_export)) },
                    icon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                    onClick = { showLogExportSheet = true },
                    modifier = Modifier
                        .testTag("logs-export-fab")
                        .semantics { contentDescription = logsExportDescription },
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHost) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentDestination,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { m3FadeThroughSequential() },
            label = "destinationContent",
        ) { destination ->
            when (destination) {
                SidebikeDestination.HOME -> HomeScreen(
                    state = state,
                    recentLogs = state.recentLogs.ifEmpty { logs },
                    contentPadding = innerPadding,
                    onStart = actions.requestStart,
                    onStop = actions.stop,
                    onOpenRules = { currentRoute = SidebikeDestination.RULES.route },
                    onOpenLogs = { currentRoute = SidebikeDestination.LOGS.route },
                    onOpenCertGuide = { currentRoute = SidebikeDestination.CERT_GUIDE.route },
                    onToggleMitm = { enabled ->
                        scope.launch {
                            if (!enabled) {
                                actions.updateConfig(
                                    state.config.copy(
                                        proxy = state.config.proxy.copy(mitmEnabled = false),
                                        mitm = state.config.mitm.copy(enabled = false),
                                    ),
                                )
                                showMessage("MITM 已关闭")
                            } else {
                                val result = actions.setMitmEnabled(true)
                                if (result.effective) {
                                    actions.updateConfig(
                                        state.config.copy(
                                            proxy = state.config.proxy.copy(mitmEnabled = true),
                                            mitm = state.config.mitm.copy(enabled = true),
                                        ),
                                    )
                                    showMessage("MITM 已开启（仅对信任用户 CA 的应用生效）")
                                } else {
                                    showMessage(result.message ?: "需要先安装 CA 证书")
                                }
                            }
                        }
                    },
                    onRefreshCa = actions.refreshCaInstalled,
                    notificationsDenied = notificationsDenied,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                )

                SidebikeDestination.RULES -> RulesScreen(
                    state = state,
                    contentPadding = innerPadding,
                    actions = actions,
                    onShowMessage = showMessage,
                    initialTab = rulesTabRequest,
                    onTabConsumed = { rulesTabRequest = null },
                )

                SidebikeDestination.LOGS -> LogsScreen(
                    logs = logs,
                    contentPadding = innerPadding,
                    onClearLogs = {
                        actions.clearLogs()
                        showMessage("日志已清空")
                    },
                )

                SidebikeDestination.SETTINGS -> SettingsScreen(
                    config = state.config,
                    caInstalled = state.caInstalled,
                    contentPadding = innerPadding,
                    onConfigChange = actions.updateConfig,
                    onOpenCertGuide = { currentRoute = SidebikeDestination.CERT_GUIDE.route },
                    onRefreshCaInstalled = actions.refreshCaInstalled,
                    onExportCa = actions.exportCaToFile,
                    diagnosticsRunning = state.diagnosticsRunning,
                    onRunNetworkDiagnostics = actions.runNetworkDiagnostics,
                    onSaveTextFile = actions.saveTextFile,
                    onCopyText = actions.copyText,
                    onOpenRules = {
                        rulesTabRequest = 3
                        currentRoute = SidebikeDestination.RULES.route
                    },
                    onOpenLogs = { currentRoute = SidebikeDestination.LOGS.route },
                    onShowMessage = showMessage,
                    onSetMitmEnabled = actions.setMitmEnabled,
                )

                SidebikeDestination.CERT_GUIDE -> CertGuideScreen(
                    state = state,
                    contentPadding = innerPadding,
                    onPrepareInstall = actions.installCa,
                    onLoadCaInfo = {
                        actions.prepareCaInstall()?.let { request ->
                            CaInstallUiResult(
                                status = CaInstallLaunchStatus.LAUNCHED,
                                fingerprint = request.sha256Fingerprint,
                                subject = request.subject,
                            )
                        } ?: CaInstallUiResult(CaInstallLaunchStatus.NO_CA)
                    },
                    onExportCaToFile = actions.exportCaToFile,
                    onRefreshCaInstalled = actions.refreshCaInstalled,
                    onOpenCaSettings = actions.openCaSettings,
                    onOpenLockScreenSettings = actions.openLockScreenSettings,
                    onCopyFingerprint = actions.copyText,
                    onShowMessage = showMessage,
                )
            }
        }
    }

    if (showLogExportSheet) {
        LogExportSheet(
            onDismiss = { showLogExportSheet = false },
            onExportLog = {
                showLogExportSheet = false
                actions.exportLogFile()
            },
            onExportBundle = {
                showLogExportSheet = false
                actions.exportDebugBundle()
            },
            onCopyLogText = {
                showLogExportSheet = false
                actions.copyLogText()
            },
        )
    }
}

/** 日志导出底表（0.1.1-alpha item 1）：.log 文件 / .zip 诊断包 / 复制文本。 */
@Composable
private fun LogExportSheet(
    onDismiss: () -> Unit,
    onExportLog: () -> Unit,
    onExportBundle: () -> Unit,
    onCopyLogText: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.logs_export_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.logs_export_sheet_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.logs_export_log)) },
                supportingContent = { Text(stringResource(R.string.logs_export_log_desc)) },
                leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                colors = colors,
                modifier = Modifier
                    .clickable(onClick = onExportLog)
                    .testTag("export-log-file"),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.logs_export_bundle)) },
                supportingContent = { Text(stringResource(R.string.logs_export_bundle_desc)) },
                leadingContent = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                colors = colors,
                modifier = Modifier
                    .clickable(onClick = onExportBundle)
                    .testTag("export-debug-bundle"),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.logs_export_clipboard)) },
                supportingContent = { Text(stringResource(R.string.logs_export_clipboard_desc)) },
                leadingContent = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                colors = colors,
                modifier = Modifier
                    .clickable(onClick = onCopyLogText)
                    .testTag("export-copy-log"),
            )
        }
    }
}

/**
 * 构造 [SidebikeActions]：ViewModel 转发 + 系统 Intent / SAF / 剪贴板。
 * 所有文件导出都走 `ActivityResultContracts.CreateDocument`，由系统文件选择器决定保存位置。
 */
@Composable
private fun rememberSidebikeActions(
    viewModel: SidebikeViewModel,
    requestStart: () -> Unit,
    snackbarHostState: SnackbarHostState,
): SidebikeActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val show: (String) -> Unit = { message ->
        if (message.isNotBlank()) scope.launch { snackbarHostState.showSnackbar(message) }
    }

    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingPem by remember { mutableStateOf<String?>(null) }

    fun writeBytes(uri: Uri, bytes: ByteArray, successMessage: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }.isSuccess
            }
            if (ok) {
                show(successMessage)
            } else {
                show(context.getString(R.string.logs_export_failed, "无法写入文件"))
            }
        }
    }

    val logSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFileKind.LOG.mimeType),
    ) { uri ->
        val text = pendingText
        pendingText = null
        if (uri == null) {
            show(context.getString(R.string.logs_export_cancelled))
        } else if (text != null) {
            writeBytes(
                uri = uri,
                bytes = ExportPayloads.logText(text),
                successMessage = context.getString(R.string.logs_export_done, exportFileName(ExportFileKind.LOG)),
            )
        }
    }
    val zipSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFileKind.DEBUG_BUNDLE.mimeType),
    ) { uri ->
        val bytes = pendingBytes
        pendingBytes = null
        if (uri == null) {
            show(context.getString(R.string.logs_export_cancelled))
        } else if (bytes != null) {
            writeBytes(
                uri = uri,
                bytes = bytes,
                successMessage = context.getString(
                    R.string.logs_export_done,
                    exportFileName(ExportFileKind.DEBUG_BUNDLE),
                ),
            )
        }
    }
    val pemSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFileKind.CA_CRT.mimeType),
    ) { uri ->
        val pem = pendingPem
        pendingPem = null
        if (uri == null) {
            show(context.getString(R.string.cert_export_cancelled))
        } else if (pem != null) {
            writeBytes(
                uri = uri,
                bytes = ExportPayloads.pem(pem),
                successMessage = context.getString(R.string.cert_exported),
            )
        }
    }
    val textSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFileKind.TEXT.mimeType),
    ) { uri ->
        val text = pendingText
        pendingText = null
        if (uri == null) {
            show(context.getString(R.string.logs_export_cancelled))
        } else if (text != null) {
            writeBytes(
                uri = uri,
                bytes = ExportPayloads.text(text),
                successMessage = context.getString(R.string.logs_export_done, exportFileName(ExportFileKind.TEXT)),
            )
        }
    }

    return SidebikeActions(
        requestStart = requestStart,
        stop = viewModel::stop,
        updateConfig = { config -> scope.launch { viewModel.updateConfig(config) } },
        reloadConfig = { scope.launch { viewModel.reloadConfig() } },
        clearLogs = viewModel::clearLogs,
        refreshCaInstalled = viewModel::refreshCaInstalled,
        setMitmEnabled = viewModel::setMitmEnabled,
        installCa = {
            val request = runCatching { viewModel.prepareCaInstall() }.getOrNull()
            if (request == null) {
                CaInstallUiResult(
                    status = CaInstallLaunchStatus.NO_CA,
                    message = context.getString(R.string.cert_install_no_ca),
                )
            } else {
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                if (keyguard != null && !keyguard.isDeviceSecure) {
                    // 系统证书安装器要求设备已设置锁屏；先引导用户设置，避免出现无法操作的黑色弹窗
                    CaInstallUiResult(
                        status = CaInstallLaunchStatus.LOCK_SCREEN_REQUIRED,
                        fingerprint = request.sha256Fingerprint,
                        subject = request.subject,
                        message = context.getString(R.string.cert_lock_screen_required_hint),
                    )
                } else {
                    val launched = runCatching { context.startActivity(request.intent) }.isSuccess
                    if (launched) {
                        CaInstallUiResult(
                            status = CaInstallLaunchStatus.LAUNCHED,
                            fingerprint = request.sha256Fingerprint,
                            subject = request.subject,
                            message = context.getString(R.string.cert_install_launched),
                        )
                    } else {
                        val fallback = runCatching { context.startActivity(request.settingsFallbackIntent) }.isSuccess
                        CaInstallUiResult(
                            status = if (fallback) {
                                CaInstallLaunchStatus.FALLBACK_LAUNCHED
                            } else {
                                CaInstallLaunchStatus.FAILED
                            },
                            fingerprint = request.sha256Fingerprint,
                            subject = request.subject,
                            message = context.getString(
                                if (fallback) R.string.cert_install_fallback else R.string.cert_install_failed_detail,
                            ),
                        )
                    }
                }
            }
        },
        exportCaToFile = {
            scope.launch {
                val request = runCatching { viewModel.prepareCaInstall() }.getOrNull()
                val pem = request?.pem
                if (pem == null) {
                    show(context.getString(R.string.cert_export_failed))
                } else {
                    pendingPem = pem
                    pemSaver.launch(exportFileName(ExportFileKind.CA_CRT))
                }
            }
        },
        openCaSettings = {
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            runCatching { context.startActivity(intent) }.onFailure {
                show(context.getString(R.string.cert_install_failed_detail))
            }
        },
        openLockScreenSettings = {
            // 系统没有单独的"设置锁屏"公开 Intent（不同 OEM 不同），安全设置页是通用兜底
            val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            runCatching { context.startActivity(intent) }.onFailure {
                show(context.getString(R.string.cert_install_failed_detail))
            }
        },
        exportLogFile = {
            scope.launch {
                val text = runCatching { viewModel.buildLogText() }.getOrNull()
                if (text == null) {
                    show(context.getString(R.string.logs_export_failed, "生成日志失败"))
                } else {
                    pendingText = text
                    logSaver.launch(exportFileName(ExportFileKind.LOG))
                }
            }
        },
        exportDebugBundle = {
            scope.launch {
                val bytes = runCatching { viewModel.buildDebugBundle() }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    show(context.getString(R.string.logs_export_failed, "生成诊断包失败"))
                } else {
                    pendingBytes = bytes
                    zipSaver.launch(exportFileName(ExportFileKind.DEBUG_BUNDLE))
                }
            }
        },
        copyLogText = {
            scope.launch {
                val text = runCatching { viewModel.buildLogText() }.getOrNull()
                if (text == null) {
                    show(context.getString(R.string.logs_export_copy_failed, "生成日志失败"))
                } else {
                    clipboard.setText(AnnotatedString(text))
                    show(context.getString(R.string.logs_export_copy_done))
                }
            }
        },
        copyText = { text ->
            clipboard.setText(AnnotatedString(text))
            show(context.getString(R.string.logs_export_copy_done))
        },
        saveTextFile = { suggestedName, content ->
            pendingText = content
            textSaver.launch(suggestedName.ifBlank { exportFileName(ExportFileKind.TEXT) })
        },
        runNetworkDiagnostics = viewModel::runNetworkDiagnostics,
        saveRule = viewModel::saveRule,
        deleteRule = viewModel::deleteRule,
        setDirectPassDomains = viewModel::setDirectPassDomains,
        setDomesticAllowList = viewModel::setDomesticAllowList,
        importRulesFromText = viewModel::importRulesFromText,
        refreshRuleSources = viewModel::refreshRuleSources,
        launchIntent = { intent -> runCatching { context.startActivity(intent) }.isSuccess },
        prepareCaInstall = viewModel::prepareCaInstall,
    )
}

@Composable
private fun SidebikeTopBar(
    destination: SidebikeDestination,
    scrollBehavior: androidx.compose.material3.TopAppBarScrollBehavior,
    onOpenLogs: () -> Unit,
    onOpenCertGuide: () -> Unit,
) {
    if (destination == SidebikeDestination.HOME) {
        LargeTopAppBar(
            title = { Text(text = stringResource(R.string.app_name)) },
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

/** 供 Preview/测试使用的空动作集。 */
val EmptySidebikeActions: SidebikeActions = SidebikeActions()

/** 未使用但保留：SectionHeader 让底表标题与其它页面一致（避免重复实现）。 */
@Composable
private fun SheetHeader(title: String) {
    SectionHeader(title = title)
}
