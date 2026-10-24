# QA emulator offline installer (Windows PowerShell 5.1 compatible, ASCII-only on purpose:
# PS 5.1 reads BOM-less scripts as ANSI and Chinese comments break parsing).
# Downloads emulator + google_apis_playstore x86_64 API35 image from Tencent mirror,
# verifies SHA1, extracts into the offline SDK, writes package.xml files and a manual AVD.
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File _script/qa/setup_emulator.ps1
#   ... -SkipDownload   (reuse existing zips)
#   ... -CreateAvd      (also create qa35 AVD)
param(
    [string]$Root = 'E:\WorkSpace\Dev-Sidebike',
    [switch]$SkipDownload,
    [switch]$CreateAvd
)
$ErrorActionPreference = 'Stop'
$sdk = Join-Path $Root 'tools\android-sdk'
$dl = Join-Path $Root 'downloads'
$avdHome = Join-Path $Root 'tools\avd'
New-Item -ItemType Directory -Force -Path $dl, $avdHome | Out-Null
$mir = 'https://mirrors.cloud.tencent.com/AndroidSDK'

# Pinned to Tencent stable repo metadata (repository2-1.xml / sys-img/google_apis_playstore/sys-img2-1.xml)
$emulator = @{
    Name = 'emulator-windows_x64-16632760.zip'
    Url  = "$mir/emulator-windows_x64-16632760.zip"
    Sha1 = '5ecdf654031e9791e20c336c8d87d2238cb69f3a'
    Size = 459732447
}
$sysImg = @{
    Name = 'x86_64-35_r09.zip'
    Url  = "$mir/sys-img/google_apis_playstore/x86_64-35_r09.zip"
    Sha1 = '2f0054868e6aab3c098acd3decba17a82aed4176'
    Size = 1762061559
}

function Get-RemoteFile([hashtable]$pkg) {
    $out = Join-Path $dl $pkg.Name
    if ((Test-Path $out) -and ((Get-Item $out).Length -eq $pkg.Size)) {
        $h = (Get-FileHash -Algorithm SHA1 $out).Hash.ToLower()
        if ($h -eq $pkg.Sha1) { Write-Host "[ok] cached $($pkg.Name) sha1 ok"; return $out }
    }
    for ($i = 1; $i -le 3; $i++) {
        Write-Host ("[dl] attempt {0}: {1}" -f $i, $pkg.Url)
        $progress = & curl.exe -sk --noproxy '*' --retry 2 --retry-delay 2 -C - -o $out $pkg.Url -w "http=%{http_code} size=%{size_download} speed=%{speed_download}"
        Write-Host $progress
        if ((Test-Path $out) -and ((Get-Item $out).Length -eq $pkg.Size)) {
            $h = (Get-FileHash -Algorithm SHA1 $out).Hash.ToLower()
            if ($h -eq $pkg.Sha1) { Write-Host "[ok] $($pkg.Name) sha1 ok"; return $out }
            Write-Host "[warn] sha1 mismatch: got $h expected $($pkg.Sha1)"
            Remove-Item $out -Force
        } else {
            Write-Host ("[warn] size mismatch: got {0} expected {1}" -f (Get-Item $out -ErrorAction SilentlyContinue).Length, $pkg.Size)
        }
        Start-Sleep -Seconds 3
    }
    throw "download failed: $($pkg.Url)"
}

function Expand-SdkZip([string]$zip, [string]$dest) {
    Write-Host "[unzip] $zip -> $dest"
    & tar.exe -xf $zip -C $dest
    if ($LASTEXITCODE -ne 0) { throw "tar failed for $zip" }
}

function Write-SysImgPackageXml {
    $dir = Join-Path $sdk 'system-images\android-35\google_apis_playstore\x86_64'
    if (-not (Test-Path $dir)) { Write-Host "[warn] sysimg dir missing: $dir"; return }
    $pkgXml = Join-Path $dir 'package.xml'
    if (Test-Path $pkgXml) { Write-Host "[ok] package.xml exists"; return }
    $xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/01"
    xmlns:ns3="http://schemas.android.com/sdk/android/repo/sys-img2/02"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <localPackage path="system-images;android-35;google_apis_playstore;x86_64" obsolete="false">
    <type-details xsi:type="ns3:sysImgDetailsType">
      <api-level>35</api-level>
      <tag><id>google_apis_playstore</id><display>Google Play</display></tag>
      <vendor><id>google</id><display>Google Inc.</display></vendor>
      <abi>x86_64</abi>
    </type-details>
    <revision><major>9</major></revision>
    <display-name>Google Play Intel x86_64 Atom System Image</display-name>
  </localPackage>
</ns2:repository>
'@
    Set-Content -Path $pkgXml -Value $xml -Encoding UTF8
    Write-Host "[ok] wrote $pkgXml"
}

function Write-EmulatorPackageXml {
    $dir = Join-Path $sdk 'emulator'
    if (-not (Test-Path $dir)) { return }
    $pkgXml = Join-Path $dir 'package.xml'
    if (Test-Path $pkgXml) { return }
    $xml = @'
<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/01"
    xmlns:ns3="http://schemas.android.com/sdk/android/repo/generic2/01"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <localPackage path="emulator" obsolete="false">
    <type-details xsi:type="ns3:genericDetailsType"/>
    <revision><major>36</major><minor>6</minor><micro>11</micro></revision>
    <display-name>Android Emulator</display-name>
  </localPackage>
</ns2:repository>
'@
    Set-Content -Path $pkgXml -Value $xml -Encoding UTF8
}

function New-QaAvd {
    $avdName = 'qa35'
    $avdDir = Join-Path $avdHome "$avdName.avd"
    $iniFile = Join-Path $avdHome "$avdName.ini"
    New-Item -ItemType Directory -Force -Path $avdDir | Out-Null
    $esc = $avdDir.Replace('\', '\\')
    $ini = "avd.ini.encoding=UTF-8`npath=$esc`npath.rel=avd\$avdName.avd`ntarget=android-35`n"
    Set-Content -Path $iniFile -Value $ini -Encoding UTF8
    $config = @'
AvdId=qa35
PlayStore.enabled=true
abi.type=x86_64
avd.ini.displayname=QA API35 Play
avd.ini.encoding=UTF-8
disk.dataPartition.size=4G
hw.accelerometer=yes
hw.audioInput=no
hw.audioOutput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.dPad=no
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=1920
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=3072
hw.sdCard=no
hw.sensors.orientation=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images/android-35/google_apis_playstore/x86_64/
runtime.network.latency=none
runtime.network.speed=full
tag.display=Google Play
tag.id=google_apis_playstore
vm.heapSize=256
'@
    Set-Content -Path (Join-Path $avdDir 'config.ini') -Value $config -Encoding UTF8
    Write-Host "[ok] AVD created (manual): $iniFile -> $avdDir"
}

function Repair-SysImgLayout {
    # Tencent sys-img zip has root "x86_64/..." while sdkmanager expects
    # "system-images/android-35/google_apis_playstore/x86_64/...".
    $src = Join-Path $sdk 'x86_64'
    $dst = Join-Path $sdk 'system-images\android-35\google_apis_playstore\x86_64'
    if (Test-Path $dst) { Write-Host "[ok] sysimg layout already corrected"; return }
    if (-not (Test-Path $src)) { Write-Host "[warn] no x86_64 dir to move"; return }
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    Get-ChildItem $src -Force | Move-Item -Destination $dst -Force
    Remove-Item $src -Recurse -Force
    Write-Host "[ok] moved sysimg x86_64 -> $dst"
}

if (-not $SkipDownload) {
    $emuZip = Get-RemoteFile $emulator
    Expand-SdkZip $emuZip $sdk
    $sysZip = Get-RemoteFile $sysImg
    Expand-SdkZip $sysZip $sdk
}
Repair-SysImgLayout
Write-EmulatorPackageXml
Write-SysImgPackageXml
if ($CreateAvd) { New-QaAvd }

Write-Host '=== result ==='
Write-Host ("emulator.exe: " + (Test-Path (Join-Path $sdk 'emulator\emulator.exe')))
Write-Host ("sysimg      : " + (Test-Path (Join-Path $sdk 'system-images\android-35\google_apis_playstore\x86_64\system.img')))
Write-Host ("avd         : " + (Test-Path (Join-Path $avdHome 'qa35.ini')))
Write-Host 'SETUP DONE'
