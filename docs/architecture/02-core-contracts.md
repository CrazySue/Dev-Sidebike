# :core 契约（Lead 维护，勿改签名；实现者可加 internal 辅助类）

所有公开 API 在 `dev.sidebike.core.api` 包。`app` 只依赖这些 API + `SidebikeEngineFactory`。
需要加/改契约字段：先发消息给 lead，由 lead 修改契约文件。

## 文件

| 文件 | 内容 |
| --- | --- |
| `api/Config.kt` | `SidebikeConfig` 及全部配置模型（含默认值、上游兼容字段） |
| `api/Dns.kt` | `DnsResolver` 接口、`DnsStats`、DNS 查询结果模型 |
| `api/Proxy.kt` | `ProxyServer` 接口、`ProxyRequest`/`ProxyResult`、默认端口常量 |
| `api/Logging.kt` | `SidebikeLogger`、`LogEntry`、`LogLevel` |
| `api/Stats.kt` | `TrafficStats`、`EngineStats` |
| `api/Engine.kt` | `SidebikeEngine` 唯一门面 + `SidebikeEngineFactory` |
| `engine/DefaultSidebikeEngine.kt` | 默认实现（由 backend workstream 填充） |

## 线程模型
- 所有公开 `suspend` 方法可被任意协程调度器调用，实现内部自行切换 `Dispatchers.IO`。
- `start()/stop()` 幂等；重复调用安全。
- 事件回调（日志/统计订阅）可能来自 IO 线程，UI 侧自行切主线程。

## 配置 JSON
- 序列化使用 kotlinx.serialization（结构化 JSON，键名与 Android 侧持久化一致）。
- 上游 `running.json` 兼容字段：`intercepts`、`whiteList`、`preSetIpList`、`excludeIpList`、`dns.providers`/`mapping`/`familyMapping`、`proxy.*`。
- 反序列化必须 `ignoreUnknownKeys = true`、`isLenient = true`（允许 JSON5 风格单引号由预处理器处理）。

## 测试要求
- `:core` 测试全部为 JVM 单元/集成测试（JUnit4），**不得访问外部网络**：用本地 `ServerSocket`/`DatagramSocket` 模拟上游。
- 每个 workstream 至少覆盖：正常路径、超时、异常/坏包、并发、配置边界。
- `_script/test.ps1` 会运行 `:core:test` + `:app:testDebugUnitTest` + 静态审计；失败即测试环境不通过。

## 变更记录（Lead 维护）
- 2026-10-25：`DnsProvider` 新增 `bootstrapIps`（禁止用系统 resolver 自举，避免 VPN DNS 环/二次污染）；默认 provider 已带自举 IP。
- 2026-10-25：tun DNS wire 应答器约定——**永不置 TC**（不处理 TCP:53），按客户端 EDNS0 UDP buffer（默认 512B）裁剪答案、回显 question、计数一致。
- 2026-10-25：代理启动顺序由 `:app` 保证（先起代理并自检可连接 → establish VPN → setHttpProxy）；`:core` 只保证 `ProxyServer.start()` 返回后端口可连接。
- 2026-10-25：`SidebikeUiState` 新增 `systemProxyRegistered` / `privateDnsActive` / `lockdownDetected` / `effectiveProxyPort`（app 层状态契约，UI 必须展示相应告警）。
- 2026-10-31（0.1.1-alpha）：新增规则管理契约 —— `RuleSource` / `RuleSourceFormat`（AUTO/SIDEBIKE_JSON/SHADOWROCKET_LIST/DOMAIN_LIST/HOSTS）、`RuleSourceManager`（HTTP(S) 拉取 + ETag 条件请求 + 解析，不自行持久化）+ `RuleSourceManagerFactory`、`ImportedRuleSet`（intercepts/directPassDomains/domesticDomains/preSetIpList）；`SidebikeConfig` 新增 `ruleSources`（持久化来源与 lastUpdated/lastError/etag/lastImported）与 `disabledIntercepts`（"域名\t路径"，删除内置规则时写入，RuleEngine 必须跳过）。
- 2026-10-31（0.1.1-alpha）：`InterceptAction` 新增 `abort: Boolean?`（true=直接拒绝，路由为 `RouteKind.REJECT`，供广告/跟踪规则导入）。
- 2026-10-31（0.1.1-alpha）：健康检查 —— `SidebikeEngine.dnsHealth(): List<DnsProviderHealth>`、`DnsResolver.health()`（默认空实现，实现必须容错不抛异常）；`ensureCaCertificate()` 语义修订为**与 MITM 开关无关、始终可生成**（CA 安装引导页需在开启 MITM 前生成并安装证书）。
- 2026-10-31（0.1.1-alpha）：app 侧新增接口（实现与契约同时冻结）——日志 `buildLogText()` / 调试包 `buildDebugBundle()`（ZIP）、网络诊断 `runNetworkDiagnostics()`、CA `prepareCaInstall()`（KeyChain intent + 系统设置 fallback + PEM/指纹）与 `setMitmEnabled()`（CA 未安装时必须拒绝启用）、规则 `saveRule` / `deleteRule` / `setDirectPassDomains` / `setDomesticAllowList` / `importRulesFromText` / `refreshRuleSources`；`SidebikeUiState` 新增 `ruleSourcesBusy` / `diagnosticsRunning`。
