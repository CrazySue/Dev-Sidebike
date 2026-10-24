# Dev-Sidebike scripts (Windows PowerShell 5.1)

All .ps1 files in this folder are saved as UTF-8 **with BOM** so Windows PowerShell 5.1 parses
Chinese log messages correctly. If you create/rewrite a .ps1 file, run:

    powershell -NoProfile -File _script\ensure-ps1-bom.ps1

## Entry points

| Script | Environment | What it does |
| --- | --- | --- |
| `env.ps1` | shared | Exports JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / PATH for this workspace |
| `gradle.ps1` | shared | Gradle wrapper with a cross-process build lock (ALL agents must use it) |
| `pipeline.ps1 dev` | 开发环境 | Builds source -> pre-delivery artifact `out/dev/app-debug.apk` (+ build-info.json) |
| `pipeline.ps1 test` | 测试环境 | Runs core/app tests + MD3 audit + APK/requirements checks -> `out/test/test-report.md` |
| `pipeline.ps1 prod` | 生产环境 | Promotes only when test passed -> `dist/` release APK + sha256 + manifest |
| `pipeline.ps1 all` | all | dev -> test -> prod (stops at first failure) |
| `build.ps1` / `test.ps1` / `release.ps1` | shortcut | Thin aliases for the three stages above |
| `ensure-ps1-bom.ps1` | shared | Fixes UTF-8 BOM for every .ps1 (Windows PowerShell 5.1 requirement) |
| `qa/audit_md3.ps1` | 测试环境 gate | Strict Material 3 / motion compliance audit of `app/.../ui/**` |
| `qa/check_apk.ps1` | 测试/生产 gate | aapt2 manifest / SDK / permission / debuggable verification |
| `qa/check_requirements.ps1` | 测试环境 gate | Repo structure, three-environment scripts, ABI policy, docs, git tags |
| `qa/setup_emulator.ps1` | QA | Installs emulator + x86_64 API35 system image from Tencent mirror and creates AVD |
| `qa/device_verify.md` | owner | 10-minute on-device acceptance checklist (M1-M6) |

## Rules

1. Never run `tools\gradle-8.9\bin\gradle.bat` directly while other agents may build: use `gradle.ps1`.
2. Test stage must be green before prod stage; failures go back to dev and are fixed at the root cause.
3. Do not weaken tests to make the pipeline pass.
