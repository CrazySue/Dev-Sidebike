@echo off
rem QA headless emulator launcher (cmd is used because Windows PowerShell 5.1's Start-Process
rem fails when the environment contains duplicate case-variant keys such as NO_PROXY/no_proxy).
setlocal
set "ROOT=E:\WorkSpace\Dev-Sidebike"
set "ANDROID_HOME=%ROOT%\tools\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "ANDROID_AVD_HOME=%ROOT%\tools\avd"
set "AVD_HOME=%ROOT%\tools\avd"
set "EMU=%ANDROID_HOME%\emulator\emulator.exe"
set "LOG=%ROOT%\tools\avd\emulator.log"
set "ERR=%ROOT%\tools\avd\emulator.err.log"
if not exist "%EMU%" (
  echo [launch_emulator] emulator.exe missing: %EMU%
  exit /b 1
)
echo [launch_emulator] starting qa35 ...
start "dsh-emulator" /b "%EMU%" -avd qa35 -no-window -gpu swiftshader_indirect -no-audio -no-snapshot -accel auto -no-boot-anim -port 5554 > "%LOG%" 2> "%ERR%"
echo [launch_emulator] started (log: %LOG%)
endlocal
exit /b 0
