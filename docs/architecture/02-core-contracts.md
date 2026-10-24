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
