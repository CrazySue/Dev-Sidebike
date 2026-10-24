# Dev-Sidecar 后端深度分析与 Android 移植规格（T1 交付物）

- 作者：analyst（T1），日期：2026-10-25
- 分析对象：
  - 源码（浅克隆）：`E:\WorkSpace\Dev-Sidebike\reference\dev-sidecar`（父仓 HEAD `805c793`，git submodule `vendor/ds-core` = `Blue-Frontier/ds-core@c14c481`；内核 pnpm 包版本号 `2.3.2`（`vendor/ds-core/core/package.json:4`，该版本号仅形式作用，见 `vendor/ds-core/AGENTS.md`））。
  - 正在运行的安装版：`D:\dev-sidecar\dev-sidecar.exe`（Electron），配置目录 `C:\Users\CrazySue\.dev-sidecar`，代理监听 `127.0.0.1:31181`（只读探测，未做任何修改）。
- 引用约定：本文用 `core/...` 指 `reference/dev-sidecar/vendor/ds-core/core/...`，用 `mitmproxy/...` 指 `reference/dev-sidecar/vendor/ds-core/mitmproxy/...`。所有行号均为源码实测行号。若与安装版（v2.3.1）有差异，会显式标注。

---

## 0. 结论摘要（TL;DR）

1. **Dev-Sidecar 后端 = 一个进程内的 HTTP/HTTPS MITM 代理 + 代理内置的"自定义 DNS 解析层" + 启动时下发到代理进程的"规则表"**，而不是一套独立网络服务。
   - 只有 **1 个监听端口**：`127.0.0.1:31181`（HTTP 代理与 HTTPS CONNECT 共用，`mitmproxy/src/lib/proxy/mitmproxy/index.js:88-115,140-143`）。
   - **没有 DNS 服务器**（不监听 53/udp、不写 hosts、不改系统 DNS）。所谓"DNS 防污染"发生在代理进程内部：它为每个上游连接手工选择 DNS provider 并自行解析 IP，然后把 IP 交给 socket 直连（`mitmproxy/src/lib/dns/index.js:19-135` + `mitmproxy/src/lib/proxy/mitmproxy/dnsLookup.js:90-267`）。
   - 运行实例实测：代理进程（PID 21696，`dev-sidecar.exe ...\app.asar\src\bridge\mitmproxy.js running.json`）只监听 TCP 31181 与若干 `127.0.0.1` 随机端口（每域名一个 MITM"假 TLS 服务器"），**无任何 UDP 监听**（见附录 B）。
2. **项目 owner 的前提需要纠正**：Dev-Sidecar 并非利用"HTTPS SNI 伪装漏洞"。
   - SNI 在 TLS 1.2/1.3（未启用 ECH 时）**本来就是明文**，GFW 正是按明文的 SNI 做阻断；Dev-Sidecar 是**主动把上游 ClientHello 的 SNI 改写成"诱饵域名"**（如 `github.com → baidu.com`、`*.googleapis.com → www.google.cn`）来**规避基于 SNI 的审查**（`mitmproxy/src/lib/interceptor/impl/req/sni.js:1-43`、`core/src/config/index.js:698-725`）。
   - 这不是"漏洞利用"：目标服务器/ CDN 通常按 IP 或默认证书返回证书、不校验 SNI 与 Host 是否一致，所以诱饵 SNI 仍能握手；但**要求诱饵必须是对方边缘拥有的域名**（Cloudflare 系用 `cloudflare-ech.com`），否则会 handshake failure（`core/src/config/remote-config-rule.js:19-26`）。Dev-Sidecar 为此**关闭了这些连接的上游证书校验**（`sni.js:23-27`，实测响应头 `DS-Interceptor: sni: baidu.com, unVerifySsl`），这是它的安全代价。
   - 另一条更"正规"的路线是 **ECH（Encrypted Client Hello，RFC 9848/9460）**：Dev-Sidecar 2.3.x 对配置名单内的域名用 DNS HTTPS(65) 记录取 ech 参数做加密 SNI 握手，实测 `https://x.com/` 返回 `DS-ECH: 1`（见附录 B）。
   - 因此正确表述是：**"伪装/诱饵 SNI 规避 SNI 阻断 + DoH/DoT/预置 IP/测速选优规避 DNS 污染 + ECH 加密 SNI" 三套机制**，而不是"HTTPS SNI 漏洞"。
3. **Android 端口可行，但必须重写而非照搬**：Node/Electron 的 `fork` 子进程、系统代理设置、系统 CA 安装、`checkServerIdentity`、动态 `lookup` 钩子、HTTP/2 假服务器、ECH 客户端在 Android 上都不存在等价物或不可用（详见 §5）。
4. **最小可交付内核（MVP）建议**：本地 HTTP 代理（单端口）→ 规则引擎（域/路径匹配 + 15 种拦截器中的 6 种）→ MITM（CA + 按域名签发叶证书 + 假 TLS 服务器）→ 上游连接（预置 IP / DoH / DoT→ 选 IP，SNI 诱饵 + 可关闭证书校验）→ 规则表直接复用官方配置（`intercepts/preSetIpList/dns/whiteList`）。**ECH、NAT64、overwall、p2p/share、free-eye、cloudflareRoute、进程归属统计在 beta 中应砍掉或降级**。

---

## 1. 证据基线与前提澄清

### 1.1 版本/源码基线

| 项 | 值 | 证据 |
|---|---|---|
| 父仓 | `docmirror/dev-sidecar` 浅克隆，HEAD `805c793` | `git -C reference/dev-sidecar log --oneline -1` |
| 内核子模块 | `vendor/ds-core` → `https://github.com/Blue-Frontier/ds-core.git` @ `c14c481`（heads/main） | `reference/dev-sidecar/.gitmodules:1-3`、`git submodule status` |
| 包布局 | `packages/core`、`packages/mitmproxy` 是指向 `vendor/ds-core/{core,mitmproxy}` 的 Windows 符号链接 | `(Get-Item packages/core).Target` = `..\vendor\ds-core\core` |
| 代理端默认端口 | `31181`（单端口） | `mitmproxy/src/lib/proxy/common/config.js:5-7`、`core/src/config/index.js:99-106` |
| 内核包"版本" | `2.3.2`（AGENTS.md 声明该字段已废弃，仅占位） | `vendor/ds-core/core/package.json:4` |
| 运行实例 | Electron GUI + 子进程代理引擎，配置目录 `C:\Users\CrazySue\.dev-sidecar`，端口 31181 | 附录 B |
| 远端官方配置 | `https://ds-official-config.bestar.de5.net/remote_config.json5` | `core/src/config/index.js:23,64-70`；本地缓存 `~/.dev-sidecar/remote_config.json5`（1497 行） |
| 规则注入方式 | 构建脚本 `_script/sync-official-config.mjs` 把官方配置的 `intercepts/preSetIpList/whiteList/dns` **整体替换**进 `core/src/config/index.js` 的 `SYNC:OFFICIAL-FALLBACK` 区块 | `core/src/config/index.js:170-176` 注释、`vendor/ds-core/AGENTS.md`；`core/src/config/index.js:177-1547` |

> 说明：安装版是 v2.3.1，源码是 2.3.2-dev（子模块 c14c481）。我抽查了安装版 `app.asar` 内的 `sni.js`/`proxy.js` 代码，关键逻辑与源码一致（附录 B.4），因此以源码为准确依据、安装版实测为行为校核。个别差异（如 `running.json` 中 `cf-DoH.sni: "cf"` vs 源码默认 `baidu.com`）属于用户/官方配置差异，不影响机制结论。

### 1.2 前提澄清："HTTPS SNI 伪装漏洞"？

**结论：错误前提，需纠正为"SNI 诱饵（decoy SNI）规避 SNI 阻断"。**

证据链（源码 + 实测）：

1. 规则表对大量被墙域名配置了 `sni` 字段，值是一个**国内可访问的诱饵域名**：
   - `github.com`/`api.github.com`/`*.github.io`/`docker.io` 等 → `sni: "baidu.com"`（`core/src/config/index.js:698-731`，实际生效值又由官方配置覆盖，见 `C:\Users\CrazySue\.dev-sidecar\remote_config.json5` 第 30~60 行附近）；
   - `*.googleapis.com`、youtube 系 → `sni: "www.google.cn"`（`core/src/config/index.js:792-827`）；
   - `*.huggingface.co` → `sni: "huggingface.cn"` 且 `verifyHost: "huggingface.cn"`（官方配置；示例见 `running.json`）。
2. `sni` 拦截器把诱饵域名写到上游 TLS 的 `servername` 上：`mitmproxy/src/lib/interceptor/impl/req/sni.js:19-21`；`sni: ""` 表示**完全不发 SNI**（`sni.js:9-15`）；`verifyHost` 用于"换成另一个真域名的诱饵"时的证书校验（`sni.js:45-63`）。
3. 上游 https 请求只把 `servername` 当 SNI 用，证书校验默认仍按**真实域名**进行：`createRequestHandler.js:246-261`（`checkServerIdentity = (_h,cert) => tls.checkServerIdentity(verifyHost, cert)`）。但**只要配置了 sni 改写，拦截器会把 agent 换成 `unVerifySslAgent` 并置 `unVerifySsl=true`**（`sni.js:23-27`），即上游证书校验被关闭——所以实测头 `DS-Interceptor: sni: baidu.com, unVerifySsl` 是预期行为，不是异常。
4. 为什么服务器能接受：TLS 服务端通常不因为 ClientHello 的 SNI 与自身证书域名不匹配而拒绝握手（它只按 SNI 选证书/虚拟主机；未命中时返回默认证书）。因此"诱饵 SNI"能否工作，取决于目标边缘是否"认这个诱饵"：Cloudflare 前置域名必须用 Cloudflare 自家域名做诱饵，否则握手失败（`core/src/config/remote-config-rule.js:19-26` 注释有实测记录）。
5. 实测（附录 B.2）：`curl -k https://github.com/` 经 31181 返回 `DS-Interceptor: sni: baidu.com, unVerifySsl`、`DS-Proxy-Request: https://github.com:443/`、`server: github.com`——即"用 Baidu 的 SNI 连到 GitHub 的真实服务器"。
6. 这是**规避审查的技术（censorship circumvention）**，不是对 HTTPS/服务器漏洞的利用；同时 Dev-Sidecar 还有标准方案 ECH（见 §2.8），对支持 ECH 的域名无需伪装。

> 给 Lead/产品文案的建议：对外描述用"SNI 伪装（诱饵 SNI）+ DoH 防污染 + ECH 加密 SNI"，禁止写"利用 HTTPS SNI 漏洞"。这是本项目对外表述的合规/准确性风险点。

---

## 2. 后端模块地图与数据流

### 2.1 进程、端口、文件布局

```
┌───────────────────────────── Windows 运行实例（实测） ──────────────────────────────┐
│ GUI 主进程: dev-sidecar.exe (Electron, PID 21432)                                   │
│   ├─ 配置加载/合并（core/src/config-api.js）→ 写 ~/.dev-sidecar/running.json          │
│   ├─ fork 子进程: dev-sidecar.exe app.asar\src\bridge\mitmproxy.js running.json      │
│   │                 (PID 21696；Electron 以 node 模式运行；即 mitmproxy 引擎)          │
│   ├─ 系统代理设置 / CA 安装 / 插件（git/node/pip…）                                   │
│   └─ GUI 渲染进程（Vue）                                                             │
├────────────────────────────────────────────────────────────────────────────────────┤
│ 代理引擎子进程（PID 21696）                                                          │
│   ├─ TCP 127.0.0.1:31181  ← HTTP 代理 + HTTPS CONNECT（唯一对外端口）                 │
│   ├─ TCP 127.0.0.1:<随机> × N ← 每域名一个 MITM 假 TLS 服务器（http2+http1.1）         │
│   ├─ 无 UDP 监听、无 DNS 监听、无 PAC 服务                                             │
│   └─ 上游连接：自定义 DNS 解析 → TCP 直连目标 IP → TLS（可诱饵 SNI / ECH）              │
└────────────────────────────────────────────────────────────────────────────────────┘

配置目录 C:\Users\CrazySue\.dev-sidecar\
  config.json            用户覆盖（差分存储，3.1KB）
  remote_config.json5    官方共享配置缓存（49KB，1497 行；规则表的真正来源）
  remote_config_personal.json5   个人远程配置（可选）
  remote_config_history/ 历史版本快照
  running.json           合并后的运行配置（39.8KB；fork 给代理子进程的唯一输入）
  setting.json           环境设置（overwall 开关、rootCa.setuped 安装标记）
  automaticCompatibleConfig.json  兼容性自动学习结果
  dev-sidecar.ca.crt / dev-sidecar.ca.key.pem   本地 CA（20 年有效期）
  domestic-domain-allowlist.txt  国内域名直连清单（系统代理排除）
  logs/{core,gui,server}.log      日志（server 日志量极大：单日可达 100MB 级）
```

（进程/端口实测命令与输出见附录 B.1；fork 与 running.json 写入见 `core/src/modules/server/index.js:151-174`；单端口监听见 `mitmproxy/src/lib/proxy/mitmproxy/index.js:88-115`；假服务器监听见 `mitmproxy/src/lib/proxy/tls/FakeServersCenter.js:146-154`；日志目录见 `core/src/config/index.js:91-97`。）

### 2.2 总请求数据流（以 HTTPS 请求为例）

```
[浏览器/系统] --HTTP proxy 或 CONNECT--> [31181 代理入口]
   │
   ├─(1) CONNECT host:443
   │      └─ sslConnectInterceptor 判定（options.js:178-202）:
   │            whiteList 命中        → 直连隧道（不解密）
   │            intercepts 命中       → MITM（解密）
   │            ECH/NAT64 域名        → MITM（必须解密才能用 ECH/NAT64）
   │            其它                  → 直连隧道（不解密，纯转发）
   │      命中 MITM: 建/取 FakeServer(host,port) → 把客户端 socket 转发到 127.0.0.1:<fakePort>
   │                (createConnectHandler.js:43-68; FakeServersCenter.js:74-154)
   │      直连隧道: net.connect(host, port) + 双向 pipe + DNS 自定义 lookup
   │                (createConnectHandler.js:69-73,77-161; DNS 选择见 136-145)
   │
   ├─(2) MITM 假服务器收到解密后的 HTTP 请求（http2 或 http1.1）
   │      └─ requestHandler（createRequestHandler.js:127-837）
   │           ├─ getOptionsFromRequest 构造 rOptions（common/util.js:138-235）
   │           │    · 真实 host/port/path、headers、https agent（keepAlive、TLS 版本、verifySsl）
   │           ├─ createIntercepts(context) 规则引擎生成**已排序拦截器数组**（options.js:203-331）
   │           ├─ 依次执行 request 拦截器（按 priority 升序）
   │           │    OPTIONS(101)→success(102)→abort(103)→cacheRequest(104)→redirect(105)
   │           │    →requestReplace(111)→proxy(121)→sni(123)→unVerifySsl(124)→baiduOcr(131)
   │           │    →插件 middleware（overwall）
   │           ├─ 选择 DNS provider + 解析 IP（dnsLookup.createLookupFunc, createRequestHandler.js:305-357）
   │           ├─ 发起上游请求（https/http.request, createRequestHandler.js:389-417）
   │           │    · servername = 诱饵 SNI（若有）；checkServerIdentity 用真实域名/verifyHost
   │           │    · ECH 域名：agent 包一层 echAgentPair（380-385）
   │           ├─ 失败：超时/错误/aborted → 重试（600-633）→ 错误页（739-835）
   │           ├─ 执行 response 拦截器（AfterOPTIONSHeaders 201 → cacheResponse 202
   │           │    → responseReplace 203 → script 211）
   │           └─ 回写响应头/状态码/流式 body（pipeline，709-738）
   │
   └─(3) 响应回到客户端（MITM 场景下客户端看到的是本地签发证书）
```

### 2.3 模块清单（后端）

| 模块 | 位置 | 职责 | Android 对应 |
|---|---|---|---|
| 配置加载/合并/远程拉取 | `core/src/config/*`, `core/src/config-api.js`, `core/src/merge.js` | 4 层合并、差分保存、远程配置下载与历史版本 | 重写为 Kotlin `ConfigRepository`（JSON5→JSON 预转换） |
| 进程编排/状态 | `core/src/expose.js`, `core/src/modules/*`, `core/src/event.js`, `core/src/status.js` | 启动 server/proxy/插件，事件总线 | 单进程 Service + Flow/StateFlow |
| 系统代理 | `core/src/modules/proxy/index.js`, `core/src/shell/scripts/set-system-proxy/` | 写 Windows/macOS 系统代理、环境变量、CA bundle | **不可照搬**，见 §5 |
| 代理引擎 | `mitmproxy/src/lib/proxy/mitmproxy/*` | 单端口 HTTP+CONNECT、MITM 假服务器、上游请求 | Kotlin 重写 |
| 规则引擎 | `mitmproxy/src/utils/util.match.js` + `options.js:23-331` | 域名/路径匹配、拦截器选择、优先级/去重/排除 | Kotlin 重写（语义必须逐条对齐） |
| 拦截器族 | `mitmproxy/src/lib/interceptor/impl/**` | sni/proxy/redirect/success/abort/cache/requestReplace/responseReplace/script/retry/OPTIONS/unVerifySsl/baiduOcr | 只移植 MVP 子集 |
| TLS/CA | `mitmproxy/src/lib/proxy/tls/*`, `mitmproxy/src/lib/proxy/common/config.js` | CA 生成、叶证书签发、假 TLS 服务器（HTTP/2+1.1）缓存 | Kotlin + BouncyCastle；HTTP/1.1 优先，h2 可选 |
| DNS 解析层 | `mitmproxy/src/lib/dns/**`, `mitmproxy/src/lib/proxy/mitmproxy/dnsLookup.js` | provider 抽象（UDP/TCP/DoT/DoH/预置）、缓存、失败优选、回退 | Kotlin 重写（无系统级 DNS 服务） |
| ECH | `mitmproxy/src/lib/dns/base.js:89-321`, `mitmproxy/src/lib/proxy/common/ech/**` | 查 HTTPS(65) 记录、解析 ech、包 TLS 握手 | **beta 砍掉**（Android 无公开 ECH API） |
| NAT64 | `mitmproxy/src/lib/dns/nat64.js` | 真实 IPv4 嵌入 NAT64 前缀走 IPv6 | 低优先级，可后置 |
| IP 测速/优选 | `mitmproxy/src/lib/speed/*`, `mitmproxy/src/lib/choice/*` | TCP 443 握手测速、成功率动态切换 | 移植（简化） |
| 重写/镜像 | 规则表 `intercepts`（`core/src/config/index.js:698-1106`、官方配置） | github 加速、google/gstatic/recaptcha 改写、jsdelivr 备用、缓存 1 年等 | 移植（纯规则解释器，无需改代码） |
| CA 安装 | `core/src/shell/scripts/setup-ca.js` | Windows 打开证书文件；Linux 写系统信任库 | **不可照搬**（Android 无 root 不能写系统 CA），改为引导+KeyChain |
| 插件 | `core/src/modules/plugin/**` | git/node/pip 写配置与环境变量；overwall 反代；free-eye 网络诊断；share P2P；xget | **全部砍掉**（无意义/无桌面环境） |
| 流量统计 | `mitmproxy/src/lib/traffic/*` | 按域名/IP 统计、进程归属解析 | 简化：按 host 统计连接数与字节数 |
| Cloudflare 路由 | `mitmproxy/src/lib/cloudflareRoute.js` | 命中 CF IP 段改写到优选地址 | beta 砍掉（默认 `enabled:false`，`core/src/config/index.js:155-159`） |
| PAC/增强模式 | `mitmproxy/src/lib/proxy/middleware/overwall.js`, `.../source/pac.js` | 用 PAC/gfwlist 判定走远端反代 | 砍掉（依赖 `ow-prod.docmirror.top` 服务，默认关闭） |

### 2.4 关键子流程

**(a) 规则引擎（每个请求都会跑）**

```
createIntercepts(context)  [options.js:203-331]
  host = rOptions.hostname; path = rOptions.path
  1. matchHostnameAll(intercepts, host)  → 合并所有命中的域名规则（见 §3.1 优先级）
  2. LRU(pathCache 512) 命中 → 直接返回
  3. 遍历该 host 规则里所有"path 正则"键：
       isMatched(path, regexp) 匹配失败 → 跳过
       若有 exclusions 且 path 命中任一排除正则 → 跳过
       对每个 impl：impl.is(interceptOpt) 为真 → 生成 {name, priority, requestIntercept/responseIntercept}
                   同名拦截器按 order 比较去重（order 大者胜）
  4. 按 priority 升序排序 → 缓存并返回
```

调用方：MITM 请求处理前（`createRequestHandler.js:187-196`）；`sslConnectInterceptor` 用**域名级** `matchHostname` 判定是否 MITM（`options.js:178-202`）。

**(b) DNS 解析（代理内置，不是 DNS 服务器）**

```
上游连接需要 host:port
  ├─ DNS 配置选择（dns/index.js:237-292 getDNSAndFamily）
  │    0) NAT64 域名 → Nat64 通道
  │    1) 域名命中 preSetIpList（且非 ECH 默认忽略）→ PreSet provider（只查表）
  │    2) ECH 域名 → ech.dns 指定的 provider（默认 cf-DoH）
  │    3) dns.mapping 命中 → 指定 provider + family(4/6)
  │    4) 都不命中 → 返回 null → 走系统 DNS（Node dns.lookup）
  ├─ 解析调用（dnsLookup.js:90-267 createLookupFunc）
  │    speedTest 池里有该域名 → 直接返回 alive[0]（最快存活 IP）；否则返回轮转探测 IP
  │    否则 dns.lookup(host) → provider 内部:
  │         · 缓存命中（LRU 1024，按 hostname 存 DynamicChoice，**无 TTL 过期**）
  │           缓存值若已失败退化为 hostname 本身 → 触发 fallback
  │         · 未命中 → 预置 IP 检查 → 实际查询（UDP/TCP/DoT/DoH）
  │         · 结果: [ip1, ip2, ..., hostname] 一起写入 DynamicChoice 备选表
  │         · 当前 value 的 IP 协商成功与否通过 ipChecker/count 反馈切换
  ├─ 主 provider 解析不出 → 其它 provider 逐个兜底（getFallbackDnsList，dns/index.js:218-235）
  │    → 再不行 → 系统 DNS（defaultDns.lookup）
  └─ 得到 IP → 写响应头 DS-DNS: "<provider>: <ip> (IPv4/IPv6)"
```

**(c) MITM 建链**

```
CONNECT host:443
  ├─ 命中拦截 → fakeServerCenter.getServerPromise(host, port, ssl)
  │    · dnsName = getDnsName(host)  // a.b.com → *.b.com → 证书按通配符复用
  │    · cert/key = CertAndKeyContainer.getCertPromise(dnsName)（LRU，容量=fakeServerMaxLength）
  │    · 起 http2.createSecureServer({allowHTTP1:true, SNICallback→同一个 secureContext})
  │    · listen(0,'127.0.0.1')（固定回环，防止把 DS 的假证书暴露到局域网）
  │    · 返回 {port, ssl}；代理把客户端 socket 转发到该内部端口
  │    · 客户端看到的是 CA 签发的 *.b.com 证书（浏览器需信任 DS CA）
  ├─ 不拦截 → 直接 net.connect(host, port)（走自定义 DNS lookup）并 pipe
  └─ CONNECT 本身不是 CONNECT（HTTP 绝对 URI）→ requestHandler(ssl=false)
```

**(d) CA 管理（启动时 + 按需签发）**

```
启动: tlsUtils.initCA({caCertPath, caKeyPath})   [tlsUtils.js:298-333]
  ├─ 文件存在 → 直接读
  └─ 不存在 → createCA("DevSidecar - This certificate is generated locally")
         · RSA 2048，自签，SHA256；有效期 now-1h → now+20 年
         · subject/issuer: CN/OU/O/L/ST/C = 见 tlsUtils.js:44-62
         · 扩展: basicConstraints(critical, cA:true) + keyUsage(keyCertSign, critical) + SKI
         · 写 ~/.dev-sidecar/dev-sidecar.ca.crt + .key.pem（PEM）
按需: createFakeCertificateByDomain(caKey, caCert, dnsName, mappingHostNames)  [tlsUtils.js:127-210]
  · RSA 2048（原生 crypto 异步生成，tlsUtils.js:19-25）
  · CN = dnsName；SAN = [dnsName, dnsName 去掉 "*."]
  · 有效期 now-1年 → now+1年；issuer = CA subject；SHA256 签名
  · 扩展: basicConstraints(cA:false) + SAN + SKI + extKeyUsage + AKI（strict 客户端兼容）
安装: 由 GUI 触发 core/shell/scripts/setup-ca.js
  · Windows: `start "" <ca.crt>` 打开系统证书导入向导（:103-109）
  · macOS: `open <ca.crt>`（:114-119）；Linux: 按发行版写入系统信任库并 update-ca-*（:45-101）
  · 安装结果记录在 ~/.dev-sidecar/setting.json → rootCa.setuped（实测 true）
```

**(e) 配置加载器（4 层合并 + 差分保存）**

```
defaultConfig (core/src/config/index.js:54-168 + 官方规则覆盖 170-1547)
   ↑ merged with
共享远程配置  ~/.dev-sidecar/remote_config.json5     (可被 app.remoteConfig.enabled/url 控制)
个人远程配置  ~/.dev-sidecar/remote_config_personal.json5
用户覆盖      ~/.dev-sidecar/config.json（只存 diff；兼容 config.json5）
Xget 覆盖层   （plugin.xget 启用时，xget_config.json5 模板替换实例地址）

合并顺序（local-config-loader.js:234-259 getConfigFromFiles）:
  merged = user
  merge personal → merge shared → merge default  （先各合并一次，决定数组/字段铺底顺序）
  merge shared → merge personal → merge user      （再合并一次，保证优先级 user > personal > shared > default）
  merge xgetOverlay；deleteNullItems（null 与 "[delete]" 表示删除）
数组: 后者整体替换（merge.js:78-84）；对象: 深合并；doDiff（merge.js:10-64）用于把改动存成 config.json
远程配置: 每 24h 下载一次（config-api.js:57-85）；下载策略=先经本地代理、再直连（:154-167）；
         内容不变不落盘（:576-579）；落盘前处理"远程与用户重复项"（remote-overrides）
代理进程输入: server 模块把 allConfig.server 深拷贝 + 注入插件规则/远程拉取对抗规则
             → 写 running.json → fork mitmproxy.js（core/src/modules/server/index.js:93-174）
```

**(f) 插件系统**

```
core/src/expose.js:18-56
  每个插件 = { key, config（默认配置，会 addDefault 到 config）, status, plugin: Factory(context) }
  启动时（expose.js:64-113）: conf.plugin[key].enabled && !status → plugin.start()
  插件拿到 context = { config, shell, status, event, log }
实际插件:
  git    写 git config（http.proxy / sslVerify / noProxy），无开关时默认关闭
  node   写 npm/yarn 配置与环境变量（ELECTRON_MIRROR 等）
  pip    写 pip.ini（registry/trusted-host）
  overwall  增强模式：把命中域名反代到 ow-prod.docmirror.top 并带 dspassword 头（默认关闭，且 setting.json 需 overwall:true）
  free_eye  网络环境诊断（Route/DNS/TCP/TLS 检查点，向 8.8.8.8 等发探测）
  share     P2P 节点共享（TLS 服务端 + UPnP/STUN）
  xget      把 xget 实例地址生成为加速规则覆盖层
Android 结论: 全部不移植；仅"规则覆盖层"思路（xget）可作为后续自定义规则源的参考。
```

---

## 3. 必须精确复现的算法与配置语义

> 本节是给实现者的"规格书"；每小节末尾给出"最小复现测试"。

### 3.1 域名匹配语义（规则引擎核心，务必逐条对齐）

源码：`mitmproxy/src/utils/util.match.js`。

**3.1.1 域名表 → 编译（`domainMapRegexply`，util.match.js:44-76）**

输入是 `{ "pattern": value }` 形式的域名表（`intercepts`、`whiteList`、`preSetIpList`、`dns.mapping`、`timeoutMapping`、`ech.domains` 等都用它）。编译规则：

1. key 以 `.` 开头（如 `.xxx.com`）：若表中同时存在 `*.xxx.com`，忽略 `.xxx.com`；否则视为 `*` + key。
2. key 含 `*` 或首字符为 `^`：
   - 首字符非 `^` 时，转成锚定正则：`^` + 把 `.` 转义为 `\.` + 把 `*` 转成 `.*` + `$`（注意：`*` 是 `.*` 而不是 `[^.]+`，即 `*.a.com` 能匹配 `x.y.a.com`）。
   - 同时，如果 key 形如 `*xxx`（首尾都只有一个 `*` 前缀），额外放入 `origin` 快查表。
   - 首字符为 `^` 的**原样作为正则**（不转义、不加锚点）。
3. 不含 `*`、不以 `^` 开头：直接放 `origin` 快查表（精确匹配）。

**3.1.2 单值匹配（`matchHostname`，util.match.js:78-123）——用于"域名级判定"**

优先级：`origin[hostname]` → `origin["*."+hostname]` → `origin["*"+hostname]` → 按插入顺序遍历正则表，第一个 `hostname.match(regexp)` 命中的值返回。全部不命中返回 `undefined/null`。

> 用途：`sslConnectInterceptor`（是否 MITM）、`dns.getDNSAndFamily` 的预置 IP/DNS 选择、`speedTest` 的 DNS 选择、`timeoutMapping`、`overwall.targets`。

**3.1.3 多值合并匹配（`matchHostnameAll`，util.match.js:133-212）——用于 `intercepts`**

对**同一 host** 汇总所有命中规则，优先级从低到高；高优先级使用深合并覆盖（数组会被整体替换）：

| 顺序 | 来源 | 优先级 |
|---|---|---|
| 1 | 所有正则键（按对象属性插入顺序遍历，逐个 merge） | 最低 |
| 2 | `origin["*"+hostname]` | 2 |
| 3 | `origin["*."+hostname]` | 3 |
| 4 | `origin[hostname]` 精确 | 4（最高，可覆盖/用 null 删掉低优先级配置） |

细节：
- 若某条规则值为 `null`，最终 `deleteNullItems` 会把它删掉（表示"关闭此规则"）；实测官方配置用 `null` 来禁用过宽的规则（如 `github.com` 的 `^([\w-.]+){2,}` 规则、`proxy.excludeIpList` 里的覆盖）。
- 正则命中且含捕获组时，合并结果带 `matched` 数组，供 `proxy/redirect` 目标里的 `${p[0]}`、`${p[1]}` 占位符使用；`hostname` 正则命名组用 `${name}`（`proxy.js:20-37`）。
- 路径匹配（`isMatched`，util.match.js:14-35）：
  - 特殊值 `.*` / `*` / `true` / `"true"` → 恒命中，返回 `[path]`；
  - 否则把正则字符串编译为 `RegExp`（有 512 条 LRU 缓存）；若首字符是 `*`/`?`/`+`，前面补一个 `.`（让 `*.js` 之类也能当正则用）；
  - 用 `path.match()`；编译失败返回 null（视为不命中）。
- **排除规则**（`options.js:248-267`）：规则对象里的 `exclusions` 可以是数组或对象（取 key），任一排除正则命中 → 跳过该拦截器。

**3.1.4 白名单语义（`options.js:178-202`）**

`whiteList` 命中的域名**不做 MITM**，CONNECT 直通隧道（但仍可被 `dns` 等影响吗？不会：直连时也走 `dnsLookup` 的 provider 选择，见 `createConnectHandler.js:136-145`）。`whiteList` 里的 `null` 值配合 `deleteNullItems` 表示删除默认项（如 `cn.*: null`）。

### 3.2 拦截器族（按 priority 升序执行）

源码：`mitmproxy/src/lib/interceptor/index.js:24-43` 注册，`impl/**` 实现。每个拦截器有 `is(interceptOpt)` 判定是否生效；同名拦截器按规则里的 `order` 去重（order 大者替换小者，`options.js:283-291`）。下表为全部内置拦截器（priority 已核对源码）：

| # | 名称 | 阶段 | priority | 触发键（is） | 行为要点 |
|---|---|---|---|---|---|
| 1 | retry | req | 100 | `retry` | `{times, statuses(默认[500]), methods(默认 GET/HEAD/OPTIONS/…)}`；仅对已缓冲 body 或 GET 生效（`createRequestHandler.js:598-633`） |
| 2 | options | req | 101 | `options` | CORS 预检直接回 204/200（`impl/req/OPTIONS.js`） |
| 3 | success | req | 102 | `success` | 直接返回配置的 200/自定义状态码+headers+body（html/json/script/css/text）；`success: true` 返回内置文本（`success.js:6-89`） |
| 4 | abort | req | 103 | `abort` | 直接断开连接（`impl/req/abort.js`） |
| 5 | cacheRequest | req | 104 | `cacheSeconds/cacheMinutes/cacheHours/cacheDays/cacheWeeks/cacheMonths/cacheYears/cacheMaxAge/cache` | GET 且带 `if-modified-since` 且未过期 → 直接 304（`cacheRequest.js:60-107`） |
| 6 | redirect | req | 105 | `redirect` | 302 + Location（目标同样支持占位符/replace） |
| 7 | requestReplace | req | 111 | `requestReplace` | 改请求头（值 `"[remove]"` 删头）；`doDownload:true` 时去掉 `?DS_DOWNLOAD` 并打下载标记（`requestReplace.js:6-56`） |
| 8 | proxy | req | 121 | `proxy` | 把目标 host/port/path 改写成 `proxy` 值（支持绝对 URL/路径拼接/`${host}`/`${p[n]}`/`${h[n]}`/命名组）；`backup[]` 走成功率动态选择；可带 `sni/verifyHost/unVerifySsl`（`proxy.js:39-166`） |
| 9 | sni | req | 123 | `sni !== undefined && sni !== null` 或 `verifyHost != null`；若配置了 `proxy` 则跳过 | 改写上游 `servername`；`sni: ""` 表示不发 SNI；实测会顺带把 agent 切到 unVerifySsl（`sni.js:1-43`） |
| 10 | unVerifySsl | req | 124 | `unVerifySsl === true` 或 `ssl === false` | 切到不校验上游证书的 agent（`unVerifySsl.js`） |
| 11 | baiduOcr | req | 131 | `baiduOcr` | 百度 OCR 相关特殊处理（beta 不需要） |
| 12 | AfterOPTIONSHeaders | res | 201 | `options` | 预检响应补头（`AfterOPTIONSHeaders.js:45-46`） |
| 13 | cacheResponse | res | 202 | 同 cacheRequest 的 key | 仅 GET、状态码默认 200~303：改写 `Cache-Control/Last-Modified/Expires`（默认 `public, max-age=<n+1>, immutable`；`cacheExcludeStatusCodeList` 可排除；原 max-age 更大时优先保留原值，`cacheResponse.js:8-113） |
| 14 | responseReplace | res | 203 | `responseReplace` | 仅 200：改/删响应头（`"[remove]"` 删）；`doDownload` 时加 `content-disposition: attachment` 与 octet-stream，且无缓存配置时删缓存头（`responseReplace.js:72-118`） |
| 15 | script | res | 211 | `script` | 向 HTML/JS 注入/替换脚本（油猴脚本、GitHub 增强下载等；依赖 `setting.script`） |

> 官方配置实际用到的键（从 `~/.dev-sidecar/running.json` 统计）：`sni` 出现最多，其次 `proxy`、`cacheDays`、`script`/`tampermonkeyScript`、`responseReplace`、`requestReplace`、`success`、`redirect`、`backup`。**MVP 必须实现：proxy、sni、redirect、success、cacheDays（cacheRequest/Response）、responseReplace、requestReplace；其余可返回"未实现"日志并放行。**

### 3.3 TLS 上游参数

| 项 | 值/语义 | 证据 |
|---|---|---|
| TLS 版本 | 默认 `minVersion=TLSv1.3, maxVersion=TLSv1.3`；`setting.allowTls12=true` 才允许 1.2；可按域名 `setting.tlsVersionMapping` 覆盖 | `mitmproxy/src/lib/proxy/common/util.js:39-66` |
| 超时 | `timeout`（请求 socket 空闲超时，默认 20000；`timeoutMapping.github.com` 同为 20000）；`keepAliveTimeout` 默认 30000；`freeSocketTimeout` 默认 5000 | `core/src/config/index.js:121-134`；`util.js:30-58` |
| 建连超时 | 上游建连（含 TLS）7s；ECH 请求 30s；>8s 视为"慢"并计入失败统计 | `createRequestHandler.js:18-24,403-405,454` |
| 请求超时 | `max(agent.timeout*2, 10000)` ms（socket 无数据即超时） | `createRequestHandler.js:546-552` |
| 证书校验 | 默认 `setting.verifySsl=true`；SNI 诱饵规则会切换为 unVerifySsl；`compatible.request` 可按域名强制 `rejectUnauthorized:false` 并写入 `~/.dev-sidecar/automaticCompatibleConfig.json` | `core/src/config/index.js:107-110`；`sni.js:23-27`；`compatible/compatible.js`；实测 `automaticCompatibleConfig.json` |
| HTTP response 头上限 | `maxHeaderSize: 65536` | `common/util.js:211-214` |
| 自动兼容 | 连接/请求两阶段自动学习 `ssl` 与 `rejectUnauthorized`（`compatible/compatible.js`；FakeServersCenter.js:78-83,207-216；createRequestHandler.js:368-378,527-529） | 可后置，beta 可不做 |

### 3.4 DNS provider 语义

源码：`mitmproxy/src/lib/dns/index.js`、`base.js`、`https.js`、`udp.js`、`tcp.js`、`tls.js`、`preset.js`、`nat64.js`。

**3.4.1 provider 类型推断（dns/index.js:36-98）**

`server` 字段决定类型（也可显式 `type`）：

| 前缀 | 类型 | 默认端口 | 实现 |
|---|---|---|---|
| `https://` 或 `http://`（无 path 时自动补 `/dns-query`） | DoH | 443/80 | `DNSOverHTTPS`：RFC8484 wire format（POST application/dns-message），失败回退 JSON API（`name&type=HTTPS`） |
| `tls://` 或 `dot://` | DoT | 853 | `DNSOverTLS` |
| `tcp://` | DNS over TCP | 53 | `DNSOverTCP` |
| 其它（含 `udp://`） | DNS over UDP | 53 | `DNSOverUDP` |
| （内置）`PreSet` | 预置 IP 表 | — | `DNSOverPreSetIpList`，只查 `preSetIpList` + 缓存 |
| （内置）`Nat64` | NAT64 | — | 见 §3.9 |

provider 级配置字段：`server`（必填）、`type`、`port`、`family`（4/6，影响 DNS 查询类型 A/AAAA）、`sni`/`servername`（DoT/DoH 的 TLS SNI，DoH 还会 `rejectUnauthorized:false` 以兼容 IP 直连 DoH）、`cacheSize`（默认 1024）、`forSNI`/`forSni`（设为 SNI 未配 DNS 时的默认 provider，`dnsMap.ForSNI`，默认退化为 PreSet）、`ech:false`（单 provider 关闭 ECH）。证据：`dns/index.js:100-115`；`https.js:67-90`。

**3.4.2 默认 provider（官方配置实际生效值）**

- `local: 127.0.0.1`（UDP 53，即本机 DNS）
- `aliyun: 223.5.5.5`（UDP）
- `safe360: tls://dot.360.cn` + `forSNI: true`（国内 DoT，作为"没配 DNS 但有 SNI"时的默认）
- `cf-DoT: tls://1.1.1.1` + `sni: "baidu.com"`（Cloudflare DoT 也玩诱饵 SNI）
- `bebasid: tls://dns.bebasid.com` + `sni: "baidu.com"`
- `cf-DoH: https://cloudflare-dns.com/dns-query` + `sni`（源码默认 `baidu.com`；实测运行配置为 `"cf"`，属于配置差异/异常值，移植时不要硬编码该诱饵）
- `quad9-ip: https://9.9.9.9/dns-query`、`cf-ip: https://1.1.1.1/dns-query`（IP 直连 DoH）
- `rubyfish: https://rubyfish.cn/dns-query`（国内 DoH）
证据：`core/src/config/index.js:1402-1435`；实测 `~/.dev-sidecar/running.json` 同结构。

**3.4.3 缓存与 TTL（重点，容易移植错）**

- IP 缓存：每个 provider 一个 `LRUCache(maxSize=cacheSize 默认 1024)`，key=hostname，value=`IpCache extends DynamicChoice`；**没有任何 TTL 过期机制**（`base.js:82-87`）。缓存项内部维护候选 IP 列表与失败计数。
- 解析流程（`base.js:323-397`）：
  1. 命中缓存：若 `options.ipChecker` 存在且判定当前 value 可用 → `doCount(ip,false)` 并直接返回；不可用 → 返回 hostname（触发上层 fallback）。
  2. 未命中：创建 `IpCache`；查询（优先预置 IP）得到 `ipList`；本机无 IPv6 时过滤 IPv6（见 3.4.5）；**末尾追加 hostname 作为兜底项**；`setBackupList(ipList)` 后当前 value = 第一个 IP。
  3. `ipChecker` 存在时按列表顺序找第一个可用 IP 返回，否则返回当前 value。
- 失败反馈：`DynamicChoice.doCount(ip, isError)`（`choice/index.js:139-166`）：
  - 每次成功 `keepErrorCount=0`、`total++`；每次失败 `error++`、`keepErrorCount++`、`total++`；
  - `successRate = 1 - error/total`；
  - 当失败的是"当前 value"时：**连续失败 ≥1 次即切换到下一个候选**（源码注释："坏 IP 不值得等 3 次×15s=45s"）；或成功率 <0.4 也切换。
  - `changeNext` 会把该候选的 error/total 清零并取 `backupList` 下一个作为 value（`choice/index.js:84-98`）。
- 上层 fallback（`dnsLookup.js:90-267`）：
  - 预置 IP / DNS 返回 `hostname` 本身（说明该 provider 无可用 IP）→ 用其它 provider 兜底（`getFallbackDnsList` 排除 PreSet 与当前 provider）→ 仍失败 → 系统 DNS；
  - 系统 DNS 失败（如国内解析境外域名 EAI_AGAIN）时也会用 provider 列表兜底一次（`createDefaultLookupFunc`，dnsLookup.js:272-339）。
- ECH 缓存有 TTL（与 IP 缓存不同）：`emptyTtl=600000ms`、`minTtl=60000ms`、`maxTtl=3600000ms`，按 DNS 记录 TTL 截断（`base.js:52-57,199-226`）。

**3.4.4 DNS 相关响应头（可观测契约）**

- `DS-DNS`：最终使用的 provider 与 IP，如 `PreSet: 20.27.177.113 (IPv4)`、`safe360: 120.253.253.107 (IPv4)`、`default: 172.66.147.243 (IPv4)`；带 IP 请求（host 是 IP）时为 `host: <ip>`。
- `DS-Proxy-Request`：改写后的完整上游 URL；`DS-Proxy-Request-Family`：4/6。
- `DS-Interceptor`：命中的拦截器描述（sni/proxy/redirect/cache…）；`DS-ECH`：`1`/`0` 是否 ECH 握手成功；`DS-Retry`：重试次数；`DS-Overwall`、`DS-Cache-*` 等为对应拦截器。
- 证据：`dnsLookup.js:52-59,115-142,218-254`；`createRequestHandler.js:305-357,366,387,457-462,615-627`。

**3.4.5 IPv6 处理**

- 启动时检测本机是否有非 `fe80::/10` 的 IPv6 地址；没有则过滤所有 IPv6 DNS 结果（`base.js:9-22`）。
- 运行中累计 3 次 `ENETUNREACH` 也判定 IPv6 不可用并禁用（`base.js:24-34`；`createRequestHandler.js:520-522`）。
- `familyMapping` 可把域名强制为 IPv4（`dns.familyMapping`，编译为 `{dnsName, family}`，`options.js:49-70`；`dns/index.js:288-291`）。
- Android 上等价做法：`NetworkCapabilities.hasTransport(TRANSPORT_WIFI/CELLULAR)` 不能判断 IPv6；应直接尝试 `Inet6Address` 连接并在失败时标记禁用（对应实现见契约 `DnsResolver` 的 family 处理）。

### 3.5 IP 测速与优选

源码：`mitmproxy/src/lib/speed/index.js`、`SpeedTester.js`、`config.js`。

- 启动：`dns.speedTest.enabled=true` 时，对 `hostnameList`（默认 `["google.com","github.com"]`）各建一个 `SpeedTester`；之后 `getSpeedTester(host, port)` 对**任意域名**按需懒创建（`speed/index.js:32-55,74-79`）。
- 每 60s（`interval`）重测：`startChecker` 在最近 1 小时无访问后停止（`DISABLE_TIMEOUT=1h`，`SpeedTester.js:125-142`）。
- 测速 DNS 选择（`SpeedTester.js:144-181`）：① 命中 `preSetIpList` → 只用 PreSet；② 命中 `dns.mapping` → 用该 provider + family；③ 否则用 `dns.speedTest.dnsProviders`（默认 `["cf-DoT","safe360"]`）全部并发查询。
- 测试方法（`SpeedTester.js:327-380`）：对每个 IP 做 **TCP connect 到该域名的端口（通常 443），超时 5s**，记录握手耗时 `time`；全部并发测试，按 time 升序 → `alive` 列表；失败且无历史 time 的标记 `status:'failed'`。**不做 TLS 握手、不发 HTTP 请求、不做 ICMP。**
- 选用（`dnsLookup.js:127-171`）：请求时若 `alive[0]` 可用 → 直接用它（写 DS-DNS 标注 provider）；否则调用 `pickNextForProbing()` 轮转分配未失败 IP；`SpeedTester.ready` 后每条请求还会通过连接结果反馈 `reportProbeResult(ip, success)` 更新 alive/failed（`createRequestHandler.js:397-399,440-442,514-516`）。
- `pickFastAliveIpObj` 在无 alive 时按 `tryTestCount % 10` 触发全量重测/只重测备份（`SpeedTester.js:97-116`）。
- 选择结果的 fallback 顺序：speedTest alive IP → 预置 IP → 指定 DNS provider → 其它 provider → 系统 DNS（与 §3.4.3 一致）。
- 配置键：`dns.speedTest.{enabled, interval, hostnameList, dnsProviders}`；**未显式配置 `enabled` 视为关闭**（源码专门加日志防静默失效，`speed/index.js:38-45`）。
- 与 Cloudflare 路由联动：`cloudflareRoute` 启用时测速前等待其就绪（`SpeedTester.js:243-257`）；beta 不做 CF 路由。

> 结论：这是一套"代理内的 TCP 握手延迟选优"，不是真正的端到端质量测速；Android 版可原样移植（`Socket.connect` + 计时），并保留"每个 host 单飞测速 + 定时重测"。

### 3.6 重试与失败语义

- 可重试错误：连接超时（`retryable=true`）、请求超时（`ETIMEOUT/408`）、上游 error（`e.retryable=true`）（`createRequestHandler.js:433-454,490-508,509-524`）。
- 重试次数：若规则配置了 `retry` 用其 `times`；否则 GET 且 body 已缓冲时 1 次，其它 0 次（`createRequestHandler.js:606-620`）。
- 请求体缓冲上限 1MB（`MAX_RETRY_BODY_SIZE`）；chunked/Expect:100-continue/POST 无 Content-Length 不重试（`createRequestHandler.js:41-87,263-285`）。
- 重试时响应头加 `DS-Retry: n`；配了 `retry.statuses` 时收到这些状态码也会重试（默认 `[500]`，`impl/req/retry.js`）。
- 最终失败输出：浏览器导航（Accept: text/html 或 Sec-Fetch-Dest=document/iframe）→ HTML 错误页；XHR/JSON → JSON 错误体；资源 → text/plain；状态码取 `e.status || 500`；带 `Access-Control-Allow-Origin` 反射（`createRequestHandler.js:739-835`）。
- 兜底：60s 无任何响应写入 → 强制 504（`emptyGuard`，`createRequestHandler.js:130-169`）。
- CONNECT 直连失败：写 `HTTP/1.1 400/408` 后断开（`createConnectHandler.js:162-196`）。

### 3.7 `proxy` 目标 URL 构造（镜像/反代改写）

`proxy.js:39-63 buildTargetUrl` 的精确语义（镜像规则全部依赖它）：

1. 若规则对象带 `replace` 字段：`target = rOptions.path.replace(new RegExp(replace), proxyConf)`（正则替换，支持 `$1` 反向引用）。
2. 若 `proxy` 值以 `http:`/`https:` 开头：直接作为目标 URL。
3. 否则：`target = proxyConf + path`（path 为请求的 path+query；若 path 本身是绝对 URI 先取 path）。
4. 占位符替换：`${host}` → 真实 hostname；`${p[0]}…`/`${p[name]}` → 路径正则捕获组；`${h[0]}`/`${h[name]}` → hostname 正则捕获组；剩余未替换的 `${...}` 全部删除。
5. 若无协议前缀，补 `rOptions.protocol + "//"`。
6. 生效后改写 `rOptions` 的 protocol/hostname/host/port/path 与 `headers.host`；原始值备份到 `rOptions.original`（`doProxy`，proxy.js:65-89）。
7. `backup` 数组：以 `${hostname}/${interceptOpt.key}` 为 key 走 `RequestCounter` 成功率动态选择（`proxy.js:102-125`；`choice/index.js`）。

官方配置实例（实测运行配置）：`fonts.googleapis.com → proxy: fonts.googleapis.cn`、`ajax.googleapis.com → ajax.proxy.ustclug.org`、`www.google.com/recaptcha/* → www.recaptcha.net`、`cdn.jsdelivr.net → fastly.jsdelivr.net`（backup `gcore.jsdelivr.net`）、`repo1.maven.org → maven.proxy.ustclug.org`、`packages.elastic.co → elastic.proxy.ustclug.org`、`*.googlevideo.com → ${pre}.xn--ngstr-lra8j.com`（联网正则命名组 `(?<pre>.*)`）。

### 3.8 ECH（RFC 9848/9460）——Android beta 不支持，需降级

流程（`dns/index.js:303-459` + `createRequestHandler.js:380-385,457-462` + `ech/EchHttpsAgent.js`）：

1. 仅对 `server.dns.ech.domains` 名单（通配符/正则）内、且 `ech.enabled/use` 未关闭的域名启用；
2. 通过 DoH 查 `HTTPS(65)` 记录：优先 wire format（`application/dns-message`），失败回退 JSON；解析 SvcParam `ech`，支持 AliasMode 最多跟随 3 层；
3. 取不到域名自己的 ech 时回退共享配置 `ech.publicName`（默认 `cloudflare-ech.com`）的 ech 记录；
4. 上游 TLS 时把 echConfigList 注入 ClientHello（自研 `EchHttpsAgent`/`tls13`），失败自动回退原生 TLS；响应头 `DS-ECH` 标记结果；
5. ECH 域名强制忽略 `sni` 改写与预置 IP（除非在 `ech.preSetIpDomains`），且强制 MITM（`options.js:139-146,195-199`；`options.js:18-20` 的 `ECH_IGNORED_INTERCEPTORS`）。

Android：**Conscrypt/Java 没有公开的 ECH 客户端 API**（截至 Android 15 SDK），无法原样实现。beta 策略：识别 ECH 域名后**不发 ECH、用真实 SNI 直接 TLS**（等于放弃该机制），并在设置中隐藏 ECH；文档标注"ECH 域名仍可能被 SNI 阻断"。这属于已知功能缺口，不应伪装为已支持。

### 3.9 NAT64（可后置）

`dns/nat64.js`：把真实 IPv4 解析结果嵌入用户配置的 NAT64 前缀（如 `64:ff9b::/96`）得到 IPv6 地址，走 IPv6 直连；要求域名在 `dns.nat64.domains` 且 `prefix` 非空（默认 `prefix:''` 即事实禁用，“不内置任何公共 NAT64 网关”，`mitmproxy/src/options.js:119-137`）。NAT64 域名同时按 ECH 域名处理（`dns/index.js:142-168`）。beta 不做。

### 3.10 读/写缓存与容量（内存参数，移植时保持同量级）

| 缓存 | 容量/参数 | 证据 |
|---|---|---|
| MITM 假服务器 | `fakeServerMaxLength`（默认 100，LRU，key=`dnsName:port:ssl`） | `core/src/config/index.js:106`；`FakeServersCenter.js:37-72` |
| 叶证书 | 容量=fakeServerMaxLength（LRU，key=dnsName） | `CertAndKeyContainer.js:7-50` |
| DNS IP 缓存 | 每 provider 1024 条（LRU，无 TTL） | `base.js:49,82-87` |
| URL 正则编译缓存 | 512 条 | `util.match.js:7-12` |
| 每域名 path 拦截器缓存 | 512 条 | `options.js:16,210-226` |
| ECH 配置缓存 | 512 条 + TTL（10min/1min/1h） | `base.js:52-57,123-135` |
| 请求计数器（备用域名） | 1024 条 | `choice/index.js:4-14` |
| 网络测速 | 每组 host 一个 tester，interval 60s | `speed/index.js:32-55` |

---

## 4. 配置契约（键名、默认值、语义）

### 4.1 顶层结构（`running.json` = fork 给代理引擎的完整 server 配置）

| 键 | 类型 | 默认 | 语义/约束 |
|---|---|---|---|
| `server.enabled` | bool | true | 代理服务总开关 |
| `server.host` | string | `127.0.0.1` | 代理端口绑定地址（Android 无 root 时应保持回环；只对本机 App/VPN 可用） |
| `server.port` | int | **31181** | 唯一监听端口，HTTP+HTTPS CONNECT 共用；**不要改默认值** |
| `server.fakeServerMaxLength` | int | 100 | 假服务器/证书 LRU 容量上限 |
| `server.setting.NODE_TLS_REJECT_UNAUTHORIZED` | bool | true | false 时设置 `process.env.NODE_TLS_REJECT_UNAUTHORIZED=0`（全局关闭校验，危险，Android 不应复刻该全局开关） |
| `server.setting.verifySsl` | bool | true | 上游证书校验总开关（SNI 诱饵规则会临时关闭） |
| `server.setting.allowTls12` | bool | false | 允许 TLS1.2（默认 min/max=TLS1.3） |
| `server.setting.script.{enabled,defaultDir}` | bool/string | true/`./extra/scripts/` | 脚本拦截器目录 |
| `server.setting.userBasePath` | string | `~/.dev-sidecar` | 配置/CA/日志根目录；Android 用 `context.filesDir` |
| `server.setting.rootCaFile.{certPath,keyPath}` | string | `~/.dev-sidecar/dev-sidecar.ca.crt` / `.key.pem` | CA 文件路径 |
| `server.setting.defaultTimeout` / `defaultKeepAliveTimeout` | int(ms) | 20000 / 30000 | 见 §3.3 |
| `server.setting.timeoutMapping` | map<域名, {timeout,keepAliveTimeout}> | github.com 20s/30s | 域名级覆盖，按 §3.1.2 匹配 |
| `server.setting.lowSpeedDelay` | int(ms) | 200 | 测速慢的展示阈值（UI 用） |
| `server.compatible.{connect,request}` | map | `{}` | 兼容配置：`connect['host:port'].ssl`；`request['host:port'][pathRegex].rejectUnauthorized` |
| `server.cloudflareRoute.{enabled,preferredEndpoint}` | bool/string | false/`''` | beta 不做 |
| `server.intercept.enabled` | bool | true | 总开关；false 时清空 `intercepts`（=全部直通） |
| `server.intercepts` | map<域名, map<路径正则, 规则对象\|null>\|null> | 官方规则表 | 核心规则表，语义见 §3.1/§3.2 |
| `server.preSetIpList` | map<域名, map<IP, bool\|对象>> | 官方规则表 | 预置 IP；value 真值生效（`true`/非空字符串/对象均算启用；`false/null/0` 不启用），对象可带 `desc`；命中后跳过 DNS，直接连该 IP |
| `server.whiteList` | map<域名, bool> | 见配置 | 命中则**不 MITM**（直连隧道），`cn.*`、`localhost` 等 |
| `server.dns.providers` | map<名称, provider> | 见 §3.4.2 | provider 定义 |
| `server.dns.mapping` | map<域名, provider名> | 见配置 | 域名→provider；value 也可为 `{dnsName, family}`（`options.js:49-70` 归一化） |
| `server.dns.familyMapping` | map<域名, 4\|6> | 见配置 | 强制 IP 族 |
| `server.dns.speedTest.{enabled,interval,hostnameList,dnsProviders}` | bool/int/array/array | true/60000/google.com,github.com/cf-DoT,safe360 | §3.5 |
| `server.dns.ech.{enabled,use,domains,preSetIpDomains,dns,publicName,cacheSize,emptyTtl,minTtl,maxTtl,tryAllProviders,parallelDelay}` | 混合 | enabled/use=true, dns=cf-DoH, publicName=cloudflare-ech.com, 512/600000/60000/3600000/true/200 | §3.8（beta 不支持，需解析但忽略） |
| `server.dns.nat64.{enabled,prefix,domains,dns}` | 混合 | true/`''`(=禁用)/chatgpt、openai/cf-DoH | §3.9 |
| `proxy.*` | 见 `core/src/modules/proxy/index.js` | — | 系统代理排除表、`proxyHttp`、`setEnv/setCaBundle`、国内域名白名单文件路径/更新 URL；**Android 不需要** |
| `plugin.*` | 见 §2.4(f) | — | Android 不需要 |
| `app.*` | GUI/元信息/远程配置 | — | Android 只需 `app.metaInfo`、远程配置 URL 等少量字段 |

### 4.2 `intercepts` 规则对象字段全表

| 字段 | 类型 | 语义（详见 §3.2） |
|---|---|---|
| `sni` | string | 上游 SNI 改写；`""`=不发 SNI |
| `verifyHost` | string | 证书校验用域名（换真域名诱饵时） |
| `proxy` | string | 目标地址/路径前缀（§3.7）；可配 `backup: [string...]` |
| `redirect` | string | 302 目标（支持 `${host}`/`${p[n]}`/`${h[n]}`/命名组） |
| `success` | true \| {status?, headers?, html?/json?/script?/css?/text?/body?} | 直接返回成功 |
| `abort` | true | 断开 |
| `cacheSeconds/cacheMinutes/cacheHours/cacheDays/cacheWeeks/cacheMonths/cacheYears/cacheMaxAge/cache` | number | 缓存时长（按最小可用键取） |
| `cacheExcludeStatusCodeList` | map<int,bool> | 不缓存的状态码 |
| `cacheMinStatusCode/cacheMaxStatusCode` | int | 默认可缓存范围 200~303 |
| `cacheControlType` | string | 默认 `public` |
| `cacheImmutable` | bool | 默认 true（加 immutable） |
| `requestReplace` | {headers?: map<string,string\|"[remove]">, doDownload?: bool} | 改请求头 |
| `responseReplace` | {headers?: map<string,string\|"[remove]">, doDownload?: bool} | 改响应头（仅 200） |
| `retry` | {times, statuses?: int[], methods?: string[]} | 自动重试 |
| `options` | true | CORS 预检处理 |
| `unVerifySsl` | bool | 关闭上游证书校验 |
| `ssl` | bool | 兼容配置等价 unVerifySsl（connect/request 阶段） |
| `script` / `tampermonkeyScript` | string(url)/... | 脚本注入（beta 可忽略） |
| `order` | int | 同名拦截器去重优先级 |
| `exclusions` | array \| object | 路径排除（§3.1.3） |
| `replace` | string(正则) | 与 proxy 配合，做路径正则替换 |
| `baiduOcr` | object | 百度 OCR 专用（忽略） |
| `desc/remark` | string | 仅备注（UI 展示） |

### 4.3 一份最小可运行配置示例（Android :core 应能解析）

```json
{
  "server": {
    "enabled": true,
    "host": "127.0.0.1",
    "port": 31181,
    "fakeServerMaxLength": 100,
    "setting": {
      "verifySsl": true,
      "allowTls12": false,
      "userBasePath": "/data/data/com.devs.sidebike/files",
      "rootCaFile": {
        "certPath": "/data/data/com.devs.sidebike/files/dev-sidecar.ca.crt",
        "keyPath": "/data/data/com.devs.sidebike/files/dev-sidecar.ca.key.pem"
      },
      "defaultTimeout": 20000,
      "defaultKeepAliveTimeout": 30000,
      "timeoutMapping": { "github.com": { "timeout": 20000, "keepAliveTimeout": 30000 } },
      "lowSpeedDelay": 200
    },
    "intercept": { "enabled": true },
    "intercepts": {
      "github.com": {
        ".*": { "sni": "baidu.com" }
      },
      "api.github.com": {
        ".*": { "sni": "baidu.com" }
      },
      "*.githubusercontent.com": {
        ".*": {
          "sni": "baidu.com",
          "requestReplace": { "headers": { "accept-language": "en-US,en;q=0.8" } }
        }
      },
      "fonts.googleapis.com": {
        ".*": { "proxy": "fonts.googleapis.cn" }
      },
      "www.google.com": {
        "/recaptcha/.*": { "proxy": "www.recaptcha.net" }
      },
      "cdn.jsdelivr.net": {
        "^/.*\\.(js|css|png|jpg|jpeg|gif|json)(\\?.*)?$": {
          "proxy": "fastly.jsdelivr.net",
          "backup": ["gcore.jsdelivr.net"]
        }
      },
      "github.githubassets.com": {
        "^(/[^/]+)*/[^./]+\\.(svg|png|gif|jpg|jpeg|ico|js|css)(\\?.*)?$": { "cacheDays": 365, "sni": "baidu.com" }
      }
    },
    "preSetIpList": {
      "github.com": {
        "20.27.177.113": true,
        "20.205.243.166": true,
        "140.82.121.3": false
      },
      "*.githubusercontent.com": {
        "185.199.111.133": true
      }
    },
    "whiteList": {
      "*.icloud.com": true,
      "localhost": true,
      "127.*.*.*": true,
      "192.168.*.*": true
    },
    "dns": {
      "providers": {
        "local": { "server": "127.0.0.1" },
        "safe360": { "server": "tls://dot.360.cn", "forSNI": true },
        "cf-DoH": { "server": "https://cloudflare-dns.com/dns-query" },
        "cf-DoT": { "server": "tls://1.1.1.1", "sni": "baidu.com" }
      },
      "mapping": {
        "*.github.io": "cf-DoT",
        "*.cloudflare.com": "cf-DoT"
      },
      "familyMapping": {
        "*.github.com": "4",
        "*.gstatic.com": "4"
      },
      "speedTest": {
        "enabled": true,
        "interval": 60000,
        "hostnameList": ["google.com", "github.com"],
        "dnsProviders": ["cf-DoT", "safe360"]
      },
      "ech": { "enabled": false, "use": false },
      "nat64": { "enabled": false, "prefix": "", "domains": [] }
    }
  }
}
```

> 兼容建议：Android 侧直接以"上游 `running.json` 的 `server` 节点"为输入模型（规则表原样导入），可最大程度复用官方规则、降低回归风险。JSON5 在构建期转成严格 JSON 后打进 assets（`core/src/config/remote_config.json5` 是 JSON5；解析器在 `mitmproxy/src/json`）。

---

## 5. 可移植性判定表（上游模块 → Android 决策）

| 上游模块 | Android 决策 | 理由 / 无 root 限制 |
|---|---|---|
| 单端口 HTTP 代理 + CONNECT（`mitmproxy/.../mitmproxy/index.js`） | **Kotlin 重写（必须）** | Node `http.Server` 不存在；用 `ServerSocket`+协程或 Netty。协议本身标准，可 1:1 实现 |
| 规则引擎（`util.match.js` + `options.js`） | **Kotlin 重写（必须）** | 语义复杂（§3.1），必须逐条对齐并做单测；纯数据逻辑，适合重写 |
| 拦截器族 | **移植 MVP 子集** | proxy/sni/redirect/success/cache/requestReplace/responseReplace；script 注入、baiduOcr、OPTIONS 后置 |
| MITM 假服务器（`FakeServersCenter`） | **Kotlin 重写** | Java `SSLServerSocket`/`SSLContext` + 自定义 `X509ExtendedKeyManager` 按 SNI 选证书；HTTP/1.1 必做，HTTP/2（ALPN h2）可选做（很多客户端可回退 1.1）。注意 Java 动态证书选择较麻烦：可先按"每域名一个 `SSLServerSocket`（监听回环随机端口）"实现，与上游结构一致，绕开 JDK 的 SNI 多证书限制 |
| CA 生成/叶证书签发（node-forge） | **重写（BouncyCastle）** | BC `bcpkix` 完全可做：自签 CA（RSA2048/SHA256/20 年）、按域名签叶证书（SAN 通配符）、见 §3.4(d)。**不要**用 Android 系统 keystore 生成（不能导出私钥给代理用） |
| CA 安装（`setup-ca.js`） | **重设计（不可照搬）** | 无 root 不能写系统 CA 信任库。做法：`Intent(Settings.ACTION_SECURITY_SETTINGS)` 引导用户在"加密与凭据→安装证书→CA 证书"手动安装；App 自身用 `network_security_config` 信任内置 CA（仅自己的网络栈）；对第三方 App 的 MITM 能力受"用户 CA 是否被该 App 信任"限制（Android 7+ 默认不信任用户 CA，只有声明了 `debug-overrides`/`trust-anchors user` 的 App）。**这是产品层硬限制，必须向 owner 报告** |
| 系统代理设置（Windows 注册表 / macOS networksetup / 环境变量） | **重设计** | 无 root 不能写 `Settings.Global.HTTP_PROXY`（需 `WRITE_SECURE_SETTINGS`，一般靠 adb 一次性授权）；可行替代：① 引导用户手动设置 Wi-Fi 代理到 `127.0.0.1:31181`（需 App 保持前台/前台服务）；② **VpnService + 自建 tun→本地代理转发**（无需 root，但用户需授权 VPN，且要自己实现 TCP/IP 栈或用现成库；工作量最大）；③ 仅服务 App 自身。**需 Lead 决策 beta 采用哪条** |
| 透明劫持全部 App 流量 | **重设计（VpnService）** | 无 root 唯一合法通道；需要 `BIND_VPN_SERVICE` + 用户授权，不能静默 |
| 进程归属统计（`traffic/processResolver`） | **砍掉** | Android 无 netstat 进程映射（需 root 读 `/proc/net/*` 反查 uid）；保留按域名/连接数的简化统计 |
| DNS 解析层 | **Kotlin 重写** | 无 Node 自定义 lookup 钩子；自己在连接前解析 IP（预置/DoH/DoT/UDP），再 `Socket.connect(ip, port)`。IPv6/回退/失败优选逻辑可完整移植。**注意：这不是 DNS 服务器** |
| ECH | **砍掉/降级** | Java/Conscrypt 无公开 ECH API；识别后走普通 TLS（真实 SNI），文档标注不支持 |
| NAT64 | **后置/砍掉** | 依赖网络环境与用户配置；beta 无收益 |
| 测速/优选 | **移植（简化版）** | `Socket.connect` 计时即可；注意 Android 后台限制（前台服务内跑） |
| Cloudflare 路由 | **砍掉** | 需拉取 CF IP 段与优选地址；默认关闭 |
| overwall/PAC/增强模式 | **砍掉** | 依赖 `ow-prod.docmirror.top` 反代服务（默认关闭，`setting.json.overwall=false`），且需要额外服务器端密码；不是本地能力 |
| git/node/pip 插件 | **砍掉** | 桌面开发环境专属（写 `.gitconfig`、`.npmrc`、`pip.ini`），Android 不适用 |
| free-eye / share(p2p) / xget | **砍掉** | 诊断/P2P/第三方实例，均非 beta 目标 |
| system-tray/GUI/IPC/单实例锁 | **砍掉** | Electron 专属 |
| 日志（log4js → 文件） | **移植（简化）** | Android 用 logcat + 环形文件日志；注意脱敏（上游有 `util.redact*.js`，凭据/授权头不落盘） |
| 配置远程下发 | **移植（简化）** | 下载远程 JSON5 → 校验 → 原子替换配置；可选实现，beta 可先内置 assets 规则 |

### 5.1 明确"无 root 做不到"清单

1. 写系统 CA 信任库 / 让任意第三方 App 信任用户 CA（Android 7+）。
2. 以静默方式设置全局 HTTP 代理（`Settings.Global.HTTP_PROXY`）——需 `WRITE_SECURE_SETTINGS`（adb 授权一次）或 root。
3. 绑定 1024 以下端口（如 80/443/53），除非用 VpnService 的 `protect()` + 本地高位端口转发。
4. 无感知透明代理其他 App 流量（必须 VpnService + 用户手动授权，且 VPN 生效期间不能与其他 VPN 共存）。
5. 全局环境变量/系统级 `.gitconfig`/`.npmrc` 改写（Android 无对应概念）。
6. 读取其他进程的连接归属（需 root `/proc`）。

### 5.2 Android beta 边界（与 Lead 架构决策对齐）

> **Lead 已在 `docs/architecture/01-architecture.md`（v0.1 决策版）拍板**：采用 VpnService 两条腿——① tun 只接管 DNS（`10.111.0.2:53`），在 tun 内解析 DNS 并返回防污染结果（对所有 App 生效、不需要 CA）；② API 29+ 用 `VpnService.Builder.setHttpProxy(127.0.0.1:31181)` 把本地代理设为系统代理；MITM 需要 CA，**默认关闭**（`mitm.enabled=false`），作为增强项。非目标：ECH、NAT64、p2p、git/node/pip 插件、root 方案。本节以下内容与该决策一致，仅补充后端视角的优先级。

- **P0（必须）**：`:core` 纯 Kotlin 后端 = 配置仓库 + 规则引擎 + DNS 解析层（预置 IP + UDP/DoH 两种 provider 足够）+ **DNS wire 应答器（供 VpnService tun 使用，见 §6.11）** + 测速优选（轻量版）+ MITM（CA/证书/假服务器，默认关闭）+ HTTP 代理（HTTP + CONNECT）+ sni/proxy/redirect/cache 五类拦截器 + DS-* 响应头 + 日志/统计。
- **P1（尽力）**：DoT、requestReplace/responseReplace、success/abort、远程配置。
- **P2（砍）**：ECH、NAT64、HTTP/2 MITM、TLS1.2 兼容矩阵、CF 路由、overwall、插件、进程统计、遥测。
- **接入方式**：先做"App 内/手动 Wi-Fi 代理可用"，VpnService 作为独立任务另排（工作量与风险需单独评估）。

---

## 6. Android `:core` 纯 Kotlin 后端契约（供实现者照此编码）

> 目标：`:core` 不依赖 Android UI、不依赖 root；只依赖 JDK + 协程 + BouncyCastle（证书）+ 少量 Android API（`Context.filesDir`、`VpnService` 由外层 `:app` 负责，不在 `:core`）。
> 命名空间建议：`com.devs.sidebike.core`。以下为**契约级**签名，字段名即 JSON 键名的映射来源；实现可增内部类，但不得改变外部语义。

### 6.1 包结构与职责

```
core/
├─ config/     SidecarConfig 数据模型 + ConfigRepository（JSON5/JSON 加载、4 层合并、用户覆盖持久化）
├─ rule/       RuleSet 编译、RuleEngine（域名/路径匹配、拦截器选择）、InterceptSpec 定义
├─ dns/        DnsResolver、DnsProvider(Udp/Doh/Dot)、PresetIpProvider、DnsCache、SpeedTester、IpChoice
├─ ca/         CertAuthority（CA 生成/持久化/叶证书签发）、FakeTlsServerPool、MitmDecision
├─ proxy/      ProxyServer（入口 ServerSocket）、ClientConnection、ConnectTunnel、HttpExchange、UpstreamClient
├─ intercept/  Interceptor 接口 + Proxy/Sni/Redirect/Success/Cache/RequestReplace/ResponseReplace 实现 + InterceptorChain
├─ stats/      StatsSink、RequestRecord、StatsSnapshot
├─ log/        Logger、Redactor
└─ api/        SidecarEngine（门面：start/stop/reload/status/config）
```

### 6.2 数据模型（与上游 JSON 一一对应）

```kotlin
package com.devs.sidebike.core.config

data class SidecarConfig(
    val server: ServerConfig,
    val app: AppConfig = AppConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
    val plugin: Map<String, PluginConfig> = emptyMap(), // 解析但忽略
)

data class ServerConfig(
    val enabled: Boolean = true,
    val host: String = "127.0.0.1",
    val port: Int = 31181,
    val fakeServerMaxLength: Int = 100,
    val setting: ServerSetting = ServerSetting(),
    val compatible: CompatibleConfig = CompatibleConfig(),
    val intercept: InterceptToggle = InterceptToggle(true),
    val intercepts: Map<String, Map<String, InterceptRule?>> = emptyMap(),
    val preSetIpList: Map<String, Map<String, Any?>> = emptyMap(),   // 值可为 true/false/对象，统一按"真值"判断
    val whiteList: Map<String, Boolean?> = emptyMap(),
    val dns: DnsConfig = DnsConfig(),
    val cloudflareRoute: CloudflareRouteConfig = CloudflareRouteConfig(false, ""),
)

data class ServerSetting(
    val verifySsl: Boolean = true,
    val allowTls12: Boolean = false,
    val script: ScriptSetting = ScriptSetting(true, "./extra/scripts/"),
    val userBasePath: String = "",          // 由 :app 注入 context.filesDir.absolutePath
    val rootCaFile: RootCaFile = RootCaFile(), // 由 :app 注入
    val defaultTimeout: Int = 20_000,
    val defaultKeepAliveTimeout: Int = 30_000,
    val timeoutMapping: Map<String, TimeoutConfig> = emptyMap(),
    val lowSpeedDelay: Int = 200,
    val tlsVersionMapping: Map<String, Any?> = emptyMap(),
)

data class DnsConfig(
    val providers: Map<String, DnsProviderConfig> = emptyMap(),
    val mapping: Map<String, Any?> = emptyMap(),        // 值可为 provider 名或 {dnsName, family}
    val familyMapping: Map<String, Any?> = emptyMap(),
    val speedTest: SpeedTestConfig = SpeedTestConfig(),
    val ech: EchConfig = EchConfig(),                   // beta 解析但不在握手使用
    val nat64: Nat64Config = Nat64Config(),
)

data class DnsProviderConfig(
    val server: String,                 // 必填；类型推断规则见 §3.4.1
    val type: String? = null,           // https|doh|tls|dot|tcp|udp
    val port: Int? = null,
    val family: Int? = null,            // 4|6
    val sni: String? = null,
    val servername: String? = null,
    val cacheSize: Int = 1024,
    val forSNI: Boolean = false,
)

data class SpeedTestConfig(
    val enabled: Boolean = false,       // 缺省视为关闭（与上游一致）
    val interval: Long = 60_000,
    val hostnameList: List<String> = emptyList(),
    val dnsProviders: List<String> = emptyList(),
)

data class InterceptRule(
    val sni: String? = null,            // "" 表示禁用 SNI
    val verifyHost: String? = null,
    val proxy: String? = null,
    val backup: List<String> = emptyList(),
    val replace: String? = null,
    val redirect: String? = null,
    val success: SuccessRule? = null,
    val abort: Boolean? = null,
    val retry: RetryRule? = null,
    val cache: CacheRule? = null,
    val requestReplace: RequestReplaceRule? = null,
    val responseReplace: ResponseReplaceRule? = null,
    val options: Boolean? = null,
    val unVerifySsl: Boolean? = null,
    val script: String? = null,
    val order: Int = 0,
    val exclusions: List<String> = emptyList(),
)
// SuccessRule(status, headers, bodyBytes, contentType)；CacheRule(maxAgeSeconds)；
// RequestReplaceRule(headers: Map<String,String?>, doDownload)；ResponseReplaceRule(headers, doDownload)
// RetryRule(times, statuses=[500], methods=[GET,HEAD,OPTIONS,DELETE,PUT,POST,PATCH])
```

**配置仓库契约**

```kotlin
interface ConfigRepository {
    /** 加载并合并（顺序：defaults → sharedRemote → personalRemote → userOverride；数组替换、null/[delete] 删除） */
    suspend fun load(): SidecarConfig
    /** 当前生效配置（不可变快照；热更新时原子替换） */
    val config: StateFlow<SidecarConfig>
    /** 局部更新（如 GUI 改某个开关）；持久化为用户覆盖文件后 reload */
    suspend fun update(patch: JsonObject): SidecarConfig
    /** 远程配置下载（可选；beta 可只读 assets） */
    suspend fun refreshRemote(url: String): Boolean
    /** 用户覆盖文件路径（Android：filesDir/config.json） */
    val userOverridePath: String
}
```
- **约定**：任何字段缺失都必须回落到上表的默认值；未知字段忽略但保留（便于兼容未来官方配置）。
- **约定**：`intercepts` 里的 `null` 值表示"删除该键/规则"，编译期丢弃。
- **约定**：配置变更（尤其 `intercepts/dns`）必须**重建 RuleSet + DnsRegistry**；若代理正在运行，按上游行为需要重启监听或热替换（建议热替换，对外端口不变；这是 Android 相比上游 fork 模型的优势）。

### 6.3 规则引擎契约

```kotlin
package com.devs.sidebike.core.rule

/** 编译后的规则集；构造后不可变，线程安全 */
class RuleSet(config: ServerConfig) {
    fun isWhitelisted(host: String): Boolean
    fun isIntercepted(host: String): Boolean                   // 对应 sslConnectInterceptor 的域名级判定
    fun hasIntercepts(host: String): Boolean
    /** 路径级匹配 + 拦截器构建（含 exclusions/order/priority 排序），对应 createIntercepts(context) */
    fun interceptors(host: String, path: String): List<InterceptorSpec>
    fun dnsSelection(host: String): DnsSelection?              // 对应 dns.getDNSAndFamily（null=系统 DNS）
    fun presetIps(host: String): List<String>?                 // 命中 preSetIpList 时返回
    fun familyFor(host: String): Int?                          // familyMapping
    fun timeoutFor(host: String): TimeoutConfig
    fun matchedCaptures(host: String, path: String): MatchInfo // ${p[n]}/${h[n]}/${name} 用
}

data class DnsSelection(val providerName: String, val family: Int?)
enum class InterceptorKind { RETRY, OPTIONS, SUCCESS, ABORT, CACHE_REQUEST, REDIRECT, REQUEST_REPLACE, PROXY, SNI, UNVERIFY_SSL, AFTER_OPTIONS, CACHE_RESPONSE, RESPONSE_REPLACE }

data class InterceptorSpec(
    val kind: InterceptorKind,
    val priority: Int,
    val rule: InterceptRule,          // 已合并（matchHostnameAll 结果）
    val captures: MatchInfo?,         // 占位符数据
)
```
- 匹配优先级、`domainMapRegexply` 编译规则、`matchHostnameAll` 合并顺序、`isMatched` 特殊值/缓存、`exclusions` 行为：**必须逐条按 §3.1 实现**，并用 §8 的单元测试锁定。
- 线程安全：`RuleSet` 全局只读；每请求 `interceptors()` 内部可使用并发 Map 做 path 拦截器缓存（容量 512，LRU），但不得跨配置版本复用。

### 6.4 DNS 解析契约

```kotlin
package com.devs.sidebike.core.dns

interface DnsProvider {
    val name: String
    val type: DnsType                       // UDP/TCP/DOT/DOH/PRESET
    /** 返回 A 或 AAAA 记录（family 4/6），超时/失败抛 IOException，由上层决定 fallback */
    suspend fun query(host: String, family: Int): List<String>
    /** ECH 用：HTTPS(65) 记录；beta 可返回 null */
    suspend fun queryHttpsRecord(host: String): HttpsRecord? = null
}

class DnsRegistry(config: DnsConfig) {
    /** 对应 dns/index.js initDNS + getDNSAndFamily */
    fun select(host: String): SelectedDns?               // null = 用系统 DNS
    fun provider(name: String): DnsProvider?
    fun fallbackList(exclude: DnsProvider?): List<DnsProvider>
    suspend fun lookup(host: String, sel: SelectedDns?, opts: LookupOptions): LookupResult
    fun count(host: String, ip: String, isError: Boolean)
}

data class LookupOptions(
    val family: Int? = null,
    val ipChecker: ((String) -> Boolean)? = null,
    val ignorePreSetIp: Boolean = false,
    val resetOnHostnameFallback: Boolean = false,
)

sealed interface LookupResult {
    data class Ip(val ip: String, val family: Int, val provider: String) : LookupResult
    data class System(/** 交给平台 InetAddress 解析 */ val host: String) : LookupResult
    data class Failed(val cause: Exception) : LookupResult
}

/** 单主机解析入口（供 proxy 用）：speedTest → presetIp → provider → fallback provider → system */
interface DnsResolver {
    suspend fun resolve(host: String, port: Int, opts: LookupOptions = LookupOptions()): LookupResult
    fun selectedProviderName(host: String): String?     // 写 DS-DNS 用
    fun disableIpv6()
}
```
- 缓存语义：每 provider `Map<host, IpCache>`，容量 `cacheSize`（LRU），**无 TTL**；`IpCache` = 候选 IP 列表 + 当前 index + 每 IP `{total,error,keepErrorCount,successRate}`；失败计数规则按 §3.4.3（连续失败 1 次或成功率 <0.4 即切换）。
- 系统 DNS 兜底：`InetAddress.getAllByName(host)`（Android 实现会走系统解析器）；解析失败也要尝试 provider 兜底一次（对齐 `createDefaultLookupFunc`）。
- IPv6：连接失败累计 3 次 `ENETUNREACH`（Java: `SocketException` 判定）→ `disableIpv6()`，此后过滤 AAAA 结果。
- 所有阻塞网络调用必须在 `Dispatchers.IO`；同一 host 的并发解析必须"单飞"（Mutex/Deferred 去重），避免上游 DNS 放大。

### 6.5 MITM / CA 契约

```kotlin
package com.devs.sidebike.core.ca

data class CaMaterial(val certPem: String, val keyPem: String, val certDer: ByteArray)

interface CertAuthority {
    /** 幂等：存在即读，不存在生成（RSA2048/SHA256/20 年；subject 对齐 §3.4(d)） */
    suspend fun ensureCa(certPath: String, keyPath: String): CaMaterial
    /** 按 host 签发叶证书；dnsName 规则：a.b.com → *.b.com，单级域名 → *.x，IP 原样；SAN=[dnsName, 去*.后的域名] */
    suspend fun issueLeaf(host: String, dnsName: String, validityDays: Int = 365): CertKeyPair
    fun caCertPem(): String       // 用于导出/安装引导
}

interface MitmServerPool {
    /** 等价 FakeServersCenter.getServerPromise：LRU(容量=fakeServerMaxLength)，key=dnsName:port:ssl */
    suspend fun acquire(host: String, port: Int, ssl: Boolean): FakeServerHandle
    suspend fun closeAll()
}
data class FakeServerHandle(val localPort: Int, val ssl: Boolean)

/** 域名级拦截判定（§3.1.2 语义） */
interface MitmDecision {
    fun shouldMitm(host: String, port: Int): Boolean
}
```
- 假服务器：监听 `127.0.0.1:0`；TLS 用本地 CA 叶证书；**MVP 只协商 HTTP/1.1**（Java `SSLServerSocket` 不主动 ALPN h2，客户端可回退）；若要做 h2，使用 `SSLEngine.setApplicationProtocols` + 独立 h2 帧处理，风险较高，P2。
- 证书缓存与签发串行化：`Mutex` + LRU；第一台设备冷启动首连时签发耗时可能 >100ms，需后台预热常用域名或允许"先回 502 重试"。
- 安全：CA 私钥只存 App 私有目录；不出现在日志；导出 CA 证书仅用于用户安装。

### 6.6 HTTP 代理契约

```kotlin
package com.devs.sidebike.core.proxy

interface ProxyServer {
    suspend fun start(host: String, port: Int)
    suspend fun stop()
    val isRunning: Boolean
    fun snapshot(): ProxyStats
}

data class ProxyStats(val activeConnections: Int, val totalRequests: Long,
                      val bytesUp: Long, val bytesDown: Long,
                      val perHost: Map<String, HostStat>)

/** 单条客户端连接的处理器（由 ProxyServer 分发） */
interface ConnectionHandler {
    suspend fun handle(socket: Socket)   // 解析 CONNECT / 绝对 URI / MITM 内层 HTTP
}

interface UpstreamClient {
    /**
     * 打开到目标的上游 TLS/Plain 连接：
     * @param ip 已解析 IP（null 表示由平台解析）
     * @param sni 上游 SNI（诱饵值；null=真实 host；""=不发 SNI）
     * @param verifyHost 证书校验域名（默认真实 host；诱饵换真域名时用 verifyHost）
     * @param verifySsl 是否校验证书（SNI 诱饵规则默认 false，与上游一致）
     */
    suspend fun connect(host: String, ip: String?, port: Int, tls: Boolean,
                        sni: String?, verifyHost: String?, verifySsl: Boolean,
                        timeoutMs: Int): UpstreamConnection
}

interface UpstreamConnection { fun sendHeaders(...); fun writeBody(...); fun readResponse(): UpstreamResponse; fun close() }
```
- 协议要求（MVP）：
  - `CONNECT host:port` → 判定 MITM/直连（§2.4c）；返回 `HTTP/1.1 200 Connection Established\r\nProxy-agent: dev-sidecar\r\n\r\n`；
  - 直连隧道：双向字节流的全双工转发（协程两个 loop + `close` 传播）；
  - MITM 内层：解析 HTTP/1.1 请求（MVP 不要求 h2），经拦截器链后转发上游，回写响应（支持 chunked、`Connection: keep-alive`、流式 body；不支持的上游头按上游规则过滤 `connection/keep-alive/proxy-connection/transfer-encoding/upgrade/http2-settings`，`createRequestHandler.js:709-726`）；
  - 绝对 URI 的明文 HTTP 请求：同一条 requestHandler 流程（ssl=false）。
- 每连接一个协程（`Dispatchers.IO`，Semaphore 限流，默认单进程并发上限 256）；连接空闲超时 `keepAliveTimeout`；读/写各自 cancel 传播；服务停止时先 `close` 监听再取消协程作用域。
- 必须处理的边界：CONNECT 目标失败 → 400/408；上游超时 → 504；客户端 abort → 上游 cancel；响应流错误 → 销毁两端（对齐上游 `pipeline` 行为）。

### 6.7 拦截器契约

```kotlin
package com.devs.sidebike.core.intercept

interface RequestInterceptor {
    val kind: InterceptorKind
    val priority: Int       // 排序键，见 §3.2
    /** 返回 Decision：CONTINUE（继续下一个）/ RESPOND（本拦截器已写响应，结束）/ MODIFY（改了 ctx，继续） */
    suspend fun intercept(ctx: RequestContext, spec: InterceptorSpec): Decision
}
interface ResponseInterceptor {
    val kind: InterceptorKind
    val priority: Int
    suspend fun intercept(ctx: RequestContext, spec: InterceptorSpec, resp: UpstreamResponse): ResponseDecision
}

data class RequestContext(
    var host: String, var port: Int, var path: String, var method: String,
    var protocol: String,                 // http/https
    var headers: MutableMap<String, String>,
    var sni: String? = null,
    var verifyHost: String? = null,
    var verifySsl: Boolean = true,
    var dnsSelection: DnsSelection? = null,
    var retryConfig: RetryRule? = null,
    val captures: MatchInfo?,
    val responseHeaders: MutableMap<String, String> = mutableMapOf(), // 写 DS-* 响应头
)
```
- MVP 实现顺序：`PROXY → SNI → REDIRECT → SUCCESS → CACHE_REQUEST → CACHE_RESPONSE → REQUEST_REPLACE → RESPONSE_REPLACE`（优先级数值照 §3.2）。未实现的 kind 只记日志、继续。
- `proxy`/`redirect` 的 URL 构造必须复用 §3.7 的 5 步算法与占位符替换；`backup` 可用简化版（首个失败后切换并保留成功率计数）。

### 6.8 统计与日志契约

```kotlin
data class RequestRecord(
    val ts: Long, val host: String, val port: Int, val method: String, val path: String,
    val intercepted: Boolean, val matchedRules: List<String>,      // DS-Interceptor 文本
    val dnsProvider: String?, val dnsIp: String?, val ipFamily: Int?,
    val statusCode: Int?, val upstreamMs: Long?, val bytesUp: Long, val bytesDown: Long,
    val error: String?,
)
interface StatsSink { fun record(r: RequestRecord); fun snapshot(): ProxyStats; fun clear() }
interface Logger { fun d(module: String, msg: String); fun i(...); fun w(...); fun e(module: String, msg: String, t: Throwable? = null) }
```
- 日志必须脱敏：`Authorization`/`Cookie`/`Set-Cookie`/`Proxy-Authorization`/URL query 中的 `token=` 之类按上游 `core/src/utils/util.redact.js` 思路处理（beta 至少不打完整头）。
- 控制台/文件双写可选；文件按天滚动，保留 15 份、单文件上限 1GB 对齐上游默认（`core/src/config/index.js:91-97`）。

### 6.9 线程模型（强制）

| 组件 | 模型 |
|---|---|
| 监听/接受连接 | 1 个 `CoroutineScope(Dispatchers.IO)`；`ServerSocket.accept()` 循环；每连接 `launch` 一个协程 |
| 上游连接/读写 | `Dispatchers.IO`；每连接阻塞 socket；读与写可并行（`launch` 两个 loop） |
| 证书签发 | CPU/RSA 生成放 `Dispatchers.Default`，全局 `Mutex` 串行 + LRU 缓存；禁止在主线程 |
| DNS 解析 | `Dispatchers.IO`；同 host 单飞（Map<host, Deferred>）；缓存用 `ConcurrentHashMap`/LRU + Mutex |
| 测速 | 独立 scope，每 host 一个 Job，`interval` 循环；过期 1h 无访问自动停（对齐上游） |
| 配置热更新 | 单写者：`ConfigRepository` 内部 Mutex；构建新 RuleSet/DnsRegistry 后原子替换引用；正在处理的请求用旧快照 |
| 统计 | `AtomicLong`/LongAdder + 每分钟快照；不阻塞请求路径 |

### 6.10 与上游行为对齐的常量表（实现时直接抄）

| 常量 | 值 | 用途 |
|---|---|---|
| 默认端口 | 31181 | 监听 |
| 默认 fakeServerMaxLength | 100 | 假服务器/证书 LRU |
| 默认 DNS cacheSize | 1024 | provider IP 缓存 |
| URL 正则缓存 | 512 | 编译缓存 |
| path 拦截器缓存 | 512 | 每域 path 匹配缓存 |
| ECH emptyTtl/minTtl/maxTtl | 600000/60000/3600000 ms | ECH 缓存（beta 解析但不实现握手也可保留常量） |
| 上游建连超时 | 7000 ms（ECH 30000） | `createRequestHandler.js:18-24` |
| 慢请求阈值 | 8000 ms | 同上 |
| 测速 interval / TCP 超时 | 60000 ms / 5000 ms | SpeedTester |
| 测速 DNS 默认 | cf-DoT、safe360 | 配置默认 |
| 请求体重试缓冲上限 | 1 MiB | `MAX_RETRY_BODY_SIZE` |
| 响应头大小上限 | 64 KiB | `maxHeaderSize` |
| CONNECT 隧道 connectTimeout | 10000 ms | `createConnectHandler.js:131-135` |
| keepAlive 默认 | 30000 ms；freeSocketTimeout 5000 ms | `util.js:30-58` |
| 状态机返回 | `HTTP/1.1 200 Connection Established` + `Proxy-agent: dev-sidecar` | CONNECT 成功 |

### 6.11 与 Lead 架构/契约文件的强制对齐（2026-10-25 更新）

> **权威文件**：`docs/architecture/02-core-contracts.md`（Lead 维护，**勿改签名**）。公开 API 包名为 `dev.sidebike.core.api`，文件为 `api/Config.kt`、`api/Dns.kt`、`api/Proxy.kt`、`api/Logging.kt`、`api/Stats.kt`、`api/Engine.kt`，`:core` 内默认实现放 `engine/DefaultSidebikeEngine.kt`。本节 §6 的名称是"机制级参考"，**落地时以 Lead 契约文件的签名与命名为准**；两者冲突时立即发消息给 lead，不要自行改契约。

Lead 架构相对上游新增、**本文 §6 未覆盖但必须实现**的能力：

1. **DNS wire 应答器（上游没有，Android 必需）**：VpnService 的 tun 只会把发往 `10.111.0.2:53` 的 IPv4/UDP DNS 报文交给 `:app`，因此 `:core` 必须提供：
   ```kotlin
   interface RawDnsResponder {                     // 放在 api/Dns.kt 或 engine 内部，由 Lead 契约文件定稿
       /** 输入 DNS wire query（UDP payload），输出 DNS wire response；null=不处理/交还系统 */
       suspend fun resolveRawDnsQuery(query: ByteArray): ByteArray?
   }
   ```
   实现要求：解析 QNAME/QTYPE/QCLASS（至少 A/AAAA，**未知类型返回空回答而非错误**）；用 `DnsRegistry` + `preSetIpList` 解析（与 §3.4/§3.5 完全同一套逻辑，不允许另起一份 resolution 路径）；合成响应时：QR=1、RA=1、RD 回显、保留原 question、TTL 建议 60s（无上游 TTL 语义，见 §3.4.3）、UDP 报文 >512 字节时置 TC=1；解析失败/超时返回 SERVFAIL（rcode=2）或空 NOERROR（可配置）；必须支持并发与去重（同一 QNAME 单飞）。**不要**尝试监听 53 端口（无 root 且会与 tun 冲突）。
2. **MITM 开关**：Android 配置新增 `mitm.enabled`（默认 `false`）。关闭时 `CONNECT` 一律"直连 + SNI 诱饵 + 防污染 DNS"（不做解密、不需要 CA）；打开时才走 §2.4(c)/§6.5 的 MITM 流程。上游没有该键，属于 Android 新增，需在配置 schema/UI 中显式体现。
3. **`setHttpProxy` 与防环路**：代理引擎自身的上游 socket 必须能绕开 tun（否则上游连接又被 VpnService 抓回形成环路）。VpnService 模式下由 `:app` 对 `:core` 暴露的 `Protector` 回调调用 `VpnService.protect(socket)`；建议契约：
   ```kotlin
   fun interface SocketProtector { fun protect(socket: java.net.Socket): Boolean }
   ```
   在 `SidebikeEngine` 启动参数里注入；直连隧道与上游连接建立后立即调用。纯 `:core` 测试中用 no-op 实现。
4. **API 29 兼容降级**：`setHttpProxy` 仅 API 29+；低于 29 时 UI 降级为"仅 DNS 模式"，`:core` 的代理监听仍可运行（供 App 内或手动代理使用）。
5. **测试约束**：`:core` 测试不得访问外部网络（`docs/architecture/02-core-contracts.md:28-31`），因此 §8 的 DNS/HTTP 集成测试必须用本地 `ServerSocket`/`DatagramSocket` mock（DoH 用本地 HTTP server 模拟）；与桌面版对拍（§8.5）放在 `:app` 或人工验收脚本中做。

### 6.12 启动/停止/状态契约（`:api` 门面）

```kotlin
interface SidecarEngine {
    val status: StateFlow<EngineStatus>   // STOPPED / STARTING / RUNNING / STOPPING / ERROR(msg)
    suspend fun start()                   // 1) ConfigRepository.load 2) RuleSet/DnsRegistry 3) CA ensure 4) ProxyServer.start
    suspend fun stop()                    // 关闭监听→取消连接→关闭假服务器→flush 统计/日志
    suspend fun reload(configPatch: JsonObject? = null)
    fun stats(): ProxyStats
    fun requestStats(): List<RequestRecord>   // 最近 N 条（环形缓冲，默认 1000）
}
```
- `start()` 必须幂等；失败要返回可读错误（端口占用/CA 生成失败/配置非法）。
- 对外暴露 `caCertPem` 供 UI 引导安装。
- **不在 `:core` 内**：VPN/TUN、系统代理设置、Activity/通知、证书安装 Intent——由 `:app` 调用 `:core` 并实现 Android 平台交互。

---

## 7. 风险与坑（按严重度排序）

### R1（阻塞级）Android 7+ 不信任用户 CA → 第三方 App 流量无法 MITM
- 事实：App 默认只信任系统 CA；用户安装的 CA 仅在 App 声明 `networkSecurityConfig` 且包含 `<certificates src="user"/>` 时生效（多用于 debug）。浏览器（Chrome/Firefox）行为亦受限（Chrome 默认不信任用户 CA？实际 Chrome 跟随系统 store，但 Android 7+ 应用级信任限制同样适用）。
- 影响：即使 VpnService 劫持了流量，绝大多数 App 的 TLS 会校验失败（表现为 App 网络错误）。
- 应对：① 明确 beta 定位为"浏览器/可配置 App 可用 + 演示镜像加速规则（对明文/可信任流量）"；② 若 owner 要求全局生效，需要 root/Magisk 模块把 CA 写入系统 store，或改用"不 MITM、只做 SNI/直连加速"模式（不需要 CA）；③ 与 Lead 确认验收口径，不要承诺"全 App 生效"。

### R2（阻塞级）无 root 不能静默设置系统代理
- 应对：VpnService（用户授权）或手动 Wi-Fi 代理；`WRITE_SECURE_SETTINGS` 需 adb（可写文档让用户执行一次 `adb shell pm grant ...`，但不可默认）。
- 决策点需 Lead 拍板（影响 UI 与验收方式）。

### R3（高）TLS 版本策略差异：上游默认只允许 TLS1.3
- Android 7/8/9 的 Conscrypt 可能不支持 TLS1.3，若直接抄 `minVersion=TLS1.3` 会导致上游连接全部失败。
- 应对：Android 默认 `minVersion=TLSv1.2`，并在设置暴露 `allowTls12`（或自动探测 `SSLContext.getSupportedSSLParameters()` 是否含 TLSv1.3）；对敏感场景可再逐步收紧。

### R4（高）SNI 诱饵要求"诱饵域名与目标同边缘"
- 诱饵用错会直接 handshake failure（Cloudflare 域名不能用 baidu.com 诱饵，需 cloudflare-ech.com）。
- 官方规则表已按实测配置好，Android 直接复用规则表即可；**不要自创诱饵**；若某规则失败，需回退真实 SNI（上游没有显式回退逻辑，移植时可加"诱饵失败→用真实 SNI 重试一次"的增强，但需标注与上游行为差异）。

### R5（高）MITM 证书生成性能/兼容性
- RSA2048 冷生成在低端机可能 100~500ms；首连可能超时。
- 应对：后台线程生成 + LRU 缓存 + 对常用域名预热；叶证书 1 年有效期减少重签；Wildcard 复用（a.b.com 全部用 `*.b.com`，与上游一致）。
- 严格客户端（如 Python 3.13+ strict）要求 AKI/SKI 正确——BouncyCastle 需显式加 `AuthorityKeyIdentifier`（对齐 `tlsUtils.js:86-120`）。

### R6（中）HTTP/2 客户端兼容
- 若客户端（Chrome/OkHttp）与服务端 ALPN 无交集，会回退 HTTP/1.1，通常可用；但某些场景（h2 强制）会失败。
- 应对：MVP 明确 h1.1；如必须 h2，用 Netty 或自研 h2 帧层，单列任务。

### R7（中）配置规则表体量与编译
- 官方配置 1497 行、`intercepts` 几百条 + 大量正则；每次启动编译正则可能几百 ms。
- 应对：构建期把 JSON5 转 JSON + 预编译正则表（或启动一次后台编译、首请求等待 ready）；LRU 缓存 path 解析结果。

### R8（中）Android 后台限制
- 前台服务+常驻通知；Doze/省电模式会杀后台；VPN 场景需 `VpnService` 生命周期管理。
- 应对：ForegroundService + `startForeground` 常驻通知；引导用户加电池白名单；beta 演示时保持屏幕/App 前台。

### R9（中）ECH/NAT64 缺失导致部分域名（X/Twitter、ChatGPT 等）仍不可用
- 官方配置把 x.com/twitter/chatgpt 等交给 ECH/NAT64；Android 不支持 ECH 时这些域名只有 SNI 诱饵/预置 IP 可用，成功率下降。
- 应对：为这些域名保留真实 SNI + 预置 IP（`preSetIpDomains`、`preSetIpList`）路径；在 UI/文档明确"部分站点不支持"。

### R10（低）安全与合规
- SNI 伪装 + 关闭上游证书校验 = 可被中间人利用；必须在设置中默认开启校验、仅对规则命中的诱饵连接关闭，且日志脱敏。
- 不要把 CA 私钥/用户流量落盘或上报；不要内置第三方 NAT64/反代网关（上游明确不内置）。
- 对外宣传避免"漏洞/破解"表述（见 §1.2）。

### R11（低）版本漂移
- 源码 2.3.2-dev 与安装版 2.3.1 有差异；官方规则表会持续更新。
- 应对：锁定子模块 SHA，规则表以 `~/.dev-sidecar/running.json`/`remote_config.json5` 为基准做快照；规则更新走 assets 版本化，不硬编码。

---

## 8. 验证清单（"Dev-Sidecar 后端在 Android 上跑起来"的判定标准）

> 建议做成可自动化的集成测试（JVM 单测 + Android Instrumentation/模拟器），每条都给出可观测断言。

### 8.1 配置层
- [ ] V1 用 `running.json` 原文（或裁剪版）作为输入，`ConfigRepository.load()` 成功，`server.port==31181`、`fakeServerMaxLength==100`、`verifySsl==true`、`allowTls12==false`。
- [ ] V2 缺字段/未知字段：删除 `setting.defaultTimeout` 后加载回落 20000；未知字段不导致异常。
- [ ] V3 合并优先级：构造 defaults/shared/personal/user 四层，断言 user > personal > shared > defaults；数组整体替换；`null` 与 `"[delete]"` 被删除。
- [ ] V4 用户覆盖持久化：`update()` 后磁盘只写 diff（不写全量默认值），再次加载结果一致。

### 8.2 规则引擎（纯单测，逐条对齐 §3.1）
- [ ] V5 `domainMapRegexply`：`*.a.com` → `^.*\.a\.com$`；`.a.com` 且存在 `*.a.com` 时忽略；`^...` 原样；精确域名进 origin 快查。
- [ ] V6 `matchHostname` 优先级：精确 > `*.host` > `*host` > 正则。
- [ ] V7 `matchHostnameAll`：低优先级先 merge、高优先级覆盖；`null` 删除；捕获组/命名组合并进入 `matched`。
- [ ] V8 `isMatched`：`.*`/`*`/`true` 恒真；`*`/`?`/`+` 前缀自动加点；非法正则返回 null；缓存 512 行为。
- [ ] V9 `exclusions`/`order` 去重：同一 host 两条规则命中的拦截器按 priority 排序、同名 order 大者替换。
- [ ] V10 官方规则抽样：`github.com "/"` → `[sni(baidu.com)]`；`fonts.googleapis.com "/css?..."` → `[proxy(fonts.googleapis.cn)]`；`www.google.com "/recaptcha/api.js"` → `[proxy(www.recaptcha.net)]`；`cdn.jsdelivr.net "/x.js"` → `[proxy(fastly.jsdelivr.net, backup...)]`；`whiteList` 命中不发 MITM。

### 8.3 DNS 层
- [ ] V11 UDP provider：对 `github.com` 查询 A 得到 ≥1 个 IP；带超时。
- [ ] V12 DoH wire format：POST `https://cloudflare-dns.com/dns-query`（`application/dns-message`）查询 A/AAAA 成功；失败回退 JSON API 的逻辑生效（可用 mock server 断言两次请求）。
- [ ] V13 预置 IP：`preSetIpList.github.com` 命中时不发 DNS 查询直接返回配置 IP；`DS-DNS: PreSet: <ip> (IPv4)`。
- [ ] V14 缓存与切换：首次解析 3 个 IP，标记前两个失败（`count(...,true)`），断言第三次取第 3 个 IP；连续失败 1 次即切换（§3.4.3）。
- [ ] V15 fallback：主 provider 抛异常/返回空 → 使用 fallback provider；再失败 → 系统 DNS；断言 `LookupResult` 类型与 provider 名。
- [ ] V16 IPv6 过滤/禁用：模拟 3 次 ENETUNREACH 后不再返回 IPv6。
- [ ] V17 单飞：并发 50 个同 host 请求，DNS provider 只被调用 1 次（统计调用次数）。

### 8.4 代理/MITM 层（可用 JVM 测试 + `curl` 对拍）
- [ ] V18 明文 HTTP：`curl -x http://127.0.0.1:<port> http://example.com/` → 200，响应头含 `DS-Proxy-Request`（对齐实测 B.3）。
- [ ] V19 CONNECT 直连隧道：目标域名不在 `intercepts`/`whiteList` 时透明转发（可用自建 TCP echo/TLS server 验证字节流完整、无解密）。
- [ ] V20 CONNECT MITM：对 `github.com` 建 CONNECT，服务端返回由本地 CA 签发的 `*.github.com` 证书；用 CA 校验通过；主机名匹配。
- [ ] V21 CA 属性：CN/OU/O/L/ST/C、20 年有效期、SHA256RSA、`basicConstraints cA:true`、SKI/AKI（对齐 `tlsUtils.js`）。
- [ ] V22 叶证书：`a.github.com` → CN=`*.github.com`、SAN 含 `*.github.com` 与 `github.com`；有效期 ±1 年；由 CA 签发可链验。
- [ ] V23 SNI 诱饵：用 mock TLS server 捕获 ClientHello 的 server_name，断言请求 `github.com` 时 SNI=`baidu.com`；`sni:""` 时无 SNI 扩展；`verifyHost` 时按 verifyHost 校验证书。
- [ ] V24 上游证书校验：默认 verifySsl 时，mock 服务器提供错误证书 → 请求失败；SNI 诱饵规则下按上游行为关闭校验（断言行为与 §1.2 一致，并在设置中可见）。
- [ ] V25 拦截器：`proxy` 改写后上游 Host=目标（mock server 断言）；`redirect` 返回 302 Location 正确；`success` 返回配置 body；`cacheDays` 的 `If-Modified-Since` 307 天内返回 304；`requestReplace`/`responseReplace` 头增删生效（含 `[remove]`）。
- [ ] V26 重试：mock 首次 500 → 配置 `retry:{times:1,statuses:[500]}` 时第二次成功，响应头 `DS-Retry: 1`。
- [ ] V27 错误语义：上游拒绝 → 502/500 错误页（HTML/JSON/text 三种 Accept 分支）；无响应 60s → 504；CONNECT 目标不可达 → 400/408。
- [ ] V28 流式/大文件：下载 10MB 文件字节一致（SHA256 对拍）；chunked 响应正确；客户端中途断开时上游连接被取消（无泄漏）。
- [ ] V29 并发：100 并发 GET 常见域，全部成功且无 fd 泄漏；进程 RSS 稳定。
- [ ] V30 停止/重启：`stop()` 后端口释放、连接被断开；`start()` 可再次监听（幂等）。

### 8.5 端到端（与桌面版对拍）
- [ ] V31 同一组 curl（`https://github.com/`、`https://fonts.googleapis.com/css?family=Roboto`、`https://www.google.com/recaptcha/api.js`、`http://example.com/`）分别经桌面 31181 与 Android 代理执行，断言：状态码一致、`DS-Interceptor`/`DS-DNS` 的**语义**一致（IP 可不同）、关键响应头一致、body 关键片段一致。
- [ ] V32 桌面实测样本（附录 B.3）作为 golden case：`github.com` → `sni: baidu.com`；`fonts.googleapis.com` → `proxy: fonts.googleapis.cn, sni: www.google.cn`；`recaptcha` → `proxy: www.recaptcha.net`；明文 HTTP → 直连转发。
- [ ] V33 反例：不在规则表的域名（如 `https://www.bing.com/`）应不被 MITM（客户端证书为真实 bing 证书，无 DS-* 头），与实测一致。

### 8.6 Android 集成层（`:app`）
- [ ] V34 CA 引导：导出 CA → 用户安装 → App 内检测"系统是否已信任该 CA"（`TrustManagerFactory` 校验自签链）→ 状态展示。
- [ ] V35 代理接入：手动 Wi-Fi 代理模式下浏览器访问 `https://github.com` 成功（信任 CA）；VpnService 模式下同样通过（若实现）。
- [ ] V36 前台服务：切后台/锁屏 10 分钟代理仍存活（通知常驻）；Doze 下重新唤醒可恢复。
- [ ] V37 设置项：`allowTls12`/`verifySsl` 开关生效；规则表可查看；导入 `remote_config.json5` 成功。
- [ ] V38 安全：日志中无 Cookie/Authorization；CA 私钥不在 logcat 出现；App 私有目录权限正确。
- [ ] V39（Lead 架构必测）DNS wire 应答器：向 `RawDnsResponder.resolveRawDnsQuery()` 输入 `github.com` A 查询 wire，返回合法 DNS 响应且 answer IP 属于 §3.4/§3.5 解析结果；AAAA/未知类型/坏包不崩溃；同 QNAME 并发只解析一次。
- [ ] V40（Lead 架构必测）VpnService 端到端：开启 VPN 后系统 DNS 指向 `10.111.0.2`，浏览器/命令行解析 `github.com` 得到非污染 IP（与桌面 DS `DS-DNS` 语义对照）；Private DNS 开启时 UI 有提示。
- [ ] V41（Lead 架构必测）`setHttpProxy` 生效：API 29+ 设备上调用后，支持系统代理的客户端请求经 `127.0.0.1:31181`（服务端可观测到 CONNECT）；API<29 时 UI 明确降级为"仅 DNS 模式"。
- [ ] V42（Lead 架构必测）防环路：VpnService 模式下代理自身上游 socket 均被 `protect()`，不出现"代理→tun→代理"死循环（可用 mock Protector 计数断言 + 真机抓包验证）。

### 8.7 "可宣称后端移植成功"的最小充分条件
1. V18/V20/V21/V22/V23/V25/V31 全部通过；
2. V5~V9 规则引擎单测 100% 通过；
3. V11/V12/V13/V15 **V39** DNS 关键路径通过；
4. V27/V28/V30 稳定性通过；
5. V40/V41/V42 通过（VpnService 两条腿接入，Lead 架构的核心验收）；
6. 已知缺口清单（ECH/NAT64/HTTP2/第三方 App 信任）文档化并在 UI 标注。

---

## 9. 未决问题 / 不确定项（交给 Lead，不臆造）

| # | 问题 | 现状/依据 |
|---|---|---|
| Q1 | ~~beta 的接入方式选哪种？~~ **Lead 已决策（2026-10-25）**：VpnService 两条腿（tun DNS 拦截 + API29+ `setHttpProxy`），MITM 默认关闭 | 见 `docs/architecture/01-architecture.md:12-13,46-62`；本文 §5、R1/R2、§6.11 已按此对齐 |
| Q2 | 是否接受"第三方 App 无法 MITM"的硬限制？ | Android 7+ 用户 CA 信任机制；若 owner 要求全局，需要 root 或非 MITM 模式 |
| Q3 | 规则表来源：内置官方配置快照 vs 在线拉取？ | 上游 `remote_config.json5` 24h 更新（`config-api.js:57-85`）；beta 建议内置+可选手动更新 |
| Q4 | 最小拦截器集合确认 | 本文建议 6 类（§3.2 注），需产品确认 github/google 加速的关键能力优先级 |
| Q5 | ECH 域名（X/ChatGPT 等）在 Android 上的替代方案 | 无公开 ECH API；只能真实 SNI + 预置 IP，成功率未知，需要实测 |
| Q6 | 2.3.1 安装版与 2.3.2-dev 源码的差异面 | 已抽查 sni/proxy 一致；其余模块未逐文件 diff（时间限制），若发现行为差异以运行实例为准 |
| Q7 | `cf-DoH.sni` 在运行实例为 `"cf"`（源码默认 `baidu.com`） | 可能是用户/官方配置差异或异常值；移植时该项按配置读取，不硬编码（§3.4.2） |

---

## 附录 A：文件索引（按阅读优先级）

| 主题 | 文件 | 关键行 |
|---|---|---|
| 端口/单端口监听 | `mitmproxy/src/lib/proxy/mitmproxy/index.js` | 88-115, 140-143 |
| 内核默认端口/CA 路径 | `mitmproxy/src/lib/proxy/common/config.js` | 5-11 |
| CONNECT 处理/隧道 | `mitmproxy/src/lib/proxy/mitmproxy/createConnectHandler.js` | 37-75, 77-234 |
| MITM 请求主流程 | `mitmproxy/src/lib/proxy/mitmproxy/createRequestHandler.js` | 127-196, 246-261, 305-357, 389-417, 600-633, 709-835 |
| 假服务器/证书缓存 | `mitmproxy/src/lib/proxy/tls/FakeServersCenter.js`, `CertAndKeyContainer.js` | 37-72, 74-154 / 7-50 |
| CA/叶证书生成 | `mitmproxy/src/lib/proxy/tls/tlsUtils.js` | 19-120, 127-210, 298-333 |
| 规则编译/匹配 | `mitmproxy/src/utils/util.match.js` | 14-35, 44-76, 78-212 |
| 拦截配置/去重/白名单 | `mitmproxy/src/options.js` | 49-70, 178-202, 203-331 |
| 拦截器注册/优先级 | `mitmproxy/src/lib/interceptor/index.js`；`impl/**` | 24-43；各文件 2-3 行 |
| SNI 诱饵 | `mitmproxy/src/lib/interceptor/impl/req/sni.js` | 4-43, 45-63 |
| proxy 改写 | `mitmproxy/src/lib/interceptor/impl/req/proxy.js` | 39-89, 91-171 |
| 缓存（304/缓存头） | `impl/req/cacheRequest.js`, `impl/res/cacheResponse.js` | 60-108 / 8-113 |
| 重试 | `impl/req/retry.js`；`createRequestHandler.js` | 37-52；600-633 |
| DNS provider 工厂 | `mitmproxy/src/lib/dns/index.js` | 19-135, 237-292 |
| DNS 缓存/失败切换 | `mitmproxy/src/lib/dns/base.js`；`lib/choice/index.js` | 82-87, 323-397；139-166 |
| DoH 实现 | `mitmproxy/src/lib/dns/https.js` | 67-90, 92-147 |
| 连接内 DNS 选择/回退 | `mitmproxy/src/lib/proxy/mitmproxy/dnsLookup.js` | 90-267, 272-339 |
| 测速/优选 | `mitmproxy/src/lib/speed/SpeedTester.js`；`speed/index.js` | 144-181, 229-325, 345-380 |
| ECH | `mitmproxy/src/lib/dns/base.js`；`proxy/common/ech/**` | 89-321 |
| NAT64 | `mitmproxy/src/lib/dns/nat64.js`；`mitmproxy/src/options.js` | 119-137 |
| 默认配置/规则表 | `core/src/config/index.js` | 54-168, 170-1547 |
| 配置合并 | `core/src/config/local-config-loader.js`；`core/src/merge.js` | 234-259；10-87 |
| 配置 API/远程拉取 | `core/src/config-api.js` | 43-101, 116-167, 215-282, 469-543 |
| 代理进程 fork/running.json | `core/src/modules/server/index.js` | 93-174, 197-213 |
| 启动编排/插件 | `core/src/expose.js`；`core/src/modules/plugin/**` | 18-113 |
| 系统代理 | `core/src/modules/proxy/index.js`；`core/src/shell/scripts/set-system-proxy/` | 17-27 |
| CA 安装 | `core/src/shell/scripts/setup-ca.js` | 45-124 |
| 官方配置地址/同步说明 | `core/src/config/index.js`；`core/src/config/remote-config-rule.js`；`vendor/ds-core/AGENTS.md` | 23-52；19-43 |
| overwall/PAC | `mitmproxy/src/lib/proxy/middleware/overwall.js`；`middleware/source/pac.js` | 164-250 |
| 流量统计/进程归属 | `mitmproxy/src/lib/traffic/*` | — |

## 附录 B：只读实测记录（2026-10-25，运行实例）

### B.1 进程/端口
```
GUI:  PID 21432  dev-sidecar.exe
代理: PID 21696  D:\dev-sidecar\dev-sidecar.exe
      D:\dev-sidecar\resources\app.asar\src\bridge\mitmproxy.js C:\Users\CrazySue\.dev-sidecar\running.json
监听: 127.0.0.1:31181（TCP）+ 15 个 127.0.0.1 随机端口（2833/2837/4782/4796/4803/5474/6022/6309/7221/7258/9746/9867/11711/12475/14035，MITM 假服务器）
UDP:  代理进程无 UDP 监听
```

### B.2 代理行为探针（经 `http://127.0.0.1:31181`，未修改实例）
```
$ curl -k -D - -o NUL https://github.com/
HTTP/1.1 200 Connection Established
HTTP/1.1 200 OK
DS-Interceptor: sni: baidu.com, unVerifySsl
DS-DNS: PreSet: 20.27.177.113 (IPv4)
DS-Proxy-Request: https://github.com:443/
DS-Proxy-Request-Family: 4
server: github.com

$ curl -k -D - -o NUL "https://fonts.googleapis.com/css?family=Roboto"
DS-Interceptor: proxy: https://fonts.googleapis.cn/css?family=Roboto, sni: www.google.cn, unVerifySsl
DS-DNS: safe360: 120.253.253.107 (IPv4)
DS-Proxy-Request: https://fonts.googleapis.cn:443/css?family=Roboto
server: ESF

$ curl -k -D - -o NUL https://www.google.com/recaptcha/api.js
DS-Interceptor: proxy: https://www.recaptcha.net/recaptcha/api.js
DS-DNS: safe360: 120.253.253.34 (IPv4)
DS-Proxy-Request: https://www.recaptcha.net:443/recaptcha/api.js

$ curl -D - -o NUL http://example.com/
HTTP/1.1 200 OK
DS-DNS: default: 172.66.147.243 (IPv4)
DS-Proxy-Request: http://example.com:80/
server: cloudflare

$ curl -k -D - -o NUL https://x.com/         # ECH 域名
DS-DNS: PreSet: 172.67.172.22 (IPv4)
DS-ECH: 1                                    # 实际使用了 ECH 握手（SNI 加密）
DS-Proxy-Request: https://x.com:443/
server: cloudflare envoy
（无 DS-Interceptor，说明 ECH 域名不参与 sni 改写）

$ curl -k -D - -o NUL https://www.bing.com/  # 不在规则表
HTTP/1.1 302 Found  （无 DS-* 头，未被 MITM/改写）
```

### B.3 配置目录实况
- `config.json`：用户覆盖（`app.*`、`proxy.setEnv/setCaBundle/proxyHttp`、`plugin.git/node/pip`、以及两条 google recaptcha 规则）——说明"用户配置只存差异"。
- `remote_config.json5`：官方共享配置缓存，1497 行；`running.json` 39.8KB；`setting.json` 内 `overwall:false`、`rootCa.setuped:true`。
- `automaticCompatibleConfig.json`：`{"connect":{},"request":{"registry.npmjs.org:443":{"rejectUnauthorized":false}}}` —— 自动兼容学习落盘实例（§3.3）。
- CA：`dev-sidecar.ca.crt` `CN=DevSidecar - This certificate is generated locally`、`O=dev-sidecar`、`OU=https://github.com/docmirror/dev-sidecar`、`C=CN`、`ST=GuangDong`、`L=ShenZhen`、SHA256RSA、`NotBefore 2026-08-10 / NotAfter 2046-08-11`（与 `tlsUtils.js:36-84` 一致）。

### B.4 安装版（v2.3.1）源码抽查
从 `D:\dev-sidecar\resources\app.asar` 直接检索到 `sni.js`/`proxy.js` 的嵌入代码：`sni` 拦截器的 `unVerifySsl` 切换、`DS-Interceptor` 头格式、`proxy` 改写与 `verifyHost` 逻辑与 `vendor/ds-core@c14c481` 一致，因此本文以源码行号为准确依据。

---

## 附录 C：给 Lead 的三条短结论

1. **前提纠正**：不是"HTTPS SNI 伪装漏洞"，而是"诱饵 SNI 规避审查 + DoH/预置 IP 防污染 + ECH 加密 SNI"；对外文案必须改口，否则准确性/合规风险。
2. **后端可移植**：核心是"单端口 MITM 代理 + 进程内 DNS 解析层 + 规则表"，Kotlin 可完整重写；ECH/NAT64/overwall/插件/进程统计应砍掉，规则表可直接复用官方配置。
3. **两个硬限制需决策**：Android 7+ 不信任用户 CA（第三方 App 无法 MITM）、无 root 不能静默设系统代理；beta 接入方式（手动代理 vs VpnService）与验收口径需 Lead 尽快拍板。


