# Dev-Sidebike UI 设计规范（严格 Material Design 3）

> 适用范围：`app/src/main/java/dev/sidebike/app/ui/**` + `MainActivity.kt`。
> 唯一允许的非 MD3 东西：没有。所有视觉、排版、形状、颜色、动效均来自 MD3 token。
> 对应任务：task-4（T4: Strict MD3 Compose UI with non-linear motion）。

## 1. 设计原则

1. **MD3 only**：只使用 `androidx.compose.material3` 组件与 token；禁止 Material 2（`androidx.compose.material.*`，`material-icons*` 除外）。
2. **Token 驱动**：颜色只用 `MaterialTheme.colorScheme.*`；字号只用 `MaterialTheme.typography.*`；圆角只用 `MaterialTheme.shapes.*`；间距只用 4 / 8 / 12 / 16 / 24 dp 刻度。
3. **状态可读**：`RunStatus` 的每个状态都有专属中文文案、图标、MD3 容器色（见 §4）。
4. **诚实的能力边界**：DNS 级防污染对全部应用生效；HTTP 代理只对遵循系统代理的应用生效；MITM 需要用户安装 CA 且仅部分应用信任用户 CA。UI 中不做夸大宣传（Home 能力说明 + CertGuide「能力边界」卡片）。
5. **一点不空**：每个列表都有空状态；占位/无数据时 UI 不崩溃、不显示假数据。
6. **动效有据**：所有状态变化都用 MD3 motion token 做非线性动画，见 [03-motion-spec.md](03-motion-spec.md)。

## 2. 信息架构（5 个顶级目的地）

MD3 NavigationBar 推荐 3–5 个 destination，本应用正好 5 个（全部使用 selected=filled / unselected=outlined 图标规则）：

| # | 目的地 | 路由 | 图标（selected / unselected） | 内容 |
| - | --- | --- | --- | --- |
| 1 | 首页 | `home` | Home / Home | 状态 Hero、启动/停止、实时统计、环境提示、快捷操作、最近日志 |
| 2 | 规则 | `rules` | Rule / Rule | 搜索、功能开关、域名规则列表（直连放行 Switch + 展开动作详情） |
| 3 | 日志 | `logs` | Article / Article | 实时日志、级别筛选 chips、自动滚动、清空、导出（FAB） |
| 4 | 设置 | `settings` | Settings / Settings | 代理端口、DNS 模式/Provider、MITM+CA、通用、外观、关于/许可 |
| 5 | 证书 | `cert` | VerifiedUser / VerifiedUser | CA 状态 Hero、安装/导出/重新检测、四步指引、能力边界 |

- 顶层切换使用 MD3 **fade-through**（`AnimatedContent`，见 motion spec）。
- 非首页目的地按返回键回到首页（`BackHandler` + `rememberSaveable` 保存路由）。
- 首页使用 `LargeTopAppBar` + `exitUntilCollapsedScrollBehavior`（顶栏折叠）；其余目的地使用 `TopAppBar`。
- `Scaffold` 统一管理 topBar / NavigationBar / SnackbarHost / FAB / insets，内容通过 `innerPadding` 让列表内容滚动到栏下方（edge-to-edge）。

## 3. 设计 token

### 3.1 颜色（`ui/theme/Color.kt`）

- 静态兜底：MD3 **baseline** color scheme（`lightColorScheme()` / `darkColorScheme()`），每个值都是 MD3 color role，保证对比度。
- Android 12+ 且用户开启「动态取色」：`dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)`（Material You）。
- 语义映射（全部取自 color Scheme，禁止 hex）：

| 语义 | Light/Dark 通用 role |
| --- | --- |
| 页面背景 | `background` / `surface` |
| Card/磁贴 | `surfaceContainerLow`、`surfaceContainerHigh`、`surfaceContainerHighest` |
| 主操作按钮 | `primary` / `onPrimary` |
| 次级操作（停止、MITM、警示） | `secondaryContainer` / `onSecondaryContainer`、`tertiaryContainer` / `onTertiaryContainer` |
| 错误 | `error`、`errorContainer` / `onErrorContainer` |
| 分割线 | `outlineVariant` |
| 次要文字 | `onSurfaceVariant` |

### 3.2 字体（`ui/theme/Type.kt`）

- `SidebikeTypography = Typography()`：即 MD3 官方 type scale（display/headline/title/body/label × large/medium/small）。
- **禁止自定义字体文件/FontFamily**；系统 Roboto 由 MD3 default 提供。
- 数字/标题层级：Hero 标题 `headlineSmall`，统计数字 `headlineSmall`，分区标题 `titleMedium`，说明 `bodyMedium/bodySmall`，标签 `labelMedium/labelSmall`，按钮 `labelLarge`。

### 3.3 形状（`ui/theme/Shape.kt`）

`Shapes(extraSmall=4, small=8, medium=12, large=16, extraLarge=28)`（dp），全部来自 MD3 shape scale。
- 大容器（Hero/Card）：`extraLarge`（28dp）或 `large`（16dp）；Hero 状态变化时在 16↔28 之间做 shape morph。
- 小容器（徽标、chip、搜索框）：`small` / `medium` / `extraLarge`（搜索框用 full-width 大圆角）。

### 3.4 间距

4dp 基准：4 / 8 / 12 / 16 / 24。页面水平 padding 16；列表项间距 8–12；分区间距 16–24；卡片内 16；徽标 6–12。

## 4. RunStatus 视觉语言（`ui/components/StatusVisuals.kt`）

| RunStatus | 中文文案 | 图标 | Hero 容器色 | 内容色 | 强调色 |
| --- | --- | --- | --- | --- | --- |
| IDLE | 未运行 | PowerSettingsNew | `surfaceContainerHigh` | `onSurface` | `onSurfaceVariant` |
| PREPARING | 正在准备 VPN | HourglassTop | `secondaryContainer` | `onSecondaryContainer` | `secondary` |
| STARTING | 正在启动 | Sync | `secondaryContainer` | `onSecondaryContainer` | `secondary` |
| RUNNING | 运行中 | CheckCircle | `primaryContainer` | `onPrimaryContainer` | `primary` |
| STOPPING | 正在停止 | StopCircle | `secondaryContainer` | `onSecondaryContainer` | `secondary` |
| ERROR | 启动失败 | ErrorOutline | `errorContainer` | `onErrorContainer` | `error` |

- 启动/停止控件相位：`IDLE/ERROR → START`、`PREPARING/STARTING → STARTING`、`RUNNING → STOP`、`STOPPING → STOPPING`。
- 环境提示（Home）：Private DNS（tertiaryContainer）、Lockdown（errorContainer）、系统代理未生效（tertiaryContainer）、通知权限被拒绝（tertiaryContainer + 允许通知重试）、端口回退（tertiaryContainer）。

### 4.1 Home Hero 信息层级（避免状态文案重复）

| 位置 | 内容 | 说明 |
| --- | --- | --- |
| Status chip | 唯一状态标签（未运行/正在准备 VPN/…/启动失败） | MD3 tonal pill + 运行中呼吸点 |
| Headline | `尚未启动` / `正在启动代理引擎` / `已连接 · 01:02:04` / `启动失败` | 信息密度更高，不与 chip 重复 |
| Subtitle（RUNNING） | 能力行：`DNS 防污染已生效 · 系统代理已注册 · MITM 已关闭` | 由 `vpnActive / systemProxyRegistered / mitmEnabled` 实时组合 |
| Subtitle（其他） | `statusMessage` 或状态说明 | 错误时展示 lastError 横幅 |
| Chips | `VPN 已连接`、`代理 :端口`、`MITM 已开启/关闭` | 端口在回退时显示 `effectiveProxyPort` |
| CA/MITM 文案 | 三态：`MITM 未启用（可选增强）` / `需要安装 CA 证书` / `CA 证书已就绪` | 由 `mitmCaStatusOf()` 决定，运行中绝不提示「请先启动服务」 |

- 字节统计：`UiFormat.bytesOrDash()` —— 有连接但字节为 0 时显示 `—`（未知），避免误报 `0 B`。
- 统计数字统一用 `AnimatedCounterText`（Long 滚动动画）。
- 通知权限（Android 13+）：`SidebikeRoot` 首次进入非阻塞请求；被拒绝时 Home 显示「通知权限未授予」横幅 + 「允许通知」重试按钮（VPN 前台通知/停止操作依赖该权限）。


## 5. 关键实现约定

### 5.1 状态与数据（不变量）

- UI 通过 `SidebikeViewModel`（AndroidViewModel）拿 `SidebikeController.state/logs`（StateFlow），Compose 用 `collectAsStateWithLifecycle`。
- UI 只调用 controller 接口：`start/stop/onStartAborted/updateConfig/reloadConfig/clearLogs/caCertificatePem/refreshCaInstalled/exportDiagnostics`。
- **不修改** controller/vpn/data/boot 与 core 代码；不直接触碰 VpnService/Engine。
- VPN 授权：`MainActivity`/`SidebikeRoot` 用 `VpnService.prepare()` + `rememberLauncherForActivityResult`；授权失败调用 `controller.onStartAborted(reason)`。
- 测试/Preview：`SidebikeControllerHolder.install { fake }` 注入 fake controller（见 `app/src/test/java/dev/sidebike/app/ui/TestFixtures.kt`）。

### 5.2 规则页「直连放行」的语义（重要设计决策）

冻结契约 `SidebikeConfig.intercepts` 是只读的 `Map<domain, Map<path, InterceptAction>>`，没有 enabled 字段。
因此**逐域名启停**持久化为 `whiteList[domain] = true`（上游语义：白名单域名 → 直连、不代理、不 MITM）：

- UI 文案诚实标注为「**直连放行（不代理）**」，而不是「启用规则」；
- Switch ON = 直连放行（写 `whiteList[domain]=true`），OFF = 恢复拦截（从 whiteList 移除）；
- `intercepts` 规则本身**只读展示**（路径数量、动作详情），不会因 UI 操作丢失；
- 列表摘要同时显示：域名数 / 路径规则数 / 直连放行数。

### 5.3 MITM 两个契约字段

契约里 `proxy.mitmEnabled`（代理门控）与 `mitm.enabled`（引擎）语义重叠。UI 统一规则：

- 读取时 `effective = proxy.mitmEnabled || mitm.enabled`；
- 写入时**两个字段同时写**，避免二者长期不一致；
- 文档化单源语义：`mitm.enabled` 供引擎启动 MITM，`proxy.mitmEnabled` 供代理层判断是否终结 TLS；任一为 true 视为用户意图开启。

### 5.4 CA 安装 / 导出（UI-local）

- `SidebikeController.caCertificatePem()` 返回 PEM（未启用 MITM/未生成时 null）。
- `ui/cert/CaCertificateActions.kt`（UI 层，自包含）：PEM → DER → `KeyChain.createInstallIntent()` + `EXTRA_CERTIFICATE`；导出走 `ACTION_SEND`（PEM 文本）。
- 失败路径：PEM 为 null → Snackbar「CA 证书尚未生成/无法安装」；`ActivityNotFoundException` → Snackbar 提示改为导出后手动安装。
- 从系统证书安装器返回时 `LifecycleEventSource ON_RESUME → controller.refreshCaInstalled()`。
- 说明：android-service workstream 另有 `data/CaInstaller`；UI 不复用其实现以避免跨 workstream 编译耦合（契约只保证 controller 方法）。

### 5.5 日志导出

- Logs 页 ExtendedFAB「导出」→ `controller.exportDiagnostics()`（挂起）→ 结果用 MD3 `ModalBottomSheet` 展示，可复制（`LocalClipboardManager`）后反馈问题。
- 清空：`controller.clearLogs()` + Snackbar 确认。

### 5.6 空数据 / 占位状态

- 每个列表都有 MD3 `EmptyState`（图标 + 标题 + 说明）：
  - Home 最近日志：暂无日志（服务运行后显示）
  - Rules：暂无拦截规则（空配置时附「重新加载配置」动作 → `controller.reloadConfig()`） / 没有匹配的规则（搜索）
  - Logs：暂无日志 / 当前筛选条件下没有日志
  - Settings：无列表，纯配置项
  - CertGuide：无列表，纯步骤
- 空配置说明文案明确提示「刚启动应用时配置可能仍在加载」，避免把加载态误判为故障。
- `PlaceholderSidebikeController` 提供的默认状态（IDLE + 空配置）可直接渲染，无崩溃路径。
- 所有 Preview/测试使用 `ui/preview/PreviewData.kt`，不访问网络。

## 6. 组件清单（全部 MD3）

`Scaffold`、`LargeTopAppBar` / `TopAppBar`、`NavigationBar` + `NavigationBarItem`、`Card` / `ElevatedCard` / `OutlinedCard`、`Surface`（tonal）、`Button`、`FilledTonalButton`、`OutlinedButton`、`TextButton`、`ExtendedFloatingActionButton`、`IconButton`、`Switch`、`FilterChip`、`SegmentedButton`（SingleChoiceSegmentedButtonRow）、`OutlinedTextField`、`ListItem`、`HorizontalDivider`、`LinearProgressIndicator`、`CircularProgressIndicator`、`AlertDialog`、`ModalBottomSheet`、`SnackbarHost`、`Icon`（Material Icons，filled/outlined）、`Text`、`Spacer` 等布局原语。

自组合组件（只由上述 MD3 token 组合，无自绘视觉）：
- `StatusPill`：`Surface` 语义 + `shapes.small` + `Text`（状态徽标/计数器）
- `InfoBanner`：tonal `Surface` + 图标 + 文本（环境提示/错误）
- `StatTile`：`Card`（surfaceContainerLow）+ `AnimatedCounterText`
- `EmptyState` / `SectionHeader`：排版组合
- `RunControlButton`：MD3 `Button` + 内容 morph（非自造按钮外观）
- `AnimatedCounterText`：文本 + `animateValueAsState`（Long 版）

## 7. Edge-to-edge 与系统栏

- `MainActivity.enableEdgeToEdge()`；`Scaffold` 处理 insets，列表使用 `contentPadding = innerPadding + 页面 padding`。
- 系统栏图标明暗：`SidebikeRoot` 按实际 darkTheme 设置 `isAppearanceLightStatusBars/NavigationBars`（跟随应用主题而非仅系统深色）。
- 列表内容滚动到 top app bar / NavigationBar 之下（MD3 推荐 scroll-under）。

## 8. 可访问性

- 所有交互组件有可见文案或 `contentDescription`；纯装饰图标 `contentDescription = null`。
- 状态不只靠颜色：同时有文案 + 图标（色盲友好）。
- 点击目标不小于 MD3 最小 48dp（IconButton / ListItem / Switch 内置）。
- 字号随系统字体缩放（全部使用 sp 的 MD3 type scale，不写死 px）。

## 9. QA 静态审计期望

由 `app/src/test/java/dev/sidebike/app/ui/Md3ComplianceTest.kt` 自动执行：

1. `ui/**` 与其 `MainActivity.kt` 无 Material 2 import（仅 `androidx.compose.material3.*` / `androidx.compose.material.icons.*`）。
2. 无 `LinearEasing`（grep 级禁止）。
3. `ui/theme/**` 之外无 `Color(0x` 硬编码颜色。
4. 无 `FontFamily(` 自定义字体。
5. `ScreenPreviews.kt` 覆盖 5 屏 + 根壳，`@Preview` ≥ 10 且含 `UI_MODE_NIGHT_YES`（浅色+深色）。
6. `Theme.kt` 使用 MD3 typography + shape token + light/dark + dynamic color。
7. `docs/design/03-motion-spec.md` 存在且含 easing/duration token（事件→动画表）。
