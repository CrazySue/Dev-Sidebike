# Dev-Sidebike 项目计划与质量监督（Lead 执行版）

- 版本：v1.0（2026-10-25 03:26 启动，目标 8 小时内交付 alpha 版本）
- Lead：lead（负责规划、架构决策、集成、构建流水线、质量监督、git 版本控制与最终验收）
- 用户（owner）：在睡觉，全程不打扰；交付后按本计划与验收清单验收。

## 1. 目标与验收标准

**交付目标**：功能完整、脚本测试全绿、UI 无明显缺陷的 Android alpha 版本，包含：

| # | 验收项 | 判定标准 | 负责 |
| --- | --- | --- | --- |
| A1 | 可行性验证 | 严谨、有源码/实测证据；纠正常见误解（不是"SNI 漏洞"）；给出 Android 限制清单与 Go/No-Go | Lead + analyst + challenger |
| A2 | 后端移植 | 纯 Kotlin `:core`（配置/DNS 防污染/规则引擎/代理/MITM/日志统计）+ 单测全绿 | analyst + proxy-dev |
| A3 | Android 系统级接入 | VpnService（tun DNS 拦截 + API29+ 系统代理）+ 前台服务 + always-on + 故障安全 | android-dev |
| A4 | 前端 MD3 | 严格 MD3、无非 MD3 元素、每个状态变化有非线性动画、深色/动态色 | ui-dev |
| A5 | 三环境流水线 | `pipeline.ps1 dev/test/prod`；test 失败禁止晋级；交付物含签名 APK + 校验和 + 清单 | Lead |
| A6 | 质量 | 单测/静态审计全绿；模拟器安装启动截图无阻断缺陷；已知限制文档化 | challenger (QA) |
| A7 | ABI | 后端纯 Kotlin ⇒ ABI 无关；Gradle 预留 arm64-v8a/armeabi-v7a 过滤与 x86/x64 扩展点 | Lead |
| A8 | git | main 分支 + 里程碑提交 + tag `v0.1.0-alpha`；不提交构建产物与密钥 | Lead |

## 2. 团队与分工（Agent Teams）

| 角色 | 名字 | 职责 | 写作用域 |
| --- | --- | --- | --- |
| Lead | lead | 架构/契约/评审/集成/流水线/构建/提交/最终验收 | `core/api`、`docs/**`、`_script/**`、gradle、git |
| 后端数据线 | analyst | T1 上游逆向 → `:core config/dns/rules/log/stats/hosts/speed` | 对应包 + 测试 |
| 后端代理线 | proxy-dev | `:core proxy/mitm/cert/engine/util` | 对应包 + 测试 |
| Android 集成 | android-dev | `app controller/vpn/data/boot` + manifest + DNS 包编解码 | 对应包 + 测试 |
| 前端 | ui-dev | `app ui/**` + MD3 主题/运动 token + 设计文档 | 对应包 + 测试 |
| 独立 QA | challenger | T2 平台限制/对抗评审 → 静态审计/对抗测试/截图/验收报告 | `docs/qa`、`_script/qa`、`qa` 测试包 |

协作规则：契约（`core/api`、`app/state`、`app/controller` 接口）由 Lead 冻结；跨 workstream 依赖只经 Lead 协调；禁止改他人文件；git 操作仅 Lead；质量门禁见 `docs/dev-workflow.md`。

## 3. 里程碑

| 时间 | 里程碑 | 产出 |
| --- | --- | --- |
| T+0:00 | 环境侦察 | JDK/SDK/Gradle/镜像/本地 Dev-Sidecar 实例确认 |
| T+0:10 | 上游克隆 + T1/T2 派单 | `reference/dev-sidecar`（含 ds-core 子模块） |
| T+0:25 | 构建链打通 | Android SDK 补齐、Gradle 构建通过、git 初始化、`:core` 契约冻结 |
| T+0:30 | 四条开发线并行 | analyst/proxy-dev/android-dev/ui-dev 全部开工 |
| T+1:10 | T1/T2 交付 + 可行性报告 | `docs/feasibility/*` + Go/No-Go |
| T+1:30 | 首次全量编译 | `:core:compileKotlin` + `:app:assembleDebug` 通过 |
| T+1:30–T+4:30 | 开发主线 | 各 workstream 功能 + 分层测试持续集成 |
| T+4:00 | QA 启动 | 静态审计/对抗测试/模拟器截图 |
| T+4:30–T+5:30 | 集成联调 | 端到端：VpnService→Engine→DNS/Proxy；修根因 |
| T+5:30–T+6:30 | 测试环境全量 | `pipeline.ps1 test` 全绿；缺陷清零（阻断级） |
| T+6:30–T+7:00 | 生产环境 | `pipeline.ps1 prod`；交付物 + 文档 + 模拟器复核 |
| T+7:00–T+8:00 | 收尾 | 最终验收清单、git tag、交接说明、buffer |

## 4. 质量门禁（Gate）

1. **G1 编译门禁**：`:core:compileKotlin` + `:app:assembleDebug` 通过。
2. **G2 单测门禁**：`:core:test`、`:app:testDebugUnitTest` 0 failure/0 error，且测试总数 > 0。
3. **G3 静态门禁**：`audit_md3.ps1`（MD3/动画）0 违规；`check_apk.ps1`（清单/SDK/签名）通过；`check_requirements.ps1` 通过。
4. **G4 评审门禁**：可行性文档经 QA 对抗复核；已知限制全部文档化并在 UI 提示。
5. **G5 交付门禁**：仅当 G1–G4 全绿才允许 `pipeline.ps1 prod` 生成 `dist/`；否则回开发环境修根因重跑。
6. **G6 版本门禁**：交付时 git 工作区干净（构建产物/密钥被 ignore）、有 tag、提交历史可读。

## 5. 风险登记与应对

| # | 风险 | 级别 | 应对 |
| --- | --- | --- | --- |
| R1 | VPN 系统代理是否被各应用遵循无法本地实测 | 高 | 模拟器 + owner 手机 10 分钟验收清单；UI 显示 `systemProxyRegistered` 检测；文档如实说明 |
| R2 | 无 root 下第三方应用不信任用户 CA | 高 | MITM 默认关闭；能力边界写入 README/UI；不夸大 |
| R3 | FGS 类型/always-on/Private DNS 等 Android 版本差异导致真实设备异常 | 高 | QA 的 T2 清单逐条落地（specialUse、检测告警、fail-safe） |
| R4 | 多人并行编译冲突/契约漂移 | 中 | 文件所有权 + 冻结契约 + Lead 集成；filtered gradle 任务 |
| R5 | 规则表迁移体量与语义偏差 | 中 | 官方快照直接复用 + 表驱动单测 |
| R6 | 时间不足导致 UI 打磨/测试覆盖欠缺 | 中 | 优先级：可安装可运行 > 主链路正确 > UI 打磨；每 30 分钟检查进度 |
| R7 | 依赖镜像不稳定 | 低 | aliyun/tencent 双镜像 + 本地缓存；失败重试不降版本 |

## 6. 质量监督方式（Lead）
- 每 30–45 分钟检查：`git status`、编译状态、测试计数、各 workstream 里程碑消息。
- 关键代码由 Lead 抽查（契约实现、fail-safe、线程泄漏、测试有效性；禁止"假绿"：skip/放宽断言/吞异常）。
- 决策记录写入 `docs/architecture/` 与提交信息，保证可追溯。
- 交付前 Lead 亲自执行：`pipeline.ps1 all`、抽查 `dist/`、核对验收清单、打 tag。
