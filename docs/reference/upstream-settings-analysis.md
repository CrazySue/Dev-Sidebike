# 上游 Dev-Sidecar「设置/加速」能力盘点 → 0.1.1-alpha 暴露建议

> 任务：T11（0.1.1-alpha）。目的：以**上游源码 file:line** 为依据，盘点上游 Dev-Sidecar 的设置项/加速能力，对照 Dev-Sidebike 0.1.1-alpha 现状，给出"已实现 / 建议 0.1.1-alpha 暴露 / 建议后续"的明确清单，供 UI/产品排期与 Lead 裁决。
> 上游代码：`reference/dev-sidecar/vendor/ds-core` @ c14c481（v2.3.1/2.3.2-dev，2026-10）；本文行号均为当前工作区实测。

## 1. 上游设置与加速能力地图（带证据）

### 1.1 服务/代理基础
| 能力 | 上游配置键 | 证据（file:line） | 说明 |
|---|---|---|---|
| 代理开关/绑定 IP/端口 | `server.enabled/host/port`（端口 31181 单端口） | `core/src/config/index.js:99-106`；`mitmproxy/src/lib/proxy/mitmproxy/index.js:88-115` | HTTP+CONNECT 共用；0.0.0.0 可局域网共享 |
| fakeServer/证书缓存 | `fakeServerMaxLength` | `core/src/config/index.js:106` | 对应我们 `mitm.maxLeafCache` |
| 系统代理开关 | `proxy.enabled`、`setEnv`、`setCaBundle`、`proxyHttp` | `core/src/modules/proxy/index.js:17-27,54-62` | 桌面写注册表/环境变量；Android 由 VpnService/setHttpProxy 替代 |
| 系统代理排除（国内/局域网/自定义） | `excludeDomesticDomainAllowList`、`autoUpdateDomesticDomainAllowList`、`remoteDomesticDomainAllowListFileUrl`、`domesticDomainAllowListFilePath`、`excludeIpList` | `core/src/modules/proxy/index.js:64-70,72-240`；官方配置覆盖 `core/src/config/index.js:178-235` | 上游有内置国内域名文件 + 自动更新 + 排除表 |
| Cloudflare 路由重定向 | `cloudflareRoute.enabled/preferredEndpoint` | `core/src/config/index.js:155-159`；`mitmproxy/src/lib/cloudflareRoute.js` | 默认关闭 |
| 拦截总开关 | `intercept.enabled` | `core/src/config/index.js:160-162` | 关闭=全部直通 |

### 1.2 DNS 与防污染
| 能力 | 上游配置键 | 证据 | 说明 |
|---|---|---|---|
| Provider 列表（UDP/TCP/DoT/DoH） | `dns.providers.<name>.{server,type,port,family,sni,forSNI,cacheSize}` | `core/src/config/index.js:1402-1435`；`mitmproxy/src/lib/dns/index.js:19-135` | 支持 `forSNI` 默认 DNS |
| 域名→DNS 分流 + IP 族 | `dns.mapping`、`dns.familyMapping` | `core/src/config/index.js:1436-1491` | 0.1.1 应可读展示 |
| IP 测速/优选 | `dns.speedTest.{enabled,interval,hostnameList,dnsProviders}` | `core/src/config/index.js:1492-1503`；`mitmproxy/src/lib/speed/index.js:32-55`；`speed/SpeedTester.js:144-181,345-380` | 60s 间隔 TCP 443 测速，选最快 IP |
| 预置 IP 列表 | `preSetIpList`（→ PreSet provider，优先级最高） | `core/src/config/index.js:1106-1392`（官方）；`dns/preset.js:3-10`；`dns/base.js:399-420` | DNS 被污染时终极保底 |
| ECH（加密 SNI） | `dns.ech.{enabled,use,domains,dns,publicName,cacheSize,emptyTtl,minTtl,maxTtl,tryAllProviders,parallelDelay}` | `core/src/config/index.js:1504-1537` | Android 无公开 ECH API，0.1.1 明确不支持 |
| NAT64 | `dns.nat64.{enabled,prefix,domains,dns}` | `core/src/config/index.js:1538-1545`；`dns/nat64.js` | 需用户自备 NAT64 前缀，非目标 |
| IP 缓存/失败切换 | 内部 LRU 1024 + DynamicChoice（连续失败 1 次/成功率<0.4 切换） | `dns/base.js:82-87,323-397`；`choice/index.js:139-166` | 我们 T6 已实现 TTL 缓存 + 简化失败计数 |
| 超时/TLS 版本/重试 | `setting.defaultTimeout/defaultKeepAliveTimeout/timeoutMapping`、`tlsVersionMapping`、`retry` 拦截器、`backup` 代理后备 | `core/src/config/index.js:121-134`；`proxy/common/util.js:30-66`；`interceptor/impl/req/retry.js:37-52`；`interceptor/impl/req/proxy.js:102-125` | 0.1.1 先暴露默认超时；细粒度后续 |

> 注意：**上游没有"DNS hosts 覆盖"配置**（hosts 是 Android 端 0.1.1 新增能力，`core/api/Config.kt:118-119`）；上游用 `preSetIpList` 承担同类作用。

### 1.3 规则与加速（镜像/改写）
| 能力 | 上游配置键 | 证据 | 说明 |
|---|---|---|---|
| 域名+路径级拦截动作 | `intercepts[domainPattern][pathRegex] = {sni/proxy/success/cacheDays/requestReplace/responseReplace/script/abort/redirect/...}` | `core/src/config/index.js:698-1105`（官方）；`mitmproxy/src/options.js:203-331` | 官方配置 69 域/77 规则（0.1.0 快照） |
| 白名单直连（不 MITM） | `whiteList` | `core/src/config/index.js:1393-1401`；`mitmproxy/src/options.js:178-202` | 对应我们 `whiteList` |
| GitHub/Google 镜像加速 | 官方 `intercepts`（github sni=baidu.com、fonts.googleapis→.cn、recaptcha→recaptcha.net、jsdelivr backup 等） | `core/src/config/index.js:698-1105`；`docs/feasibility/01-dev-sidecar-analysis.md` §2/§3.7 | 我们已内置同源快照（0.1.0） |
| 远程配置（官方/个人） | `app.remoteConfig.{enabled,url,personalUrl,fetchRule}` + 历史版本/选择/差异保存 | `core/src/config/index.js:64-70`；`core/src/config-api.js:57-167,215-249`；`config/local-config-loader.js:69-174` | 我们 0.1.1 用"用户规则源"替代（RuleSourceManager） |
| 脚本注入/油猴 | `script`/`tampermonkeyScript` + `setting.script` | `core/src/config/index.js:111-114,701-706`；`interceptor/impl/res/script.js` | MITM 增强项，后续 |
| 限速/低延迟提示 | `setting.lowSpeedDelay` | `core/src/config/index.js:133-134` | UI 展示级 |

### 1.4 插件（桌面专属，Android 不移植）
`plugin.git/node/pip/overwall/free_eye/share/xget`：`core/src/modules/plugin/**`、`core/src/config/index.js:236-350`。0.1.1 明确不暴露（桌面开发工具链/第三方反代/P2P）。

## 2. Dev-Sidebike 现状（0.1.1-alpha 契约已具备的入口）

| 能力 | 现状 | 证据 |
|---|---|---|
| DNS Provider 配置/开关、mode/mapping/familyMapping/hosts、超时 | `core/api/Config.kt:105-162`（DnsSettings）；`DefaultDnsResolver` 已实现 | `core/src/main/kotlin/dev/sidebike/core/dns/DefaultDnsResolver.kt` |
| DNS Provider 健康检查 | 契约新增 `DnsResolver.health()`/`SidebikeEngine.dnsHealth()` | `core/api/Dns.kt:42-47`；`core/api/Engine.kt:51`及 `dnsHealth()` |
| 规则源（远程导入/本地导入/Shadowrocket/DOMAIN_LIST） | 契约 `RuleSourceManager`/`RuleSource`/`ImportedRuleSet` + Controller `refreshRuleSources/importRulesFromText/setDirectPassDomains/setDomesticAllowList/saveRule/deleteRule` | `core/api/RuleSources.kt`；`core/api/Config.kt:196-228`；`app/.../controller/SidebikeController.kt:60-81` |
| MITM 开关 + CA 安装/检测 | Controller `setMitmEnabled`/`prepareCaInstall`/`caCertificatePem`/`refreshCaInstalled` | `app/.../controller/SidebikeController.kt:33-58` |
| 网络诊断/日志导出/调试包 | Controller `runNetworkDiagnostics`/`buildLogText`/`buildDebugBundle` | 同上 :42-51 |
| 规则页（intercepts 列表、直连放行 = whiteList、MITM 开关） | UI 已实现（0.1.1 待接规则源/健康） | `docs/design/01-ui-spec.md` §2、§5.2 |
| 系统代理/VPN/私有 DNS 检测 | UI 状态已含 `systemProxyRegistered/privateDnsActive/lockdownDetected/effectiveProxyPort` | `app/state/SidebikeUiState.kt`、UI spec §4 |
| 未实现 | ECH、NAT64、speedTest 后台测速、retry/backup、cloudflareRoute、timeoutMapping、脚本注入、overwall/P2P/桌面板插件 | 任务/API 中无对应字段 |

## 3. 0.1.1-alpha 暴露建议（按投入产出排序）

### 3.1 建议 0.1.1-alpha 暴露（低成本、直接解决用户问题）
| # | 暴露项 | UI 形态 | 依据/理由 |
|---|---|---|---|
| 1 | **DNS Provider 健康状态 + 一键检测** | 设置→DNS 列表每行 ok/失败/延迟；"运行网络诊断"聚合展示 | 契约已有 `health()`/`dnsHealth()`（`api/Dns.kt:46`）；用户 item-2 根因排查必需 |
| 2 | **DNS 模式切换（SMART/DOH_ONLY/SINGLE）+ Provider 启停** | 单选 + 开关 | `DnsMode`/`DnsProvider.enabled` 已在配置（`api/Config.kt:62-93`） |
| 3 | **preSetIpList / dns.mapping / familyMapping 的只读可见 + hosts 可编辑** | 设置→DNS→"高级（只读展示规则条数/前 N 条；hosts 可增删）" | `api/Config.kt:113-119`；上游同类能力见 §1.2；只读展示几乎零成本 |
| 4 | **国内直连（domesticAllowList）开关/列表编辑** | 设置→分流→"国内域名直连"开关 + 列表 | Controller `setDomesticAllowList` 已定义（`:71`）；对应上游 `excludeDomesticDomainAllowList` |
| 5 | **直连放行（whiteList）编辑** | 规则页已有"直连放行"语义（UI spec §5.2） | `setDirectPassDomains` 已定义（`:68`） |
| 6 | **远程规则源（推荐 3 个模板，默认仅启用 cnip）** | 设置→规则源：启停/更新/最后更新时间/导入条数/lastError/删除 | `RuleSources` 契约 + `docs/reference/rule-sources.md` |
| 7 | **MITM 按域范围说明 + CA 未装拒绝开启** | 设置→MITM：开关 + "仅规则命中域名会被解密"说明；未装 CA 时禁用并引导 | 引擎已按 `requiresMitm(host)` 逐域判定（`HttpProxyServer.kt:409-438`）；Controller `setMitmEnabled` 已定义（`:58`） |
| 8 | **代理端口 + 端口回退显示** | 设置→代理：端口（当前已有 `effectiveProxyPort` 状态） | `api/Config.kt` ProxySettings.port + UI spec §4 |
| 9 | **超时（连接/读取）预设：默认/保守/快速** | 设置→高级：三档预设写 `proxy.connectTimeoutMs/readTimeoutMs` | `api/Config.kt` ProxySettings；上游 `setting.defaultTimeout` 同类（`config/index.js:121-134`） |

### 3.2 建议后续版本（收益中高、成本高或风险高）
| # | 能力 | 原因 |
|---|---|---|
| 1 | DNS 测速/优选（上游 speedTest） | 需要后台定时任务/流量代价；0.1.1 先用 provider 健康 + 并行竞速 |
| 2 | 细粒度 retry/backup failover | 需代理层改造（当前 `retry`/`backup` 未实现；上游 `retry.js`/`ChoiceCache`） |
| 3 | timeoutMapping / tlsVersionMapping 按域配置 | 配置面复杂，先给全局档位 |
| 4 | ECH / NAT64 | Android 无公开 ECH API；NAT64 需用户网关；与 0.1.1 目标无关 |
| 5 | 脚本注入 / tampermonkey | MITM 增强，易碎；需 CA + 用户理解成本 |
| 6 | cloudflareRoute | 默认关闭，需 IP 段数据与优选源 |
| 7 | 上游"官方远程配置订阅"（4 层合并/版本历史/差异保存） | 我们改为"用户规则源"模型；若未来要完整 parity 再评估 |
| 8 | 桌面板插件 / overwall / P2P / git/node/pip | 平台不适用/依赖第三方服务 |

### 3.3 明确不暴露
- `NODE_TLS_REJECT_UNAUTHORIZED`（全局关校验，危险；`config/index.js:108`）
- 系统级环境变量/CA bundle 写入（Android 无 root 不可行）
- fakeServer/证书缓存等内部参数（→ 保留默认，最多诊断页只读展示）

## 4. 给 Lead 的三条结论

1. **0.1.1-alpha 设置面应聚焦"可解释 + 可诊断"**：DNS Provider 健康/模式、hosts/mapping/preSet 可见、国内直连、直连放行、规则源、MITM 范围与 CA 前置校验、端口/超时预设。这些在上游都有对应能力（§1），且 0.1.1 契约已预留入口（§2），不需要新增大架构。
2. **上游最值得借鉴但暂缓**：speedTest/retry/backup 与 ECH/NAT64。前者排 0.1.2，后者明确不在 Android beta 目标内（且 ECH 无公开 API）。
3. **面向用户的问题 #2（真 GFW 网络下 MITM 关闭+加速开启仍不能访问境外）** 最可能落在 DNS/provider 策略上，本任务 (c) 正在加固：provider 健康 + 多源共识防污染 + DoH/DoT 优先 + 预置 IP 兜底 + 短超时并行竞速；设置页应把 `health()` 结果与"当前 DNS 解析路径（provider/IP）"直接展示，避免用户误判为"加速失效"。
