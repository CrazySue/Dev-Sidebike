# 开发工作流与协作规约（Dev-Sidebike）

> 所有 agent（Lead 与 teammate）共同遵守。用户（项目所有者）在睡觉，期间不得提问、不得等待确认。

## 1. 环境（Windows，本机）

```powershell
# 每次新 shell 先加载环境（JDK17 / Android SDK / Gradle / GRADLE_USER_HOME）
. E:\WorkSpace\Dev-Sidebike\_script\env.ps1
```

| 组件 | 位置 |
| --- | --- |
| 工作区 | `E:\WorkSpace\Dev-Sidebike`（E 盘，约 30GB 空闲） |
| JDK 17 | `D:\Java\zulu17.68.17-ca-jdk17.0.20-win_x64`（`JAVA_HOME` 由 env.ps1 设置） |
| Android SDK | `tools\android-sdk`（platform-tools、platforms/android-35、build-tools/35.0.0） |
| Gradle 8.9 | `tools\gradle-8.9` |
| Gradle 缓存 | `.gradle-home`（env.ps1 设置的 `GRADLE_USER_HOME`） |
| 上游参考源码 | `reference/dev-sidecar`（只读！含 submodule `vendor/ds-core`） |
| 网络 | 依赖下载走 aliyun/tencent 镜像；直连 github/raw 可能被墙；`web_fetch`/`web_search` 工具可用 |

常用命令：

```powershell
# 编译 debug APK（预交付物）
powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :app:assembleDebug
# 只跑 core 测试（最快反馈）
powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :core:test
# 跑 app JVM 测试
powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\gradle.ps1 :app:testDebugUnitTest
# 三环境流水线（Lead 负责执行）
powershell -NoProfile -File E:\WorkSpace\Dev-Sidebike\_script\pipeline.ps1 test
```

首次全量构建依赖下载可能耗时数分钟，属正常。**遇到依赖下载失败 → 重试或用镜像，不要改版本降级。**

### 构建锁故障排查（跨 agent 并发）
- 现象：构建长时间停在 `[gradle] 等待其他 agent 的构建完成（全局构建锁）...`。
  1. 等待 5 分钟仍未获得锁 → 重试一次（`gradle.ps1` 已处理被放弃的 mutex：上一个持有者被 kill 时会自动接管）。
  2. 若仍卡住，检查是否有卡死的测试进程（`java ... GradleMain ... :app:testDebugUnitTest` 运行 >10 分钟即视为卡死），向 Lead 报告 PID，不要自行 kill 其他 agent 的进程。
- 现象：`AbandonedMutexException` → 已由 wrapper 捕获接管，属正常恢复路径，无需处理。
- 所有 Gradle Test 任务已有 10 分钟超时保护（防止死锁测试卡住整条流水线）；UI/Robolectric 测试若需连续动画，必须在测试中关闭无限动画或使用 `mainClock.autoAdvance=false`。

## 2. 文件所有权（写作用域）

| 角色 | 可写路径 |
| --- | --- |
| Lead | 全部；负责 `core/src/main/kotlin/dev/sidebike/core/api/**`、`docs/**`、`_script/**`、gradle 配置、git 提交 |
| backend-dns（analyst） | `core/src/main/kotlin/dev/sidebike/core/{config,dns,rules,hosts,log,stats,speed,util}/**` + `core/src/test/kotlin/dev/sidebike/core/{config,dns,rules,util}/**` |
| backend-proxy | `core/src/main/kotlin/dev/sidebike/core/{proxy,mitm,cert,engine}/**` + `core/src/test/kotlin/dev/sidebike/core/{proxy,mitm,engine}/**` |
| android-service | `app/src/main/java/dev/sidebike/app/{controller,vpn,data,boot}/**`、`app/src/main/java/dev/sidebike/app/DevSidebikeApplication.kt`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/values/strings_vpn.xml`、`app/src/test/java/dev/sidebike/app/{controller,vpn,data}/**` |
| ui-md3 | `app/src/main/java/dev/sidebike/app/ui/**`、`app/src/main/java/dev/sidebike/app/MainActivity.kt`、`app/src/main/java/dev/sidebike/app/state/**`（Lead 批准后）、`app/src/main/res/values/strings.xml`、`app/src/main/res/drawable/**`（自有图标）、`docs/design/**`、`app/src/test/java/dev/sidebike/app/ui/**` |
| qa | `docs/qa/**`、`_script/qa/**`、`core/src/test/kotlin/dev/sidebike/core/qa/**`、`app/src/test/java/dev/sidebike/app/qa/**`、`dist` 只读 |

**禁止**：修改 `reference/**`；修改他人作用域文件；`git add/commit`（git 操作只由 Lead 执行）；关闭/放宽测试；删除他人未交付代码。

## 3. 契约与接口
- `core/api/**` 已冻结。实现者若发现契约不足，先 `send_message` 给 lead 说明（what/why），由 Lead 修改。
- 私有实现放各自包内，禁止跨包直接依赖实现的内部类（通过 api 接口通信）。

## 4. 质量门禁（Definition of Done）
1. `:core:test` 与 `:app:testDebugUnitTest` 全绿；新增逻辑有对应测试。
2. `:app:assembleDebug` 成功，APK 可安装（无 manifest/lint 致命错误）。
3. 代码无 `TODO()` 运行时炸弹（未实现分支要么报错清晰，要么不进入正式路径）。
4. 不访问外网的测试保持 hermetic；涉及时间/随机数可注入。
5. 完成后在任务板标记 complete，并给 lead 发一条简短 message：交付内容、测试命令结果、已知限制。

## 5. 三环境流水线（用户硬性要求）
- **dev**：源码 → `out/dev/app-debug.apk`（预交付物）。
- **test**：对预交付物对应的源码树运行全部测试与静态审计 → `out/test/test-report.md`。
- **prod**：test 全绿后才允许 `_script/release.ps1` 生成 `dist/` 交付物（release APK + sha256 + manifest）。
- 测试失败 → 回 dev 修**根因**（禁止 skip、禁止降低断言、禁止 try/catch 吞掉）→ 重新构建 → 重跑 test，循环直至全绿。

## 6. 沟通
- 长任务用共享任务板（claim/complete）；阻塞或契约问题发 `send_message(target:"lead")`。
- 汇报要短：结论、证据（文件/命令/测试结果）、风险。
- 不要等待用户；所有决策自行做出并记录到 docs。
