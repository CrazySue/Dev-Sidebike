# QA 自动化使用说明（0.1.1-alpha 轮）

> 维护：challenger（QA）。这些脚本只做“独立验证”，不修改生产代码；所有产物落在 `docs/qa/` 下。
> 环境前提：Windows PowerShell 5.1；离线 SDK（`tools/android-sdk`）；headless 模拟器 `qa35`（x86_64 API35 google_apis_playstore）；AVD 在 `tools/avd`（E 盘）。
> 所有 `.ps1` 需 UTF-8 with BOM（仓库有 `_script/ensure-ps1-bom.ps1`）；本轮脚本已处理。

## 1. 模拟器生命周期

```powershell
# 首次/重建：下载 emulator + system image（腾讯镜像，SHA1 校验），生成 qa35 AVD
powershell -NoProfile -File _script\qa\setup_emulator.ps1

# 启动 headless（-no-window -gpu swiftshader_indirect，E 盘 AVD）
cmd /c _script\qa\launch_emulator.cmd
# 等 adb online：adb devices / adb shell getprop sys.boot_completed
```

注意：模拟器在**并发 Gradle 构建**（CPU 打满）时可能弹出 `System UI isn't responding` 系统对话框，偷走 UI dump/截图。`lib_android.ps1` 的 `Qa-DismissSystemDialogs` 会自动点 `Wait`；重活建议加 `-WaitBuildQuiet` 或错峰跑。

## 2. 0.1.1 用户问题验收 flows

```powershell
# 全流程（U1–U9 自动化部分）：安装 + 五屏 + 进度/uptime + 导航 + CA + MITM/诊断 + 导出 + 规则 + 导入
powershell -NoProfile -File _script\qa\qa_011_flows.ps1 -Apk out\dev\app-debug.apk

# 单个 flow
... -Flow smoke|progress|nav|ca|mitm|export|rules|import
# 已安装时跳过安装；并发构建时先等构建安静
... -SkipInstall -WaitBuildQuiet
```

产物：
- `docs/qa/evidence/011/qa-<flow>-<stamp>.log`：每步 PASS/FAIL/BLOCKED/SKIP 明细；
- `docs/qa/evidence/011/<stamp>-NN-<name>.png`：真实 PNG 截图（cmd 二进制重定向，已校验 magic）；
- 状态语义：**PASS** 已验证通过；**FAIL** 已交付行为回归/明确错误；**BLOCKED** 当前构建还没实现该 UI（需 owner 落地后重跑，不算 PASS）。

规则/导入相关用例依赖仓库内的 hermetic “远程规则源”：

```powershell
# 手动启动（qa_011_flows.ps1 -Flow import 会自动启动并做设备可达性探测）
powershell -NoProfile -File _script\qa\rule_source_server.ps1 -Port 18080 -BindAddress any
# 设备侧 URL： http://10.0.2.2:18080/rules.json  (JSON) / rules.list / domains.txt
# 注入失败： /malformed.json / /slow / /error500；请求日志：http://127.0.0.1:18080/requests.json
```

**关键经验（已固化）**：模拟器经 `10.0.2.2` 访问宿主时，服务器必须绑定 **0.0.0.0**（`-BindAddress any`）；只绑 `127.0.0.1` 设备侧 TCP 不通。`adb reverse` 在本机模拟器环境不可用（已弃用）。ETag/If-None-Match 用于验证“自动更新/304 增量”路径。

## 3. 基线回归 / 静态门禁

```powershell
# E1 系统代理 / E2 DNS 防污染 / SMK 停止恢复（0.1.0 起沿用）
powershell -NoProfile -File _script\qa\emulator_smoke.ps1 -Apk out\dev\app-debug.apk

# 静态：MD3 合规 / APK 结构 / 需求一致性（可用 -Apk/-Release）
powershell -NoProfile -File _script\qa\run_qa_tests.ps1 -Apk out\dev\app-debug.apk
powershell -NoProfile -File _script\qa\run_qa_tests.ps1 -Apk dist\Dev-Sidebike-<ver>-release.apk -Release

# 交付物一致性（dist：SHA256SUMS / delivery-manifest / check_apk -Release）
powershell -NoProfile -File _script\qa\check_delivery.ps1

# 回归单测（经共享锁 wrapper）
powershell -NoProfile -File _script\gradle.ps1 :app:testDebugUnitTest
powershell -NoProfile -File _script\gradle.ps1 :core:test --tests "dev.sidebike.core.qa.*"
```

Robolectric 截图默认 opt-in：`_script\qa\run_screenshots.ps1`（设 `DSH_QA_SCREENSHOTS=1`）。beta 的 UI 验收以模拟器真实截图（`docs/qa/screenshots/`、`docs/qa/evidence/011/`）为准。

## 4. 资产清单

| 文件 | 用途 |
| --- | --- |
| `lib_android.ps1` | adb/UI helper：UiDump/Texts/FindCenter/TapText/WaitText/Screenshot/PingHost/StartVpn/StopVpn/结果收集/ANR 消解/构建安静等待 |
| `qa_011_flows.ps1` | 0.1.1 U1–U9 自动化 flows（需 0.1.1 UI；未实现报 BLOCKED） |
| `rule_source_server.ps1` | hermetic 远程规则源（JSON/list/domains + ETag/304 + 故障注入） |
| `emulator_smoke.ps1` | E1/E2/SMK 基线回归（VPN 启停、系统代理、DNS、截图） |
| `setup_emulator.ps1` / `launch_emulator.cmd` | 离线模拟器安装/启动 |
| `audit_md3.ps1` / `check_apk.ps1` / `check_requirements.ps1` / `check_delivery.ps1` / `run_qa_tests.ps1` / `run_screenshots.ps1` | 静态/交付门禁 |
| `device_verify.md` | owner 真机 M1–M6 清单 |
| `docs/qa/fixtures/` | 规则导入/畸形输入夹具 |
