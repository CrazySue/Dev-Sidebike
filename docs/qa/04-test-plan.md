# 04 · Dev-Sidebike QA 测试计划（风险驱动）

> 作者：challenger（独立 QA）。日期：2026-10-25。状态：v0.1（随代码推进迭代）。
> 依据：`docs/architecture/01-architecture.md`、`docs/architecture/02-core-contracts.md`、`docs/feasibility/01-dev-sidecar-analysis.md`、`docs/feasibility/02-android-constraints.md`、`docs/feasibility/03-review-checklist.md`、`docs/dev-workflow.md`。
> 原则：**每个用例必须能失败**、必须可复现、必须有证据（日志/截图/命令输出/文件）。无法在当前工作区执行的（真机）进入 owner 手机清单，不当作"通过"。

## 1. 目标与环境

| 环境 | 用途 | 能力 | 限制 |
| --- | --- | --- | --- |
| **JVM（本工作区）** | core 单元/集成、代理协议、DNS wire、配置、规则、证书、engine 生命周期 | 全部本地、快、可 CI | 无 Android framework/真实 tun |
| **Robolectric（本工作区）** | app 层 controller/vpn codec、UI 渲染与截图 | 无设备可跑 Compose 截图 | 非真实系统服务；截图与真机有像素差异 |
| **Android 模拟器（本工作区，x86_64 API35 google_apis_playstore）** | APK 安装/启动/截图、VPN 授权流程、DNS/代理 smoke、系统行为 | 比 Robolectric 真实 | 无 Google 账号/网络受限；VPN/Chrome 行为可能与真机不同；x86_64 不代表 arm64 性能 |
| **Owner 真机（清单 `_script/qa/device_verify.md`）** | Plan A 核心假设、CA 安装、MIUI 后台、lockdown/Private DNS、真实网络 | 唯一可信的最终验收 | 需要主人配合，异步 |
| **静态审计（PowerShell）** | MD3 合规、APK 结构、需求/仓库一致性 | 秒级、可门禁 | 只能发现模式化问题 |

**当前环境事实**：本工作区 `adb devices` 为空；初始无 emulator/system-image，QA 已通过 `_script/qa/setup_emulator.ps1` 从腾讯镜像离线安装（emulator + google_apis_playstore x86_64 API35），AVD/镜像放 `E:\WorkSpace\Dev-Sidebike\tools\avd`（C: 空间不足）。

## 2. 质量目标与验收定义（对应用户 6 项要求）

| 用户要求 | 可验证定义 | 用例组 |
| --- | --- | --- |
| (a) 诚实可行性 | 02/03 文档 + App 内能力边界提示 + 已知不生效清单可复现（Private DNS/非代理 app/MITM 限制/API<29）；无虚假宣传 | T-FEA |
| (b) 后端行为对齐 Dev-Sidecar | DNS 防污染（UDP/DoT/DoH+SNI 伪装+mapping）、intercepts 动作（DIRECT/PROXY/SNI/SYNTHETIC/replace/script/cache/status）、规则匹配、config 导入与 4 层合并 | T-PAR |
| (c) 三环境流水线 | `_script/pipeline.ps1 dev|test|prod` 存在且 test 失败不产出 prod；产物 hash/manifest | T-PIPE |
| (d) 严格 MD3 + 非线性动画 | `audit_md3.ps1` PASS；截图人工复核（无 M2 组件/无线性动画/state 事件均有动画） | T-MD3 |
| (e) 功能完整/健壮/不崩 | 状态机无僵尸态；代理/DNS 畸形输入不崩；start/stop 50 次无泄漏；无 ANR/FATAL | T-ROB |
| (f) APK 交付完整性 | `check_apk.ps1` PASS：包名/minSdk/targetSdk/权限/签名 debug 标记/版本/无意外权限/ABI 说明 | T-APK |

## 3. 测试矩阵（用例 ID → 环境 → 通过标准）

### 3.1 T-ROB 健壮性 / 对抗输入（JVM，hermetic，无外网）

| ID | 用例 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| ROB-01 | IP/UDP/DNS codec 随机字节 fuzz（10k 用例，随机长度/字段） | JVM | 不抛异常、不 OOB、不无限循环 |
| ROB-02 | DNS 请求畸形：空包/截断 header/超长域名/压缩指针环/QdCount 异常/EDNS0 越界 | JVM | 返回格式错误或被丢弃，不崩 |
| ROB-03 | DNS 响应构造：A/AAAA/CNAME/多记录/应答超 512/客户端 EDNS buffer 512/1232/4096 | JVM | 按客户端 buffer 裁剪、TC=0（禁止 TCP 回退） |
| ROB-04 | HTTP 代理：CONNECT 非法目标（无端口/超长/控制字符）、绝对 URI、chunked、CL+TE 冲突、超长头（64KB）、重复 Host | JVM | 4xx/关闭连接，不崩不挂起 |
| ROB-05 | slowloris：连接后每 5s 发 1 字节，持续 30s | JVM | 到达 readTimeout 后主动关闭，线程回收 |
| ROB-06 | 并发：50 客户端同时 CONNECT + 中途断开 | JVM | 无异常泄漏、线程数回到基线±少量 |
| ROB-07 | 引擎生命周期：start/stop ×50；运行中 updateConfig ×20；stop 期间 start | JVM | 无僵尸线程/协程；无 crash；状态可重入 |
| ROB-08 | 配置 fuzz：缺字段/未知字段/null/超大字符串（1MB）/错误类型/JSON5 注释/单引号 | JVM | 不崩，回退默认值并记录 warning |
| ROB-09 | 证书：CA 生成/leaf 签发/链校验/SAN/有效期/并发签发缓存 | JVM | 与 JDK 校验一致；无内存泄漏 |
| ROB-10 | 规则匹配表驱动：通配/正则/大小写/超长域名/白名单优先级 | JVM | 全部符合上游语义 |

### 3.2 T-PAR 行为对齐（JVM + 模拟器）

| ID | 用例 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| PAR-01 | 本地 fake DoH/DoT/UDP 上游：provider 选择/失败切换/超时/缓存 TTL | JVM | 查询走正确 provider；缓存命中不重复请求 |
| PAR-02 | 防污染：上游返回 0.0.0.0/127.0.0.1/已知投毒 IP → 判定无效并尝试其它 provider | JVM | 返回可用 IP 或明确失败 |
| PAR-03 | intercepts 动作：DIRECT/MIRROR(sni/proxy)/REJECT/SYNTHETIC/requestReplace/responseReplace/script/cacheDays/status | JVM | 与 01 文档算法一致；错误动作安全降级 |
| PAR-04 | config 导入上游 `running.json` 子集 + 4 层合并 + 未知字段忽略 | JVM | 字段映射正确；冲突优先级正确 |
| PAR-05 | 端到端：本地 HTTP/TLS 测试服务器模拟镜像目标（含 SNI 捕获），验证 SNI 伪装与 MITM 改写 | JVM | 上游收到的 SNI = 配置值；改写/注入生效 |
| PAR-06 | 模拟器：DNS 拦截全链路（浏览器解析 → tun 收到 query → 返回） | 模拟器 | App 日志/dumpsys 有证据；解析成功 |
| PAR-07 | 模拟器：系统代理注册（`dumpsys connectivity` 含 Proxy）+ Chrome 请求出现在代理日志 | 模拟器 | 与 03 文档 E1 相同标准 |
| PAR-08 | 模拟器：Private DNS/lockdown/其他 VPN 检测与 UI 警告 | 模拟器 | 检测准确、UI 有明确提示、不崩溃 |

### 3.3 T-FEA 诚实边界（文档 + 模拟器/真机）

| ID | 用例 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| FEA-01 | API 26–28：无系统代理，UI 明确提示"仅 DNS 模式"（模拟器可建 API28 image 或静态审计） | 静态/模拟器 | 提示存在、setHttpProxy 未调用（API guard） |
| FEA-02 | Private DNS=hostname 时 App 检测并提示"DNS 拦截被绕过" | 模拟器/真机 | 检测/提示 |
| FEA-03 | 非代理 app（WebView 外部/原生 socket）不受代理影响：README/UI 有边界说明 | 文档审查 | 有明确清单 |
| FEA-04 | MITM 默认关闭；开启后 UI 警告"多数 app 不信任用户 CA" | 截图/真机 | 有警告、默认关闭 |
| FEA-05 | CA 安装后检测逻辑（自签 TLS 握手）；未安装时状态正确 | Robolectric/模拟器 | 状态准确 |
| FEA-06 | 无 root 清单（02 §7）与 App 实际能力一致，无越权承诺 | 文档审查 | 一致 |

### 3.4 T-MD3 严格 MD3 + 非线性动画（静态 + Robolectric 截图）

| ID | 用例 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| MD3-01 | `audit_md3.ps1` PASS（无 M2 import/硬编码色/LinearEasing/自定义字体；motion token 使用≥8；动画≥15） | 静态 | exit 0 |
| MD3-02 | 每个屏幕 × 状态（IDLE/PREPARING/RUNNING/STOPPING/ERROR + 空数据）Robolectric 截图 | Robolectric | 无裁剪/重叠/对比度问题；截图存 `docs/qa/screenshots/` |
| MD3-03 | 状态变化均有动画（开始/停止/状态卡/数字/导航/列表/展开） | 代码审查+截图序列 | 与 docs/design/03-motion-spec.md 映射表一致 |
| MD3-04 | 无 M2 视觉痕迹（Material2 组件样式/无 ripple 差异） | 截图人工 | 符合 MD3 |
| MD3-05 | 深色/浅色/动态色三态渲染 | Robolectric | 均可读、无硬编码色异常 |

### 3.5 T-PIPE 三环境流水线（静态 + 执行）

| ID | 用例 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| PIPE-01 | `_script/pipeline.ps1 dev` 产出 `out/dev/app-debug.apk` | 工作区 | 文件存在、check_apk(debug) PASS |
| PIPE-02 | `pipeline.ps1 test` 运行 core+app 单测+静态审计；任一失败则退出非 0 且不产出 prod | 工作区 | 负向验证：故意破坏一个测试 → pipeline 失败 |
| PIPE-03 | `pipeline.ps1 prod` 产出 `dist/`：release APK + manifest + sha256；debuggable=false | 工作区 | check_apk(release) PASS；sha256 可复算 |
| PIPE-04 | 无 git 提交/标签由 Lead 控制（QA 只读） | 工作区 | 不修改 git |

### 3.6 T-APK 交付完整性（静态）

| ID | 用例 | 环境 | 通过标准 |
| --- | --- | --- | --- |
| APK-01 | `check_apk.ps1`（debug/release 两次）PASS | 静态 | 包名、版本、minSdk=26、targetSdk≥34、权限、启动 Activity |
| APK-02 | 无 WRITE_SECURE_SETTINGS 等越权权限；无多余危险权限 | 静态 | 通过 |
| APK-03 | VpnService 声明（BIND_VPN_SERVICE + action + FGS type + always-on meta-data） | 静态 | aapt2 xmltree 通过 |
| APK-04 | NSC 含 user CA 信任 | 静态 | 通过 |
| APK-05 | ABI 说明：纯 JVM 时无 native-code；如引入 .so 必须四 ABI 或明确说明 | 静态 | 通过 |
| APK-06 | 签名与版本：debug 包 debuggable=true；release 包 debuggable=false 且 versionCode/Name 正确 | 静态 | 通过 |

### 3.7 崩溃/ANR/冒烟（模拟器）

| ID | 用例 | 通过标准 |
| --- | --- | --- |
| SMK-01 | 冷启动 → 首页渲染 <3s，无 FATAL | logcat 无 FATAL/ANR |
| SMK-02 | 启动 VPN（点击 + 系统授权）→ RUNNING → 通知出现 | dumpsys + 截图 |
| SMK-03 | 停止 VPN → 网络恢复（`dumpsys connectivity` 无 VPN） | 通过 |
| SMK-04 | 导航所有页面/返回/旋转/深色模式 | 无崩溃 |
| SMK-05 | kill app（`adb shell am force-stop`）后重新启动 → 状态一致/可恢复 | 无僵尸状态 |

## 4. 执行顺序（时间盒）

1. **P0 静态门禁**（T-MD3 静态、T-APK、T-PIPE 静态部分、check_requirements）——随代码随时跑（秒级）。
2. **T-ROB 对抗测试**——先写 codec/proxy/config 的 fuzz（不依赖 engine 完成），engine 完成后补生命周期/并联测试。
3. **Robolectric 截图**（UI 就绪后，最高视觉价值）。
4. **模拟器**（APK 可构建后）：SMK → PAR-06/07/08 → T-FEA；截图存 `docs/qa/screenshots/`。
5. **Owner 手机 M1/M2**（Plan A 核心假设）——清单已交付，结果回来立即更新 05 报告。
6. 回归：每轮修复后按缺陷 ID 重跑相关用例。

## 5. 缺陷分级与处理

| 级别 | 定义 | 例子 | 处理 |
| --- | --- | --- | --- |
| S0 | 崩溃/数据丢失/全设备断网/安全（代理监听 0.0.0.0、私钥泄漏） | FGS 类型异常崩溃、proxy 死后无法恢复网络 | 阻塞交付，Lead 协调 owner（android-dev/proxy-dev）修 |
| S1 | 核心功能失效/严重体验 | 系统代理不生效、DNS 拦截失效、CA 检测永远失败 | 必须修或明确降级口径 |
| S2 | 明显功能缺陷 | 某类规则不生效、统计错误、截图缺陷 | 修（时间不够则文档记录已知问题） |
| S3 | 轻微/文案/视觉 | 间距、文案错别字 | 记录，时间允许修 |

缺陷统一记录在 `docs/qa/05-test-report.md`（ID/现象/复现/日志/级别/负责 workstream/复测状态）。QA 不直接改生产代码，通过 Lead 转交对应 dev。

## 6. 入口/出口准则

- **进入测试**：`:app:assembleDebug` 成功；`:core:test` 可运行（至少部分）。
- **退出（可交付）**：§3 全部 P0 用例通过 + S0/S1 清零或明确降级口径；05 报告含证据；静态门禁全绿；owner 手机 M1/M2 已回报（或明确"未验证"并写入交付说明）。
- **停止条件**：见 03 §5 Stop conditions（Plan A 被证伪且未启用 fallback、崩溃不可规避、无法提供 E2E 证据）。

## 7. 报告节奏

- 每完成一组：向 Lead 发短消息（组 ID + 通过/失败数 + S0/S1 缺陷）。
- 静态工具就绪、截图 verdict、对抗发现、最终报告四个里程碑必报。
- 05 报告随每次运行更新（含时间戳/APK sha256/git 版本）。
