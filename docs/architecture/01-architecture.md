# Dev-Sidebike 架构总览（v0.1 beta 决策版）

> 本文档为 2026-10-25 可行性验证后的架构结论，是后续所有开发工作的唯一基准。
> 上游参考实现：`reference/dev-sidecar`（GUI）+ `reference/dev-sidecar/vendor/ds-core`（内核，Node.js）。

## 1. 结论摘要

| 问题 | 结论 |
| --- | --- |
| Dev-Sidecar 后端能否原样运行在 Android？ | **不能**。内核是 Node.js（`core` + `mitmproxy` 子进程），Android 无 Node 运行时；nodejs-mobile 已停更且 ABi/体积/生命周期不可控。 |
| Android 上的最佳后端实现 | **纯 Kotlin/JVM 重写后端核心**（`:core` 模块），模块边界、配置语义、规则数据模型、拦截器动作与上游保持一致；Android 专属能力（VpnService、前台服务、证书安装）在 `:app` 实现。 |
| 系统级代理怎么给所有应用生效？ | **VpnService 两条腿**：① 只路由 VPN DNS 地址（`10.111.0.2/32`），在 tun 内解析 UDP DNS 并返回"防污染"结果（对所有应用生效，不需要 CA）；② `VpnService.Builder.setHttpProxy(127.0.0.1:31181)`（API 29+）把本地 HTTP 代理设为系统代理，OkHttp/Chromium 等遵循系统代理的组件自动走代理。 |
| CA/MITM 还有必要吗？ | 有必要但**默认关闭**（`mitm.enabled=false`）。需要 HTTP 层改写（GitHub 加速、gstatic/recaptcha 镜像、脚本注入）时开启，用户需安装 CA；Android 7+ 只有浏览器/自选应用信任用户 CA，因此 MITM 是增强项而非必选项。 |
| ABI/架构 | 后端纯 Kotlin ⇒ APK **与 ABI 无关**（arm64-v8a、armeabi-v7a、x86、x86_64 均可运行）。Gradle 已预留 `ndk.abiFilters`（arm64-v8a, armeabi-v7a），未来引入原生模块（如完整 TUN userspace 转发）时只需改一行并给出 x86 构建开关。 |
| 明确的非目标（beta） | ECH、NAT64、p2p、流量整形、git/node/pip 插件（桌面专属）、root 方案。 |

## 2. 分层与模块

```
┌─────────────────────────────────────────────────────────────────┐
│ :app                                                            │
│  ui/        Compose + Material3（严格 MD3；非线性动画 token）     │
│  controller/ SidebikeController：状态机、配置持久化、生命周期      │
│  vpn/       SidebikeVpnService：tun DNS 拦截 + setHttpProxy + 通知│
│  data/      DataStore 配置、CA 安装、日志导出                     │
└───────────────▲─────────────────────────────────────────────────┘
                │ SidebikeEngine API（core/api，Lead 维护，勿改签名）
┌───────────────┴─────────────────────────────────────────────────┐
│ :core（纯 Kotlin/JVM，Android 兼容 API 子集，JVM 上可单测）        │
│  config/    配置模型、默认值、分层合并、JSON5 兼容、迁移           │
│  dns/       多 Provider（UDP/DoT/DoH、SNI 伪装）、防污染、缓存、   │
│            mapping/familyMapping、hosts、preSetIpList             │
│  rules/     域名匹配（*、正则）、intercepts（域名→路径→动作）、    │
│            whiteList / excludeIpList / 国内域名放行               │
│  proxy/     HTTP/HTTPS(CONNECT) 代理、连接复用、限流、统计         │
│  mitm/      CA 生成（BouncyCastle）、按域名签发、TLS 终结、        │
│            requestReplace/responseReplace/script/cache/sni 改写    │
│  speed/     测速与 Provider/IP 选择（对齐上游 speedTest）          │
│  engine/    DefaultSidebikeEngine：装配以上模块，对外唯一入口      │
│  log/       RingBuffer 日志 + 订阅                                 │
└─────────────────────────────────────────────────────────────────┘
```

## 3. 关键运行流程

### 3.1 启动（用户点"启动"）
1. UI → `SidebikeController.start()`。
2. `VpnService.prepare()` 授权（若未授权，UI 落 `ActivityResultLauncher`）。
3. `SidebikeVpnService` 启动前台服务：
   - 构建 tun：`addAddress("10.111.0.1", 32)`、`addDnsServer("10.111.0.2")`、`addRoute("10.111.0.2", 32)`、`setMtu(1500)`、`setMetered(false)`、**blocking read（独立线程阻塞读，禁止非阻塞忙轮询）**；
   - **先启动引擎并自检本地代理可连接（127.0.0.1:31181，占用则回退高位端口），确认成功后才 establish tun / setHttpProxy**；
   - API 29+ 调 `setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", config.proxy.port))`；API 26-28 仅 DNS 模式；
   - 启动 `SidebikeEngine`：DNS 解析器 + HTTP 代理监听 `127.0.0.1:31181`；
   - 运行中 watchdog：代理线程死亡 → 立即拆 VPN（系统自动清除 HTTP 代理）+ ERROR 状态；
   - `always-on` 支持（`meta-data android.net.VpnService.SUPPORTS_ALWAYS_ON=true`）+ 前台服务类型 `specialUse`（Android 14+ 不用 `systemExempted`，避免首次启动即崩溃）。
4. tun 读线程：只收到发往 `10.111.0.2:53` 的 IPv4/UDP 报文 → 解析 DNS wire → `engine.resolveRawDnsQuery()` → 构造响应 IP/UDP 包写回 tun。
5. 通知栏/QuickSettings Tile 反映状态与统计。

### 3.2 应用请求
- 遵循系统代理的应用：`HTTP CONNECT host:443` → 引擎按规则决策：
  - `DIRECT`：用防污染 DNS 解析出的真实 IP 直连（可带 `sni` 改写，见 3.3）；
  - `PROXY/MIRROR`：按 `intercepts` 的 `proxy` 动作连接镜像主机；
  - `SNI`：上游 TLS 握手时使用动作中的 `sni`（如 `baidu.com`），默认不校验证书；
  - `SYNTHETIC`：`success` 动作直接合成响应；`responseReplace`/`requestReplace`/`script`/`cacheDays` 需 MITM。
- 不遵循系统代理的应用：至少获得**防污染 DNS**（VPN DNS），在 GFW 只做 DNS 污染的场景（本机实测 GitHub 核心域可用）可直连成功。

### 3.3 SNI 伪装的正确姿势
上游 `intercepts` 中大量 `sni: "baidu.com"`。原理：客户端 → 本机 MITM（用本机 CA 给目标域名签发临时证书）→ 上游 TLS 用伪装 SNI 与真实 IP 握手（GitHub/CDN 忽略 SNI 返回默认证书，我们关闭上游校验）→ 绕过基于 SNI 的阻断。**该链路必须 MITM**；Android 上仅当目标应用信任用户 CA 时可用（Chrome 等浏览器可行），因此默认关闭，UI 中明确标注。

## 4. 上游模块移植对照

| 上游模块（ds-core） | Dev-Sidebike 决策 |
| --- | --- |
| `core/src/config/*`（4 层合并、remote json5） | 移植为 `:core config`（JSON 配置 + 内置默认值 + 用户覆盖；远程配置仅作兼容读取，不强制联网） |
| `mitmproxy/src/lib/dns/*`（multi provider、DoH/DoT、mapping、speedTest） | 移植核心：UDP/DoH/DoT、mapping/familyMapping、缓存、hosts、preSetIpList；speedTest 做轻量版 |
| `mitmproxy/src/lib/interceptor/*`（domain+path→action） | 移植：`sni`、`proxy`、`success`、`requestReplace`、`responseReplace`、`script`、`cacheDays`、`status`；暂不做 tampermonkeyScript/OPTIONS 预检等边缘动作 |
| `mitmproxy/src/lib/proxy/tls/*`（CA、按域签证书、LRU） | 移植：BouncyCastle 生成 CA、按域签发、LRU 缓存；CA 存 App 私有目录 |
| `mitmproxy/src/lib/choice/*`（失败切换） | 移植简化版：Provider 连续失败降权/切换 |
| `core/src/shell/*`（系统代理、装 CA、kill 端口） | **不可移植**：Android 由 VpnService/`setHttpProxy` 替代；CA 安装改为 `KeyChain.createInstallIntent()` |
| `core/src/modules/plugin/*`（git/node/pip） | 不移植（桌面开发工具链专属） |
| `gui`（Vue/AntD） | 不移植；前端完全用 Compose + 严格 MD3 重写 |

## 5. 开发工作流与文件所有权

工作流（三环境）：

```
dev（源码） ──build──▶ 预交付物 out/dev/app-debug.apk
        ▲                      │
        │ 修复根因              ▼
        │              test 环境：_script/test.ps1（单元/集成/静态审计/lint）
        │                      │ 全部通过
        └──────────────────────┴──▶ prod：_script/release.ps1 → dist/（release APK + manifest + sha256）
```

- `_script/pipeline.ps1 dev|test|prod|all` 为唯一入口；失败必须修根因后重跑，禁止放宽测试。
- 文件所有权（详见 `docs/dev-workflow.md`），越界写入视为冲突，由 Lead 裁决。

## 6. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| `setHttpProxy` 在低版本不可用/部分应用不遵循 | API<29 时降级为"仅 DNS 模式"并在 UI 说明；README 列出已知不遵循系统代理的应用类型 |
| 私有 DNS（DoT）绕过 VPN DNS | UI 检测并提示关闭 Private DNS；解析失败时回退直连 |
| MITM 需用户 CA，多数应用不信任 | 默认关闭；证书安装引导页 + 一键导出；明确能力边界 |
| 代理进程被杀 / 死循环 | 前台服务 + 系统 VPN 图标 + 应用内可恢复；代理自身 socket 不走 tun（只路由 DNS IP）|
| 电池/后台限制（MIUI/HyperOS） | 引导页说明自启动与电池白名单；always-on VPN 支持 |

## 7. 2026-10-25 修订记录（T1 上游分析 + T2 独立复核后）

1. **事实校正（重要，影响产品文案）**：上游 Dev-Sidecar **没有本地 DNS 服务器**（无 53 端口监听；"DNS 防污染"是代理进程内置的解析层），其反审查手段是 **"诱饵 SNI（decoy SNI，如 `github.com → baidu.com`）规避 SNI 阻断 + DoH/DoT/预置 IP/测速选优规避 DNS 污染 + ECH 加密 SNI"** 三套机制，**不是"HTTPS SNI 漏洞"**。详见 `docs/feasibility/01-dev-sidecar-analysis.md`（含源码行号与实测响应头 `DS-Interceptor: sni: baidu.com, unVerifySsl`）。
2. 因此 Android 端的 **tun DNS 拦截是 Android 专属增强**（上游没有），也是"无需证书即可让所有应用获得防污染解析"的核心价值来源。
3. **MITM 必要性**：`sni`（诱饵 SNI）与 `proxy`（镜像改写）都必须 MITM 终止客户端 TLS 才能生效。`mitm.enabled=false`（默认）时这两类规则降级为"直连真实域名"，由 DNS 防污染保证可达性；开启后浏览器等信任用户 CA 的应用可获得完整加速。
4. **VpnService 细节**：tun blocking read；API29+ `setHttpProxy`、API26-28 仅 DNS 模式；`setMetered(false)`；FGS 类型用 `specialUse`（含 `FOREGROUND_SERVICE_SPECIAL_USE` 权限与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）；always-on `meta-data`；启动前必须完成本地代理自检；watchdog 失败即拆 VPN；31181 被占用回退高位端口并反映到 `SidebikeUiState.effectiveProxyPort`。
5. **运行期检测**：Private DNS(DoT) 开启会完全绕过 VPN DNS（`LinkProperties.isPrivateDnsActive`，API28+，UI 强提示关闭/设为自动）；"始终开启 VPN 且阻止无 VPN 连接"（lockdown，`isLockdownEnabled`，API29+）与 DNS-only split-tunnel 冲突（除 DNS 外断网，UI 提示）；establish 后读取 VPN 网络 `LinkProperties.httpProxy` 以判断系统代理是否真正注册（`SidebikeUiState.systemProxyRegistered`）。
6. **DNS 自举安全**：engine 永不使用系统 resolver 解析 DNS Provider 主机名，只使用 `DnsProvider.bootstrapIps`（契约已加）；且 VPN 只路由 `10.111.0.2/32`，engine 上游 socket 不进入 tun，无自举环。
7. **tun DNS 应答器**：永不置 TC（不处理 TCP:53）；按客户端 EDNS0 UDP buffer（无 EDNS0 默认 512B）裁剪答案，保证报文合法。
8. **本机可行性实测**（`_script/experiments/sni_probe.mjs`，DoH 干净解析后直连）：`github.com` / `api.github.com` / `codeload.github.com` / `www.gstatic.com` 可直连且证书正确；`raw.githubusercontent.com`、`objects.githubusercontent.com`（185.199.108.0/22）TCP 超时；`www.google.com` 直连不可达。结论：**默认无 CA 模式对 GitHub 核心域有效；raw/gist 文件与 Google 系需要镜像改写或 MITM 增强**，与产品文案一致。
9. **QA 复核**：本机支持 Android 模拟器（WHPX 可用、31.7GB RAM），将用于 APK 安装/启动/VPN/截图验收；无手机实测的部分（如 VPN 系统代理是否被各应用遵循）已整理成 owner 手机端 10 分钟验收清单（见 `docs/qa/`）。
