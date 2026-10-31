# 05 · Dev-Sidebike QA 测试报告（迭代更新）

> 作者：challenger（独立 QA）。本轮版本：**0.1.1-alpha**（versionCode 2）。
> 最近更新：2026-10-31（本轮进行中，随构建与验证结果迭代）。
> 环境：Windows 11 + PowerShell 5.1；离线 Android SDK 35；headless 模拟器 `qa35` = `system-images;android-35;google_apis_playstore;x86_64`（WHPX，1080x1920/420dpi，Android 15）。本工作区无真机。
> 证据：`docs/qa/evidence/011/`（本轮 log + 真实 PNG）；`docs/qa/fixtures/`（hermetic 导入夹具）；自动化见 `_script/qa/`。
> 说明：本文件在 0.1.1 轮被**整体重写为干净 UTF-8**（上一轮因 PS5.1 编码操作在部分章节产生乱码，历史 0.1.0 结论保留在 §2 摘要与 `dist/TEST-REPORT.md`）。

## 1. 本轮状态摘要（进行中）

| 项 | 状态 |
| --- | --- |
| T12 认领 / 测试计划 | ✅ `docs/qa/04-test-plan.md` 已更新 0.1.1 矩阵（U1–U10，用户 10 项问题逐条映射） |
| 自动化骨架 | ✅ `lib_android.ps1` / `rule_source_server.ps1` / `qa_011_flows.ps1` / fixtures；smoke 流程 6/6 PASS 验证通过（旧 APK 上跑通 UI 自动化链路） |
| 0.1.1-alpha 构建 | ⏳ 等 Lead 宣布 build ready（UI 功能开发中，预期暂态编译错误） |
| 用户问题 U1–U10 验收 | ⏳ 待新 APK |
| 回归（core/app 测试 + 静态门禁） | ⏳ 待新 APK |
| 最终 QA 结论 | ⏳ 待本轮 release gate |

**当前无产品缺陷结论**——尚未在 0.1.1 构建上执行验收，任何"看起来像 bug"的现象会在 §5 记录并附证据。

## 2. 0.1.0-alpha 基线摘要（上一轮，已交付 QA PASS）

- 交付：`dist/Dev-Sidebike-0.1.0-alpha-{release,debug}.apk` + SHA256SUMS + delivery-manifest + README + TEST-REPORT；`check_delivery.ps1` / `check_apk -Release` PASS（debuggable=false、ARM-only ABI、权限/NSC/VpnService/FGS/always-on 合规）。
- 功能验收（模拟器 API35）：**E1 系统代理下发**（VPN LinkProperties `HttpProxy: [127.0.0.1] 31181`）、**E2 DNS 防污染**（github.com→20.200.245.247 等真实 IP）、规则种子（69 域名/77 路径）、代理由真实 HTTP 200 / CONNECT 200、统计字节正常、停止/force-stop/KILL_PROXY/CRASH_ENGINE 故障注入全部自动恢复网络且可重启；pipeline all 全绿。
- 已知非阻塞项（结转 0.1.1 跟踪）：QA-010（tun 偶发 `not-ipv4-or-malformed` debug 日志，S3）、MITM/CA 真机端到端未测（默认关闭）、MIUI/HyperOS 保活与 Private DNS/lockdown 真机组合需 owner M1–M6。

## 3. 0.1.1-alpha 用户问题验收矩阵（待填）

> 用例定义见 `docs/qa/04-test-plan.md` §3（U1–U10）。状态取值：PASS / FAIL / BLOCKED（UI 未实现，需重跑）/ SKIP。

| ID | 用户问题 | 状态 | 证据 |
| --- | --- | --- | --- |
| U1 | 日志导出改 .log/.zip（非剪贴板） | ⏳ | — |
| U2 | MITM 开关与无 CA/不能上外网诊断 | ⏳ | — |
| U3 | CA 安装流程（黑屏/未安装该证书/检测） | ⏳ | — |
| U4 | Rules 间距 + 展开动画同步 | ⏳ | — |
| U5 | 长规则卡片 ripple 形状 | ⏳ | — |
| U6 | 规则新增/编辑/导入（文本/URL/自动更新） | ⏳ | — |
| U7 | 国内域名直连列表可见/可编辑 | ⏳ | — |
| U8 | 导航转场 Home 重叠 | ⏳ | — |
| U9 | 三进度指示 + 去 uptime | ⏳ | — |
| U10 | 跟进上游设置/加速逻辑 | ⏳ | — |

## 4. 回归与静态门禁（待填）

| 检查 | 状态 | 证据 |
| --- | --- | --- |
| `:app:testDebugUnitTest` / `:core:test` | ⏳ | — |
| `VpnCodecFuzzTest`（8）/ `CoreAdversarialQaTest`（9） | ⏳ | — |
| `audit_md3.ps1` / `check_apk.ps1` / `check_requirements.ps1` | ⏳ | — |
| `emulator_smoke.ps1`（E1/E2/SMK） | ⏳ | — |
| `check_delivery.ps1`（0.1.1-alpha dist） | ⏳ | — |

## 5. 缺陷清单（本轮）

| ID | 级别 | 现象 | 证据 | Owner | 状态 |
| --- | --- | --- | --- | --- | --- |
| — | — | 暂无（待新 APK 执行） | — | — | — |

## 6. 自动化资产（本轮新增/验证）

| 资产 | 说明 |
| --- | --- |
| `_script/qa/lib_android.ps1` | adb/UI 助手：UiDump/Texts/FindCenter/TapText/WaitText/Screenshot（真 PNG）/StartVpn/StopVpn/PingHost/结果收集；内置系统 ANR 对话框自动消解（并发构建致 SystemUI 卡死时） |
| `_script/qa/rule_source_server.ps1` | hermetic 远程规则源：`/rules.json`、`/rules.list`、`/domains.txt`、`/malformed.json`、`/slow`、`/error500`、`/requests.json`；ETag + If-None-Match→304。**模拟器需经 10.0.2.2 访问，服务必须绑 0.0.0.0（`-BindAddress any`）** |
| `_script/qa/qa_011_flows.ps1` | `-Flow smoke|progress|nav|ca|mitm|export|rules|import|all`；每步截图+UI dump；UI 未实现报 BLOCKED 不误报 PASS |
| `docs/qa/fixtures/` | 规则 JSON / Shadowrocket list / 域名列表 / 畸形输入 |
| `_script/qa/emulator_smoke.ps1` 等 | 0.1.0 基线工具继续复用（E1/E2/SMK 回归） |

**骨架自检（2026-10-31 10:58）**：`qa_011_flows.ps1 -Flow smoke -SkipInstall` 在模拟器当前已装 APK 上 **6/6 PASS**（env launch + 五屏导航渲染 + PNG 截图），证明 adb/UI/截图/结果链路可用（不代表 0.1.1 功能验收）。

## 7. 证据索引

- 本轮自动化日志/截图：`docs/qa/evidence/011/`（`qa-<flow>-<stamp>.log`、`<stamp>-NN-<name>.png`）
- 规则源导入夹具：`docs/qa/fixtures/`
- 测试计划：`docs/qa/04-test-plan.md`
- 历史 0.1.0 交付报告：`dist/TEST-REPORT.md`（pipeline 生成）

## 8. 最终 QA 结论（待本报告 §3/§4 完成后补）
