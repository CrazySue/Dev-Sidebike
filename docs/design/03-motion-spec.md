# Dev-Sidebike 动效规范（Material Design 3 Motion Tokens）

> 适用文件：`app/src/main/java/dev/sidebike/app/ui/motion/M3Motion.kt`（唯一动效 token 来源）。
> 硬性要求：**每一个 UI 状态变化都必须有非线性动画**；禁止线性缓动（`LinearEasing`）；所有 easing/duration 必须来自 MD3 motion token。
> 配套静态审计：`app/src/test/java/dev/sidebike/app/ui/Md3ComplianceTest.kt` + `MotionTokensTest.kt`。

## 1. Token 定义（与 `androidx.compose.material3.tokens.MotionTokens` 一致）

### 1.1 Easing（cubic-bezier）

| Token（Kotlin） | cubic-bezier | 用途 |
| --- | --- | --- |
| `M3Motion.EmphasizedDecelerate` | (0.05, 0.70, 0.10, 1.00) | 进入、展开、尺寸增长（强调型减速） |
| `M3Motion.EmphasizedAccelerate` | (0.30, 0.00, 0.80, 0.15) | 退出、收起、尺寸收缩（强调型加速） |
| `M3Motion.Standard` | (0.20, 0.00, 0.00, 1.00) | 等权状态过渡（颜色、数值、列表位移） |
| `M3Motion.StandardDecelerate` | (0.00, 0.00, 0.00, 1.00) | 缓慢进入的数值/呼吸动画 |
| `M3Motion.StandardAccelerate` | (0.30, 0.00, 1.00, 1.00) | 缓慢退出 |

> MD3 的「emphasis」是两段式曲线：**进入内容用 EmphasizedDecelerate，退出内容用 EmphasizedAccelerate**。
> 工程实现上二者分别用于 `AnimatedContent` 的 enter/exit 两半（fade-through 见 §2）。

### 1.2 Duration（ms）

| Token | 值 | Token | 值 | Token | 值 |
| --- | --- | --- | --- | --- | --- |
| `Short1` | 50 | `Medium1` | 250 | `Long1` | 450 |
| `Short2` | 100 | `Medium2` | 300 | `Long2` | 500 |
| `Short3` | 150 | `Medium3` | 350 | `Long3` | 550 |
| `Short4` | 200 | `Medium4` | 400 | `Long4` | 600 |

额外：`ExtraLong1..4 = 700/800/900/1000`（仅呼吸/脉冲类循环动画使用）。

## 2. 复合 transition（`ui/motion/M3Motion.kt` 中的可复用封装）

| 封装 | 组成 | 用途 |
| --- | --- | --- |
| `m3FadeThrough()` | enter: fadeIn + scaleIn(0.92) / EmphasizedDecelerate / Medium1；exit: fadeOut + scaleOut(1.08) / EmphasizedAccelerate / Short2 | MD3 标准「fade-through」：顶部导航、状态内容切换 |
| `m3FadeThroughResize()` | fade-through + `SizeTransform(clip=false, tween EmphasizedDecelerate Medium2)` | 内容尺寸不同的状态切换（启动/停止 morph、最近日志） |
| `m3ExpandIn()` | fadeIn(Medium1, EmphasizedDecelerate) + expandVertically(Medium2, EmphasizedDecelerate) | 横幅/错误/详情展开 |
| `m3ShrinkOut()` | fadeOut(Short2, EmphasizedAccelerate) + shrinkVertically(Short4, EmphasizedAccelerate) | 横幅/错误/详情收起 |
| `m3EnterFromBottom()` | fadeIn + slideInVertically(8%) / EmphasizedDecelerate / Medium2 | 列表/卡片进入 |
| `M3TopAppBarSnapSpec` | tween(Medium1, EmphasizedDecelerate) | 顶栏折叠 snap |
| `M3ListItemFadeInSpec` | tween(Medium1, EmphasizedDecelerate) | Lazy 列表项淡入 |
| `M3ListItemPlacementSpec` | tween(Medium2, Standard) | Lazy 列表项位置动画 |
| `M3ListItemFadeOutSpec` | tween(Short2, EmphasizedAccelerate) | Lazy 列表项淡出 |
| `m3StaggerDelayMillis(index)` | index × Short1 | 列表错峰入场 |

## 3. 事件 → 动画 → easing → duration 映射表（验收核心）

### 3.1 首页（Home）

| # | 触发事件 | 动画组件 | Easing token | Duration token |
| - | --- | --- | --- | --- |
| H1 | RunStatus 变化 → Hero 容器/内容/强调色 | `Surface` + `animateColorAsState` | `Standard` | `Medium2` (300ms) |
| H2 | RunStatus 变化 → Hero 圆角 shape morph（16dp ↔ 28dp） | `RoundedCornerShape(animateDpAsState)` | `EmphasizedDecelerate` | `Medium2` |
| H3 | RunStatus 变化 → Hero headline/subtitle 内容（headline 带运行时长，subtitle 为能力行） | `AnimatedContent`（fade-through） | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium1` (250) / `Short2` (100) |
| H4 | Android 13+ 通知权限被拒绝 → 首页提示横幅 | `AnimatedVisibility` + `InfoBanner`（含「允许通知」重试动作） | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium2` / `Short4` |
| H5 | RUNNING / 进行中 → 状态点呼吸 | `rememberInfiniteTransition` + `animateFloat`（alpha 1→0.25，Reverse） | `StandardDecelerate` | `ExtraLong1` (700) |
| H6 | 进入 busy（PREPARING/STARTING/STOPPING）→ 线性进度条出现 | `AnimatedVisibility` + `LinearProgressIndicator` | `EmphasizedDecelerate`（expand） | `Medium2` |
| H7 | 离开 busy → 进度条消失 | `AnimatedVisibility` shrink | `EmphasizedAccelerate` | `Short4` (200) |
| H8 | busy 时顶行显示 CircularProgressIndicator | MD3 `CircularProgressIndicator`（MD3 内建 indeterminate 动画） | 组件内建 | 连续 |
| H9 | 错误出现/消失（lastError） | `AnimatedVisibility` + `InfoBanner` | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium2` / `Short4` |
| H10 | CA/MITM 状态文案变化（MITM 未启用 → 需要安装 CA → 证书已就绪） | 文案由 `mitmCaStatusOf()` 状态映射驱动；Hero 内容色随 `animateColorAsState` 过渡 | `Standard` | `Medium2` |
| H11 | 环境提示出现（Private DNS / lockdown / 系统代理未生效 / 端口回退） | `AnimatedVisibility` + `InfoBanner` | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium2` / `Short4` |
| H12 | IDLE/ERROR ↔ RUNNING（启动/停止按钮形态 morph） | `RunControlButton`：容器/内容色 `animateColorAsState` | `Standard` | `Medium2` |
| H13 | 同上 → 按钮文案/图标/进度指示器内容切换 | `AnimatedContent`（fade-through + resize） | `EmphasizedDecelerate` / `EmphasizedAccelerate` | `Medium1` / `Short2`；size `Medium2` |
| H14 | 同上 → 按钮内容宽度变化 | `Modifier.animateContentSize` | `EmphasizedDecelerate` | `Medium2` |
| H15 | 统计数据变化（连接数/流量/DNS/延迟） | `AnimatedCounterText`（Long 值滚动） | `EmphasizedDecelerate` | `Medium2` |
| H16 | 最近日志内容刷新 | `AnimatedContent`（fade-through + resize） | `EmphasizedDecelerate` / `EmphasizedAccelerate` | `Medium1` / `Short2` |
| H17 | 快捷操作 MITM Switch 切换 | MD3 `Switch` 内建 thumb/track 动画 | 组件内建 MD3 token | 组件内建 |
| H18 | 点击快捷操作跳转日志/规则/证书 | 导航 fade-through（N1） | 见 N1 | 见 N1 |

### 3.2 导航与全局（Navigation / Scaffold）

| # | 触发事件 | 动画组件 | Easing token | Duration token |
| - | --- | --- | --- | --- |
| N1 | 切换 NavigationBar destination | `AnimatedContent`（fade-through：incoming scale 0.92→1、outgoing 1→1.08） | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium1` / `Short2` |
| N2 | NavigationBar 选中项变化 | MD3 `NavigationBarItem` 内建 indicator 动画 | 组件内建 MD3 token | 组件内建 |
| N3 | 首页滚动 → LargeTopAppBar 折叠/展开 | `exitUntilCollapsedScrollBehavior(snapAnimationSpec=…)` | `EmphasizedDecelerate`（fling 用 MD3 spline 物理衰减） | `Medium1` (snap) |
| N4 | 进入/离开日志页 → 导出 FAB 出现/消失 | `AnimatedVisibility` + `ExtendedFloatingActionButton` | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium1` / `Short2` |
| N5 | Snackbar 显示/消失（清空、复制、CA 错误） | MD3 `SnackbarHost` 内建动画 | 组件内建 MD3 token | 组件内建 |
| N6 | 导出诊断 ModalBottomSheet 打开/关闭 | MD3 `ModalBottomSheet` 内建 slide-in/out | 组件内建 MD3 token | 组件内建 |
| N7 | 端口编辑/关于/许可 AlertDialog 打开/关闭 | MD3 `AlertDialog` 内建 fade+scale | 组件内建 MD3 token | 组件内建 |
| N8 | 返回键回到首页 | 同 N1（fade-through） | 见 N1 | 见 N1 |
| N9 | 主题模式/动态取色切换 | `MaterialTheme` colorScheme 热替换；使用 `animateColorAsState` 的组件平滑过渡（Hero/按钮/开关行），其余按 MD3 组件默认重绘 | `Standard`（显式动画组件） | `Medium2` |

### 3.3 规则页（Rules）

| # | 触发事件 | 动画组件 | Easing token | Duration token |
| - | --- | --- | --- | --- |
| R1 | 搜索框清空图标出现/消失 | `AnimatedVisibility` | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium2` / `Short4` |
| R2 | 搜索过滤导致列表项增删/位移 | LazyColumn `Modifier.animateItem(fadeIn/placement/fadeOut)` | fadeIn `EmphasizedDecelerate`；placement `Standard`；fadeOut `EmphasizedAccelerate` | `Medium1` / `Medium2` / `Short2` |
| R3 | 展开/收起域名动作详情 | `Modifier.animateContentSize` + `AnimatedVisibility` | `EmphasizedDecelerate`（展开）/ `EmphasizedAccelerate`（收起） | `Medium2` / `Short4` |
| R4 | 展开箭头旋转 0°→180° | `animateFloatAsState` + `Modifier.rotate` | `EmphasizedDecelerate` | `Medium2` |
| R5 | 直连放行 Switch 切换 | MD3 `Switch` 内建动画 + 持久化后列表项重排（R2） | 组件内建 / `Standard`（placement） | 组件内建 / `Medium2` |
| R6 | 功能开关（代理规则/MITM/国内直连）切换 | MD3 `Switch` 内建动画 | 组件内建 MD3 token | 组件内建 |
| R7 | 空结果 / 空配置状态出现 | 列表项替换（R2 fade）+ `EmptyState`（空配置时提供「重新加载配置」动作） | 同 R2 | 同 R2 |
| R8 | 动作类型 chips（SNI/镜像/合成…）随数据出现 | 随卡片展开（R3） | 同 R3 | 同 R3 |

### 3.4 日志页（Logs）

| # | 触发事件 | 动画组件 | Easing token | Duration token |
| - | --- | --- | --- | --- |
| L1 | 切换级别筛选 chip（全部/DEBUG/INFO/WARN/ERROR） | MD3 `FilterChip` 内建选中动画 + 列表项 animateItem 增删 | 组件内建 / fadeIn `EmphasizedDecelerate`、fadeOut `EmphasizedAccelerate` | 组件内建 / `Medium1` / `Short2` |
| L2 | 新日志到达 | LazyColumn `animateItem`（fadeIn + placement） | `EmphasizedDecelerate` / `Standard` | `Medium1` / `Medium2` |
| L3 | 自动滚动到最新日志 | `LazyListState.animateScrollToItem`（Compose 滚动动画，非线性物理曲线） | 物理 spline（非线性） | 由 scroll 动画框架按距离决定 |
| L4 | 点击日志行展开/收起全文 | `Modifier.animateContentSize` | `EmphasizedDecelerate` / `EmphasizedAccelerate` | `Medium2` / `Short4` |
| L5 | 日志行展开箭头旋转 | `animateFloatAsState` + `Modifier.rotate` | `EmphasizedDecelerate` | `Medium2` |
| L6 | 清空日志 → 列表变空 | `animateItem` fadeOut + `EmptyState` 替换 | `EmphasizedAccelerate` | `Short2` |
| L7 | 自动滚动 Switch 切换 | MD3 `Switch` 内建动画 | 组件内建 | 组件内建 |

### 3.5 设置页（Settings）

| # | 触发事件 | 动画组件 | Easing token | Duration token |
| - | --- | --- | --- | --- |
| S1 | 主题模式 SegmentedButton 选择 | MD3 `SegmentedButton` 内建 shape/color 动画 | 组件内建 MD3 token | 组件内建 |
| S2 | DNS 模式 SegmentedButton 选择 | 同上 | 组件内建 | 组件内建 |
| S3 | 任意 Switch（系统代理/国内直连/DNS 接管/MITM/自启动/动态取色/Provider） | MD3 `Switch` 内建动画；开关行容器色 `animateColorAsState` | `Standard`（行颜色） | `Medium2` |
| S4 | 修改代理端口（点行 → Dialog → 保存） | MD3 `AlertDialog` 内建进入/退出动画；保存后数值重绘 | 组件内建 | 组件内建 |
| S5 | 打开关于 / 开源许可 Dialog | MD3 `AlertDialog` 内建动画 | 组件内建 | 组件内建 |
| S6 | MITM 开关 → 证书状态文案变化（MITM 未启用 / 需要安装 CA / 已安装并信任） | 状态映射 `mitmCaStatusOf()`；开关行/卡片颜色使用 `animateColorAsState` | `Standard` | `Medium2` |
| S7 | Provider 列表项开关 | MD3 `Switch` 内建动画 | 组件内建 | 组件内建 |

### 3.6 证书指南（CertGuide）

| # | 触发事件 | 动画组件 | Easing token | Duration token |
| - | --- | --- | --- | --- |
| C1 | CA 状态变化 → Hero 容器/内容色 | `animateColorAsState` | `Standard` | `Medium2` |
| C2 | CA 状态变化 → 「已安装/未安装/尚未生成」文案 | `AnimatedContent`（fade-through） | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium1` / `Short2` |
| C3 | 页面首次进入 → 4 个步骤卡片错峰入场（fade+slide 1/8） | `AnimatedVisibility(MutableTransitionState)`，delay = index × `Short1` | `EmphasizedDecelerate` | `Medium2` + 0/50/100/150ms delay |
| C4 | MITM 未开启 → 提示横幅出现/消失 | `AnimatedVisibility` + `InfoBanner` | enter `EmphasizedDecelerate` / exit `EmphasizedAccelerate` | `Medium2` / `Short4` |
| C5 | 安装/导出/重新检测按钮点击 | MD3 `Button` 内建 ripple + press 状态；异步结果以 Snackbar 反馈（N5） | 组件内建 | 组件内建 |

## 4. 强制约束（QA 静态审计）

QA / CI 对以下内容做静态检查（也已由 `Md3ComplianceTest` / `MotionTokensTest` 自动执行）：

1. **禁止线性缓动**：`app/src/main/java/dev/sidebike/app/ui/**` 中不得出现 `LinearEasing`；
   所有 `tween(...)` 必须显式传 `easing = M3Motion.*`（代码审查项）。
2. **禁止 Material 2**：不得出现 `androidx.compose.material.` import（`androidx.compose.material.icons.` 与 `androidx.compose.material3.` 除外）。
3. **禁止硬编码颜色**：`ui/theme/**` 之外不得出现 `Color(0x…)`；颜色一律 `MaterialTheme.colorScheme.*`。
4. **禁止自定义字体**：不得出现 `FontFamily(...)`，排版只用 `MaterialTheme.typography.*`。
5. **状态变化必须动**：`RunStatus` / 列表内容 / 配置项 / 错误 / 提示 / 导航 destination 的每次变化都必须经过上表中的动画组件；
   code review 检查点 = 「该 composable 是否直接硬切（无 Animated*/animate*AsState）？」。
6. **循环动画只允许呼吸/进度类**：`rememberInfiniteTransition` 仅用于 H5 状态脉冲与 MD3 进度指示器；
   其余状态变化一律为有限时长动画（finite animation）。
7. **token 来源唯一**：`M3Motion.kt` 是唯一 easing/duration 定义处；其他文件只能引用（grep `CubicBezierEasing(` 只允许出现在 `ui/motion/M3Motion.kt`）。

## 5. 验证方式

```powershell
# 动效 token + 静态合规测试
powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :app:testDebugUnitTest --tests "dev.sidebike.app.ui.MotionTokensTest" --tests "dev.sidebike.app.ui.Md3ComplianceTest"
# UI 全量测试
powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :app:testDebugUnitTest --tests "dev.sidebike.app.ui.*"
```

人工核对建议（Android Studio Preview / 模拟器）：
- 状态切换（IDLE→STARTING→RUNNING→STOPPING）观察 Hero 色/圆角/文案/进度条是否同时平滑；
- 快速点按启动/停止观察按钮 morph 是否无跳变；
- 规则页展开/收起 + 搜索过滤观察列表重排；
- 日志页自动滚动与级别筛选；
- 深浅色 + 动态取色切换后检查颜色 role 是否全部跟随。

## 6. 测试确定性（Robolectric + Compose）

无限动画会让 Compose 永远不进入 idle，导致 Robolectric 测试挂起。处理方式：

1. `ui/motion/LocalMotion.kt` 提供 `LocalInfiniteAnimationsEnabled`（默认 `true`，生产/Preview 行为不变）。
   测试基类 `ComposeTestBase` 通过 `CompositionLocalProvider(LocalInfiniteAnimationsEnabled provides false)` 关闭：
   - Home Hero 状态呼吸点（`rememberInfiniteTransition`）；
   - 不确定进度指示器（Hero/RunControlButton 的 `CircularProgressIndicator`/`LinearProgressIndicator`，测试时改用确定值进度）。
   **有限时长动画（本 spec 表中的所有 MD3 token 动画）仍全部开启并参与断言。**
2. `OutlinedTextField` 聚焦后的 IME/光标闪烁同样是无限动画：Settings 代理端口 Dialog 在
   `LocalInfiniteAnimationsEnabled = false` 时渲染只读端口文本（testTag 保持 `port-input`），
   输入过滤/校验由纯函数 `normalizeProxyPortInput()` / `isValidProxyPort()` 的单测覆盖（`PortValidationTest`）。
3. 导航/交互定位统一用 testTag，避免文案歧义（快捷操作文案与 NavigationBar label 重叠）：
   `nav:<route>`、`logs-export-fab`、`rules-list`、`settings-list`、`home-list`、`rule-switch:<domain>` 等。
4. 测试时钟保持 `autoAdvance = true`（`waitForIdle` 自动推进有限动画），不依赖手动 `advanceTimeBy`。
5. 稳定性验证命令（102 个 UI 测试全绿）：
   `powershell -NoProfile -File _script\gradle.ps1 :app:testDebugUnitTest --tests "dev.sidebike.app.ui.*"`

