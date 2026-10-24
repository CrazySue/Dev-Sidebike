# 03 · 对抗性评审清单与 Go/No-Go（Plan A / Plan B）

> 作者：challenger（独立 QA/怀疑论者）。日期：2026-10-25。
> 评审对象快照：`docs/architecture/01-architecture.md`、`docs/architecture/02-core-contracts.md`、shared task T3/T5/T6 描述、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/network_security_config.xml`、`app/src/main/java/dev/sidebike/app/vpn/{IpPacket,UdpDnsCodec}.kt`（T3 进行中）、上游 `reference/dev-sidecar`。
> 本文原则：**每条风险必须配一个"能失败的检测"或"明确缓解"**；没有检测手段的结论一律降级为"待实测"。

## 0. 总裁决

- **Plan A（DNS-only VpnService + API 29+ `setHttpProxy` 本地代理）：Conditional GO** —— 条件是通过 §5 的 P0 gate。当前证据显示它在"DNS 系统级防污染"上高置信可行；"系统级 HTTP 代理"受 3 个未闭环变量制约（API 29+ 门槛、proxy 下发行为、应用覆盖度），必须以 §4 的实验结果决定 beta 宣传口径。
- **Plan B（全隧道 userspace TCP 栈）：7 小时窗口内 NO-GO**。它要解决 TCP 状态机/拥塞/重传、IPv4+IPv6、UDP/QUIC、MTU/PMTUD、NAT 表、性能与功耗、native ABI（C/Go/Rust + NDK）、socket protect 自环——任何一项做错都是"全设备断网"。不要为了"所有 app 生效"在 beta 引入它；把接口留好（`ProxyServer`/`DnsResolver` seam 已够）即可。
- **最大风险不是代码写不出来，而是"写完了，Chrome 不走代理 / 用户开了 Private DNS / 小米把服务杀了"三类环境问题**。所以 QA 的最高优先任务是尽早证伪 Plan A 的核心假设（§4-M1/E2），而不是等 dev 完成后再测。

## 1. Plan A 假设逐条对抗

| # | 假设（架构中的隐含承诺） | 证据强度 | 判定 | 若不成立的后果 | 检测实验 |
| --- | --- | --- | --- | --- | --- |
| A1 | `setHttpProxy` 可用（API≥29） | **已核实**（本地 SDK api-versions.xml：since=29） | ✅ 成立（minSdk 26 需降级） | API 26–28 无系统代理 | §4-S1（静态断言 API guard） |
| A2 | VPN 只路由 DNS /32 时，系统仍把 VPN 的 HTTP proxy 下发到全局 | 【待实测】官方 note 暗示"proxy 对所有 app 生效、与路由无关"，但"VPN 非默认网络是否下发 proxy"无源码闭环 | ⚠️ **单点前提** | Chrome 等仍直连：产品核心价值坍塌，只剩 DNS | **M1**（owner 手机）/ **E1**（模拟器 `dumpsys connectivity` + proxy 日志） |
| A3 | 系统解析器在 VPN 开启时把**所有 app** 的 DNS 发进 tun | 高置信（DNS 型 adblock 成熟模式）+ 官方文档 | ✅ 成立（需无 Private DNS 主机名） | DNS 防污染不生效 | **E2**（模拟器：`tun` 收到 query）+ **M2** |
| A4 | 127.0.0.1 本地代理在 VPN 开启时仍被其他 app 可达 | 高置信（回环流量不进 VPN；官方 note 警告的是"代理只在 VPN 网络内可达"场景） | ✅（但需 E1 顺带验证） | proxy 全部失败 | **E1**（proxy access log 出现其他 app 的 CONNECT） |
| A5 | 遵循系统代理的 app 覆盖度足够支撑 beta | 部分（java.net/OkHttp/Chromium ✅；Flutter/原生/QUIC/DNS 自解析 ❌） | ⚠️ 部分成立 | 用户拿微信/抖音测会认为"没用" | **J2**（本地代理协议面）+ **E1**（Chrome）+ 文档明示边界 |
| A6 | MITM 默认关闭时 DNS+直连仍能改善 GitHub/镜像类体验 | 取决于 GFW 实际策略：仅 DNS 污染时有效；SNI 阻断/QUIC 时需要 MITM/代理 | ⚠️ 部分成立（架构已注明"本机实测 GitHub 核心域可用"） | beta 价值缩水，必须把 MITM 作为可选增强 | **M3**（真机对镜像域名的 A/B 测试） |
| A7 | Android 上可做 SNI 伪装（`SSLSocket` over raw socket with fake SNI） | 【待实测】JVM 可行；Android Conscrypt 行为需验证 | ⚠️ 未验证 | MITM 的核心用途（GitHub 加速）失效 | **J3**（JVM 可行性）+ **M4**（真机） |
| A8 | 服务在后台/开机能被可靠拉起 | always-on 系统路径可靠；普通 FGS 受 OEM/Doze 影响 | ⚠️ 部分成立 | 用一会儿就断 | **E3**（模拟器）+ **M5**（小米真机） |
| A9 | 前台服务可稳定启动（target 35 + FGS type） | `systemExempted` 有运行时条件，首次启动可能抛异常（见 R1） | ❌ **当前实现有崩溃风险** | 首次启动崩溃 | **S1/S2** 静态 + **E3** 实跑 |
| A10 | VPN 非 metered | target 29+ 默认 metered（文档明示），当前代码未 `setMetered(false)` | ❌ 当前缺失 | 应用限流/Play 暂停更新，用户感觉"代理后网络变差" | **S1** 静态 + **E3** `dumpsys connectivity` 查看 metered |
| A11 | DNS 应答不会触发 TCP:53 回退 | 当前 tun 只处理 IPv4/UDP，无 EDNS/TC 处理 | ❌ **未处理** | 特定域名 DNS 超时（解析器 TCP 重试无人应答） | **J1**（大响应裁剪/TC 单测）+ **E2** |
| A12 | 引擎不会 DNS 自举环 | Lead 决策：provider 只用字面量 IP，VPN 只路由 10.111.0.2/32，引擎上游 socket 不入 tun | ✅（需静态审计确认"无 `InetAddress.getByName` 解析 provider 域名"） | 死锁/整体解析慢 | **S3**（静态扫描 core/dns 与 core/proxy 的域名解析调用） |
| A13 | 用户环境无干扰（Private DNS / 其他 VPN / lockdown） | Private DNS 主机名模式直接废掉 DNS 拦截；lockdown + 分隧道 = 全断网；其他 VPN 互斥 | ❌ 当前无检测/引导 | 用户"装了没用"/"装了断网" | **S4**（静态：检测调用存在）+ **E4**（模拟器切换 Private DNS/lockdown）+ **M6** |
| A14 | 死代理 fail-safe | 当前架构未定义 | ❌ 缺失 | Chrome 能解析但打不开，用户以为网络坏了 | **J4**（故障注入单测）+ **E5**（模拟器 kill proxy 线程） |
| A15 | 无设备环境下也能验收 | 本工作区无手机、SDK 无 system image（已确认 `adb devices` 为空） | ❌ 验收依赖模拟器/owner 手机 | beta 交付无 E2E 证据 | **E 系列 + M 系列**（§4/§6） |

**结论**：A2/A5/A6/A9/A11/A13/A14 是必须在开发早期被证伪或补齐的假设；A9/A10/A11/A13/A14 是**当前代码/设计缺口**，其中 A9 会直接崩溃。

## 2. Plan B（全隧道 userspace TCP/IP）评估

| 维度 | Plan A（当前） | Plan B（全隧道） |
| --- | --- | --- |
| 生效范围 | DNS 全系统；HTTP 代理仅遵循系统代理的 app | 全部 IPv4 TCP（+UDP 需额外处理 QUIC/DNS） |
| 核心实现 | tun 里只解析 DNS 报文（已写 2 个 codec 文件） | 完整 TCP 状态机（握手/窗口/重传/拥塞/RST/FIN）、IPv4/IPv6、UDP、NAT 表、DNS 转发、QUIC 直通决策 |
| 依赖 | 纯 Kotlin（零 .so） | 推荐 C/Go/Rust（hev-socks5-tunnel / tun2socks / badvpn）⇒ NDK、4 ABI、构建时间 |
| 7h 可行性 | 中高（风险集中在平台行为） | **低**（工作量 ≥ 数天；调试一次全设备断网就可能吃掉半天） |
| 失败影响 | 代理不生效 → 功能缩水 | 栈写错 → 用户全设备断网（灾难级） |
| 建议 | beta 采用 | 明确非目标；保留接口 seam，beta 后评估 |

**若 A2 被证伪的 fallback 优先级**：
1. **F1：内置 WebView 浏览器**（`androidx.webkit.ProxyController` 设置 WebView 级代理，绕过系统代理限制）——把"镜像加速/改写"能力放进自有 WebView，可复用同一 `ProxyServer`，UI 成本可控（约 +2h）。这是最现实的止损方案。
2. **F2：Shizuku/adb 设置 `global http_proxy`**（有 `WRITE_SECURE_SETTINGS` 能力的用户可全系统代理）——面向开发者用户，UI 引导成本高。
3. **F3：Plan B**（不在 7h 内）。

## 3. 失败场景矩阵（触发 → 现象 → 检测 → 缓解）

| ID | 触发 | 用户现象 | 检测 | 缓解（必须落地） |
| --- | --- | --- | --- | --- |
| F-01 | Private DNS=主机名 | 打开网页仍被污染/域名解析异常 | 启动时 `LinkProperties.isPrivateDnsActive()`+`getPrivateDnsServerName()` | 强提示切"关闭/自动"；状态页标红"DNS 拦截已绕过" |
| F-02 | 系统代理未生效（A2 假） | Chrome 仍连不上被墙站，app"看起来正常" | proxy 日志无任何 CONNECT；`dumpsys connectivity` 看 VPN LinkProperties 是否带 proxy | 降级 UI + F1 内置浏览器；README 边界 |
| F-03 | proxy 未就绪就 establish | 启动瞬间所有网页打不开 | 启动顺序代码审计 + `CONNECT` 自检失败日志 | proxy 先起 + 自检 + 失败不 establish |
| F-04 | proxy 线程死 | DNS 正常但网页打不开 | watchdog 每 3–5s 健康检查 | 连续失败 → `stop VPN`（系统清 proxy）→ 报错可重启 |
| F-05 | 端口 31181 被占 | 启动失败/代理不可用 | bind 异常 | 回退 `ServerSocket(0)` 随机端口 |
| F-06 | EDNS 大响应（TXT/DNSSEC） | 特定站点 DNS 超时 | J1 单测 + 日志"truncated/" | 裁剪答案到客户端 buffer，不置 TC |
| F-07 | 用户开 lockdown | 除 DNS 外全部断网（"开了就没网"） | `VpnService.isLockdownEnabled()`（API 29+） | 拒绝启动 + 引导关闭；UI 红字 |
| F-08 | 其他 VPN（Clash/v2rayNG）在运行 | 我们的 VPN 顶掉对方 或 被对方顶掉 | 启动前 `getAllNetworks` 查 `TRANSPORT_VPN` | UI 提示"仅允许一个 VPN" |
| F-09 | MIUI/HyperOS 杀后台 | 用几分钟后断开 | 真机日志/通知消失 | 自启动+无限制引导（§02 §1.5）；always-on |
| F-10 | always-on + 未解锁启动读不到配置 | 开机后 VPN 无法连接（lockdown 下无网） | 真机 always-on 测试 | 最小默认配置/device-protected storage |
| F-11 | FGS type 不匹配/条件不满足 | 首次启动崩溃 `ForegroundServiceTypeNotAllowedException` | logcat 崩溃栈 + 静态审计 | 改 `specialUse` 或双 type + catch 降级 |
| F-12 | CA 安装后检测不到 | UI 永远显示"未安装" | app 自身 NSC 是否含 user 信任 | NSC 加 `<certificates src="user"/>`；检测用自签 TLS 握手 |
| F-13 | 用户用微信/抖音测 | "没用" | proxy 日志只有 Chrome/OkHttp 的请求 | 文档/首启引导明示能力边界 |
| F-14 | DNS 自举环（引擎解析 provider 域名） | 启动后 DNS 全部超时 | 静态扫描 `InetAddress.getByName`/`URL(host)` | 只用 bootstrapIps；断网兜底 SERVFAIL |
| F-15 | 无网络时启动 | 通知/状态混乱、崩溃 | 飞行模式测试 | 状态机 ERROR + 可重试；不 crash |

## 4. MUST-VERIFY 实验清单（按优先级；标注可执行环境）

> 时间盒：每条标 ≤ 预计耗时。"JVM"=本工作区可做；"E"=Android 模拟器（x86_64）；"M"=owner 手机（交付他执行）。

### P0（beta 前必须闭环，按顺序）

**J1. DNS 报文对抗单测（JVM，≤45min，QA 可独立完成）**
- 目标：`UdpDnsCodec`/`IpPacket` 在 EDNS0、超大响应、畸形包下不出错且**永不触发 TCP 回退**。
- 用例：EDNS0 OPT（UDP size 512/1232/4096）、响应 > buffer 时裁剪且 TC=0、QdCount=0/2、question 压缩指针、超长域名(255)、0 长度 label、截断包、非 DNS payload、IPv6 包、TCP 包、UDP checksum=0、IP options、IP 分片（应丢弃）。
- 通过标准：全部通过；**任何用例出现 TC=1 即失败**（除非同时实现 TCP:53）。

**J2. 本地 HTTP 代理协议对抗（JVM，≤60min）**
- CONNECT/绝对 URI/chunked/CL+TE 冲突/超长头/slowloris 超时/host 含 userinfo/非法端口/并发 50 连接/半关闭。
- 通过：不泄漏线程、不 OOM、错误请求返回 4xx 而非崩溃；连接超时生效。

**J3. SNI 伪装可行性（JVM，≤45min）**
- 用 `SSLSocketFactory.createSocket(rawSocket, "baidu.com", 443, true)` 连本地 TLS server（记录收到的 SNI），断言 SNI=baidu.com、TCP 目标=真实 IP（本地模拟）。此测试证明"JVM 侧 API 路径"；Android 侧仍需 M4。

**J4. 故障注入（JVM/Robolectric，≤45min）**
- 模拟 proxy `ServerSocket` 被关闭/线程崩溃/端口占用，断言控制器进入错误态、调用 `stopVpn` 路径、可重启（用 fake VpnService/controller 接口）。

**S1. 静态审计（PowerShell，≤30min，QA 独立）**
- `minSdk`/`targetSdk`；`setHttpProxy` 调用处有 `Build.VERSION.SDK_INT >= Q` 保护；manifest：`foregroundServiceType`、FGS 权限、`SUPPORTS_ALWAYS_ON` meta-data、`BIND_VPN_SERVICE`、`exported`、`POST_NOTIFICATIONS`；`setMetered(false)`、`isLockdownEnabled()`、`isPrivateDnsActive()` 调用存在；NSC 含 `<certificates src="user"/>`；无 `bind("0.0.0.0")`/`InetAddress.getByName` 于 dns/proxy 关键路径（白名单注释）；`permission.INTERNET` 存在。

**E1. 模拟器 Plan A 核心闭环（≤45–60min，最高价值）**
- 前置：模拟器（google_apis_playstore x86_64 API35）启动，`adb install` debug APK。
- 步骤：`adb shell settings get global http_proxy` 基线 → 启动 app（adb 点击 VPN 授权）→ `adb shell dumpsys connectivity | grep -iE "proxy|VPN|LinkProperties"` 检查 VPN LinkProperties 是否带 `Proxy: [127.0.0.1:31181]`；**打开 Chrome 访问 https://example.com，检查 app 日志出现 `CONNECT example.com:443`**。
- 通过：`dumpsys` 有 proxy 且日志有 CONNECT 且 Chrome 页面成功。失败：记录 A2 被证伪 → 立即转 F1。

**E2. DNS 拦截端到端（≤30min）**
- 关闭 Private DNS；用 `adb shell` 发 `nslookup`（若无则用 `ping` 域名）或让 Chrome 打开站点；检查 app 日志收到 tun DNS 查询并返回；`adb logcat` 无 DNS 超时。
- 通过：日志有 query；浏览器解析正常；`dumpsys connectivity` 显示 VPN DNS = 10.111.0.2。

**S2. 构建与既有测试（≤15min，随进度反复跑）**
- `_script/gradle.ps1 :core:test :app:testDebugUnitTest :app:assembleDebug` 全绿；APK 可用 `apkanalyzer` 检查 minSdk/targetSdk/权限。

### P1（beta 前尽量闭环）

**E3. 前台服务与锁定模式（≤30min）**
- 启动后 `adb shell dumpsys activity services dev.sidebike.app` 看 FGS type/通知；`adb shell am force-stop` 后 always-on 行为；`adb shell settings put global` 改 Private DNS（模拟器可 `adb shell settings put global private_dns_mode hostname` + `private_dns_specifier dns.google`）验证 app 检测/警告；`device_config`/`cmd vpn` 开 lockdown 模拟（若可用）。
- 通过：服务不被杀；检测提示出现；lockdown 下 app 拒绝启动而非断网。

**E4. CA 安装 + Chrome MITM（≤45min）**
- app 内触发 `KeyChain.createInstallIntent`，模拟器上用 adb 点击安装（可能需要锁屏密码设置：`adb shell locksettings set-pin 1234`）；Chrome 访问被拦截域名，验证 MITM 生效（或至少显示我们 CA 签的证书）。
- 通过：安装成功、Chrome 无证书错误、代理日志有 MITM 记录。失败：记录 ROM/版本差异（owner 手机可能不同）。

**E5. 故障注入（模拟器，≤20min）**
- 提供 debug 入口"kill proxy"→ 断言 VPN 被停、网络恢复、UI 报错。

**J5. `check_requirements.ps1` 静态断言（QA 自己写，≤40min）**：把 S1 自动化成脚本（见任务 T7）。

### P2（beta 后/时间允许）

**M1–M6. owner 手机验证清单**（写入 `_script/qa/device_verify.md` 或 `docs/qa/device-verify.md`，由主人执行）：
- M1 系统代理生效（Chrome CONNECT 出现在日志/抓包）；M2 DNS 拦截（Private DNS 关闭/自动）；M3 镜像域名 A/B 效果；M4 SNI 伪装真机；M5 后台存活 30min + 锁屏 + 切换网络；M6 lockdown/其他 VPN/未知来源 CA 安装路径（MIUI/HyperOS）。

## 5. Beta Go/No-Go Gate（发布/交付前逐项打勾；任一 P0 失败 = 停止交付）

| Gate | 内容 | 通过标准 | 阻塞级别 |
| --- | --- | --- | --- |
| G1 | 构建 | `:app:assembleDebug` + `:core:test` + `:app:testDebugUnitTest` 全绿 | P0 |
| G2 | 安装启动 | 模拟器安装、冷启动进首页、无崩溃（logcat FATAL 0） | P0 |
| G3 | VPN 启动 | 通知出现、状态 RUNNING、`dumpsys` 显示 VPN network + DNS=10.111.0.2 | P0 |
| G4 | 系统代理闭环 | E1 通过（VPN LinkProperties 带 proxy 且 Chrome 请求出现在代理日志） | **P0/PIVOT**（失败→F1 或改口径） |
| G5 | DNS 闭环 | E2 通过；Private DNS 主机名时 UI 有红色警告 | P0 |
| G6 | 停止/失败安全 | 停止后网络立即恢复；kill proxy → VPN 自动停 + 报错可重启 | P0 |
| G7 | 前台服务合规 | 不抛 `ForegroundServiceType*` 异常；通知常驻；POST_NOTIFICATIONS 引导 | P0 |
| G8 | 边界诚实 | UI/README 明示：哪些 app 不生效、MITM 默认关、API<29 无系统代理、lockdown 提示 | P0 |
| G9 | MITM（如开启） | CA 安装流程可用 + Chrome 单域名 MITM 成功；默认关闭 | P1 |
| G10 | 后台存活 | 模拟器锁屏 10min 服务存活；小米真机 30min（M5） | P1 |
| G11 | 性能 | 启动 <3s（首页可用）、DNS P50 <100ms（含上游）、空闲 CPU ~0、连续 30min 无内存增长 | P1 |
| G12 | 静态合规 | `check_requirements.ps1` 全绿；无明文密钥/CA 私钥泄漏到日志；代理仅监听 127.0.0.1 | P0 |

**Stop conditions（任一触发即停止"全系统代理"宣传口径）**：
1. E1/G4 在模拟器与 owner 手机均失败 → 切 F1 内置浏览器或把 beta 定位改为"DND 防污染 + 有限代理"。
2. G6 失败（proxy 死后无法恢复网络）→ 必须修到通过，否则用户断网事故概率高。
3. G7 失败（首次启动崩溃）→ 阻塞交付。
4. 出现 `ForegroundServiceTypeNotAllowedException`/`SecurityException` 且无法用 specialUse 规避 → 降级为"用户点开 app 时才运行"。

## 6. 风险登记册（Top 10，按 风险=概率×影响 排序）

| # | 风险 | 概率 | 影响 | 缓解 | 负责人/实验 |
| --- | --- | --- | --- | --- | --- |
| R1 | FGS `systemExempted` 首次启动抛异常/崩溃 | 高 | 高 | 改 `specialUse` 或双 type + catch 降级 | android-dev / S1、E3 |
| R2 | A2 假：VPN proxy 未下发/应用不遵循 → Chrome 不走代理 | 中 | **极高** | E1 尽早证伪；F1 内置 WebView；文档口径 | challenger / E1 |
| R3 | Private DNS/DoH/QUIC 绕过，用户以为防污染无效 | 高 | 中 | 检测+引导+状态显示 | android-dev / S4、E3 |
| R4 | EDNS/TC 导致特定 DNS 超时 | 中 | 中 | 裁剪答案不置 TC + J1 | T3/T5 / J1 |
| R5 | proxy 死/端口冲突/未就绪 → "能解析但网页打不开" | 中 | 高 | 自检+watchdog+端口回退 | T3/T5 / J4、E5 |
| R6 | lockdown / 其他 VPN / always-on 互斥导致断网 | 中 | 高 | 检测+拒绝+引导 | android-dev / E3、M6 |
| R7 | MIUI/HyperOS 杀后台 | 高 | 中 | 引导自启动/无限制 + always-on | android-dev / M5 |
| R8 | MITM 覆盖极窄（大多 app 不信任用户 CA）被当作"功能"宣传 | 中 | 中 | 默认关闭 + 明确文案 | lead / G8 |
| R9 | 无设备/无 instrumentation 测试 → 交付缺少 E2E 证据 | 高 | 中 | 模拟器 E 系列 + owner M 系列 + 录屏/截图 | challenger / E 系列 |
| R10 | APK/构建在纯离线环境不稳定（依赖/镜像） | 中 | 中 | check_apk.ps1 静态校验 + 产物 sha256 | challenger / S2 |

## 7. 对当前代码/文档的立即修改建议（按文件，P0 优先）

1. `app/src/main/AndroidManifest.xml`：
   - `foregroundServiceType` 改 `specialUse`（加 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" .../>`），或保留 `systemExempted` 同时加 `specialUse` 并用 `try/catch` 选择 type；
   - 加 `<meta-data android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON" android:value="true"/>`（若要 always-on）；
   - 确认 `POST_NOTIFICATIONS` 的运行时请求有 UI 入口。
2. `app/src/main/res/xml/network_security_config.xml`：`<base-config cleartextTrafficPermitted="true"><trust-anchors><certificates src="system"/><certificates src="user"/></trust-anchors></base-config>`（CA 安装自检必需）。
3. `docs/architecture/01-architecture.md` §3.1：统一 `setBlocking` 决策（建议阻塞读线程）；补 `setMetered(false)`；补 fail-safe 顺序（proxy 先起+自检→establish）；补端口回退；补 `isLockdownEnabled()`/Private DNS 检测步骤。
4. T3 描述："tun 只解析 IPv4/UDP"需补 EDNS0 剪裁规则（永不置 TC）与 DNS 自举"禁止系统 resolver"约束的验收测试。
5. T5/T6：`ProxyServer`/`DnsResolver` 契约加"health/readiness"查询与"shutdown 后连接清理"保证（fail-safe 依赖）；DoH/DoT provider 必须只用 bootstrapIPs（静态审计项）。
6. QA：把 §4 做成脚本/文档落盘（`_script/qa/check_requirements.ps1`、`_script/qa/device_verify.md`、`docs/qa/`）。

## 8. 引用

同 02 文档 §8（Android 官方文档 / AOSP / Chromium / 本地 SDK）。关键补充：
- `VpnService.Builder.setHttpProxy` API 29 与 split-tunnel note：https://developer.android.com/reference/android/net/VpnService.Builder
- Chromium 系统代理：`net/proxy_resolution/proxy_config_service_android.cc`（source.chromium.org）
- Chrome 用户 CA：`chrome/android/java/res_base/xml/network_security_config.xml`（github.com/chromium/chromium）
- `LinkProperties.isPrivateDnsActive` API 28：https://developer.android.com/reference/android/net/LinkProperties
- FGS type 条件：https://developer.android.com/develop/background-work/services/fgs/service-types
- 本地 SDK：`tools/android-sdk/platforms/android-35/data/api-versions.xml`
