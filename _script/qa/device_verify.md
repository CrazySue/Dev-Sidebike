# 真机验证清单（Owner 手机执行 · 10 分钟版 + 30 分钟版）

> 目的：本工作区**没有真机**（`adb devices` 为空、无 system image 时无法 E2E 验证），而 Plan A 的三个核心假设必须在真机闭环。请主人按本清单执行并回报结果（截图/文字即可）。
> 需要：Android 手机（Android 10+ 最佳；如果是小米/HyperOS 请额外做 M5）、USB 数据线、电脑上本仓库的 `tools/android-sdk/platform-tools/adb.exe`。
> 前置：手机开启「开发者选项 → USB 调试」，连接电脑后执行 `adb devices` 能看到设备；安装 `out/dev/app-debug.apk`。
> ⚠️ 开始前确认：手机**没有**连接其他 VPN（Clash/v2rayNG/WireGuard 等）；「私人 DNS/Private DNS」设为 **关闭**（或自动）；不要开「始终开启的 VPN 且阻止未使用 VPN 的连接」（lockdown），除非专门测 M6。

## M1（P0，最重要）：系统代理是否真的生效

**目的**：验证"VPN 只路由 10.111.0.2/32 时，`setHttpProxy(127.0.0.1:31181)` 是否被系统下发、Chrome 是否走代理"。这是 beta 的核心前提。

1. 电脑上开一个终端，持续看 App 日志（debug 包）：
   `adb logcat -s DevSidebike:V SidebikeVpn:V proxy:V > dsh-log.txt`
   （tag 以实际代码为准；也可用 App 内"日志"页面）
2. 手机打开 Dev-Sidebike → 点启动 → 系统弹 VPN 授权 → 允许。
3. 记录状态：通知栏出现"Dev-Sidebike VPN 已连接"；App 状态 RUNNING。
4. 电脑执行（关键证据）：
   - `adb shell dumpsys connectivity > conn.txt`
   - 在 `conn.txt` 里找 `NetworkAgentInfo` 中的 **VPN network**，看它的 `LinkProperties` 是否包含：`DnsAddresses: [10.111.0.2]` 和 **`Proxy: [127.0.0.1:31181]`**（不同 Android 版本字段名可能是 `HTTP proxy` / `Proxy`）。
   - 若找不到 VPN 段，执行 `adb shell dumpsys connectivity | findstr /i "vpn proxy dns"`。
5. 手机打开 Chrome，访问 `https://example.com` 和 `https://www.google.com`（或任意 HTTPS 站）。
6. 回看 `dsh-log.txt` / App 日志：是否出现 `CONNECT example.com:443`（或 google）的代理请求记录？

| 结果 | 判定 | 下一步 |
| --- | --- | --- |
| dumpsys 有 `Proxy: [127.0.0.1:31181]` 且日志有 CONNECT 且网页正常 | ✅ Plan A 成立 | 继续 M2 |
| dumpsys 有 Proxy 但日志无 CONNECT | ⚠️ 系统代理下发但 app 未用 | 记录手机型号/Android 版本；向 Lead 报告（可能 Chrome 版本/厂商定制） |
| dumpsys 无 Proxy | ❌ Plan A 不成立（VPN 非默认网络不下发） | 立即报告 Lead → 启用 F1 内置 WebView 浏览器方案 |

**回报格式**：手机型号 + Android 版本 + 三条命令输出片段 + 结论行。

## M2（P0）：DNS 拦截是否生效

1. Private DNS 设为关闭；启动 Dev-Sidebike。
2. 手机浏览器访问 `https://www.baidu.com`、`https://github.com`（GitHub 可能仍因 SNI 被阻断，属正常）。
3. 看 App 日志的 DNS 统计：DNS 查询数在增长；查询域名包含上述域名。
4. `adb shell dumpsys connectivity` 确认 VPN 网络 DNS = 10.111.0.2。
5. 对照：关闭 App 后再访问一次，观察同一域名是否（在被污染的网络下）解析缓慢/失败。有对比即可。

通过标准：日志有 DNS query 记录、浏览器能解析域名、无长时间 DNS 超时。

## M3（P1）：镜像/加速效果 A/B

选 2 个典型目标（例如 `github.com`、`raw.githubusercontent.com`、`objects.githubusercontent.com`），分别在 App 启动/停止时访问并记录：能否打开、耗时（秒）、是否报证书错误。这是 beta 价值证据（不要求完全成功，只要有对照数据）。

## M4（P1，如启用 MITM）：用户 CA + SNI 伪装真机验证

1. App 内点"安装证书" → 系统安装向导 → 安装为"CA 证书/VPN 和应用"；设置锁屏密码（如系统要求）。
2. App 内状态应显示"证书已安装"（若仍显示未安装请报告——检测逻辑有 bug）。
3. 开启 MITM，Chrome 访问一个被 intercept 规则覆盖的镜像域名：预期**无证书错误**且页面正常；`adb logcat` 或 App 日志出现 MITM 记录。
4. 记录：手机型号、Android 版本、是否 MIUI/HyperOS、证书安装路径（设置里的具体菜单）。

## M5（P1，小米/HyperOS 必做）：后台存活

1. 启动 App → 锁屏 → 等待 30 分钟 → 解锁查看：VPN 是否仍连接（通知栏 VPN 图标/App 状态）。
2. 打开其他 app 刷几分钟 → 回 Dev-Sidebike 看状态。
3. 若断开：在 设置→应用→Dev-Sidebike：开"自启动"、省电策略"无限制"，后台任务下拉加锁，再测一次。
4. 报告：是否 MIUI/HyperOS 版本、是否开启自启动/无限制、30 分钟是否存活。

## M6（P1）：异常环境组合（每项单独测，测完恢复）

1. **Private DNS=hostname**（如 `dns.google`）：启动 App，App 应弹出/显示红色警告"私人 DNS 会绕过 DNS 拦截"；记录实际表现。
2. **lockdown**：设置→VPN→Dev-Sidebike→开"始终开启的 VPN"和"阻止未使用 VPN 的连接"→启动 App：预期 App 拒绝启动或强警告（而不是全设备无网）。记录实际表现。
3. **其他 VPN**：先开 Clash/v2rayNG，再开 Dev-Sidebike：预期提示"只能同时使用一个 VPN"。
4. **省电模式/飞行模式切换**：开飞行再关，App 应能自动恢复或给出可重试错误（不崩溃）。

## 附：无 adb 时的最小验证（主人手点版）

如果不能接 USB 调试，也可只做：
1. 启动 App 后 Chrome 打开任意 HTTPS 站点 → App 日志页若能看到 CONNECT 记录 = M1 通过（最关键）。
2. 打开 `https://github.com`（若代理生效）→ 能否打开。
3. 锁屏 10 分钟看 VPN 是否存活。
4. 开 Private DNS 主机名模式，看 App 是否提示。

## 回报模板

```
M1: 手机型号/版本 | dumpsys 是否有 Proxy | 日志是否有 CONNECT | Chrome 是否正常
M2: DNS 查询数 | 域名解析是否正常 | 有无超时
M3: github/raw 打开情况（开/关 App 对比）
M4: CA 安装是否成功 | 检测状态 | Chrome 镜像域名是否正常（如测了）
M5: 30 分钟是否存活 | 自启动/无限制是否开启
M6: 每项实际表现
其它: 崩溃/异常截图/日志
```
