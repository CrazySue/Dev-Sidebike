# Dev-Sidebike

> Android 版「开发者边车」：把 Dev-Sidecar 的加速能力带到 Android 系统级。
> **状态：alpha（v0.1.0-alpha）** — 后端纯 Kotlin 重写、VpnService 系统级 DNS 拦截 + 系统 HTTP 代理、严格 Material 3 前端。

---

## 1. 这是什么

[Dev-Sidecar（开发者边车）](https://github.com/docmirror/dev-sidecar) 是桌面端广受欢迎的开发者网络加速工具：本地代理 + 自定义 DNS 解析层 + 规则表，通过 **诱饵 SNI（decoy SNI）+ DoH/DoT/预置 IP 防污染 + ECH** 等方式，让 GitHub、npm、Stack Overflow 等站点在中国大陆网络下可用。

它没有 Android 版本。**Dev-Sidebike** 把它的后端能力移植到 Android：

- **后端**：用纯 Kotlin/JVM 重写 Dev-Sidecar 内核（DNS 解析层、规则引擎、HTTP/HTTPS 代理、CA/MITM、拦截器），模块边界与配置语义与上游对齐；不携带 Node.js 运行时，后端逻辑与 CPU 架构无关。
- **ABI 说明**：Jetpack 传递依赖自带少量 `.so`（DataStore/GraphicsPath），因此 APK 仍按 ABI 打包。本版**交付物仅适配 arm64-v8a / armeabi-v7a**（符合项目要求）；**debug/测试 APK 额外包含 x86_64 / x86** 以便模拟器验收；其他架构可通过 `app/build.gradle.kts` 的 `ndk.abiFilters` 一行扩展。
- **系统级接入**：`VpnService` 只接管 DNS（tun 内应答 `10.111.0.2:53`），对所有应用生效且**无需安装证书**；API 29+ 同时用 `VpnService.Builder.setHttpProxy()` 把本地 HTTP 代理注册为系统代理。
- **增强模式**：开启 MITM（需用户安装本应用生成的 CA）后可用 `sni` 诱饵改写、镜像改写、响应替换等上游拦截器能力（Android 7+ 第三方应用默认不信任用户 CA，仅浏览器等部分应用受益）。
- **前端**：Jetpack Compose + **严格 Material Design 3**（MD3 token、动态取色、深色模式），所有界面状态变化均使用 MD3 非线性运动曲线（emphasized/standard 缓动 + 时长 token）。

## 2. 可行性结论（诚实版）

| 能力 | 是否需要 CA | Android 可行性 |
| --- | --- | --- |
| DNS 防污染（DoH/DoT/预置 IP/多 Provider 优选），对**所有应用**生效 | 不需要 | ✅ 本机与模拟器验证，VPN DNS 拦截是 Android 专属增强（上游桌面版没有独立 DNS 服务） |
| 本地 HTTP/HTTPS 代理（CONNECT 隧道）作为系统代理 | 不需要 | ✅ API 29+；API 26-28 降级为仅 DNS 模式 |
| 直连加速（GitHub 核心域等，干净 DNS 后直连） | 不需要 | ✅ 本机实测 github.com/api.github.com/codeload.github.com 直连可达 |
| 诱饵 SNI / 镜像改写 / 脚本注入（raw.githubusercontent、Google 系等） | 需要 | ⚠️ 需 MITM + 用户 CA；仅信任用户 CA 的应用（浏览器等）可用 |
| 让**所有**应用流量强制走代理 | — | ⚠️ 无 root 做不到；仅遵循系统代理/系统 DNS 的应用受益（OkHttp/Chromium 等通常遵循） |
| ECH、NAT64、overwall、git/node/pip 插件 | — | ❌ 本版本不做，已在文档标注 |

> 详细证据（源码行号 + 只读实测 + 逐条可移植性判定）见 [`docs/feasibility/01-dev-sidecar-analysis.md`](docs/feasibility/01-dev-sidecar-analysis.md)；独立复核与 Android 平台限制见 [`docs/feasibility/02-android-constraints.md`](docs/feasibility/02-android-constraints.md) 与 [`docs/feasibility/03-review-checklist.md`](docs/feasibility/03-review-checklist.md)。

### 2.1 已在 Android 15 模拟器（API 35，x86_64 Play 镜像）实测通过

| 验证项 | 结果 | 证据 |
| --- | --- | --- |
| VPN 启动/控制/E1 系统代理下发 | ✅ PASS | `dumpsys connectivity`：VPN `LinkProperties` 带 `HttpProxy: [127.0.0.1] 31181`；系统/第三方应用真实连接到本地代理（连接数增长） |
| E2 DNS 防污染闭环 | ✅ PASS | `ping example.com` 解析成功；`ping github.com` → 真实 GitHub IP；`ping www.google.com` → 真实 Google IP（非投毒地址）；App DNS 查询计数增长、无 SERVFAIL |
| 官方规则种子 | ✅ PASS | 规则页显示 **69 个域名 / 77 条路径规则**；预置 IP 61 域、DNS provider 9 个、国内域名 1237 条 |
| 停止/进程死亡后网络恢复 | ✅ PASS | 停止 VPN 或 `force-stop` 后 `tun0` 消失，DNS/网络立即恢复 |
| Private DNS / lockdown 环境检测 | ✅ PASS | 开启 Private DNS(DoT) / lockdown 时 UI 显示红色警告 |
| 通知权限 | ✅ PASS | Android 13+ 首次启动运行时请求，授予后 VPN 通知可见 |
| 后台存活（锁屏 10 分钟） | ⏳ 模拟器验证中 | 真机需按 `_script/qa/device_verify.md` 复验 |
| MITM/CA（默认关闭） | ⏳ JVM 端到端测试中 | 需用户在真机安装 CA；仅部分应用信任用户 CA |

> 截图与证据：`docs/qa/screenshots/`、`docs/qa/05-test-report.md`；真机验收清单：`_script/qa/device_verify.md`。

## 3. 快速开始（开发/构建）

```powershell
# 1) 加载环境（JDK17 / Android SDK / Gradle 8.9 / GRADLE_USER_HOME）
. E:\WorkSpace\Dev-Sidebike\_script\env.ps1

# 2) 三环境流水线（唯一入口）
powershell -NoProfile -File _script\pipeline.ps1 dev    # 开发环境 → 预交付物 out/dev/app-debug.apk
powershell -NoProfile -File _script\pipeline.ps1 test   # 测试环境 → 单测+静态审计+APK 校验 → out/test/test-report.md
powershell -NoProfile -File _script\pipeline.ps1 prod   # 生产环境 → 交付物 dist/（release APK + sha256 + manifest）
powershell -NoProfile -File _script\pipeline.ps1 all    # 串行执行 dev→test→prod
```

安装：`dist/Dev-Sidebike-<version>-release.apk`（自签名，需允许未知来源）。

## 4. 目录结构

```
Dev-Sidebike/
├─ app/                     Android 应用（Compose MD3 UI、VpnService、Controller、DataStore、CA 安装引导）
├─ core/                    纯 Kotlin/JVM 后端（config / dns / rules / proxy / mitm / cert / engine / log / stats）
├─ docs/
│  ├─ architecture/         架构决策与 :core 契约（Lead 冻结）
│  ├─ feasibility/          可行性分析（上游源码逆向、Android 限制、复核清单）
│  ├─ design/               MD3 设计规范 + 事件→动画映射表
│  ├─ qa/                   QA 测试计划、截图、验收报告、手机端验收清单
│  └─ dev-workflow.md       协作/环境/三环境/质量门禁
├─ _script/                 环境脚本、三环境流水线、QA 静态审计、实验脚本
├─ reference/dev-sidecar/   上游参考源码（只读，gitignore，含 ds-core 子模块）
├─ out/                     构建/测试产物（dev 预交付物、test 报告；gitignore）
└─ dist/                    交付物（release/debug APK、校验和、清单；gitignore）
```

## 5. 质量与流程

- 三环境：**dev（源码→预交付物）→ test（测试全绿才可晋级）→ prod（交付物）**；失败必须修根因，禁止放宽测试。
- 每次提交前跑：`:core:test`、`:app:testDebugUnitTest`、`_script/qa/audit_md3.ps1`（MD3/动画静态审计）、`:app:assembleDebug`。
- 可靠性设计：代理先自检再建立 VPN；运行中 watchdog；端口冲突回退；Private DNS / lockdown 检测与强提示；tun DNS 应答器永不置 TC（按 EDNS0 裁剪）。
- 版本控制：git（main 分支 + 里程碑 tag），提交信息遵循 `type(scope): 中文摘要`。

## 6. 已知限制（0.1.0-alpha）

1. 不遵循系统 HTTP 代理的应用只能获得 DNS 级加速。
2. 系统 Private DNS 设为 hostname 时会绕过 VPN DNS（应用内检测并提示）。
3. Android 7+ 第三方应用默认不信任用户 CA ⇒ MITM 增强仅对浏览器等少量应用生效。
4. 无 root 无法静默安装系统 CA / 全局代理。
5. 与本工具规则冲突的私有网络/企业 VPN 环境不保证可用；VPN 生效期间不能与其他 VPN 共存。
6. ECH、NAT64、HTTP/2 MITM、overwall、桌面插件未实现（文档已标注）。

## 7. 致谢与许可

- 本项目是 [docmirror/dev-sidecar](https://github.com/docmirror/dev-sidecar)（MPL-2.0）的 Android 移植/重写，规则数据复用其官方配置快照，部分算法语义参照 `Blue-Frontier/ds-core`。
- 本项目以 **Mozilla Public License 2.0** 发布（见 `LICENSE`）。
- 仅用于个人开发者访问公开技术资源的网络加速用途，请遵守所在地法律法规。
