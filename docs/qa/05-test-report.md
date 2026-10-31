# 05 · Dev-Sidebike QA 测试报告（0.1.1-alpha 终版，含 follow-up 复验）

> 作者：challenger（独立 QA）。版本：**0.1.1-alpha**（versionCode 2，HEAD f4ff7fb；follow-up 使用已安装的 0.1.1-alpha-debug 包，Lead 同期修复 QA-011-01 后重建，不影响本报告功能结论）。
> 环境：Windows 11 + PowerShell 5.1；离线 Android SDK 35；headless 模拟器 `qa35` = `system-images;android-35;google_apis_playstore;x86_64`（WHPX，1080x1920/420dpi，Android 15）。本工作区无真机。
> 被测：`out/dev/app-debug.apk`（0.1.1-alpha-debug，x86_64，模拟器验收）；`dist/Dev-Sidebike-0.1.1-alpha-release.apk`（release 静态门禁）。
> 证据：`docs/qa/evidence/011/`（log + 真实 PNG + 设备侧 dump/config 文本）；夹具 `docs/qa/fixtures/`；自动化 `_script/qa/`。
> 本文件已整体重写为干净 UTF-8；0.1.0 历史结论见 §8 摘要与 `dist/TEST-REPORT.md`。

## 1. 结论摘要

- **静态/交付/回归门禁：全 PASS**（§4）。
- **用户 10 项问题全部验证到可判定状态：U1/U2/U3/U5/U6/U7/U9 明确 PASS；U4/U8 初判 PASS（建议真机慢动作复核）；U10 BLOCKED（上游逐项等价性未独立审计）。**
- 本轮未发现 S0/S1/S2 产品缺陷；产品级问题 2 项均为 S3：**QA-011-01**（日志页导出 FAB 缺 a11y 标签，Lead 已在修）、**QA-011-03**（规则源刷新未回传 ETag，条件请求 304 未生效，需 dev 确认是否设计如此）。
- 自动化限制项（SAF .zip 端到端、动画逐帧设备差异）已如实标注并给替代证据，未以 PASS 误报。

## 2. 用户问题验收矩阵（U1–U10）

| ID | 用户问题 | 状态 | 证据 / 说明 |
| --- | --- | --- | --- |
| U1 | 日志导出（.log/.zip 代替剪贴板） | **PASS**（.zip 文件端到端 BLOCKED） | 日志页“导出”FAB → sheet「诊断信息」含 **导出日志文件（.log） / 导出诊断包（.zip） / 复制日志文本**（`manual-export-sheet-final.png`）；实测 .log → DocumentsUI → SAVE → 设备保存 `/sdcard/Download/dev-sidebike-20261031-040256.log.txt`（42,379 B，可读，`export-saf-log.png`）；.zip 选项已在 sheet 验证，.zip SAF 保存因 DocumentsUI 时序自动化 BLOCKED。controller 单测：`buildLogTextContainsEnvironmentDiagnosticsAndLogs`、`buildDebugBundleIsZipWithRequiredEntries`、`LogExportTest`（随 265 app tests 全绿）。 |
| U2 | MITM 开关与无 CA/不能上外网诊断 | **PASS** | (a) run-as 注入 legacy config（mitm=true，CA 未装）→ 重启自动降级：首页卡片+粉色横幅原文“检测到 MITM 已开启但 CA 证书未安装，已自动降级直连，避免境外 HTTPS 全部失败；请先安装证书再重新开启 MITM”，chips“MITM 已关闭”，logcat `SidebikeController: [mitm] …已自动降级直连…`（`20261031-113726-01-legacy-home.png`、`legacy-logcat-20261031-113726.txt`）。(b) 无 CA 开启 MITM 被拒并提示“CA 证书尚未安装或未被系统信任，请先在「证书安装」页完成安装…”（`20261031-115048-01-mitm-toggle-no-ca.png`）。(c) VPN 可启动、CONNECT 可达、**MITM/proxy 相关 CERTIFICATE_UNKNOWN=0**（仅 CaInstaller 自检 1 条，预期），不再断网。(d) 设置→「运行网络诊断」生成 5 段报告（引擎/DNS、解析、直连 TLS、本地代理、系统集成），可复制/保存到文件（`20261031-115048-03-diagnostics-result-011.png`）。 |
| U3 | CA 安装流程（黑屏/“未安装该证书”/检测） | **PASS** | ① 点“安装 CA 证书”→“生成并安装证书”弹 **标准 MD3 AlertDialog**（说明/锁屏提示/指纹/取消；`20261031-114124-02-ca-dialog.png`），**无黑屏**（黑屏检测 avgBrightness=142；旧黑遮罩已修）。② 未设锁屏分支：返回 LOCK_SCREEN_REQUIRED，UI 提示“请先在系统设置中设置锁屏密码/图案后再安装 CA 证书（系统证书安装器要求设备锁屏）”+“去设置锁屏”入口（log `20261031-114927-01-export-logs-screen.png` 日志区可见 WARN ca 文案）。③ **有锁屏 PIN 分支（follow-up 实测 PASS）**：`locksettings set-pin 1234` → `get-disabled=false` → 重新点安装并确认 → 系统 **`com.android.certinstaller/.CertInstaller`** 启动（界面“Install CA certificates in Settings…”，`u3-pin-installer-check.png`）；已清理 PIN（clear --old 1234）。④ `.crt 导出到文件` → DocumentsUI 保存器启动 PASS（`20261031-114124-04-ca-export-file.png`）。 |
| U4 | Rules 间距 + 展开动画 | **PASS（初判）** | 规则页布局/间距/分组与摘要行正常（`final-navrules-settled.png`、`20261031-114443-01-rules-list.png`）；展开/收起状态截图（`u6-rule-expanded.png`、`u6-edit-persisted-final2.png`）。逐帧动画未在设备上逐帧核对（建议真机慢动作复核）。 |
| U5 | 长规则卡片 ripple 形状 | **PASS（静态帧证据）** | 低负载下连续截帧：`u5-ripple-final-1.png`（按压/ripple 高亮**覆盖整张圆角卡片**，无球形/局部裁剪）vs `u5-ripple-final-2/3/settled.png`（动画结束展开态）；卡片圆角裁剪正常。建议真机慢动作再确认。 |
| U6 | 规则新增/编辑/删除、文本/URL 导入、自动更新 | **PASS** | ① **新增**：添加规则对话框（域名 pattern/路径正则/动作段控 SNI/镜像/abort/备注），填 `qa-persist.example.com` + SNI `cdn.qa.example` → 保存成功（Snackbar“规则已保存”），计数 拦截 77→78、域名 69→70；**重启 App 后仍存在**（`u6-after-save2.png`、`u6-search-after-restart.png`）。② **编辑**：展开卡片→编辑→改 SNI 为 `cdn-edit2.qa.example`→保存→卡片显示新值；**重启后仍为 cdn-edit2**（`u6-edit-persisted-final2.png`，prefs hasNewSni=True）。③ **删除自定义规则**：删除确认框“删除该规则？…内置规则会记录为禁用，避免下次启动被还原”→确认后列表消失，重启后仍不存在（`u6-deleted-custom-final.png`，prefs 无该规则）。④ **删除内置规则**：删除 `*.cdninstagram.com`（内置）→ 计数 69→68，重启后仍不存在；prefs 实测 `"disabledIntercepts":["*.cdninstagram.com\t.*"]`（`u6-builtin-deleted2.png`、`u6-builtin-persisted2.png`）。⑤ **规则源 URL 导入**：规则源 tab→添加自定义规则源（名称 QA，URL `http://10.0.2.2:18080/rules.list`）→ 立即刷新：服务端收到 `GET /rules.list`，导入 2 条（直连 5→7，卡显示“上次更新/上次导入 2 条”，`u6-source-refresh1.png`）；**第二次刷新成功且规则计数不变（无重复规则）**（服务端 12:25:52 再次 GET；见 QA-011-03 ETag 说明）。⑥ 编辑/删除/增删后的持久化均在**重启**后验证。 |
| U7 | 国内域名列表可见/可编辑 | **PASS（可见/可编辑入口）** | “国内域名”tab：共 **1236 个域名**、“+ 添加国内域名”、“恢复默认”、逐行删除图标（`final-tab-国内域名.png`）；“直连放行”tab 与摘要“直连 7 个”正常（`final-tab-直连放行.png`）。逐项增删落盘未逐条穷举（等价路径已被 U6 规则增删持久化覆盖）。 |
| U8 | 导航转场 Home 重叠 | **PASS（初判）** | 转场采样帧未见两页同时可见（`final-navrules-immediate.png` Home 干净帧 + settled 帧）；M3 fadeThrough 顺序转场。建议真机慢动作复核。 |
| U9 | 三进度指示/去 uptime | **PASS** | 启动中仅 **1 个**进度指示（“启动中…”按钮），状态“正在准备 VPN/正在申请 VPN 权限”，**无“已运行/加速时长”**（`final-progress-start-1/2/3.png`）；`final-running.png`、`final-progress-stop-1/2.png`；三态 dump 断言 `已运行`=0。 |
| U10 | 跟进上游设置/加速逻辑 | **BLOCKED（部分可见）** | 设置页新增：超时预设（默认/快速/稳健+秒数）、网络诊断、开机自启动、MITM 与证书等（`20261031-120059-02-export-after-tap.png` 等）；与 dev-sidecar 上游配置的逐项等价性未独立审计（后端 265 测试绿可佐证），建议 owner 或后续审计。 |

## 3. 关键截图证据（docs/qa/evidence/011/）

| 证据 | 说明 |
| --- | --- |
| `20261031-113726-01-legacy-home.png` / `legacy-logcat-20261031-113726.txt` | U2a legacy 自动降级横幅 + logcat |
| `20261031-115048-01-mitm-toggle-no-ca.png` | U2b 无 CA 开 MITM 被拒 Snackbar |
| `20261031-115048-02/03-*.png` | U2c VPN 运行 / U2d 网络诊断报告 |
| `20261031-114124-02-ca-dialog.png` / `-04-ca-export-file.png` | U3 MD3 对话框（无黑屏）/ .crt 导出 SAF |
| `u3-pin-installer-check.png` | U3 有锁屏 PIN → 系统证书安装器 CertInstaller |
| `20261031-114443-02-rules-add-dialog.png`、`u6-after-save2.png`、`u6-search-after-restart.png` | U6 新增规则 + 重启持久化 |
| `u6-edit-dialog-final2.png`、`u6-edit-persisted-final2.png` | U6 编辑 SNI + 重启持久化 |
| `u6-delete-dialog.png`、`u6-deleted-custom-final.png` | U6 删除自定义规则 |
| `u6-builtin-delete-dialog.png`、`u6-builtin-deleted2.png`、`u6-builtin-persisted2.png` | U6 删除内置规则 + 重启持久化（disabledIntercepts） |
| `u6-source-added.png`、`u6-source-refresh1.png`、`u6-source-refresh2-final.png` | U6 规则源 URL 导入 + 刷新 |
| `final-tab-直连放行.png` / `final-tab-国内域名.png` / `final-tab-规则源.png` | U6/U7 规则 tab |
| `manual-export-sheet-final.png` / `export-saf-log.png` | U1 导出 sheet / SAF 保存日志 |
| `final-progress-start-1/2/3.png` / `final-running.png` / `final-progress-stop-1/2.png` | U9 单进度/无 uptime |
| `u5-ripple-final-1.png` / `-settled.png` | U5 ripple 覆盖整卡 |
| `final-navrules-immediate.png` / `final-navrules-settled.png` | U8 转场帧 |
| `logs-current.png` | 日志页（FAB a11y 标签问题，QA-011-01） |

## 4. 回归与静态/交付门禁（证据）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `check_apk.ps1`（debug） | **PASS**（0.1.1-alpha-debug，versionCode 2，4 ABI，权限/VpnService/FGS/always-on/NSC 合规） | 本轮终端输出 |
| `check_apk.ps1 -ExpectArmOnly`（release） | **PASS**（ARM-only、debuggable=false） | 本轮终端输出 |
| `check_delivery.ps1 -Version 0.1.1-alpha` | **PASS**（版本/gitSha f4ff7fb/SHA256SUMS/manifest 一致） | 本轮终端输出 |
| SHA256SUMS 逐文件复算 | **PASS** | 本轮终端输出 |
| `:core:test --tests "dev.sidebike.core.qa.*"` | **PASS**（9/9） | 本轮执行输出 |
| `:app:testDebugUnitTest --tests "dev.sidebike.app.qa.*"` | **PASS**（8/8）；pipeline all app 265/0 failed | 本轮执行输出 |
| `audit_md3` / `lint` / `requirements 46/46` | **PASS**（Lead 广播 pipeline all GREEN f4ff7fb） | Lead 通报 |
| 模拟器 smoke 五屏 | **PASS**（6/6） | `qa-smoke-*` |

## 5. 缺陷清单

| ID | 级别 | 现象 / 影响 | 复现 | 证据 | Owner | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| QA-011-01 | **S3**（无障碍/可观测性） | 日志页“导出”FAB 在无障碍/uiautomator 树中 **无 text/content-desc**（仅 clickable 节点，bounds [774,1458][1038,1605]）→ TalkBack 无法朗读、UI 自动化无法文本定位（需坐标兜底）。不影响视觉与功能。 | 日志页 `uiautomator dump` 搜“导出”无结果；按坐标点 FAB 功能正常 | `logs-current.png`、`manual-export-sheet-final.png` | ui-dev | Open（Lead 正在修鉴权标签） |
| QA-011-03 | **S3**（网络/流量优化，需 dev 确认设计） | 规则源刷新**未回传 ETag**：服务端首次 `GET /rules.list` 返回 `ETag: "list-v1"`，第二次刷新请求 `If-None-Match` 为空 → 条件请求 304 未生效（仍 200 全量拉取）。第二次刷新功能正确、无重复规则，仅流量/性能优化项。 | 规则源添加 `/rules.list` → 立即刷新两次 → `http://127.0.0.1:18080/requests.json` 两次 `inm` 均为 null | `rule-source-server-final.log`、`u6-source-refresh2-final.png` | android-dev | Open（请确认 AUTO/.list 源是否应持久化 ETag；若设计如此可关闭） |
| QA-011-02 | BLOCKED（非缺陷） | `.zip` SAF 端到端保存、U4/U5/U8 真机慢动作动画复核、U10 上游逐项等价审计：自动化/设备条件未完成 | — | §2/§6 | QA/owner 真机 | Open（后续 run） |

> 未发现 S0/S1/S2 产品缺陷。

## 6. 自动化与限制（本轮新增/修复）

- `_script/qa/lib_android.ps1`：`Adb-Shell`（单串复合命令）、`Qa-DismissSystemDialogs`（消除并发构建 ANR 对话框）、`Qa-WaitBuildQuiet`、`Qa-TapText` 重试强制刷新 dump、`Qa-TapClickableNearBottomRight`（无文本 FAB 兜底）。
- `_script/qa/qa_011_flows.ps1`：`legacy`（run-as 注入 legacy config）、`ca`（MD3 对话框/黑屏/锁屏/.crt）、`mitm`（gating + CERTIFICATE_UNKNOWN 过滤 + 网络诊断）、`export`/`rules`/`import`。
- follow-up 手工自动化：`_script/qa/qa_u6_u3_u5.ps1`（U6 规则编辑/删除/导入巡检、U3 锁屏、U5 帧）。
- `_script/qa/rule_source_server.ps1`：hermetic 源（JSON/list/域名/畸形/慢/500/请求回显；ETag→304）。**模拟器经 10.0.2.2 访问必须 `-BindAddress any`。**
- 限制：SAF 保存按钮时序、Compose 动画帧率、模拟器高负载 ANR、真机锁屏/MIUI 组合；相关项给替代证据或 BLOCKED。

## 7. 证据索引

- 自动化日志：`docs/qa/evidence/011/qa-{smoke,legacy,mitm,ca,export,rules}-<stamp>.log`；规则源请求日志 `rule-source-server-final.log`。
- 截图：`docs/qa/evidence/011/*.png`（§3）。
- 设备侧 dump/config：`u6-*-*-20261031-*.xml`；prefs 验证：`disabledIntercepts` 含 `*.cdninstagram.com\t.*`；legacy logcat：`legacy-logcat-20261031-113726.txt`。
- 设备侧保存文件：`/sdcard/Download/dev-sidebike-20261031-040256.log.txt`（42,379 B）。

## 8. 0.1.0-alpha 历史摘要

- 交付 `dist/Dev-Sidebike-0.1.0-alpha-{release,debug}.apk` + SHA256SUMS + manifest + README + TEST-REPORT；release gate PASS。
- 模拟器 E1 系统代理（HttpProxy 127.0.0.1:31181）、E2 DNS 防污染（github.com→20.200.245.247 等）、规则种子 69 域名/77 路径、proxy 200/CONNECT 200、故障注入恢复全部 PASS；已知项 QA-010（tun 非 IPv4 debug 日志，S3，0.1.1 网络诊断已把 tun 丢弃计数纳入报告）、MITM/CA 真机 E2E、MIUI 保活/Private DNS/lockdown 需 owner 真机。

## 9. 最终 QA 建议（0.1.1-alpha）

**可发布 beta**：U1/U2/U3（含锁屏分支）/U5/U6（增删改+持久化+URL 导入）/U7/U9 已在模拟器实测 PASS 或给出等价强证据；U4/U8 初判正常，建议 owner 真机 5 分钟慢动作复核；U10 上游对齐建议下一轮独立审计。
遗留 S3：QA-011-01（Lead 已修）、QA-011-03（ETag 条件刷新，建议 android-dev 确认）。
