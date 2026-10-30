# 后端引擎真实网络 E2E 与集成审查（T6 独立验证）

- 日期：2026-10-25（Asia/Shanghai）
- 验证环境：Windows 10/11 本机，**直连网络**（不经过运行中的 Dev-Sidecar 代理，仅参考其行为）；JDK 17；Gradle 8.9；`:core` 为纯 Kotlin/JVM。
- 权限说明：本验证为**只读验证**，未修改产品代码/构建脚本；新增文件仅 `docs/verification/engine-e2e.md` 与本验证用的 env/marker-gated JVM 测试（见 §5 复跑说明）。
- 结论速览：

| 验证项 | 结果 |
|---|---|
| 1. `DefaultConfigRepository.load()` + 内置规则快照 | **PASS**（69 域名 / 61 预置 IP 域 / 9 provider / 1237 国内域名；371ms） |
| 2. `DefaultSidebikeEngine.start()`（真实监听） | **PASS**（321ms，随机端口 2710） |
| 3. `resolveRawDnsQuery` 真实上游（tun 路径） | **PASS** ×3：github/raw.githubusercontent 走 preSetIpList（0 网络，44ms/2ms）；fonts.googleapis 走 aliyun UDP（958ms，真实 TTL 217s） |
| 4. HTTP 代理 CONNECT + 真实 TLS 握手 | **PASS** ×2：mirrors.cloud.tencent.com TLS1.3（900ms，GlobalSign 证书链）；github.com TLS1.3（411ms，Sectigo 证书链） |
| 5. 明文 HTTP forward（example.com） | **PASS**（200，843ms） |
| 6. `engine.stop()` 端口释放 | **PASS**（4ms；`ServerSocket.bind(port)` 成功） |
| 7. APK 打包 rules.json | **PASS**（详见 §2） |
| 8. 集成审查 | 发现 11 项问题（3 High / 4 Medium / 4 Low，详见 §3），核心结论见 §4 |

---

## 1. 真实网络 JVM E2E 证据

测试类：`core/src/test/kotlin/dev/sidebike/core/dns/NetworkE2EVerifyTest.kt`（默认 assume-skip，保持 CI hermetic；启用方式见 §5）。
一次性证据文件：`%TEMP%\ds_e2e_evidence.txt`，原文摘录：

```
== Dev-Sidebike backend real-network E2E ==
time=2026-10-25T04:54:54.605932500+08:00
[PASS] config.load: 371ms, intercepts=69 domains, presetIp=61 domains, dnsProviders=9, mapping=25, domestic=1237, snapshot=true
[PASS] engine.start: 321ms, proxyPort=2710, running=true
[PASS] rawDNS github.com: 44ms, rcode=0, tc=false, rawIPs=[20.200.245.247/10s, 20.27.177.113/10s, 20.205.243.166/10s], resolverProvider=preset, resolverIPs=[20.200.245.247, 20.27.177.113, 20.205.243.166], fromCache=false, ttl=10
[PASS] rawDNS raw.githubusercontent.com: 2ms, rcode=0, tc=false, rawIPs=[185.199.111.133/10s, 185.199.108.133/10s, 185.199.109.133/10s, 185.199.110.133/10s], resolverProvider=preset, resolverIPs=[185.199.111.133, 185.199.108.133, 185.199.109.133, 185.199.110.133], fromCache=false, ttl=10
[PASS] rawDNS fonts.googleapis.com: 958ms, rcode=0, tc=false, rawIPs=[223.106.89.225/217s], resolverProvider=aliyun, resolverIPs=[223.106.89.225], fromCache=false, ttl=217
[INFO] dnsStats: queries=3, cacheHits=0, failures=0, cacheEntries=1, avgLatencyMs=955
[PASS] plainHTTP forward example.com via proxy: 843ms, code=200
[PASS] CONNECT+TLS mirrors.cloud.tencent.com:443 via proxy: 900ms, tls=TLSv1.3/TLS_AES_256_GCM_SHA384, peerSubject=CN=mirrors.tencent.com,O=Tencent Technology (Shenzhen) Company Limited,L=Shenzhen,ST=Guangdong,C=CN, issuer=CN=GlobalSign Atlas R3 OV TLS CA 2026 Q2,O=GlobalSign nv-sa,C=BE, validityDaysLeft=48
[PASS] CONNECT+TLS github.com:443 via proxy: 411ms, tls=TLSv1.3/TLS_AES_128_GCM_SHA256, peerSubject=CN=github.com, issuer=CN=Sectigo Public Server Authentication CA DV E36,O=Sectigo Limited,C=GB, validityDaysLeft=36
[PASS] engine.stop: 4ms, port 2710 released=true
[INFO] engineClassloader snapshot path = /dev/sidebike/core/rules/rules.json
```

关键判读：
- **DNS 防污染层真实生效**：`github.com`/`raw.githubusercontent.com` 命中内置 `preSetIpList`，完全不依赖网络（0–44ms）；`fonts.googleapis.com` 走真实 `aliyun` UDP provider（958ms）返回公网 IP 与 TTL，证明 provider/选择/超时/缓存链路可用。
- **raw tun 路径**（VpnService 会直接调用的 `resolveRawDnsQuery`）返回 `rcode=0, TC=false` 的合法应答，IP 与独立 `DefaultDnsResolver.resolve()` 一致。
- **代理链路**：明文 forward + CONNECT 隧道 + 隧穿后的真实 TLS 握手均通过；TLS 1.3，证书链正常。
- **生命周期**：stop 后端口立即释放（4ms），无 bind 残留。
- 说明：MITM 默认关闭，因此 CONNECT 走透明隧道；TLS 由本机客户端完成（证书是目标站真实证书），与预期一致。

### 1.1 补充探针（未完成，按 Lead 要求收口）

为直接证实 QA 报告的 "engine.stats() bytes=0"，同一测试已加入"隧道保持打开时读取 `engine.stats()`"探针（代码在 `NetworkE2EVerifyTest.kt` 第 5.5 节）。该探针在本轮未取得结果：执行时另一个 workstream 的测试源码正在编辑，`:core:test` 编译被 `core/src/test/kotlin/dev/sidebike/core/mitm/MitmEngineTest.kt:410` 的临时类型推断错误阻断；随后按 Lead 指示（构建锁拥堵）不再排构建。§3.1 的 bytes=0 结论基于**代码路径分析 + QA 现象**，探针可在 CI 空闲时单独复跑确认。

### 1.2 当前测试基线（供对照）

- `:core:test`（Lead 同步）：167 passed / 0 failed / **1 skipped**（skip 即本 `NetworkE2EVerifyTest`，`Assume` 设计如此，不影响 CI hermetic）。
- 本验证新增的测试文件默认 skip；不改变原有测试数量与门禁。

---

## 2. APK 打包检查（静态）

- APK：`out/dev/app-debug.apk`（22,009,649 B，2026-10-25 04:57 构建，`build-info.json` gitSha=`1657d25`，version=`0.1.0-alpha`，minSdk=26）。
- **Java 资源条目存在且字节一致**：

| 检查 | 结果 |
|---|---|
| 条目路径 | `dev/sidebike/core/rules/rules.json`（APK zip 根，Java resources 区） |
| 大小 | `40098` B（与源码资源一致；压缩后 12951 B） |
| sha256 | APK 内 `2752e58e…8df6e` == 源码 `rules.json`（一致） |
| classloader 路径 | `"/dev/sidebike/core/rules/rules.json"`（`BundledConfig::class.java.getResourceAsStream`）→ APK zip entry 前加 `/`，Android `ClassLoader.getResourceAsStream` 可命中 |
| dex 证据 | `classes10.dex` 同时包含字符串 `BundledConfig` 与 `dev/sidebike/core/rules/rules.json`（说明常量已打进 APK） |
| 额外资源 | `dev/sidebike/core/rules/README.md` 也随 JAR 资源打包（无害） |

结论：**APK 内含规则快照，Android 运行期 `BundledConfig.isSnapshotPresent()` 应为 true**；唯一风险是 `packaging.resources.excludes` 未来误伤/资源被 shrink（当前 release 未 shrink，且 JAR 资源不属于 res shrink 范围）。

---

## 3. 集成接缝独立审查（read-only，未改代码）

以下为跨模块接缝的**具体缺陷/风险**，按严重度排序；文件:行号为当前工作区代码。

### 3.1 [High][backend-proxy] 统计字节数只在连接结束时入账 → 长连接期间 `engine.stats()` 恒为 0
- 证据：`HttpProxyServer.kt:335-337`（`record()` 只在 `handleConnection` finally 调用）、`:487-493`（`bytesUp.addAndGet`）；`ByteRelay.kt:44-45,71,92,120-121`（计数在 relay 内部局部量，relay 返回时才产出）；`MitmEngine.kt:125-131`（MITM 请求结束后才返回 bytes）。
- 影响：CONNECT 隧道/keep-alive 持续期间，`engine.stats().traffic.bytesUp/Down` 一直为 0，连接关闭瞬间才跳变。**与 QA 报告的 bytes=0 现象完全吻合**（浏览器/系统代理的 CONNECT 往往存活很久）。`activeConnections/totalConnections` 正常。
- 建议修法：把计数改为实时累加：`CountingInputStream/CountingOutputStream` 与 `ByteRelay` 接收一个共享 `AtomicLong` sink（或暴露 per-connection live counter 并周期合并）；`record()` 只负责连接/成败计数。

### 3.2 [High][backend-proxy] MITM 关闭时 `MIRROR_PROXY` 的 HTTPS CONNECT 必然证书不匹配
- 证据：`HttpProxyServer.kt:409-419`（仅 MITM 开启才走 rewrite；否则 `tunnel(plan)`）、`:441-474`（隧道直连 `plan.connectHost`，即镜像主机，不做任何 HTTP 改写）；`rules/ProxyTarget.kt`/`DefaultRuleEngine.plan()` 产出 `connectHost=镜像域`。
- 影响：默认配置 `mitmEnabled=false`，而内置规则含 `fonts.googleapis.com→fonts.googleapis.cn`、`www.google.com/recaptcha→www.recaptcha.net` 等镜像规则。浏览器 CONNECT 到镜像主机后按**原域名**校验证书 → 失败，页面资源加载失败。当前实现"默认关闭 MITM"时镜像规则等于坏规则。
- 建议修法：`!mitmEnabled` 时将 `RouteKind.MIRROR_PROXY` 降级为 DIRECT（连原 host），或仅在 MITM 开启时应用 mirror 规则；并加测试：`CONNECT fonts.googleapis.com` + MITM 关闭应直连成功。

### 3.3 [Medium][backend-proxy/engine] 运行时修改代理端口不会重新监听
- 证据：`DefaultSidebikeEngine.kt:131-145`（updateConfig 只更新组件配置）、`HttpProxyServer.kt:213-215`（仅替换 config 字段）；只有 `start()` 绑定端口（`DefaultSidebikeEngine.kt:95`）。
- 影响：设置页改 port 后 UI 显示新端口、实际仍监听旧端口；`stats().traffic.proxyPort` 也来自旧监听。与 Android `setHttpProxy` 联动时可能导致系统代理指向错误端口。
- 建议：检测 port 变化 → 停/启 proxy（或引擎侧显式 `restartProxy`）；至少 UI 提示"重启生效"。

### 3.4 [Medium][backend-proxy/engine] 配置热更新非原子（跨组件短暂不一致）
- 证据：`DefaultSidebikeEngine.kt:131-145`：先写 `this.config`，再依次 `ruleEngine.updateConfig`（:140）→ `proxy.updateConfig`（:142）→ `dnsResolver.updateConfig`（:144），无统一锁。
- 影响：更新瞬间的请求可能用"新规则 + 旧 DNS/代理配置"；端口变更还叠加 3.3。beta 可接受，但建议快照化：构建新组件引用后一次性原子替换，或 updateConfig 与请求准入共锁。
- 另：`start()` 里重复调用 `ruleEngine.updateConfig(config)`/`proxy.updateConfig`（:92-94）虽然无害，但说明"构造时已注入配置"与"start 再注入"存在双路径，容易漂移。

### 3.5 [Medium][backend-proxy] MITM 失败无透明回退；开启 MITM 后未装 CA 的客户端会整域失败
- 证据：`HttpProxyServer.kt:409-412` → `MitmEngine.handleConnect`；握手失败直接返回失败关闭（`MitmEngine.kt:94-100`），不回落隧道。
- 影响：`requiresMitm()` 对任何命中规则的 host 为真（含纯 proxy/cache/sni 规则），MITM 开启但用户未安装 CA 时，这些域（GitHub 等）全部连接失败；比不开 MITM 更差。WebView/Chrome 对用户 CA 的信任策略不同，需 UI 强提示。
- 建议：MITM 开关与"CA 已安装"状态绑定（未装 CA 时禁用开关或弹引导）；文档标注"开启 MITM 后仅信任用户 CA 的客户端可用"。

### 3.6 [Medium][backend-proxy] 上游 TLS 证书默认不校验，且范围比上游 Dev-Sidecar 宽
- 证据：`api/Config.kt:158-167` `MitmSettings.trustUpstreamAll` 默认 **true**；`Upstream.kt:63` `verifyUpstream = !trustUpstreamAll && sni == null` → MITM 模式下**所有**上游 TLS 默认跳过校验（无论是否 sni 诱饵规则）。
- 上游行为（`docs/feasibility/01-dev-sidecar-analysis.md` §3.3/§1.2）：默认 `verifySsl=true`，仅 sni 诱饵连接关闭校验。
- 影响：MITM 用户对非诱饵请求也可被中间人；安全口径与上游不一致。
- 建议：默认 `trustUpstreamAll=false`（需 Lead 改 frozen api 默认值或引擎强制覆盖）；仅当 `plan.sni != null && plan.sni != realHost`（或规则显式 unVerifySsl）时不校验。

### 3.7 [Medium][backend-proxy] 每个 CONNECT 隧道 2 个独立 OS 线程 + 线程池 worker，无全局上限
- 证据：`ByteRelay.kt:60,81`（每个方向 `Thread(...)`，非池化）；`HttpProxyServer.kt:155-157`（cachedThreadPool，daemon）；上限仅 `maxConnections`（默认 512）。
- 影响：最坏 ~3×512 线程（Android 上线程栈/GC 压力大），并发隧道多时可能 OOM；`ByteRelay.kt:110` 的 `latch.await()` 无超时（依赖 SO_TIMEOUT/stop 关 socket 解除），极端 half-open 连接会长期占线程。
- 建议：共享 relay executor（有界）或 NIO/协程 relay；`latch.await(timeout)` + 超时强制关闭；Android 默认 `maxConnections` 调低（如 128）。

### 3.8 [Low→Medium][backend-dns] 系统 DNS 回退在 VPN 模式下会再次进入自家 tun DNS
- 证据：`Upstream.kt:90-99`：resolver 失败 → 返回 host 交给 `NetUtils.connectTcp`，其 `InetSocketAddress(host)` 走系统解析；VPN 模式下系统 DNS 指向 `10.111.0.2` → 由 `:app` 调回 `engine.resolveRawDnsQuery` → `DefaultDnsResolver` 再试 provider。
- 影响：provider 全失败时单次 CONNECT 延迟叠加（DNS provider 超时 ×2），且拓扑上出现"代理→系统DNS→自己"的环路（非死循环，有超时兜底）；若 VPN DNS 未接管则退回被污染系统 DNS。
- 建议：`config.dns.enabled=true` 时禁用系统回退（resolver 失败直接 502/504 + 明确日志），或回退到 preSetIpList 后失败即止。

### 3.9 [Low][backend-proxy] MITM 每 CONNECT 只服务一个 HTTP 请求（HTTP/1.1 close）
- 证据：`MitmEngine.kt:24-38`（KDoc 明示）、`:105`（只读一次请求头）、`:138-144`（finally 关闭 TLS）；响应固定 `Connection: close`（`HttpForwarder.kt:46-48,221`）。
- 影响：浏览器每个资源可能重新 CONNECT+TLS 握手，页面加载延迟/CPU 偏高；功能正确但性能上限低。建议后续支持 keep-alive 多请求循环。

### 3.10 [Low][engine] 自定义 `dnsResolverFactory` 注入的 resolver 不会收到 updateConfig
- 证据：`DefaultSidebikeEngine.kt:56-57,94,144`：`(dnsResolver as? DefaultDnsResolver)?.updateConfig(config)`，自定义实现（测试/未来扩展）静默不更新。
- 影响：仅测试接缝；生产走 DefaultDnsResolver 无影响。建议 `DnsResolver` 契约加 `updateConfig`（需 Lead 改 frozen api）或工厂返回 `Configurable`。

### 3.11 [Low][engine/stats] 两套统计模型并存，`TrafficStatsAggregator` 未被引擎使用
- 证据：`DefaultSidebikeEngine.kt:156-176` 直接用 `proxy.trafficSnapshot()` + `dnsResolver.stats()` 拼 `EngineStats`；T6 的 `stats/TrafficStatsAggregator.kt` 未接入。
- 影响：排查"bytes=0"时容易误查 aggregator；建议删除/接入二选一，保持单一真源。

---

## 4. 总体结论与建议（给 Lead）

1. **后端真实网络行为已证明可用**：配置快照→引擎启动→tun DNS（预置 IP/真实 provider）→HTTP 代理（明文 forward / CONNECT 隧道 / 真实 TLS）→停止释放端口，全链路 PASS。APK 资源打包正确。
2. **两条 High 必须修**：
   - 统计实时性（3.1）直接影响 QA/UI 验收"bytes/流量"显示；
   - MITM 关闭时 mirror 规则产生错误连接（3.2），影响 GitHub/Google 加速体验（fonts/recaptcha 资源），且默认配置就会触发。
3. **Medium 中优先 3.3（端口热更新）、3.5（MITM+CA 前置校验）、3.6（上游校验默认值）**，三者都会造成用户可感知的"配了没用/不安全"。
4. 3.8/3.9/3.10/3.11 作为 Beta 已知限制记录即可。

---

## 5. 复跑命令（可复现）

```powershell
cd E:\WorkSpace\Dev-Sidebike
# 真实网络 E2E（默认跳过；二选一开启）
$env:DS_NETWORK_E2E='1'                       # 方式 A：环境变量
New-Item -ItemType File "$env:TEMP\ds_e2e_enable" | Out-Null   # 方式 B：marker（Gradle worker 不继承环境变量时用）
$env:DS_E2E_OUT="$env:TEMP\ds_e2e_evidence.txt"
& E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :core:test --tests "dev.sidebike.core.dns.NetworkE2EVerifyTest"
Get-Content "$env:TEMP\ds_e2e_evidence.txt"
```

```powershell
# APK 资源检查（无需构建锁）
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[System.IO.Compression.ZipFile]::OpenRead((Resolve-Path out\dev\app-debug.apk))
$zip.GetEntry("dev/sidebike/core/rules/rules.json") | Select-Object FullName,Length,CompressedLength
$zip.Dispose()
```

## 6. 限制与未覆盖项

- 本验证只覆盖 JVM 引擎；**Android 真机（VpnService/tun/系统代理）未在本任务覆盖**（QA E1 负责）。
- 真实网络 E2E 的目标可达性依赖本机网络；本机到 GitHub/Bing 的 TLS 可用，但 E2E 对不可达目标采用 SKIP 语义，不视为失败。
- 未做性能/压测、未做恶意证书/中间人测试；3.6 的安全影响需 Lead 决策（frozen api 默认值）。
- 未修改任何产品代码；修复项均以"发现+建议"形式移交 owner（backend-proxy/Lead）。
