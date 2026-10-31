# 远程规则源调研：Johnshall/Shadowrocket-ADBlock-Rules-Forever

> 任务：T11（0.1.1-alpha）规则源研究。目标：为 `core/api/RuleSources.kt` 的 `RuleSourceManager`（proxy-dev 实现 `DefaultRuleSourceManager`）提供**可直接落地的 raw URL、格式说明、解析映射与许可证结论**。
> 调研时间：2026-10-31（Asia/Shanghai）。所有 URL 与大小均来自 GitHub API/raw 实测（只读）。

## 1. 仓库事实

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 | `Johnshall/Shadowrocket-ADBlock-Rules-Forever` | GitHub API `/repos/...` |
| 默认分支 | **`release`**（不是 main/master！raw URL 必须用 release） | API 返回 `default_branch=release` |
| 简介 | 多款 Shadowrocket 规则，强广告过滤，**每天北京时间 8:00 重新构建** | `readme.md`、API `description` |
| 最近 push | `2026-10-30T23:03:03Z`（= 北京时间 10-31 07:03，构建时间戳见各 .conf 头 `build time: UTC 2026-10-30 23:03:01`） | API `pushed_at` + `.conf` 头 |
| Stars / Forks | 31,696 / 2,110（2026-10-31） | API |
| 许可证 | **CC BY-SA 4.0**（Creative Commons Attribution-ShareAlike 4.0 International） | `release/LICENSE`（264B） |
| 文件形态 | 纯文本 Shadowrocket `.conf`（含 `[General]/[Rule]/[URL Rewrite]/[MITM]` 等 section，**不是**纯域名列表） | 实测 `sr_top500_whitelist.conf`、`lazy.conf`、`sr_backcn.conf` |
| 镜像站 | `https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/<file>`（readme 中列出的规则地址，GitHub Pages 部署 release 分支） | `readme.md`「规则地址」；推荐作为备用源 |
| 源数据 | GFWList、Greatfire Analyzer、EasyList/EasyList China、Peter Lowe、乘风规则、blackmatrix7/ios_rule_script、LOWERTOP/Shadowrocket | `readme.md`「本项目引用」 |

## 2. release 分支文件清单（实测大小）

| 文件 | 大小 | 内容/用途 |
|---|---|---|
| `sr_cnip.conf` | **16,711 B** | 国内外划分：中国域名/`GEOIP,CN` 直连，其余走代理；无广告规则。**最适合做"国内直连/域名分流"源** |
| `sr_top500_whitelist.conf` | **30,813 B** | 白名单：top500 可直连境外站 + 中国站 → Direct；手工 Proxy 列表；`FINAL,PROXY`。**体积小、格式干净，适合首选规则源** |
| `sr_top500_banlist.conf` | 1,067,350 B | 黑名单：GFWList 转换的代理域名 + 广告域名少；`FINAL,DIRECT` |
| `sr_top500_banlist_ad.conf` | 3,102,177 B | 黑名单 + 广告过滤（含 REJECT、USER-AGENT、URL-REGEX 等） |
| `sr_top500_whitelist_ad.conf` | 2,065,641 B | 白名单 + 广告过滤 |
| `sr_cnip_ad.conf` | 2,051,539 B | 国内外划分 + 广告过滤 |
| `sr_ad_only.conf` | 2,034,896 B | 仅广告过滤（REJECT 规则集） |
| `sr_adb.conf` | 2,063,760 B | 广告过滤变体 |
| `sr_backcn.conf` / `sr_backcn_ad.conf` | 1,342 B / 2,036,170 B | 回国规则（中国站代理、国外直连） |
| `sr_direct_banad.conf` / `sr_proxy_banad.conf` | ~2.03 MB / ~2.03 MB | 全局直连/全局代理 + 广告 |
| `lazy.conf` / `lazy_group.conf` | 24,592 B / 28,060 B | 懒人配置（国内外分流 + RULE-SET 组合 + 节点/策略组；含完整语法注释，**解析器语法的权威参考**） |

> 决策建议：**0.1.1-alpha 只推荐小体积、语义单一的两个文件**（`sr_cnip.conf`、`sr_top500_whitelist.conf`）；>1 MB 的 `_ad`/`banlist` 文件暂不默认内置/默认启用（体积 + 规则语义 + 许可证聚合风险，见 §5）。

## 3. 推荐 raw URL（可直接填 `RuleSource.url`）

优先级从高到低（全部为 release 分支，raw.githubusercontent 支持 ETag/Last-Modified 条件请求；GitHub Pages 作备用）：

1. **`sr_cnip.conf`（16.7 KB，首选）**
   `https://raw.githubusercontent.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever/release/sr_cnip.conf`
   备用：`https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/sr_cnip.conf`
2. **`sr_top500_whitelist.conf`（30.8 KB，直接放行 top500 可访问站点）**
   `https://raw.githubusercontent.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever/release/sr_top500_whitelist.conf`
   备用：`https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/sr_top500_whitelist.conf`
3. **（可选，advanced）`sr_top500_banlist.conf`（1.04 MB，GFWList 代理域；仅手动启用）**
   `https://raw.githubusercontent.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever/release/sr_top500_banlist.conf`
   备用：`https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/sr_top500_banlist.conf`

对应 `RuleSource` 建议：
```json
{ "id": "johnshall-cnip", "name": "Johnshall 国内外划分", "url": "<1 的 raw>", "format": "AUTO", "enabled": true, "autoUpdate": true, "updateIntervalHours": 24 }
{ "id": "johnshall-top500", "name": "Johnshall Top500 白名单", "url": "<2 的 raw>", "format": "AUTO", "enabled": false, "autoUpdate": true, "updateIntervalHours": 24 }
{ "id": "johnshall-banlist", "name": "Johnshall GFW 黑名单", "url": "<3 的 raw>", "format": "AUTO", "enabled": false, "autoUpdate": true, "updateIntervalHours": 24 }
```
（默认只启用 1；2/3 作为"高级规则源"由用户在 UI 打开，避免首屏解析/内存压力与 CC BY-SA 聚合问题扩大。）

## 4. 格式与解析映射（给 `DefaultRuleSourceManager`）

### 4.1 文件格式（实测确认）

- INI 风格 section：`[General]`、`[Rule]`、`[Host]`、`[URL Rewrite]`、`[MITM]`、`[Proxy]`、`[Proxy Group]`。
- 我们**只关心 `[Rule]`**；`[General]` 里的 `skip-proxy/bypass-tun/dns-server` 等属于 Shadowrocket 自身行为，**不导入**（可记 warning）。
- 规则行：`TYPE,value,POLICY[,no-resolve]`；`#` 开头为注释；空行忽略；同一行可能有首尾空格。
- 策略（policy）：`DIRECT/Direct/direct`、`PROXY/Proxy/proxy`、`REJECT/Reject/reject`（及 `REJECT-DICT/REJECT-ARRAY/REJECT-200/REJECT-IMG/...`）、`FINAL` 的 value 是策略而不是域名、还可能是代理分组/节点名（如 `PROXY` 之外的 `🇭🇰 香港`，本 App 无节点概念 → 按 PROXY 处理并告警）。
- 规则类型（Shadowrocket 官方语义见 `lazy.conf` `[Rule]` 注释）：`DOMAIN`、`DOMAIN-SUFFIX`、`DOMAIN-KEYWORD`、`DOMAIN-WILDCARD`、`IP-CIDR`(`no-resolve`)、`IP-ASN`、`GEOIP`、`RULE-SET`、`DOMAIN-SET`、`USER-AGENT`、`URL-REGEX`、`DST-PORT`、`AND/NOT/OR`、`FINAL`。
- 匹配优先级（同文件内）：从上到下；域名规则优先于 IP 规则；`FINAL` 兜底。

### 4.2 映射到 `ImportedRuleSet`（0.1.1-alpha 建议）

| Shadowrocket 行 | 我们的映射 | 备注 |
|---|---|---|
| `DOMAIN-SUFFIX,example.com,DIRECT` | `directPassDomains += "*.example.com"`；同时 `DOMAIN-SUFFIX,example.com` 的**精确域**也加入（黑名单/白名单语义差异见下） | 后缀匹配：`a.example.com` 命中；我们的 `DomainPatternMatcher` 支持 `*.example.com` |
| `DOMAIN,example.com,DIRECT` | `directPassDomains += "example.com"` | 精确 |
| `DOMAIN-SUFFIX,example.com,PROXY` | `intercepts["*.example.com"][".*"] = InterceptAction(sni = ?)` | **见 §4.3 限制**：无节点概念，PROXY 不能直接映射为"远端代理" |
| `DOMAIN,example.com,PROXY` | 同上（精确域） | 同上 |
| `DOMAIN-SUFFIX,example.com,REJECT`（及 `REJECT-*`） | `intercepts["*.example.com"][".*"] = InterceptAction(abort = true)`；`status=404` 可作为 abort 的展示码 | `InterceptAction.abort` 已由 0.1.1-alpha api 提供 |
| `DOMAIN-KEYWORD,ads,REJECT` | 无法精确映射（我们仅支持域名 pattern）；建议**跳过 + warning**（或映射为内部警告不落配置） | 关键词匹配会误伤，不建议自动展开 |
| `DOMAIN-WILDCARD,a*c.com,DIRECT` | `directPassDomains += "a*c.com"`（我们的 `*` 语义为任意字符，兼容） | 需 parser 转义：我们的 pattern 中 `.`/`*` 语义与 SR 接近 |
| `IP-CIDR,1.2.3.0/24,DIRECT|REJECT` | 写入 `excludeIpList`/白名单仅对 hostname 生效，**无法表达 CIDR** → 跳过 + warning（后续若支持 IP 规则再扩展） | 0.1.1-alpha 明确不支持 |
| `GEOIP,CN,DIRECT` | 记为"国内直连策略"提示（不落规则）；我们已有 `domesticAllowList` + `*.cn` 判定 | `sr_cnip.conf` 的价值主要来自其大量 `DOMAIN-SUFFIX,...,Direct` 行 |
| `RULE-SET,<url>,<policy>` / `DOMAIN-SET` | **不递归拉取**（0.1.1-alpha 安全/超时考虑）：跳过 + warning，可把内部 URL 记录到日志供用户复制添加为独立 RuleSource | `lazy*.conf` 大量依赖此类型，故不推荐作为默认源 |
| `USER-AGENT` / `URL-REGEX` / `DST-PORT` / `AND/NOT/OR` | 跳过 + warning | 本地代理无 UA/URL 级匹配计划 |
| `FINAL,DIRECT` / `FINAL,PROXY` | 记录为 `hasFinalDirect/hasFinalProxy`（parser 内字段，不进 `ImportedRuleSet`）；**不落配置** | 可作为 UI 提示"该规则源默认策略为 PROXY，本地代理模式下仅域名直连生 效" |
| `[General]/[Host]/[Proxy]/[Proxy Group]/[MITM]/[URL Rewrite]` | 忽略整段（只解析 `[Rule]`，若文件无 `[Rule]` section 则全文按裸规则行解析） | 容错 |

### 4.3 PROXY 策略的落地限制（重要）

Shadowrocket 的 `PROXY` 表示"交给远端代理节点转发"；本 App 是**本地 MITM/直连代理**，没有远端节点。因此：
- 0.1.1-alpha 的 `RuleSourceManager` 对 `PROXY` 行建议**只做"加速/直连决策提示"**，不自动生成 `intercepts`：
  - 方案 A（推荐，保守）：跳过 PROXY 行并计 warning `"PROXY 策略暂不支持（本 App 无远端节点）"`；
  - 方案 B（可选，需产品确认）：把 PROXY 域名作为"需要境外直连/SNI 伪装候选"，但**不能自动选择 SNI 诱饵**（诱饵必须与目标边缘同源；错误诱饵会 handshake failure，见 `docs/feasibility/01-dev-sidecar-analysis.md` §1.2/§3.3），故只在 `intercepts` 不存在的域名上补 `{ sni: null }` 无意义 → 不建议。
- `DIRECT` 行是真正有直接价值的：把域名写进 `whiteList`（直连、不 MITM）或 `domesticAllowList`（DNS/代理分流直连），这正是 0.1.1-alpha"MITM 关闭 + 加速开启仍能上外网"所需要的放行数据（减少误代理）。
- `REJECT` 行可直接映射 `abort`（域名拦截），但大体积广告规则（2MB+）不在默认源。

### 4.4 解析器实现要求（对接 `api/RuleSources.kt`）

- **格式自动识别**（`RuleSourceFormat.AUTO`）：内容含 `[Rule]`/`[General]`/`[MITM]` section 头 → `SHADOWROCKET_LIST`；纯 JSON（首字符 `{`）→ `SIDEBIKE_JSON`；否则若每行无逗号且像域名 → `DOMAIN_LIST`；`.list` 后缀可视为 SR 规则列表（blackmatrix7 的 `.list` 只有规则行没有 section 头，需同时接受"无 section 头的裸规则行"）。
- 注释：`#`；`;` 仅在非规则行场景作为注释（SR 规则行用 `#`）。
- 去重：同一域名多条规则时，**后出现的 REJECT 优先于 DIRECT/PROXY**（安全优先），或严格按文件顺序"后者覆盖前者"（建议后者覆盖，便于用户自定义排序）。
- 容错：坏行跳过 + `warnings += "line N: ..."`；单文件 warning 上限（如 50 条）后汇总"共 N 行被跳过"。
- 大小/编码：UTF-8（BOM 容忍）；`sr_top500_banlist_ad.conf` 3 MB → 响应体上限至少 8 MB，解析用流式/逐行，不要 regex 全文。
- 条件请求：raw.githubusercontent 返回 `ETag`/`Last-Modified`，按 `RuleSource.etag/lastModified` 发 `If-None-Match/If-Modified-Since`，304 → `notModified=true`。
- 超时：连接 10s/读取 20s；失败保留旧规则并回填 `lastError`（不要清空用户已导入规则）。

## 5. 许可证结论（必须记录到"关于/致谢"）

- 该仓库整体 **CC BY-SA 4.0**：允许复制/分发/改编，但要求 **署名（Attribution）+ 相同方式共享（ShareAlike）**。
- 建议：
  1. **运行期由用户配置 URL 拉取**（不把规则文件打进 APK）→ 属于用户主动获取第三方内容，App 只需在"规则源"页展示来源名称/URL/许可证链接（`https://creativecommons.org/licenses/by-sa/4.0/`）；
  2. 若未来要**内置/打包**规则：必须附带 LICENSE 全文 + 署名 + 说明修改（我们做的格式转换属于改编 → 改编后的规则数据也需 CC BY-SA 分发）；
  3. 其上游引用项目许可各异（GFWList、EasyList(GPL/CC BY-SA 混合)、Peter Lowe、乘风、blackmatrix7、LOWERTOP），批量再分发前需逐个核对；因此 **0.1.1-alpha 明确不内置该仓库规则文件**，只提供"推荐规则源"由用户启用。
- App 内"关于/第三方致谢"建议追加条目：
  `规则源：Johnshall/Shadowrocket-ADBlock-Rules-Forever (CC BY-SA 4.0)` + 项目 URL + 许可证 URL。

## 6. 风险与建议

| 风险 | 说明/缓解 |
|---|---|
| raw.githubusercontent 在部分网络不可达 | 提供 GitHub Pages 镜像 URL 作为备用；RuleSourceManager 失败不清空旧规则 |
| 每日重建导致 ETag 每天变化 | `updateIntervalHours=24` 合理；自动更新仅在 network + 用户允许时 |
| 文件中 `Direct`/`Proxy` 大小写混用 | parser 对 policy 做 `uppercase()` 归一 |
| 大文件（>1MB）解析内存/CPU | 0.1.1-alpha 不默认启用 banlist/_ad 文件；parser 逐行 + 上限 |
| PROXY 语义错映射导致误拦截 | 见 §4.3，默认跳过 PROXY 行；只导入 DIRECT/REJECT |
| CC BY-SA 合规 | 见 §5：运行期导入 + 致谢页；不打包 |

## 7. 结论（给 proxy-dev / UI 的最小落地清单）

1. `RuleSourceManager.parse()` 必须支持 `[Rule]` section + 裸规则行两种输入，策略归一化，`DOMAIN/DOMAIN-SUFFIX/DOMAIN-WILDCARD` → 规则；`DIRECT→directPassDomains`、`REJECT*→intercepts(...abort=true)`、其余类型/策略 → warning 跳过。
2. 预置 3 个"推荐规则源"模板（默认仅启用 #1）：URL 见 §3；`format=AUTO`。
3. UI："设置 → 规则源"页展示 来源名称/URL/许可证/最后更新时间/导入条数/lastError；一键更新 + 重置。
4. 不内置规则文件；不在 0.1.1-alpha 实现 RULE-SET 递归抓取。
