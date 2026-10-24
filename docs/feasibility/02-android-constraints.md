# 02 · Android 平台约束（Android 8.0–16 / API 26–36）

> 作者：challenger（独立可行性/QA）。日期：2026-10-25。
> 方法：官方文档 + AOSP/Chromium 源码 + 本机 SDK（`tools/android-sdk/platforms/android-35/data/api-versions.xml`）交叉核对。
> 标记约定：**【已核实】**=文档/源码/本地 SDK 可直接证明；**【待实测】**=文档语义可推出但没有设备无法闭环，必须做 03 文档里的验证实验；**【经验】**=OEM/社区经验，需目标机复测。
> 本文所有结论都服务于一个判断：**Plan A（DNS-only VpnService + `setHttpProxy` 本地代理）的边界在哪里、哪些边界会让 beta 变成"看起来能用其实没用"。**

## 0. 结论速览

| # | 约束 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | `VpnService.Builder.setHttpProxy` 存在性 | **API 29（Android 10）才加入**；API 26–28 无系统代理能力 | 本地 `api-versions.xml`：`setHttpProxy since=29`；[官方引用](https://developer.android.com/reference/android/net/VpnService.Builder) |
| 2 | 一个用户同时只能有一个 VPN | `prepare()` 只允许一个 app；新 VPN 会 revoke 旧 VPN（`onRevoke()`） | [VpnService.prepare](https://developer.android.com/reference/android/net/VpnService#prepare(android.content.Context)) |
| 3 | DNS-only 分隧道对所有 app 生效 | 可行：VPN 网络提供 DNS 服务地址 + 路由该 /32，系统解析器会把全系统 DNS 发进 tun | [VPN 开发指南](https://developer.android.com/develop/connectivity/vpn)、[addDnsServer](https://developer.android.com/reference/android/net/VpnService.Builder#addDnsServer(java.lang.String)) |
| 4 | 系统代理在"VPN 非默认网络"时是否下发 | **【待实测】Plan A 第一大风险**。官方 note 暗示会全局生效（见 §2.2），但未在 AOSP 源码/文档中闭环 | 见 §2.2 与 03-M1 |
| 5 | 谁遵循系统代理 | 遵循：`java.net` 栈（`URLConnection`/OkHttp 默认 `ProxySelector`）、Chromium（Chrome/WebView/Cronet）。不遵循：原生 socket/libcurl/Flutter/Unity、QUIC/HTTP3、硬编码 IP、自有 DoH/DoT 解析的应用 | [ProxyInfo 文档](https://developer.android.com/reference/android/net/ProxyInfo)、[Chromium proxy_config_service_android.cc](https://source.chromium.org/chromium/chromium/src/+/main:net/proxy_resolution/proxy_config_service_android.cc) |
| 6 | Private DNS（DoT） | 设为主机名时**系统解析绕过 VPN DNS**，DNS 防污染整体失效；可用 `LinkProperties.isPrivateDnsActive()`（API 28+）检测 | [LinkProperties](https://developer.android.com/reference/android/net/LinkProperties#isPrivateDnsActive()) |
| 7 | 用户 CA | Android 7+ 默认只有 targetSdk≤23 的 app 信任用户 CA；**Chrome 显式信任用户 CA**（自带 NSC `src="user"`），普通 app/WebView 默认不信任 | [NSC 文档](https://developer.android.com/privacy-and-security/security-config)、[Chrome NSC 源码](https://github.com/chromium/chromium/blob/main/chrome/android/java/res_base/xml/network_security_config.xml) |
| 8 | 前台服务 | API 26+ 必须在 ~5s 内 `startForeground()`；target 34+ 必须声明并匹配 FGS type；`systemExempted` 有运行时条件，`specialUse` 最稳 | [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)、[VpnService 引用](https://developer.android.com/reference/android/net/VpnService) |
| 9 | always-on / lockdown | always-on 由系统在开机/网络变化时启动服务（需 `<meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="true"/>`）；**lockdown（"Block connections without VPN"）+ DNS-only 分隧道 = 除 DNS 外全部断网** | [VpnService 引用](https://developer.android.com/reference/android/net/VpnService)、FGS/锁定模式系统行为 |
| 10 | ABI | 纯 Kotlin ⇒ 与 ABI 无关、单 APK 通吃 arm64/armv7/x86/x86_64；`ndk.abiFilters` 在无 `.so` 时是空操作，只对未来原生模块生效 | 本地 `app/build.gradle.kts`、[ABI 指南](https://developer.android.com/ndk/guides/abis) |
| 11 | 无 root 做不了 | 见 §7（透明代理非代理感知 app、系统证书库、全局设置、tcpdump、hosts、按进程分流等） | — |

---

## 1. VpnService（Android 8.0 → 16 / API 26 → 36）

### 1.1 生命周期与 `establish()` 语义【已核实】

- 入口：`VpnService.prepare(Context)`（API 14）。返回 `null` 表示已获授权；否则返回系统授权 Activity 的 `Intent`，必须由 Activity `startActivityForResult` 拉起，否则后续 `establish()` 返回 `null`。
- `establish()`（API 14）返回 `ParcelFileDescriptor`（tun 设备 fd）；失败返回 `null`（未 prepare、被 revoke、参数非法、系统策略拒绝）。**必须判 null 并优雅失败**，不能假设 false。
- 一个 user/profile 同时只有一个 VPN 处于 prepared/active：新 VPN 生效时旧 VPN 收到 `onRevoke()`；`onRevoke()` **可能不在主线程**，此时系统已拆除接口，应关闭 fd、停止线程、`stopSelf()`。默认实现即 `stopSelf()`。
- 服务声明必须：`android:permission="android.permission.BIND_VPN_SERVICE"` + `<intent-filter><action android:name="android.net.VpnService"/></intent-filter>`；`exported` 建议 `false`（系统 UID 可启动/绑定非 exported 组件，BIND_VPN_SERVICE 再兜底）。
- 数据面：fd 是所有被路由到 tun 的 IP 包（原始 L3，无以太头）。写入即模拟"来自 VPN 网关"的入站包。**tun 只在读写时推进，不存在"DROP"语义**——不读等于把包积压/丢弃（取决于内核/队列），不写就是丢包。
- `setBlocking(true)`（API 21）让 fd 变阻塞；默认是阻塞还是非阻塞由系统实现决定（AOSP ToyVpn 用非阻塞 + `Selector`，很多现网 app 用阻塞读线程）。**不要把"非阻塞 fd"当默认假设**：非阻塞 fd 若又用普通 `read()` 循环，会 100% CPU 空转。本项目二选一并写死：推荐阻塞读线程（架构文档 3.1 的 `setBlocking(false)` 与 task-3 的 "blocking read" 冲突，需统一）。
- `protect(Socket)`（API 14）：让该 socket 流量绕过 VPN。**DNS-only 分隧道下代理的出站 socket 本来就不进 tun（没有默认路由），因此不是必须**；但一旦未来加默认路由/Plan B，必须 protect 否则自环。代码里建议一律 `protect()` 关键出站 socket，成本为零、避免未来回退。

### 1.2 `VpnService.Builder` 能力矩阵（API level 已用本地 SDK 复核）

| 方法 | since API | 本项目用途/注意 |
| --- | --- | --- |
| `addAddress(InetAddress,int)` / `addAddress(String,int)` | 14 | tun 接口地址（如 `10.111.0.1/32`）。IPv4/IPv6 分别可加；加地址即隐式允许该 family 走 VPN |
| `addRoute(InetAddress,int)` / `addRoute(String,int)` | 14 | **DNS-only 必须加 DNS 服务器地址的 /32 路由**（如 `10.111.0.2/32`），否则 DNS 包可能不在 tun 路由表内（是否自动加路由随版本/实现不同，不要赌） |
| `addDnsServer(...)` | 14 | 系统解析器把该网络 DNS 指到它；⚠️ 该地址必须是"我们 tun 内的假 IP"，不能是真实 DNS |
| `addSearchDomain` | 14 | 不用 |
| `setSession` / `setConfigureIntent` | 14 | 通知/系统对话框展示名；configureIntent 指向设置页便于 always-on 场景 |
| `establish()` | 14 | 见 1.1 |
| `setMtu(int)` | 14 | DNS-only 基本无感（建议 1500）；Plan B 时才是关键参数（IPv6 最小 1280，避免分片） |
| `allowFamily(int)` | 14（已废弃 21） | 不要用 |
| `allowBypass()` | 21 | 允许 app 主动 `bindProcessToNetwork` 绕过；语义是"所有 app 可通过 API 绕过"，与 always-on/lockdown 交互时要谨慎，beta 不用 |
| `addAllowedApplication` / `addDisallowedApplication` | 21 | 分应用 VPN（白名单/黑名单）。**应用自身包名在 disallowed 列表的行为要实测**；DNS-only 方案下自身 DoH 出站不经过 tun，不是必须 |
| `setBlocking(boolean)` | 21 | 见 1.1 |
| `setUnderlyingNetworks(Network[])` | 22 | 让系统知道 VPN 背后的物理网络，影响默认网络判定/计费/回调；**Plan A 建议显式传当前活动网络**（尤其分隧道 + 网络切换场景）【待实测】 |
| `setMetered(boolean)` | 29 | **target 29+ 的 VPN 默认按 metered 处理**（官方文档原文）；本应 `setMetered(false)`，否则 Play/系统可能限制后台流量 |
| `setHttpProxy(ProxyInfo)` | 29 | Plan A 核心，见 §2 |
| `excludeRoute(IpPrefix)` | 33 | 从默认路由中排除网段（Plan B 用），beta 不用 |
| `SERVICE_META_DATA_SUPPORTS_ALWAYS_ON`（VpnService 常量） | 21 | manifest `<meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="true"/>`（**注意 value 类型 bool，字符串 "true" 会被解析为 true**）。不加则系统设置里不提供 always-on 开关 |
| `isAlwaysOn()` / `isLockdownEnabled()`（VpnService 实例方法） | 29 | 判断本次启动是否系统代启、是否处于 lockdown；**lockdown + DNS-only 会断网**，见 §1.4 |

### 1.3 前台服务与通知（版本差异，beta 必踩的坑）

| Android / API | 要求 | 本项目动作 |
| --- | --- | --- |
| 8.0 / 26 | 后台服务受限；VPN 服务被系统拉起后仅获得**短暂 allowlist**，"must promote itself to the foreground after it's launched or the system will shut down the app"（VpnService 官方引用原文） | 在 `onStartCommand` 里立刻 `startForeground()`（<5s，超时 `ForegroundServiceDidNotStartInTimeException`） |
| 8.0 / 26 | 通知渠道：必须建 NotificationChannel；VPN 活跃期应为**不可滑除**通知 | 通知渠道 + `setOngoing(true)` |
| 9 / 28 | 无新增 | — |
| 10 / 29 | VPN 网络默认 metered（target 29+） | `setMetered(false)` |
| 12 / 31 | **从后台启动 FGS 受限**；例外清单包含"收到 `ACTION_BOOT_COMPLETED` / `ACTION_LOCKED_BOOT_COMPLETED` / `ACTION_MY_PACKAGE_REPLACED` 的广播接收器"、"用户关闭电池优化"等 | BootReceiver / 用户点击后启动；always-on 由系统代启不受此限 |
| 13 / 33 | `POST_NOTIFICATIONS` 运行时权限；无权限时 FGS 通知**不在通知栏展示**（仅在任务管理器可见） | 首启请求通知权限；缺权限时 UI 提示（功能仍可用） |
| 14 / 34 | **必须**声明 `android:foregroundServiceType` 且与 `startForeground()` 传入 type 匹配，否则抛 `MissingForegroundServiceTypeException` | 见下 |
| 14 / 34 | FGS type `systemExempted` 允许条件（原文列举）：demo 模式、Device Owner、Profile Owner、ROLE_EMERGENCY、Device Admin、持有 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM、**"VPN apps (configured using Settings > Network & Internet > VPN)"**；否则 `ForegroundServiceTypeNotAllowedException` | ⚠️ 首次启动时用户还没有在系统设置里"配置 VPN"，条件是否满足**不确定**；更稳的是 `specialUse` |
| 15 / 35 | BOOT_COMPLETED receiver **不得**启动 `dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone` 类型的 FGS（`systemExempted`/`specialUse` 不在禁止列表） | 我们的 type 不受影响，但**必须真机验证** |
| 15 / 35 | 持有 SYSTEM_ALERT_WINDOW 从后台起 FGS 需可见 overlay | 我们不申请悬浮窗 |
| 16 / 36 | Local Network Protection（局域网访问新权限，逐步生效） | 代理监听 **127.0.0.1 回环**，不属于 LAN 访问，不受影响；若未来做"局域网共享代理到 PC"则受影响 |

**建议 manifest（同时兼容 34+ 与旧版本）：**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service android:name=".vpn.SidebikeVpnService"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="dns_filtering_http_proxy" />
  <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
  <meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="true" />
</service>
```

（若坚持 `systemExempted`：需 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` 权限，且建议 `try { startForeground(..., TYPE_SYSTEM_EXEMPTED) } catch (ForegroundServiceTypeNotAllowedException) { startForeground(..., TYPE_SPECIAL_USE) }` + 双 type 声明。风险见 03-R3。）

### 1.4 always-on VPN、lockdown、开机启动

- **always-on VPN**【已核实】：Android 7.0+（API 24+）系统可在开机时启动 VPN 服务并保持；服务需声明 `SUPPORTS_ALWAYS_ON` metadata，用户在 设置→网络→VPN→齿轮 里开启。always-on 场景 `onStartCommand` 的 Intent 可能为 **null**，必须处理；此时 `isAlwaysOn()` 为 true（API 29+）。
- **lockdown（"Block connections without VPN"）**：用户可勾选"阻止未使用 VPN 的连接"。**对 DNS-only 分隧道是灾难**：只有发往 `10.111.0.2` 的 DNS 走 VPN，其余流量都被判定为"未走 VPN"而被阻断 → 用户感觉"开了 Dev-Sidebike 直接没网"。必须在启动时检查 `isLockdownEnabled()`（API 29+；旧版本无法精确检测）并：拒绝启动 + 引导用户关闭 lockdown，或在 UI 明确红字警告。**这是 beta 最容易被用户一键搞坏的功能组合。**
- **开机启动**：两条路：① 系统 always-on（推荐，唯一在"未解锁前/无用户交互"也可靠的方式）；② `BOOT_COMPLETED` receiver + `startForegroundService`（Android 12+ 有例外允许；但 MIUI/HyperOS 默认拦截自启，需要用户手动允许）。注意 `BOOT_COMPLETED` 在用户解锁后送达（credential-encrypted 存储此时可读）；若业务 config 存在普通 DataStore（credential encrypted），always-on 在 **Direct Boot 阶段**启动服务时可能读不到配置 → establish 失败 → 系统弹出"VPN 无法连接"且 lockdown 下断网。**缓解：always-on 路径下先用代码内默认值（假 DNS IP/端口常量）建立最小可用配置，或把最小配置写入 device-protected storage。**
- **网络切换**：Wi-Fi↔蜂窝 切换时 VPN 通常保持，但 `setUnderlyingNetworks` 不更新会导致计费/统计/回调异常；在 `ConnectivityManager.NetworkCallback.onAvailable` 里更新 `setUnderlyingNetworks`（API 22+，需要重新 establish 或依赖 `VpnService.setUnderlyingNetworks()` 运行时方法【待实测：运行时更新是否需要重启 VPN】）。

### 1.5 Doze 与 OEM 后台清理

- **Doze**【已核实】：设备静止+锁屏+未充电进入 Doze，系统**暂停网络访问**、延迟 job/alarm、忽略 wakelock。对 DNS-only VPN 影响小（只影响 DNS 查询的及时性；维护窗口恢复）。但 Doze 不会杀死已建立的 VPN 服务进程（前台服务）；**真正杀进程的是 OEM 电源管理**。
- **MIUI / HyperOS**【经验，需真机复核】：
  - 默认"省电策略"会对后台应用限流/杀进程；VPN 服务（虽有 FGS）仍常被清 → 用户侧表现为"用一会儿就断、通知消失"。
  - 需要用户手动开：**自启动**（设置→应用→Dev-Sidebike→自启动）、**省电策略→无限制**、**锁定后台任务**（最近任务下拉加锁）、关闭"MIUI 优化"不是必须但常能救活。
  - 可用 intent 引导（不稳定，需 try/catch）：`miui.intent.action.OP_AUTO_START`（小米安全中心自启动页）、`miui.intent.action.APP_POWER_SAVE_MODE` 等；失败则回退到 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`。
  - 部分 MIUI 版本对 VPN 应用额外限制：VPN 图标常驻通知不可关、安全中心"网络助手"可能提示流量异常；用户卸载/清理时 VPN 断开。
- **电池优化豁免**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（需 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限；Play 政策限制，但本项目是 sideload beta，可用）+ 引导页。不要申请 `WAKE_LOCK` 常驻（申请了也别持有；DNS-only 不需要）。
- **现实存活预期**：AOSP/Pixel 开电池优化豁免后基本长期存活；小米需自启动+无限制（多数机型可天级）；华为/OPPO/vivo 更激进（需厂商白名单）。beta 文案要诚实：**不保证后台常活，断了就重连**；配合 always-on 可让系统重拉。

---

## 2. 系统级 HTTP 代理：`VpnService.Builder.setHttpProxy(ProxyInfo)`

### 2.1 API 与语义【已核实】

- **API 29（Android 10）加入**；API 26–28 完全没有"VPN 提供系统代理"的能力（只能走 Wi-Fi 代理设置或用户手动改，普通 app 无 `WRITE_SECURE_SETTINGS`）。
- 调用点：**只在 `establish()` 时生效**，之后无法通过 API 动态修改/清除；要改必须重建 tun（`establish()` 新配置会替换旧接口），要清必须停 VPN 或重建。**这一点决定了 fail-safe 设计**（见 §2.4）。
- 官方原文（`VpnService.Builder.setHttpProxy` 文档，API 29 段）：
  - "This proxy is only a recommendation and it is possible that some apps will ignore it."
  - "PAC proxies are not supported over VPNs."（所以必须 `ProxyInfo.buildDirectProxy`，不能 PAC）
  - "Apps that do use the proxy cannot distinguish between routes handled and not handled by the VPN and will try to access HTTP resources over the proxy regardless of the destination. In practice this means using a proxy with a split tunnel generally won't work as expected, because HTTP accesses on routes not handled by the VPN will not reach as the proxy won't be available outside of the VPN network."
  - **解读**：该警告针对"代理服务器只能通过 VPN 内网访问"的场景。我们的代理就在设备 **127.0.0.1** 回环上，不属于"VPN network 内才可达"，因此**理论上不受此限**——但"VPN 非默认网络时系统是否把 proxy 下发到全局 java 属性/广播"仍需实测（见 03-M1）。这是 Plan A 的**单点前提**。
- `ProxyInfo.buildDirectProxy(String host, int port)`（API 21）可用于构造；`isValid()` 是 API 30 才有，别用它做兼容判断（用 host 非空判断即可）。

### 2.2 系统代理的下发机制（源码证据，帮助判断"哪些 app 会跟随"）

- 框架层把 `ProxyInfo` 转成 **JVM 系统属性**：`http.proxyHost/https.proxyHost/http.proxyPort/https.proxyPort/http.nonProxyHosts`（AOSP `frameworks/base/core/java/android/net/Proxy.java#setHttpProxyConfiguration`），并发送代理变更广播；`ProxyInfo` 官方文档明确："Proxy configurations are already integrated within the java.net and Apache HTTP stack. So URLConnection and Apache's HttpClient will use them automatically. Other HTTP stacks will need to obtain the proxy info by watching for the Proxy.PROXY_CHANGE_ACTION broadcast..."
- **Chromium 系（Chrome/WebView/Cronet）**：`net::ProxyConfigService::CreateSystemProxyConfigService()` 在 Android 上返回 `ProxyConfigServiceAndroid`，它通过 JNI 监听 `ProxyChangeListener`（Java 层）读取系统代理 —— 即 **Chrome 会遵循 Android 系统代理（含 VPN 下发的代理）**【源码已核实】。
- **OkHttp**：默认 `proxySelector = ProxySelector.getDefault()`，Android 的默认 `ProxySelector` 与上述 java 系统属性联动（同上 ProxyInfo 文档），因此**默认 OkHttp 会走系统代理**；但 app 若构造 `OkHttpClient.Builder().proxy(Proxy.NO_PROXY)` 或 `ProxySelector` 自定义则绕过。
- **不遵循/绕过**（详见 §2.3）。

### 2.3 谁遵循 / 谁不遵循

| 类别 | 是否走系统代理 | 说明 |
| --- | --- | --- |
| `HttpURLConnection` / 默认 `java.net` 栈 | ✅ | ProxyInfo 官方文档明确 |
| OkHttp 默认配置 | ✅（高置信） | 依赖 `ProxySelector.getDefault()`；需 03-J2 JVM/真机验证 |
| Chrome / Android WebView / Cronet | ✅（高置信） | Chromium `ProxyConfigServiceAndroid`；WebView 的 TLS 信任仍由宿主 app 的 NSC 决定（见 §4.2） |
| 自带网络栈的 app：Flutter(`dart:io`)、Unity/Unreal、Go(自建)、libcurl/openssl、部分 electron 类 | ❌ | 原生 socket 直连，不走 java 代理 |
| QUIC/HTTP3（UDP 443） | ❌ | 系统代理只代理 TCP；Chrome 检测到代理时通常禁用 QUIC，其他 app 未必 |
| 硬编码 IP / 自有 HTTPDNS | ❌ | 连 DNS 都绕过（HTTPDNS 走 HTTPS 时，若 app 遵循系统代理仍会走代理，但目标 IP 已由 HTTPDNS 决定） |
| 自带 DoH/DoT 的解析 | 部分 | 解析绕过；实际连接若遵循代理仍走代理 |
| 绑定到具体网络（`Network.openConnection` / `bindProcessToNetwork`）的 app | ❌ | 不走 VPN 默认代理 |
| 部分系统组件/Google Play 服务 | 视情况 | 需真机验证 |

**结论**：Plan A 的"全应用生效"只对"遵循系统代理"的应用成立。对 GFW 场景要提前明确：**Chrome 通了 ≠ 所有 app 通**；这是产品边界，必须写进 UI/README，否则 beta 用户拿微信/抖音测会认为"没用"。

### 2.4 失效模式与 fail-safe（架构当前缺口）

1. **proxy 未就绪就 `establish()` 并把 `setHttpProxy` 指过去** ⇒ 用户点启动瞬间，所有遵循代理的 app（Chrome）HTTP 直接连接被拒 → 表现为"网络坏了"，比启动失败更糟。
   - 正确顺序：先启 engine/proxy → **自检**（对 `127.0.0.1:port` 发一个 `CONNECT`/健康请求，2s 超时）→ 通过才 `establish()`；失败则只 establish DNS-only 或直接报错。
2. **proxy 运行中线程崩溃/端口被抢占** ⇒ VPN 仍在、DNS 仍通，但 Chrome 打不开任何网页（DNS 正常会误导排查）。
   - watchdog：每 3–5s 健康检查；连续失败 → 停 VPN（系统会立即清理 proxy 下发 → 网络恢复）→ UI 报错 + 可一键重启。**"停 VPN 是唯一可靠的清除系统代理手段"**。
3. **端口冲突**：31181 可能被其他 app 占用（设备上少见但真实）；bind 失败要回退随机端口（`ServerSocket(0)` 取端口后再传给 tun builder）。
4. **VPN 进程被杀**：VPN 与 proxy 在同进程，进程死 → VPN 自动拆除、proxy 配置自动清除 ⇒ 天然 fail-safe（网络恢复），但用户需手动重启。可接受。
5. **`establish()` 返回 null**：必须走"未授权/被 revoke"错误分支，不能 NPE。

### 2.5 代理认证与 TLS

- `ProxyInfo` 直连代理无认证字段（`ProxyInfo` 公开构造只有 host/port/exclusionList；`buildPacProxy` 不支持）；系统代理的 407 认证依赖各 app 自己的 `Authenticator` 实现，**不可靠**。本地方案必须 **无认证 + 仅监听 127.0.0.1**（绝不能 bind 0.0.0.0）。
- 明文 HTTP 请求带绝对 URL 进代理；HTTPS 走 `CONNECT host:443` 隧道（默认不 MITM，TLS 由 app↔服务器端到端）。MITM 时需 CA（见 §4）。
- 127.0.0.1 上的明文 HTTP 对 **其他 app** 暴露：任何 app 都能把流量发到本地代理（本机代理无需沙箱权限）。风险低（仅本机、无认证、不监听外网），beta 接受；文档注明"不要在公共设备开启"。

---

## 3. DNS 拦截（DNS-only VpnService 分隧道）

### 3.1 方案与必需参数

- tun 配置（本项目架构）：
  - `addAddress("10.111.0.1", 32)`
  - `addDnsServer("10.111.0.2")`
  - `addRoute("10.111.0.2", 32)` ← **必须**，把发往"假 DNS 服务器"的包导入 tun
  - `setMtu(1500)`、`setBlocking(true)`（推荐）
- 系统解析器为全系统 app 提供 DNS 时会使用 VPN 网络的 DNS 服务器（这是 Blokada/AdGuard 等 DNS 型 adblock 的成熟模式）——**前提**是 VPN 是当前唯一/首选 VPN，且 Private DNS 未以主机名模式接管（§3.3）。
- 数据面实现：读 tun → 解析 IPv4+UDP → 目标必须 `10.111.0.2:53` → 解析 DNS wire → 引擎 `resolveRawDnsQuery()` → 构造响应 IPv4/UDP 包（**IP 头 + UDP 校验和都要算对**；UDP checksum 为 0 是合法的 IPv4"无校验"，但 Android 解析器可能验证，稳妥起见算全）→ 写回 tun。
- **源地址/端口**必须取原查询包的目的地址/端口做反转；IP 头 TTL 取合理值；`DF` 位随意。

### 3.2 数据面约束与坑（按风险排序）

1. **TCP DNS 回退**：若我们的响应超过客户端 EDNS0 UDP buffer 或设置了 `TC=1`，解析器会向 `10.111.0.2:53/TCP` 重试 → 我们只处理 UDP ⇒ **DNS 超时**。必须：解析查询的 EDNS0 OPT 字段取客户端 buffer（缺省 512），回答裁剪到该值内（A/AAAA 优先、丢弃 OPT/附加记录），**尽量不置 TC**；并在 JVM 单测覆盖"大 TXT/多 A 记录"用例。若确需支持 TCP 则加 TCP:53 处理（工作量约 0.5 天，beta 可不做但要保证不触发）。
2. **IPv6**：本项目只加 IPv4 地址/DNS，系统只会把 IPv4 DNS 包送进 tun，IPv4/UDP-only 解析可行；但**不要**在 tun 上同时加 IPv6 地址（会引入 IPv6 路由/包型，当前解析器不处理）。若未来目标网络是 IPv6-only，假 DNS IP 仍是 IPv4 内网地址，不依赖底层 IPv4，仍可用。
3. **EDNS0 透传**：应答里的 OPT 不应乱造；保留客户端的 version/DO 位语义要谨慎（DNSSEC 场景应用可能校验）。beta 可返回去掉 OPT 的普通应答（多数场景 OK），但要监控解析失败日志。
4. **DNS 报文的 0x20 随机化/压缩指针**：响应中域名可用压缩指针（指向 question），但**解析请求**时必须能处理压缩（部分客户端会压缩 question? 实际 question 不压缩，但上游答案可能压缩——那是引擎解析上游响应的事，不是 tun codec）。tun codec 只处理请求（question 通常不压缩），响应里安全起见用完整域名不压缩。
5. **MTU**：DNS over UDP 一般 < 512/1232，不涉及分片；`setMtu` 对 DNS-only 无实际影响，别为它花时间。
6. **性能/唤醒**：每条 DNS 查询一次 tun 读写 + 一次 DoH/上游查询；Android 解析器对同一域名并发查询较多，缓存（引擎侧 TTL cache）是必须的，否则耗电/延迟明显。
7. **不读 tun 的后果**：若读线程死掉，所有 app DNS 超时 → 全设备"没网"（浏览器/系统都受影响）。读线程必须永不阻塞在非 I/O 逻辑上；解析放到独立线程池 + 超时 2s 返回 SERVFAIL；读循环本身保持高频。

### 3.3 谁绕过 DNS 拦截（必须让用户知情/检测）

- **系统 Private DNS（Android 9+，设置→网络→私人 DNS）**：
  - 设为**主机名**（如 `dns.google`）时，系统解析器走 DoT 直连底层网络，**完全不经过 VPN DNS** ⇒ 我们的 DNS 防污染失效（但系统代理若生效，HTTPS 仍可能走代理）。
  - 设为**自动**：先探测 DoT，探测到我们的假 DNS 服务器不支持 853 会回落明文 → 通常仍被我们接管；不同 ROM 行为需真机验证。
  - **检测 API**：`ConnectivityManager.getLinkProperties(activeNetwork)?.isPrivateDnsActive` / `getPrivateDnsServerName()`（API 28+）。启动时若 active=true 且 serverName!=null：UI 强提示"私人 DNS 会绕过本应用的 DNS 拦截，请设为关闭/自动"。
- **应用自带 DoT/DoH**：Chrome 的 Secure DNS、Firefox 的 DoH、部分国产 app 的 HTTPDNS/自有 DoH ⇒ 解析绕过（连接层面是否走代理取决于该 app 是否遵循系统代理）。
- **硬编码 IP**（不少国产大厂 app 对核心域名硬编码）⇒ 连 DNS 都不查。
- **QUIC/HTTP3 的 DoQ**（少见）⇒ 不经过 DNS 拦截。
- **AP 侧 DNS**（移动数据网络的 DNS 由运营商下发；我们 VPN 生效时应被 VPN DNS 覆盖，但个别 ROM/企业 MDM 场景需验证）。

### 3.4 per-app VPN

- 用户在 设置→VPN→齿轮→"仅允许以下应用使用 VPN" 时，只有白名单 app 的 DNS 走我们；这是**用户可配置的正确行为**，不需要我们处理，但 UI 提示里要说明"若 DNS 不生效，检查是否设置了分应用 VPN"。
- 我们自身若 `addDisallowedApplication(ownPackage)`，自身出站（DoH/proxy 上游）不经过 tun；**DNS-only 方案下本来就不经过**，不必加；加了反而可能让自身系统 DNS 也走底层（这对"自举环"是好事，见 §3.5）。

### 3.5 DNS 自举环（本架构特有的隐患）

- 引擎的 DoH/DoT provider 需要解析自身服务器域名（如 `doh.pub`、`dns.alidns.com`）。**若用系统 resolver（`InetAddress.getByName`）解析这些域名，该查询会被我们自己的 VPN DNS 捕获** → 引擎收到自己的查询 → 需要 DoH → 又要解析 DoH 域名 → **死锁/超时**。
- 缓解（按优先级）：
  1. DoH/DoT provider **只用预置 IP 列表 / 配置里的 presetIps**（Dev-Sidecar 上游本身有 mapping/preSetIpList 可复用）；
  2. 用底层 Network 的 DNS 直接做 bootstrap：`ConnectivityManager.getLinkProperties(underlyingNetwork).dnsServers` + `Network.bindSocket` 发送 UDP 查询（不受 VPN 路由影响），或 `protect()` 后直连已知 IP；
  3. 引擎侧加"环检测"：若查询的域名 ∈ {DoH provider 域名} 且当前解析路径正在等待 bootstrap，立即用预设 IP 返回/超时短路径；
  4. 断网兜底：bootstrap 全失败时返回 SERVFAIL 并记录明确错误，UI 显示"上游 DNS 不可用"。

---

## 4. 用户 CA 与 TLS MITM

### 4.1 Android 7+ 的信任规则【已核实】

- 官方原文（Network Security Configuration 文档）："By default, secure connections (using protocols like TLS and HTTPS) from all apps trust the pre-installed system CAs, and apps targeting Android 6.0 (API level 23) and lower also trust the user-added CA store by default."
- 即：**targetSdk ≥ 24 的 app 默认不信任用户安装的 CA**；需要 app 自己在 `network_security_config.xml` 里加 `<certificates src="user"/>`（或 `debug-overrides`）。
- 系统证书库（`/system/etc/security/cacerts`）写入需要 root；用户 CA 只能进 user store。
- CA 证书要求（否则安装/校验失败）：`BasicConstraints CA:TRUE`、`keyUsage keyCertSign, cRLSign`、SHA-256 签名、有效期合理、DN 唯一。Android 11+ 安装用户 CA 要求设备已设锁屏；部分 ROM 强制"CA 证书仅用于 VPN 和应用"选择。

### 4.2 哪些 app/浏览器信任用户 CA

| 客户端 | 信任用户 CA？ | 证据/说明 |
| --- | --- | --- |
| **Chrome（Android）** | **✅ 是** | Chrome 自带 NSC：`<trust-anchors><certificates src="user"/><certificates src="system"/></trust-anchors>`（[源码](https://github.com/chromium/chromium/blob/main/chrome/android/java/res_base/xml/network_security_config.xml)）；仍建议真机复测（03-J5/M5） |
| 普通第三方 app（target 24+，无自定义 NSC） | ❌ 否 | Android 7 默认规则 |
| 普通 app 的 WebView | ❌ 默认否 | WebView 的 TLS 校验使用**宿主 app** 的 NSC；宿主不信任用户 CA 就会报证书错误 |
| 自己声明 `<certificates src="user"/>` 的 app | ✅ | 罕见；本文档测试 apk 可自行声明 |
| Firefox（Android） | **【待实测】** | Gecko 使用自带 NSS 信任库的历史行为与新版差异大，不能按"浏览器都行"假设；beta 文案不要承诺 |
| 微信/支付宝/银行 app | ❌（且多含 pinning） | MITM 会直接失败/报安全警告；绝不要尝试 |
| 系统组件/Play 服务 | 视 app 而定 | 不要依赖 |

**结论**：MITM 的可用面 = Chrome + 自选信任用户 CA 的 app（极少）。因此架构"MITM 默认关闭、作为增强项"是正确决策；UI 必须明确"开启 MITM 后：Chrome 等浏览器可用，多数 app 会证书错误"。

### 4.3 CA 安装流程

- **推荐（app 内一键）**：`KeyChain.createInstallIntent()`（API 14）→ `putExtra(KeyChain.EXTRA_CERTIFICATE, derBytes)` + `putExtra(KeyChain.EXTRA_NAME, "Dev-Sidebike CA")` → `startActivity`。系统弹出安装向导，用户确认后进 user store。
  - 【待实测】Android 11+/MIUI/HyperOS 上该 intent 是否被系统接受、是否要求锁屏/是否允许"VPN 和应用"用途；个别 ROM 会直接忽略 intent。
- **兜底（手工）**：`Settings.ACTION_SECURITY_SETTINGS` 或导出 `.crt`（DER 编码，`.cer`/`.crt` 扩展名）让用户从"设置→安全→加密与凭据→安装证书→CA 证书"安装；Android 13+ 路径为"安全→更多安全设置→加密与凭据"。
- 证书格式：`KeyChain.EXTRA_CERTIFICATE` 接受 **DER** 字节；我们的 CA 以 PEM 存储（core 生成），需转 DER 再传。安装后 alias 形如 `user:dev-sidebike-ca`。
- **检测是否已安装**（task-3 方案正确方向）：用我们自己的 CA 向本地 MITM 端点发起 TLS 握手（自签服务器证书由我们的 CA 签发），app 自身 NSC 声明信任 user CA → 握手成功即"已安装"。注意：**必须给 app 自己的 NSC 加 `<certificates src="user"/>`**，当前 `res/xml/network_security_config.xml` 没有，检测会永远失败。
- **MIUI/HyperOS 特有问题**【经验，需真机】：
  - 安装入口藏得深（设置→密码与安全→系统安全→加密与凭据→安装证书），部分版本只允许从"文件管理"选择文件；
  - 必须已设锁屏密码；部分版本安装 CA 会额外弹"你的数据可能被监控"警告；
  - MIUI 的"MIUI 优化/安全键盘/应用行为记录"可能拦截 cert install intent；
  - 用户 CA 在安全中心"信任的凭据"里可查；卸载 app 不会删除 CA，需要用户手动清除（UI 应引导）。

### 4.4 TLS MITM 的硬限制

- **证书钉扎（pinning）+ Certificate Transparency**：大量 app（尤其金融/Google 系）会校验叶子证书哈希/公钥哈希 → MITM 必然失败；Chrome 对 CT 有强制策略（CT 验证只对"公开信任的 CA"生效，用户 CA 不受 CT 约束是 Chrome 能用的原因之一，但 pinning 的网站仍可能失败）。
- **ECH（Encrypted Client Hello）**：越来越普及；MITM 无法看到真实 SNI，且上游若启用 ECH 则握手失败。Dev-Sidecar 的 SNI 伪装方案与 ECH 互斥（beta 明确非目标）。
- **QUIC/HTTP3**：MITM 只覆盖 TCP；Chrome 走代理时会禁用 QUIC，其他 app 可能仍用 QUIC 直连。
- **TLS 1.3 / 双向认证**：客户端证书场景（银行 U 盾等）无法 MITM；TLS 1.3 正常但需 Conscrypt 支持（Android 10+ 默认支持）。
- **上游 SNI 伪装**：Android 上用 `SSLSocketFactory.createSocket(rawSocket, fakeHost, 443, true)` 可让 SNI=伪装域名而 TCP 连真实 IP（需关闭 hostname 验证 + trustAll 或规则指定）；这是可行的，但必须在**真机**验证（JVM 通过不代表 Android Conscrypt 行为一致）→ 03-M4。
- **结论**：MITM 只对"信任用户 CA + 无 pinning + 非 ECH + 非 QUIC"的 HTTPS 生效；默认关闭，开启时明确 `danger` 提示。

---

## 5. 后台存活与省电（无 root 现实预期）

| 措施 | API | 作用 | 代价/限制 |
| --- | --- | --- | --- |
| 前台服务 + 常驻通知 | 26+ | 主要保活手段 | 必须通知；Android 13+ 要 POST_NOTIFICATIONS 权限才在通知栏显示 |
| always-on VPN（系统） | 24+ | 系统在开机/重连时拉起服务，最可靠 | 需用户在系统设置开启；被 kill 后系统会重拉（lockdown 下会断网提示） |
| 电池优化白名单 | 23+ | 避免 Doze/后台限制杀 | Play 政策限制（sideload 无碍）；MIUI 另有自己的省电策略 |
| 自启动白名单 | OEM | BOOT_COMPLETED 能拉起 | 用户手动开；不同 ROM 入口不同 |
| WakeLock | — | **不推荐** | 持续持锁耗电；DNS-only 不需要 |
| JobScheduler/WorkManager 守护 | 21+ | 被 kill 后尝试重启 | 重启 VPN 需用户/系统授权（always-on 才顺）；裸 Job 拉起 FGS 受 Android 12 限制 |
| 悬浮窗守护 | — | 部分国产 ROM 有效 | 需要 SYSTEM_ALERT_WINDOW，体验差，不建议 |

**beta 现实预期**：AOSP 类 ROM（Pixel/原生）白名单后基本稳定；小米需 自启动+无限制+锁后台，多数可稳定数小时到数天；华为/OPPO/vivo 更激进。**不要承诺"永久后台"**，提供"断线自动重连（有 always-on 时由系统拉起）"和"打开 app 自动恢复"。

---

## 6. Native ABI 打包（当前与未来）

- **当前**：后端纯 Kotlin/JVM，BouncyCastle 也是纯 Java ⇒ **APK 不含 `.so`，与 ABI 无关**，一个 APK 覆盖 arm64-v8a / armeabi-v7a / x86 / x86_64，模拟器（x86_64）也能直接装。
- `app/build.gradle.kts` 里的 `ndk { abiFilters += listOf("arm64-v8a","armeabi-v7a") }` 在**没有 native 库时是空操作**：它不会限制纯 Java 代码的 ABI，也不会阻止 x86_64 模拟器运行；将来引入 `.so` 时它才生效。到时注意：
  - 若要支持模拟器/Chromebook：加 `x86_64`、`x86`；
  - 用 App Bundle 时 ABI 拆分由 Play 处理；直接 APK 分发用 `splits.abi` 或保持 universal APK（体积增大）；
  - 若走 Plan B（userspace TCP 栈，C/Go/Rust 实现），ABI 矩阵会立刻变成发布负担（4 个 ABI + 可能的 NDK 版本问题）。
- **影响**：无 `.so` 是目前 7 小时 beta 的关键优势；**Plan B 会破坏该优势**，除非纯 Kotlin 写 TCP 栈（工作量与风险都极高，7h 不现实）。

---

## 7. 无 root 在 Android 上"做不了/做不好"的清单（诚实边界）

| 桌面 Dev-Sidecar 能力 | Android 无 root 可行性 | 原因 |
| --- | --- | --- |
| 修改系统全局 HTTP/HTTPS 代理 | 部分（仅 API 29+，且仅 VPN 生命周期内） | 需要 `WRITE_SECURE_SETTINGS` 才能写全局设置；VPN `setHttpProxy` 是唯一正规途径 |
| 让**所有** app 的 TCP/UDP 都经过代理（透明代理） | ❌ | 需要 VpnService 全隧道 + userspace TCP/IP 栈（Plan B）或 root iptables/TPROXY |
| 为**所有** app 安装受信任 CA | ❌ | 系统证书库需 root；用户 CA 只对 target<24 或显式 NSC 信任的 app 生效 |
| 改写 hosts 文件 | ❌ | 只读 |
| 抓包/重定向其他 app 的 localhost 流量 | ❌ | 回环流量不进 VPN，也无权限 |
| 按进程/UID 分流、socket mark、DNAT | ❌ | 需要 root/netfilter |
| 修改全局 Private DNS 设置 | ❌ | `WRITE_SECURE_SETTINGS` |
| SNI 级分流/伪装而**不** MITM | ❌ | 需要在 TLS 握手层改写，等价于 MITM |
| 静默安装 CA、静默改代理、开机无感启动 | ❌ | 全部需要用户确认（Android 权限模型） |
| 保持永久后台 | ❌（不保证） | 厂商电源管理/Doze |
| 修改其他 app 的 NSC/信任 | ❌ | app 沙箱 |
| ECH / QUIC 内部解密 | ❌ | 协议层不可行（QUIC 需 MITM 化 UDP，属 Plan B 范畴） |

**推论**：Android 版 Dev-Sidecar 的定位应写成"**DNS 防污染（系统级）+ 系统代理层加速/MITM（API 29+，仅遵循系统代理的 app）**"，与桌面版的"系统级全流量代理"不同；这不是实现缺陷，是平台边界。

---

## 8. 引用

- VpnService / Builder：https://developer.android.com/reference/android/net/VpnService ，https://developer.android.com/reference/android/net/VpnService.Builder
- VPN 开发指南（含 always-on、protect、onRevoke）：https://developer.android.com/develop/connectivity/vpn
- FGS types（含 specialUse/systemExempted 条件）：https://developer.android.com/develop/background-work/services/fgs/service-types
- FGS 后台启动限制与例外：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 14 行为变更（FGS 类型强制）：https://developer.android.com/about/versions/14/behavior-changes-14
- Android 15 行为变更（BOOT_COMPLETED FGS 限制）：https://developer.android.com/about/versions/15/behavior-changes-15
- Android 16 行为变更（Local Network Protection）：https://developer.android.com/about/versions/16/behavior-changes-16
- Doze/App Standby：https://developer.android.com/training/monitoring-device-state/doze-standby
- ProxyInfo：https://developer.android.com/reference/android/net/ProxyInfo
- LinkProperties（Private DNS）：https://developer.android.com/reference/android/net/LinkProperties
- Network Security Configuration / 用户 CA：https://developer.android.com/privacy-and-security/security-config
- Android 7 TLS 默认值变更：https://developer.android.com/about/versions/nougat/android-7.0-changes#tls-defaults
- KeyChain（createInstallIntent）：https://developer.android.com/reference/android/security/KeyChain
- ABI 指南：https://developer.android.com/ndk/guides/abis ；ABI split：https://developer.android.com/build/configure-apk-splits
- AOSP：`frameworks/base/core/java/android/net/VpnService.java`、`.../net/Proxy.java`、`.../net/Vpn.java`（android.googlesource.com）
- Chromium：`net/proxy_resolution/proxy_config_service_android.cc`、`chrome/android/java/res_base/xml/network_security_config.xml`、`net/android/java/src/org/chromium/net/X509Util.java`（source.chromium.org / github.com/chromium）
- 本地 SDK 证据：`tools/android-sdk/platforms/android-35/data/api-versions.xml`（`VpnService$Builder.setHttpProxy since=29`、`setMetered since=29`、`VpnService.isAlwaysOn/isLockdownEnabled since=29`、`LinkProperties.isPrivateDnsActive since=28`、`Builder.addAllowedApplication/addDisallowedApplication/allowBypass/setBlocking since=21`、`setUnderlyingNetworks since=22`、`excludeRoute since=33`、`addAddress/setMtu/establish since=14`）
