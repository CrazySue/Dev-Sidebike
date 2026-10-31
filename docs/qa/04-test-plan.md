# 04 · Dev-Sidebike QA 测试计划（风险驱动，0.1.1-alpha 版）

> 作者：challenger（独立 QA）。最近更新：2026-10-31（0.1.1-alpha 轮）。
> 本文覆盖两代 beta 的 QA 策略：**0.1.0-alpha 基线回归矩阵**（已完成，见 `05-test-report.md` 的 G1–G12）与 **0.1.1-alpha 用户问题驱动验收矩阵**（本轮重点，见 §3）。
> 原则：每个用例必须能失败；必须可复现；必须有证据（日志/截图/命令输出/文件）。默认测试套件必须确定性（重/慢/环境敏感测试 opt-in）。

## 1. 目标与环境

| 环境 | 用途 | 限制 |
| --- | --- | --- |
| JVM（本工作区） | core 单元/集成、代理协议、DNS wire、配置/规则/证书、engine 生命周期 | 无 Android framework/真实 tun |
| Robolectric（本工作区） | app controller/状态机、UI 渲染断言 | 非真实系统服务；截图测试 opt-in（Windows 不稳定） |
| **Android 模拟器（本工作区，x86_64 API35 google_apis_playstore）** | APK 安装/启动/全流程 UI/CA/SAF/VPN 冒烟 + 截图 | 与真机有差异（MIUI/HyperOS、Private DNS 真实组合需 owner） |
| Owner 真机（`_script/qa/device_verify.md` M1–M6） | 系统代理是否生效、CA 信任、MIUI 保活、真实网络 | 异步，需主人配合 |
| 静态审计（PowerShell） | MD3 合规、APK 结构、需求/交付一致性 | 模式化问题 |

**自动化资产**（`_script/qa/`）：`lib_android.ps1`（adb/UI 助手）、`rule_source_server.ps1`（本机规则源 HTTP 服务，模拟远程规则源）、`qa_011_flows.ps1`（0.1.1 全流程脚本，按 `-Flow` 分步）、`emulator_smoke.ps1`（基线 E1/E2 回归）、`check_apk.ps1`/`check_delivery.ps1`/`audit_md3.ps1`/`check_requirements.ps1`（静态门禁）、`setup_emulator.ps1`+`launch_emulator.cmd`（模拟器生命周期）。
**篡改数据（hermetic fixtures）**：`docs/qa/fixtures/`（规则 JSON / Shadowrocket list / 域名列表 / 畸形输入）。

## 2. 基线回归矩阵（0.1.0-alpha 已 PASS，0.1.1 每轮必跑）

| 组 | 内容 | 方式 | 通过标准 |
| --- | --- | --- | --- |
| T-ROB | core/app 对抗测试（HTTP fuzz、DNS codec fuzz、config 畸形） | JVM `:app:testDebugUnitTest`/`:core:test` | 全绿（含 `VpnCodecFuzzTest` 8、`CoreAdversarialQaTest` 9） |
| T-PAR | E1 系统代理下发 + E2 DNS 防污染 + 规则种子 + 代理 HTTP/CONNECT | `emulator_smoke.ps1` | 9/9 PASS（dumpsys HttpProxy、真实 IP、代理 200） |
| T-MD3 | MD3 严格合规 + 非线性动画 + 五屏渲染 | `audit_md3.ps1` + 截图人工复核 | audit PASS；无 M2/线性动画/硬编码色 |
| T-APK | APK 结构/权限/ABI/NSC/FGS | `check_apk.ps1` | PASS |
| T-PIPE | 三环境流水线 dev→test→prod、产物哈希一致 | `pipeline.ps1 all` + `check_delivery.ps1` | 全绿，dist 哈希/manifest 一致 |
| T-FEA | 能力边界诚实（Private DNS/lockdown/非代理 app/MITM 限制） | 模拟器 + 文档审查 | UI 提示存在、文档口径一致 |

## 3. 0.1.1-alpha 验收矩阵（用户 10 项问题 → 测试方法）

> 判定级别：**P0**=用户直接可见的功能失效/崩溃；**P1**=体验/可靠性；**P2**=细节。新缺陷编号 `011-xx` 记入 05 报告。

### U1 日志导出（不再复制到剪贴板，改为 .log/.txt/调试 zip） — P1
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U1-a | 日志页点"导出" → 出现格式选择（文本日志/调试 zip） | 有明确选项，不再只有剪贴板 |
| U1-b | 选择文本日志 → 系统 SAF 保存器（DocumentsUI）弹出，选择路径保存 | 文件成功写出、内容含诊断头（版本/设备/配置/日志正文）、扩展名 .log/.txt |
| U1-c | 选择调试 zip → SAF 保存 .zip；用 `adb pull` 解压校验内容 | zip 可解压，含 diagnostics/logs/config/env 等条目，无空文件 |
| U1-d | 取消 SAF（返回键）→ UI 恢复正常、无崩溃、无"假成功"提示 | 状态一致；logcat 无 FATAL |
| U1-e | 空日志时导出 | 导出文件仍合法（仅诊断头） |

### U2 MITM 开关与"没有 CA/不能上外网"诊断 — P0
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U2-a | 未安装 CA 时点 MITM 开关 | 开关保持 OFF + 明确提示（需先安装 CA/引导）；不得"开了但 TLS 全黑" |
| U2-b | 已装 CA 时开 MITM → 访问 HTTPS | 规则命中域名解密正常；未命中域名 direct 不受影响 |
| U2-c | MITM 开但 CA 被删除/不可用 → 启动/请求 | 不黑洞：自动 fail-safe（降级 direct 或明确报错），UI 可诊断 |
| U2-d | 网络诊断按钮 → 诊断报告 | 含 DNS provider/解析/直连 TLS/系统代理路径结果，可复制/导出 |

### U3 CA 安装流程（黑屏对话框/"未安装该证书"/检测不可靠） — P0
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U3-a | 证书页点"安装 CA" | 系统证书安装器（certinstaller）或证书设置页启动；无 90% 黑屏/无响应 |
| U3-b | 安装器取消/返回 | App 状态"未安装 CA"准确；有可重试引导；无 crash |
| U3-c | 完整安装（模拟器可设锁屏 PIN 后安装） | App 检测为已安装（自签 TLS 自检）；指纹展示与系统一致 |
| U3-d | 安装器无法弹出（ROM 差异） | 调用 fallback intent（加密与凭据设置页）；UI 有明确说明 |
| U3-e | 未设锁屏时安装 | 提示需锁屏；不是笼统失败 |

### U4 Rules 页面间距 + 展开动画同步 — P1
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U4-a | 规则列表滚动到底/顶 | 间距统一（与 MD3 网格一致），无贴边/重叠 |
| U4-b | 点击卡片展开/收起，逐帧截图（3 帧） | 卡片高度动画与内容透明度/位移同步，无内容先出现/后消失 |
| U4-c | 连续快速展开 5 个 | 无跳动/错位；`animateContentSize` 与 expand 动画协调 |

### U5 长规则卡片 ripple 形状错误（球面裁剪） — P2
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U5-a | 长文本规则卡片点击 | ripple 被正确裁剪为卡片圆角矩形，不是球面/圆形外溢 |
| U5-b | 深色模式 + 动态色 | 同上 |

### U6 规则新增/导入（含 GitHub 规则源自动更新） / 非代理域名编辑 — P0
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U6-a | 规则页"新增规则"对话框：填域名+路径+动作（sni/proxy/desc）→ 保存 | 列表出现新规则、计数 +1；重启后仍在（持久化） |
| U6-b | 编辑已有规则 → 覆盖保存 | 列表更新、无重复 key |
| U6-c | 删除自定义规则 | 列表消失、计数 -1；内置规则删除后不复活（disabledIntercepts 持久化） |
| U6-d | 从文本导入（Shadowrocket list/JSON/域名列表三种） | 计数正确、规则语义正确（DOMAIN-SUFFIX→域名通配）；无效行有 warning 但不整批失败 |
| U6-e | 从 URL 导入：本机 `rule_source_server.ps1`（模拟器经 `10.0.2.2:<port>` 访问；ETag/Last-Modified 支持） | 拉取成功、规则合并；再次刷新 → 304/notModified；服务端返回 500/超时 → 明确错误且旧规则不丢 |
| U6-f | 自动更新元数据（lastUpdated/etag/interval）持久化 | UI 显示来源/时间/错误；重进页面一致 |
| U6-g | 非代理域名（直连放行）新增/编辑/删除 | whiteList 生效：该域名不再走代理/MITM（日志/统计验证） |
| U6-h | 导入畸形规则（空/巨长/非法 JSON/二进制） | 不崩；给 warning；不影响既有规则 |

### U7 国内域名直连列表可见/可编辑 — P1
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U7-a | 设置/规则页显示 domesticAllowList（内置 1237 条可见摘要/可搜索） | 列表可见、数量正确 |
| U7-b | 新增/删除国内域名 → 保存 | 生效（`isDomestic` 行为变化可验证）；重启保留 |
| U7-c | 清空列表 → 关闭国内直连 | UI 状态与规则引擎一致 |

### U8 导航转场 Home 重叠（两页同显） — P1
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U8-a | 首页↔其他四个 tab 来回切换，逐帧截图 | 无两页同时可见；转场为淡入/滑动（MD3 fade-through），无白闪/重叠 |
| U8-b | 深色模式 + 系统返回手势 | 同上 |

### U9 启动/停止三个进度指示器同时出现 + 去掉 uptime — P1
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U9-a | 点启动瞬间 → PREPARING/STARTING 截图 | **单一**进度指示（按钮 morph 或状态卡，二选一），无三重叠加 |
| U9-b | 点停止瞬间 → STOPPING 截图 | 同上 |
| U9-c | 首页任何状态不显示"已运行 00:xx"/uptime | UI 源码/截图均无 uptime 文案 |

### U10 跟进上游 dev-sidecar 设置/加速逻辑 — P2（理解性验收）
| 用例 | 方法 | 通过标准 |
| --- | --- | --- |
| U10-a | 对照 01 分析文档 + 现有设置项：新增/对齐的设置项有文档说明 | 设置项语义与上游一致或有明确差异说明 |
| U10-b | 加速链路（DNS preset/DoH/SNI/MIRROR）行为回归 | 基线 T-PAR 用例继续 PASS |

## 4. 自动化资产映射（本轮新增）

| 资产 | 覆盖 | 说明 |
| --- | --- | --- |
| `_script/qa/lib_android.ps1` | U1–U9 基础 | PS5.1 adb/UI helper：`Adb`/`UiDump`/`Texts`/`FindCenter`/`TapText`/`WaitText`/`Screenshot`/`GrantPerms`/`InstallApk`/`StartVpn`/`StopVpn`/`NavTo` |
| `_script/qa/rule_source_server.ps1` | U6-e/f | 本机 TcpListener HTTP 服务（127.0.0.1，模拟器经 10.0.2.2 访问）：`/rules.json`、`/rules.list`、`/domains.txt`，支持 ETag/Last-Modified/304、可注入 500/超时，记录请求日志 |
| `_script/qa/qa_011_flows.ps1` | U1–U9 | 按 `-Flow rules|export|ca|mitm|nav|progress|all` 执行；每步 UI dump + 真实 PNG 截图 → `docs/qa/evidence/011/`；输出 PASS/FAIL/BLOCKED（UI 未实现时 BLOCKED 而非误报 PASS） |
| `docs/qa/fixtures/` | U6-d/h | 规则 JSON / Shadowrocket .list / 域名列表 / 畸形输入 |

## 5. 缺陷分级与处理

| 级别 | 定义 | 处理 |
| --- | --- | --- |
| S0 | 崩溃/数据丢失/全设备断网/安全（监听 0.0.0.0、私钥泄漏、CA 黑屏卡死） | 阻塞交付；Lead 立即转 owner |
| S1 | 用户可见核心功能失效（导出失败、MITM 黑洞、CA 永远检测不到、规则不能保存、导航重叠） | 必须修或明确降级口径 |
| S2 | 明显缺陷/体验问题（间距、ripple、动画不同步、多进度指示、uptime 残留） | 修；时间不足则记录已知问题 |
| S3 | 轻微/文案 | 记录 |

缺陷记录到 `docs/qa/05-test-report.md`（ID/现象/复现/日志/证据截图/级别/owner/复测状态）。

## 6. 入口/出口准则

- **进入测试**：Lead 宣布 "0.1.1-alpha build ready"；`out/dev/app-debug.apk` 存在且 build-info 与源码一致。
- **退出（可交付）**：U1–U9 的 P0/P1 用例 PASS（UI 未实现项明确 BLOCKED 并报 Lead）；基线回归 T-ROB/T-PAR/T-MD3/T-APK/T-PIPE 全绿；无 S0/S1；05 报告含证据。
- **停止条件**：CA 流程/导出/规则编辑任一 P0 无法通过且无明确降级口径；或构建持续失败。

## 7. 报告节奏

- 测试计划 + 自动化骨架就绪 → 报 Lead（本轮）。
- 每完成一个 Flow（rules/export/ca/mitm/nav/progress）→ 短消息（PASS/FAIL 数 + S0/S1 + 证据路径）。
- 0.1.1 最终验收 → 完整矩阵 + 最终 QA 结论。
