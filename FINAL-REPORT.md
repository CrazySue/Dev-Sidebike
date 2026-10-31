# Dev-Sidebike v0.1.1-alpha · 交付报告

> 初版交付：2026-10-25；**0.1.1-alpha 增量交付：2026-10-31**（修复真机反馈的日志导出/CA/MITM/境外访问问题，新增规则与规则源管理、网络诊断、DNS 加固）。作者：Lead（Agent Teams）。
> 状态：**0.1.1-alpha 完成。`pipeline.ps1 all` 全绿（dev → test → prod），交付物已生成于 `dist/`（最终交付哈希与构建信息以 `dist/delivery-manifest.json` 为准）。**

---

## 1. 一句话结论

**把 Dev-Sidecar 的后端能力搬到了 Android 系统级**：纯 Kotlin 重写的代理/DNS/规则引擎内核 + VpnService（系统级 DNS 防污染 + API 29+ 系统 HTTP 代理）+ 严格 Material 3 前端；在 Android 15 模拟器上完成端到端实测（E1 系统代理下发、E2 DNS 防污染、E5 故障安全全部 PASS）。

同时**纠正了项目最初的错误前提**：Dev-Sidecar 不是"利用 HTTPS SNI 漏洞"，而是 **诱饵 SNI（decoy SNI）+ DoH/DoT/预置 IP 防污染 + ECH** 三套机制（源码逐行 + 运行实例实测证据见 `docs/feasibility/01-dev-sidecar-analysis.md`）。

---

## 2. 交付物

### 2.1 安装包（`dist/`）

| 文件 | 说明 |
| --- | --- |
| `Dev-Sidebike-0.1.1-alpha-release.apk` | **推荐安装**。约 15 MB，release 签名（自签名），仅 ARM（arm64-v8a / armeabi-v7a），debuggable=false；sha256 以 `dist/SHA256SUMS.txt` / `delivery-manifest.json` 为准 |
| `Dev-Sidebike-0.1.1-alpha-debug.apk` | 约 22 MB，debug 预交付物（含 x86_64/x86，便于模拟器/排障）；sha256 同上 |
| `SHA256SUMS.txt` | 校验和（与 APK 实测一致） |
| `delivery-manifest.json` | 版本 / git SHA / 构建时间 / 产物哈希 / 测试报告索引 |
| `README.md` | 安装与使用说明、已知限制 |
| `TEST-REPORT.md` | 测试环境完整报告（单测/静态审计/APK 校验/门禁结果，结论 PASS） |

> 0.1.0-alpha 的交付物已由 0.1.1-alpha 取代（旧 APK 仅保留在 GitHub 旧 Release 历史中）。

### 2.2 源码与文档（工作区 `E:\WorkSpace\Dev-Sidebike`）

- `app/`：Android 应用（Compose MD3 UI、VpnService、Controller、持久化、CA 安装引导）≈ 8000 行 Kotlin
- `core/`：纯 Kotlin/JVM 后端内核（config / dns / rules / proxy / mitm / cert / engine / log / stats / speed）≈ 6500 行 + 3700 行测试
- `docs/feasibility/`：可行性验证（上游逆向 103KB、Android 约束、对抗复核、Go/No-Go、配置移植）
- `docs/architecture/`：架构决策与 `:core` 契约；`docs/design/`：MD3 设计规范 + 事件→动画映射表
- `docs/reference/`：0.1.1 新增 —— 上游 Dev-Sidecar 设置与加速能力盘点、推荐规则源调研（URL/格式/许可证）
- `docs/qa/`：QA 测试计划、验收报告、模拟器真 PNG 截图与 0.1.1 自动化证据、手机端验收清单
- `docs/release-notes/`：各版本发行说明（0.1.0-alpha / 0.1.1-alpha）
- `docs/verification/engine-e2e.md`：独立真实网络 E2E 验证 + 11 项集成审查发现
- `_script/`：环境脚本、三环境流水线（`pipeline.ps1 dev|test|prod|all`，统一走共享 Gradle 锁）、QA 静态门禁与 0.1.1 flows、hermetic 规则源服务、实验脚本
- `LICENSE`：MPL-2.0（与上游一致），`README.md`：项目说明
- git：main 分支，里程碑提交，tag `v0.1.1-alpha`

---

## 3. 功能清单（0.1.1-alpha）

### 3.1 系统级网络能力
- **DNS 防污染（无需证书，对全部应用生效）**：VpnService 分隧道只接管 `10.111.0.2:53`，tun 内解析 IPv4/UDP DNS；多 Provider（UDP / DoH / DoT，含诱饵 SNI 支持）、预置 IP、域名→Provider 映射、A/AAAA 族偏好、TTL 缓存、并行 race + 串行回退、防污染答案校验；**永不置 TC**（EDNS0 payload 钳制 + 答案裁剪）。
- **系统 HTTP 代理（API 29+）**：`VpnService.Builder.setHttpProxy(127.0.0.1:31181)`，单端口 HTTP + HTTPS CONNECT；遵循系统代理的应用（OkHttp/Chromium 等）自动走本地代理；API 26–28 自动降级为"仅 DNS 模式"。
- **规则引擎**：对齐上游域名匹配语义（`*` 通配 / `^` 正则 / 精确）、69 域 / 77 条路径拦截规则、61 域预置 IP、白名单/排除/国内域名放行（1237 条）、`sni`/`proxy`/`success`/`requestReplace`/`responseReplace`/`script`/`cacheDays`/`status` 动作。
- **MITM 增强（默认关闭）**：BouncyCastle CA 生成 + 按域签发叶证书 + 按 SNI 选证书；诱饵 SNI、镜像改写、脚本注入、响应替换、内存缓存；用户 CA 安装引导（KeyChain intent + 安装检测）。
- **故障安全**：启动前检查 VPN 授权、本地代理端口可用、DNS 真实可用（DNS readiness probe）；任一失败 → 拒绝接管网络（避免"VPN 已连接但全设备断网"）；运行中 watchdog 检测代理/引擎死亡 → 自动拆 VPN 恢复网络 + 清晰错误；停止/被杀后网络立即恢复（模拟器实测）。
- **环境检测**：Private DNS(DoT) 绕过警告、lockdown 冲突警告、其他 VPN 互斥提示、系统代理是否注册（`systemProxyRegistered`）、端口冲突自动回退（31181→高位端口）。
- **生命周期**：前台服务（specialUse FGS，Android 14+ 合规）、always-on VPN 支持、开机自启（用户开关）、通知权限引导、always-on 重启自举。

### 3.2 前端（严格 MD3）
- 五个页面：首页（状态/启停/实时统计/能力边界提示）、规则（搜索/开关/详情/计数）、日志（级别筛选/实时/导出）、设置（端口/DNS 模式/Provider/MITM/自启/主题）、证书引导。
- 完全使用 `androidx.compose.material3` 组件与 token；深色/动态取色/edge-to-edge；**每个状态变化均有 MD3 非线性动画**（emphasized/standard 缓动 + 时长 token，事件→动画映射见 `docs/design/03-motion-spec.md`）；静态审计 0 违规。
- 中文界面；空态/错误态/加载态完整；状态横幅（Private DNS / lockdown / 系统代理未注册 / 端口回退）。

### 3.3 0.1.1-alpha 增量能力（真机反馈驱动）

- **可观测性**：日志导出 `.log`/`.txt` 与诊断调试包 `.zip`（SAF 保存、敏感头脱敏）；网络诊断（DNS Provider health、真实解析结果、直连 TLS、本地代理 CONNECT/GET、系统代理/Private DNS/lockdown/MITM/CA 状态、tun 丢包计数）；诊断结论可直接反馈开发者。
- **规则管理**：规则页四标签（拦截规则/直连放行/国内域名/规则源）；新增/编辑/删除规则（SNI、镜像目标、拦截 abort）、编辑直连与国内域名列表、文本/URL 导入、远程规则源（推荐 Johnshall Shadowrocket 规则，ETag/304 增量、自动更新、手动全部刷新）；删除内置规则持久化到 `disabledIntercepts`。
- **CA/MITM 安全**：CA 与 MITM 开关解耦生成；系统证书安装 Intent + 系统凭据设置兜底、锁屏前置检查、指纹展示、`.crt` 导出、取消/重试检测；MITM 开启 CA 门禁；0.1.0 旧配置“MITM 开 + CA 未装”启动自动降级并持久化；MITM CA 不可用时按域直连降级 + 握手失败诊断。
- **DNS 加固**：Provider 健康检查、境外域名 DoH/DoT 优先 + 多源共识防污染、私有/保留 IP 拒收、短超时并行 race、预置 IP 兜底、失败/污染不缓存、未验证短 TTL、诱饵 SNI 被拒回退真实 SNI。
- **设置对齐上游**：DNS hosts 编辑、DNS 详情只读（providers/mapping/familyMapping/preSetIp）、超时三档预设、网络诊断入口、规则源/日志导出快捷入口。

---

## 4. 质量验证证据

| 验证 | 结果 |
| --- | --- |
| `:core:test` | **246 tests，0 failed，1 skipped**（真实网络 E2E 为 opt-in skip；含 0.1.1 新增规则源/解析/合并/规则引擎 abort+disabled/MITM CA 降级/DNS 防污染 48 例，以及既有对抗 fuzz） |
| `:app:testDebugUnitTest` | **265 tests，0 failed，2 skipped**（Robolectric 截图采集为 opt-in skip；含 Controller/数据/VPN 编解码/Robolectric UI/QA 对抗 fuzz；0.1.1 新增日志导出/诊断/CA 安装流程/规则编辑/VPN 运行时统计） |
| MD3/动画静态审计 | PASS（36 个 UI Kotlin 文件，动画 69 处，运动 token 使用 32 处，无 M2/硬编码颜色/线性动画/自定义字体） |
| APK 门禁 | PASS（release：包名/minSdk26/targetSdk35/权限/VPN 声明/FGS type/always-on/NSC user CA/debuggable=false；debug：符合变体） |
| 需求结构门禁 | **46/46 PASS**（三环境脚本、ABI 配置、docs、git tag、截图真 PNG 校验） |
| 三环境流水线 | **dev → test → prod 全绿**；失败禁止晋级；build-info 与 APK sha256/gitSha 强一致性校验 |
| 签名校验 | `apksigner verify` PASS（自签名 key） |
| 模拟器 E2E（Android 15 / API 35 / x86_64 Play 镜像） | E1 系统代理 PASS；E2 DNS 防污染 PASS（github/google 真实 IP，62+ 连接、69 规则加载）；E5/E5b 故障注入 PASS（自动拆 VPN + 网络恢复 + 可重启）；停止/force-stop 网络恢复 PASS；Private DNS/lockdown 横幅 PASS；通知权限 PASS；QA 自动化 smoke **9/9 PASS** |
| 独立真实网络 JVM E2E | PASS：config 371ms / engine.start 321ms / 真实上游 DNS（preset、DoH、TTL）/ CONNECT+真实 TLS（github、tencent）/ stop 后 4ms 释放端口；APK 内 `rules.json` 40KB 与源码一致 |
| 代码质量扫描 | 无 `TODO()` 生产炸弹；DNS provider 无系统 resolver 自举；代理仅监听 127.0.0.1；debug 故障注入仅存在于 debug source set；无密钥/CA 私钥日志泄漏 |

### 4.1 QA 独立发布门禁（release APK）

| 项 | 结果 |
| --- | --- |
| `check_delivery.ps1` | **PASS**：6 个交付物齐全；SHA256SUMS 逐项一致；manifest version/gitSha/两 APK sha256 一致 |
| Release APK 独立校验 | **PASS**：`dev.sidebike.app` / 0.1.1-alpha / versionCode 2 / debuggable=**false** / minSdk26 / targetSdk35 / ARM 双 ABI / 权限与 VPN 声明 / NSC(user CA) 合规 |
| Release APK 真机实测（模拟器 API35） | **PASS**：安装→VPN 授权→E1 系统代理注册→E2 DNS 防污染（github 真实 IP）→HTTP `GET` 返回 200 / `CONNECT` 返回 200→统计字节（上行 621B/下行 2.3KB，QA-009 已关闭）→停止后 tun0 消失、DNS 恢复 |
| 失败安全 | **PASS**：stop / force-stop / KILL_PROXY / CRASH_ENGINE 均自动恢复网络且可重启（E5/E5b） |
| G1–G12 验收矩阵 | G1–G8、G11、G12 ✅；G9 MITM 真机端到端未做（默认关闭，core MITM/CA 20 单测 PASS，非阻塞）；G10 模拟器后台 PASS、真机 MIUI 待 owner M5 |
| QA 对抗测试 | app `VpnCodecFuzzTest` 8/8 PASS；core `CoreAdversarialQaTest` 9/9 PASS（HTTP/DNS/JSON5/配置/规则 fuzz，全部 hermetic） |
| **QA 最终结论** | **有条件通过（release gate PASS）**，条件仅为环境类未测项（MIUI/HyperOS 后台策略、Private DNS/lockdown 真机组合、MITM 默认关闭），已写入交付说明与 owner 真机清单（`_script/qa/device_verify.md`）。完整矩阵见 `docs/qa/05-test-report.md` §17。 |

---

## 5. 已知限制（诚实边界）

1. **无 root 无法透明代理所有应用**：仅"DNS 全系统生效 + 系统代理层（遵循系统代理的应用）"。不遵循系统代理的原生 socket/Flutter/Unity 等应用仅获 DNS 级加速。
2. **MITM 默认关闭**：Android 7+ 第三方应用默认不信任用户 CA；开启后仅浏览器等信任用户 CA 的应用获得诱饵 SNI/镜像改写能力；安装 CA 需设备先设置锁屏。0.1.1-alpha 起未检测到 CA 无法开启 MITM，旧配置会自动降级直连。
3. **API 26–28**：无 `setHttpProxy`，仅 DNS 模式。
4. **交付 APK 仅 ARM 双 ABI**（项目要求）；debug 含 x86/x86_64。x86 release 可用 Gradle 一行开启。
5. **Private DNS(DoT hostname)** 开启会绕过 VPN DNS（应用内检测并警告）；lockdown 与分隧道冲突（警告）。
6. **规则源导入范围**：仅映射 DIRECT→直连放行、REJECT→拦截；PROXY、IP-CIDR/GEOIP/RULE-SET 等复杂类型跳过并提示（无远端节点与递归规则集）；推荐规则文件按 CC BY-SA 4.0 运行期拉取，不随 APK 分发。
7. **未实现**：ECH、NAT64、HTTP/2 MITM、overwall、桌面插件、QuickSettings 磁贴、全隧道 userspace TCP 栈（Plan B，已评估为 NO-GO，接口 seam 保留）。
8. **真机验证待 owner 执行**：模拟器无法覆盖 MIUI/HyperOS 后台策略、厂商 ROM 差异、真机 CA 安装路径；请按 `_script/qa/device_verify.md`（M1–M6，约 10 分钟）复验。若开启加速后境外站点仍无法访问，请先「设置 → 运行网络诊断」并「日志 → 导出诊断包」反馈。
9. 已知 S2/S3：长连接字节统计偶见延迟；tun 偶发非 IPv4/畸形包（0.1.1 起计数并纳入诊断，不影响解析）；上游 TLS 在 MITM 规则域默认不校验证书（与上游行为一致）。

---

## 6. 开发过程（Agent Teams 与三环境工作流）

- **团队**：Lead（架构/契约/集成/流水线/质量监督/git）+ 5 名 teammate：analyst（上游逆向/后端数据线 → 0.1.1 的 DNS 加固与上游设置调研）、proxy-dev（代理/MITM/引擎 → 0.1.1 的远程规则源与 MITM CA 降级）、android-dev（VpnService/Controller/系统集成 → 0.1.1 的日志导出/诊断/CA 流程/规则 API）、ui-dev（MD3 前端 → 0.1.1 的规则管理、CA/导出界面与动画修复）、challenger（独立 QA/对抗评审 → 0.1.1 的 U1–U10 自动化）。
- **流程**：可行性验证（T1 上游分析 + T2 Android 约束/对抗复核）→ 架构与契约冻结 → 四线并行开发 → 分层测试 + 静态门禁 → 模拟器 E2E → 三环境流水线（dev→test→prod，失败回开发修根因）→ 交付；0.1.1-alpha 以真机诊断证据驱动修复（日志导出→诊断→根因→修复→回归）。
- **质量控制**：契约冻结 + 文件所有权 + 跨进程 Gradle 构建锁（0.1.1 起流水线也复用该锁）+ 测试超时保护 + QA 独立静态审计/自动化 flow/截图验收 + Lead 终检（`pipeline.ps1 all` 全绿后发布）。
- 关键工程决策与踩坑记录见 `docs/dev-workflow.md`、`docs/architecture/01-architecture.md` §7（修订记录）。

---

## 7. 后续路线图（0.1.1-alpha 之后）

1. **P0（owner 验收后）**：真机 M1–M6 复验与 ROM 适配；根据用户新诊断包继续定位真实网络下的境外访问问题（0.1.1 已内置网络诊断与 DNS 加固）。
2. **P1**：HTTP/2 MITM；MITM 失败透明回退的进一步自动化；QuickSettings 磁贴；规则源递归 Rule-SET/更多格式；明文 HTTP 代理的域名规则增强。
3. **P2**：全隧道 userspace TCP 栈 / native tun2socks（4 ABI），实现真正"所有应用透明代理"；ECH；NAT64；x86 release 构建；远程配置订阅。

---

## 8. 命令速查

```powershell
# 环境
. E:\WorkSpace\Dev-Sidebike\_script\env.ps1
# 三环境（唯一入口）
powershell -NoProfile -File _script\pipeline.ps1 all    # dev→test→prod
powershell -NoProfile -File _script\pipeline.ps1 test   # 测试门禁
powershell -NoProfile -File _script\pipeline.ps1 prod   # 生成 dist/
# 真机验收清单
notepad _script\qa\device_verify.md
```
