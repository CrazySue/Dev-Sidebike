# Dev-Sidebike 可行性验证总结（Go/No-Go 决策）

- 日期：2026-10-25（项目启动 ~35 分钟完成）
- 作者：Lead（基于 analyst T1 上游逆向、challenger T2 独立复核、Lead 本机实测）
- 证据文档：
  - [`01-dev-sidecar-analysis.md`](01-dev-sidecar-analysis.md)：上游源码逐文件分析（含行号）、运行实例只读实测
  - [`02-android-constraints.md`](02-android-constraints.md)：Android 8.0–16 平台约束（API level + URL 证据）
  - [`03-review-checklist.md`](03-review-checklist.md)：Plan A 15 条假设对抗、失败矩阵、Go/No-Go gate
  - [`../architecture/01-architecture.md`](../architecture/01-architecture.md)：架构决策与修订记录

---

## 0. 总裁决

| 方案 | 裁决 | 理由 |
| --- | --- | --- |
| **直接运行上游 Node/Electron 后端** | **NO-GO** | Android 无 Node 运行时；nodejs-mobile 停更、ABI/体积/生命周期不可控；上游强依赖 Electron 的 `fork`、系统代理/系统 CA 脚本、`checkServerIdentity`、动态 `lookup` 钩子、HTTP/2 假服务器与 ECH 客户端，Android 无等价物。 |
| **Plan A：纯 Kotlin 重写后端 + DNS-only VpnService 分隧道 + API29+ `setHttpProxy`** | **Conditional GO** | DNS 防污染路径高置信可行（DNS 型 adblock 成熟模式 + 本机实测）；系统级 HTTP 代理受"API29+ / proxy 下发行为 / 应用覆盖度"三个变量制约，已安排模拟器 + owner 手机实验闭环（E1/M1）。 |
| **Plan B：VpnService 全隧道 + userspace TCP/IP 栈** | **NO-GO（beta 内）** | 需完整 TCP 状态机/重传/拥塞/IPv6/UDP/QUIC/MTU/性能/原生 ABI，7 小时内做不扎实；一旦写错 = 用户全设备断网（灾难级）。接口 seam 已保留，beta 后评估。 |
| **前端直接移植 Vue/Ant Design GUI** | **NO-GO** | 与"严格 MD3"硬性要求冲突；改用 Jetpack Compose + Material3 全量重写。 |

**止损方案（若 E1 证伪"系统代理生效"）**：F1 —— 内置 WebView 浏览器（`androidx.webkit.ProxyController` 显式代理），把镜像/改写能力收进自有浏览器，可复用同一 `ProxyServer`；同时把 beta 宣传口径改为"DNS 系统级防污染 + 有限系统代理"。详见 `03-review-checklist.md` §2。

---

## 1. 关键事实澄清（影响产品表述，必须纠正）

项目 owner 的初始假设是"Dev-Sidecar 利用 HTTPS 的 SNI 欺骗漏洞"。**源码 + 运行实例实测证明该表述不准确**：

1. **Dev-Sidecar 没有本地 DNS 服务器**（不监听 53/UDP、不改系统 DNS）。"DNS 防污染"发生在代理进程内部：为每个上游连接自行选择 DNS provider（预置 IP / UDP / DoH / DoT）解析并直连。运行实例只监听 `127.0.0.1:31181`（HTTP 代理 + HTTPS CONNECT 共用单端口）+ 若干 `127.0.0.1` 随机端口（MITM 假 TLS 服务器）。
2. 其核心手段是三套机制的**组合**：
   - **诱饵 SNI（decoy SNI）规避 SNI 阻断**：规则表把上游 ClientHello 的 SNI 改写成国内可访问的诱饵域名（如 `github.com → baidu.com`、`*.googleapis.com → www.google.cn`），并关闭这些连接的上游证书校验（实测响应头 `DS-Interceptor: sni: baidu.com, unVerifySsl`）。目标边缘按默认证书响应即可握手成功。**这不是"漏洞利用"**，且需要 MITM 才能实施（客户端先被本机 CA 接管）。
   - **DoH/DoT/预置 IP + 测速选优**规避 DNS 污染（实测 `DS-DNS: PreSet: 20.27.177.113`、`DS-DNS: safe360: ...`）。
   - **ECH（Encrypted Client Hello）** 对支持域名加密 SNI（实测 `https://x.com/` 返回 `DS-ECH: 1`）。
3. 因此本项目的准确表述是：**"Android 版开发者边车：DNS 防污染 + 诱饵 SNI/MITM 增强 + 严格 MD3 前端"**。README、UI 文案、交付说明必须使用此口径，禁止写"利用 SNI 漏洞"。

---

## 2. 本机实测证据（2026-10-25，无 VPN、经本机 Dev-Sidecar 对照）

`_script/experiments/sni_probe.mjs`（DoH 干净解析后直连 TLS，不走本地代理）：

| 域名 | DoH 解析 IP | 直连 TLS（真实 SNI） | 结论 |
| --- | --- | --- | --- |
| github.com | 20.205.243.166 | ✅ 216ms，证书 CN=github.com | 干净 DNS 后直连可用 |
| api.github.com | 20.205.243.168 | ✅ 179ms | 同上 |
| codeload.github.com | 20.205.243.165 | ✅ 318ms | 同上 |
| www.gstatic.com | 120.253.253.162 | ✅ 125ms（中国边缘节点） | 直连可用 |
| raw.githubusercontent.com | 185.199.108.133 | ❌ 12s 超时 | 该 IP 段被阻断，需镜像/MITM |
| objects.githubusercontent.com | 185.199.109.133 | ❌ 超时 | 同上 |
| www.google.com | 185.45.5.35（可疑/被污染） | ❌ 超时 | Google 直连不可行，需镜像改写/MITM |

补充对照（经运行中的 Dev-Sidecar 127.0.0.1:31181，只读）：`curl -k https://github.com/` 返回 `200` 且带 `DS-Interceptor: sni: baidu.com, unVerifySsl`、`DS-DNS: PreSet: 20.27.177.113`，证明诱饵 SNI + 预置 IP 路径真实存在。

**结论**：Android 版默认（不装 CA、MITM 关闭）通过 VPN DNS 防污染 + 直连，即可显著改善 **GitHub 核心域 / npm / 部分海外开发站**；**raw/gist 文件、Google 系、社交类仍需 MITM 镜像改写，且仅对信任用户 CA 的应用（浏览器等）有效**。该边界写入 README 与 UI。

---

## 3. Android 平台硬限制（无 root，详见 02 文档）

| 能力 | 结论 |
| --- | --- |
| `VpnService.Builder.setHttpProxy` | **API 29+ 才有**；API 26–28 只能"仅 DNS 模式" |
| DNS 分隧道对所有应用生效 | ✅ 可行（VPN 提供 DNS 地址 + 路由其 /32；需关闭 Private DNS 主机名模式） |
| 系统代理是否在"VPN 非默认网络"下全局下发 | ⚠️ **单点前提，待模拟器/真机实测**（E1/M1）；失败则止损 F1 |
| 遵循系统代理的应用 | `java.net`/OkHttp 默认 ProxySelector、Chromium(Chrome/WebView/Cronet) 通常遵循；Flutter/Unity/原生 socket/QUIC/自解析 DNS 应用不遵循 |
| 所有应用透明代理 | ❌ 需 Plan B 或 root |
| 所有应用信任用户 CA | ❌ Android 7+ 默认不信任（Chrome 例外，其 NSC 显式信任用户 CA） |
| 静默改系统代理/装 CA/全局 Private DNS | ❌ 权限模型禁止 |
| 同时只能有一个 VPN | ⚠️ 新 VPN 会 revoke 旧 VPN，需 UI 提示 |
| 后台存活 | ⚠️ 前台服务 + always-on + 电池白名单引导；MIUI/HyperOS 仍有风险 |
| ABI | ✅ 后端纯 Kotlin ⇒ 单 APK 通吃 arm64-v8a / armeabi-v7a / x86 / x86_64；Gradle 预留 `abiFilters` 与未来原生模块扩展点 |

---

## 4. 交付范围（beta）

**P0 必做**（用户要求"功能完整 beta"的最低充分集）：
- 纯 Kotlin `:core`：配置仓库（含官方规则快照）、DNS 解析层（UDP/DoH/DoT + 预置 IP + 映射/族偏好 + 缓存 + 防污染校验 + 自举 IP）、规则引擎（域名通配/正则、intercepts、白名单/排除/国内放行、presetIps）、HTTP 代理（HTTP + CONNECT、超时/限流/统计）、MITM（CA 生成/按域签证书/诱饵 SNI/镜像改写/请求响应替换/脚本注入/缓存，默认关闭）、日志/统计/引擎门面。
- `:app`：VpnService（tun DNS 拦截 + API29+ 系统代理 + 前台服务 + always-on + 故障安全/看门狗/端口回退）、Controller 状态机、配置持久化、CA 安装引导、环境检测（Private DNS/lockdown/其他 VPN/系统代理注册）。
- Compose MD3 UI：首页/规则/日志/设置/证书引导，严格 MD3 token + 非线性动画，深色/动态色。
- 三环境流水线（dev→test→prod）、单元/集成/静态审计/APK 校验、模拟器截图验收、git 版本控制与 tag。

**明确非目标（文档标注）**：ECH、NAT64、HTTP/2 MITM、overwall/PAC、git/node/pip 插件、进程归属统计、远程配置自动同步、Plan B 全隧道、root 方案。

---

## 5. 验证矩阵（beta 交付前必须闭环）

| 级别 | 实验 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| P0 | G1 构建/单测全绿 | 本机 | `pipeline.ps1 test` 全 PASS |
| P0 | G2 安装/冷启动 | 模拟器 | `adb install` 成功、首页无崩溃（logcat 无 FATAL） |
| P0 | G3 VPN 启动 | 模拟器 | 通知出现、状态 RUNNING、`dumpsys` 显示 VPN network + DNS=10.111.0.2 |
| P0/PIVOT | **E1 系统代理闭环** | 模拟器 | VPN LinkProperties 带 `127.0.0.1:31181` 且 Chrome 请求出现在代理日志 |
| P0 | E2 DNS 闭环 | 模拟器 | tun 收到查询并正确应答；Private DNS 主机名时 UI 强警告 |
| P0 | J1 DNS 报文对抗 | JVM | 永不计 TC；EDNS0/畸形包不崩、不触发 TCP:53 超时 |
| P0 | J2 代理协议对抗 | JVM | 畸形请求 4xx/关闭、无线程泄漏、超时生效 |
| P0 | J4/E5 故障注入 | JVM/模拟器 | kill proxy → VPN 自动停、网络恢复、UI 报错可重启 |
| P0 | G6 停止/失败安全 | 模拟器 | 停止后网络立即恢复 |
| P0 | G7 FGS 合规 | 模拟器 | 无 `ForegroundServiceType*` 异常，通知常驻 |
| P0 | G8 边界诚实 | 文档/UI | 限制逐条可见 |
| P1 | G9 MITM/CA | 模拟器 | CA 安装流程可用，Chrome 单域名 MITM 成功（默认关闭） |
| P1 | G10 后台存活 | 模拟器 | 锁屏 10min 服务存活 |
| P1 | G11 性能 | 模拟器 | 启动 <3s、DNS P50 <100ms、空闲 CPU ≈0 |
| P0 | G12 静态合规 | 本机 | 无密钥泄漏、仅监听 127.0.0.1、`check_requirements.ps1` 全绿 |
| — | M1–M6 owner 手机清单 | 真机 | 交付后主人执行（`docs/qa/` 清单） |

---

## 6. 结论

1. **可行性成立**：Android 版 Dev-Sidecar 在"DNS 系统级防污染 + 系统代理层加速"定位下可交付可用的 beta；纯 Kotlin 重写是唯一现实的 7 小时路径。
2. **不可夸大**：无 root 无法透明代理所有应用、无法让所有应用信任 CA；Google/社交类站点只能通过"用户 CA + MITM 镜像改写"在浏览器等有限场景生效。该边界已写入 README/UI/验收清单。
3. **最大未闭环风险**是 A2（VPN 系统代理下发行为）——**这是决定宣传口径的单点前提**，必须由模拟器 E1 与 owner 手机 M1 实测，失败则立即执行 F1 止损。
4. 交付以 `03-review-checklist.md` §5 的 Go/No-Go gate 为准：任一 P0 未通过即不进入"生产环境交付"。
