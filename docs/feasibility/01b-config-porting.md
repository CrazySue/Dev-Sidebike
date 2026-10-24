# 上游 Dev-Sidecar 配置 → Dev-Sidebike `SidebikeConfig` 映射（T6）

> 一页纸规格。配套实现：`core/src/main/kotlin/dev/sidebike/core/config/`，快照：`core/src/main/resources/dev/sidebike/core/rules/rules.json`。
> 上游事实基线见 [01-dev-sidecar-analysis.md](01-dev-sidecar-analysis.md)。

## 1. 配置层次（离线优先）

| 层 | 来源 | 何时加载 |
|---|---|---|
| 1. 编译期默认值 | `core/api/Config.kt` 的 `data class` 默认参数 | 始终 |
| 2. 官方规则快照（离线内置） | `rules.json`（resources，**40 KB**，生成于 2026-10-25） | 始终（`BundledConfig.load()`） |
| 3. 用户覆盖 | `<dataDir>/config.json`（本 App JSON，接受 JSON5 片段） | 存在时深合并（`DefaultConfigRepository.load()`） |

运行期**不访问网络**。可选 `ConfigRepository.importUpstream(json)` 导入上游 `running.json` / `remote_config.json5` / `config.json`（映射后返回，不自动落盘）。

## 2. 字段映射

| 上游（`running.json` 根 = serverConfig / `~/.dev-sidecar/config.json`） | Dev-Sidebike | 说明 |
|---|---|---|
| `port` | `proxy.port`（默认 31181） | 单端口 HTTP+CONNECT |
| `host` | 固定 `127.0.0.1` 监听 | Android 端口绑定不暴露局域网 |
| `server.fakeServerMaxLength` | `mitm.maxLeafCache`（近似 1:1） | 假服务器/叶证书 LRU |
| `server.setting.{verifySsl,allowTls12,timeout...}` | `mitm.trustUpstreamAll` / `proxy.*TimeoutMs` | TLS1.3 默认在 Android 降为 1.2（见 §4） |
| `intercepts` | `intercepts` 同名 | 动作字段裁剪见 §3 |
| `preSetIpList` | `preSetIpList`（`Map<域名, Map<IP, Boolean>>`） | 真值判定对齐上游 `mapToList` |
| `whiteList` | `whiteList` | null 值忽略 |
| `proxy.excludeIpList` | `excludeIpList` | 系统代理排除语义在 Android 变为"代理不接管" |
| `dns.providers` | `dns.providers` | `server` 串解析为协议/端口；**新增 `bootstrapIps`** |
| `dns.mapping` | `dns.mapping`（`Map<pattern, providerName>`） | 值若为 `{dnsName,family}` 只取 dnsName |
| `dns.familyMapping` | `dns.familyMapping`（`Map<pattern,"4"\|"6">`） | 数字自动转字符串 |
| `~/.dev-sidecar/domestic-domain-allowlist.txt` | `domesticAllowList`（List<String>，1237 条） | 用于 `RuleEngine.isDomestic` 直连 |
| `dns.speedTest.*` | 未建模（beta 无后台测速） | 后续任务 |
| `dns.ech.*` / `dns.nat64.*` | 未建模（Android 无公开 ECH API、NAT64 非目标） | 文档化缺口 |
| `plugin.*` / `app.*`（GUI/桌面） | 忽略 | 桌面专属 |

内置快照包含：`intercepts`（69 域名 / 77 path 规则）、`preSetIpList`（61 域名 / 153 IP）、`whiteList`、`excludeIpList`、`dns.providers`（9 个）、`dns.mapping`（25 条）、`dns.familyMapping`、`domesticAllowList`（1237 条）以及顶层 `_meta`（source/generatedAt/stats）。

## 3. 拦截动作字段裁剪（MVP 集合）

| 上游字段 | 处理 |
|---|---|
| `sni`（含 `""`=禁发 SNI） | **保留**（`InterceptAction.sni`） |
| `proxy` | **保留**（`InterceptAction.proxy`；`backup` 备用列表丢弃，仅主目标） |
| `success`（`true`/对象） | **保留**（JsonElement；`{"abort":true}` 映射 REJECT） |
| `status` | **保留**（与 success 组合时用于合成响应状态码） |
| `script` / `tampermonkeyScript` | **保留**（由 MITM 层注入，proxy-dev 实现） |
| `requestReplace` / `responseReplace` | **保留**（JsonElement，MITM 层执行） |
| `cacheSeconds/Minutes/Hours/Days/Weeks/Months/Years/cacheMaxAge/cache` | 折算为 `cacheDays`（向上取整，最小 1） |
| `desc` / `remark` | 保留（仅展示） |
| `abort` / `redirect` / `verifyHost` / `unVerifySsl` / `options` / `exclusions` / `order` / `replace` / `baiduOcr` | **丢弃**（MVP 未建模；`${...}` 占位符 proxy 目标运行期回退 DIRECT） |

`plan(host, port, path=null)`（CONNECT 预判）对"有任意 path 规则"的域名返回 `action + reason="path-rule-pending-mitm"`；代理侧也可用 `DefaultRuleEngine.requiresMitm(host)`（非 frozen api、直接类型调用）。

## 4. 已知偏差（必须让产品/QA 知道）

1. **上游 `local`(127.0.0.1) DNS provider 被移除**：VPN 内会自递归，属故意偏差；国内解析由 `aliyun`(UDP 223.5.5.5) + `doh.pub`（腾讯 DoH）承担。
2. **bootstrapIps 固化**：DoH/DoT 主机名禁止系统 DNS 自举（会污染/自环），快照内固化 2026-10-25 实测 IP（doh.pub=cф. `DnsBootstraps`/快照）；无 bootstrap 的 provider 运行期干净失败。
3. `abort`/`redirect` 类规则被丢弃（如 `redd.it → www.reddit.com` 302、googleapis 隐私域 abort）；对功能影响小，但规则数与上游不等。
4. `proxy.backup`（jsdelivr 的 gcore 备用）未实现：只走主目标。
5. TLS 上游默认在 Android 用 **TLS1.2+**（上游桌面默认仅 TLS1.3），避免 Android 7-9 全部失败；`sidebike` 侧可按需收紧。
6. ECH/NAT64/speedTest 未实现；HTTPS(65) 记录查询在 tun 路径返回空 NOERROR（客户端回退 A/AAAA）。
7. 动态 `cacheDays` 命中语义依赖 proxy-dev 的 MITM 内存缓存（`cacheDays` 仅作为动作参数传递）。

## 5. 资源加载与测试

- 快照读取：`BundledConfig::class.java.getResourceAsStream("/dev/sidebike/core/rules/rules.json")`（:core JAR classpath，Android APK 内同样有效；不用 assets/File）。
- 测试：`BundledConfigTest`（快照完整/关键规则/provenance）、`ConfigRepositoryTest`（分层合并/JSON5/导入导出）、`UpstreamConfigMapperTest`（runing.json 根识别、cacheDays、bootstrap）、`Json5Test`。
- 更新规则：重新生成 `rules.json`（保留 `_meta`），无需改代码；禁止运行期下载未校验规则。
