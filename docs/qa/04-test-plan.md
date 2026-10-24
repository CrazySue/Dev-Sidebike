# 04 路 Dev-Sidebike QA 娴嬭瘯璁″垝锛堥闄╅┍鍔級

> 浣滆€咃細challenger锛堢嫭绔?QA锛夈€傛棩鏈燂細2026-10-25銆傜姸鎬侊細v0.1锛堥殢浠ｇ爜鎺ㄨ繘杩唬锛夈€?> 渚濇嵁锛歚docs/architecture/01-architecture.md`銆乣docs/architecture/02-core-contracts.md`銆乣docs/feasibility/01-dev-sidecar-analysis.md`銆乣docs/feasibility/02-android-constraints.md`銆乣docs/feasibility/03-review-checklist.md`銆乣docs/dev-workflow.md`銆?> 鍘熷垯锛?*姣忎釜鐢ㄤ緥蹇呴』鑳藉け璐?*銆佸繀椤诲彲澶嶇幇銆佸繀椤绘湁璇佹嵁锛堟棩蹇?鎴浘/鍛戒护杈撳嚭/鏂囦欢锛夈€傛棤娉曞湪褰撳墠宸ヤ綔鍖烘墽琛岀殑锛堢湡鏈猴級杩涘叆 owner 鎵嬫満娓呭崟锛屼笉褰撲綔"閫氳繃"銆?
## 1. 鐩爣涓庣幆澧?
| 鐜 | 鐢ㄩ€?| 鑳藉姏 | 闄愬埗 |
| --- | --- | --- | --- |
| **JVM锛堟湰宸ヤ綔鍖猴級** | core 鍗曞厓/闆嗘垚銆佷唬鐞嗗崗璁€丏NS wire銆侀厤缃€佽鍒欍€佽瘉涔︺€乪ngine 鐢熷懡鍛ㄦ湡 | 鍏ㄩ儴鏈湴銆佸揩銆佸彲 CI | 鏃?Android framework/鐪熷疄 tun |
| **Robolectric锛堟湰宸ヤ綔鍖猴級** | app 灞?controller/vpn codec銆乁I 娓叉煋涓庢埅鍥?| 鏃犺澶囧彲璺?Compose 鎴浘 | 闈炵湡瀹炵郴缁熸湇鍔★紱鎴浘涓庣湡鏈烘湁鍍忕礌宸紓 |
| **Android 妯℃嫙鍣紙鏈伐浣滃尯锛寈86_64 API35 google_apis_playstore锛?* | APK 瀹夎/鍚姩/鎴浘銆乂PN 鎺堟潈娴佺▼銆丏NS/浠ｇ悊 smoke銆佺郴缁熻涓?| 姣?Robolectric 鐪熷疄 | 鏃?Google 璐﹀彿/缃戠粶鍙楅檺锛沄PN/Chrome 琛屼负鍙兘涓庣湡鏈轰笉鍚岋紱x86_64 涓嶄唬琛?arm64 鎬ц兘 |
| **Owner 鐪熸満锛堟竻鍗?`_script/qa/device_verify.md`锛?* | Plan A 鏍稿績鍋囪銆丆A 瀹夎銆丮IUI 鍚庡彴銆乴ockdown/Private DNS銆佺湡瀹炵綉缁?| 鍞竴鍙俊鐨勬渶缁堥獙鏀?| 闇€瑕佷富浜洪厤鍚堬紝寮傛 |
| **闈欐€佸璁★紙PowerShell锛?* | MD3 鍚堣銆丄PK 缁撴瀯銆侀渶姹?浠撳簱涓€鑷存€?| 绉掔骇銆佸彲闂ㄧ | 鍙兘鍙戠幇妯″紡鍖栭棶棰?|

**褰撳墠鐜浜嬪疄**锛氭湰宸ヤ綔鍖?`adb devices` 涓虹┖锛涘垵濮嬫棤 emulator/system-image锛孮A 宸查€氳繃 `_script/qa/setup_emulator.ps1` 浠庤吘璁暅鍍忕绾垮畨瑁咃紙emulator + google_apis_playstore x86_64 API35锛夛紝AVD/闀滃儚鏀?`E:\WorkSpace\Dev-Sidebike\tools\avd`锛圕: 绌洪棿涓嶈冻锛夈€?
## 2. 璐ㄩ噺鐩爣涓庨獙鏀跺畾涔夛紙瀵瑰簲鐢ㄦ埛 6 椤硅姹傦級

| 鐢ㄦ埛瑕佹眰 | 鍙獙璇佸畾涔?| 鐢ㄤ緥缁?|
| --- | --- | --- |
| (a) 璇氬疄鍙鎬?| 02/03 鏂囨。 + App 鍐呰兘鍔涜竟鐣屾彁绀?+ 宸茬煡涓嶇敓鏁堟竻鍗曞彲澶嶇幇锛圥rivate DNS/闈炰唬鐞?app/MITM 闄愬埗/API<29锛夛紱鏃犺櫄鍋囧浼?| T-FEA |
| (b) 鍚庣琛屼负瀵归綈 Dev-Sidecar | DNS 闃叉薄鏌擄紙UDP/DoT/DoH+SNI 浼+mapping锛夈€乮ntercepts 鍔ㄤ綔锛圖IRECT/PROXY/SNI/SYNTHETIC/replace/script/cache/status锛夈€佽鍒欏尮閰嶃€乧onfig 瀵煎叆涓?4 灞傚悎骞?| T-PAR |
| (c) 涓夌幆澧冩祦姘寸嚎 | `_script/pipeline.ps1 dev|test|prod` 瀛樺湪涓?test 澶辫触涓嶄骇鍑?prod锛涗骇鐗?hash/manifest | T-PIPE |
| (d) 涓ユ牸 MD3 + 闈炵嚎鎬у姩鐢?| `audit_md3.ps1` PASS锛涙埅鍥句汉宸ュ鏍革紙鏃?M2 缁勪欢/鏃犵嚎鎬у姩鐢?state 浜嬩欢鍧囨湁鍔ㄧ敾锛?| T-MD3 |
| (e) 鍔熻兘瀹屾暣/鍋ュ．/涓嶅穿 | 鐘舵€佹満鏃犲兊灏告€侊紱浠ｇ悊/DNS 鐣稿舰杈撳叆涓嶅穿锛泂tart/stop 50 娆℃棤娉勬紡锛涙棤 ANR/FATAL | T-ROB |
| (f) APK 浜や粯瀹屾暣鎬?| `check_apk.ps1` PASS锛氬寘鍚?minSdk/targetSdk/鏉冮檺/绛惧悕 debug 鏍囪/鐗堟湰/鏃犳剰澶栨潈闄?ABI 璇存槑 | T-APK |

## 3. 娴嬭瘯鐭╅樀锛堢敤渚?ID 鈫?鐜 鈫?閫氳繃鏍囧噯锛?
### 3.1 T-ROB 鍋ュ．鎬?/ 瀵规姉杈撳叆锛圝VM锛宧ermetic锛屾棤澶栫綉锛?
| ID | 鐢ㄤ緥 | 鐜 | 閫氳繃鏍囧噯 |
| --- | --- | --- | --- |
| ROB-01 | IP/UDP/DNS codec 闅忔満瀛楄妭 fuzz锛?0k 鐢ㄤ緥锛岄殢鏈洪暱搴?瀛楁锛?| JVM | 涓嶆姏寮傚父銆佷笉 OOB銆佷笉鏃犻檺寰幆 |
| ROB-02 | DNS 璇锋眰鐣稿舰锛氱┖鍖?鎴柇 header/瓒呴暱鍩熷悕/鍘嬬缉鎸囬拡鐜?QdCount 寮傚父/EDNS0 瓒婄晫 | JVM | 杩斿洖鏍煎紡閿欒鎴栬涓㈠純锛屼笉宕?|
| ROB-03 | DNS 鍝嶅簲鏋勯€狅細A/AAAA/CNAME/澶氳褰?搴旂瓟瓒?512/瀹㈡埛绔?EDNS buffer 512/1232/4096 | JVM | 鎸夊鎴风 buffer 瑁佸壀銆乀C=0锛堢姝?TCP 鍥為€€锛?|
| ROB-04 | HTTP 浠ｇ悊锛欳ONNECT 闈炴硶鐩爣锛堟棤绔彛/瓒呴暱/鎺у埗瀛楃锛夈€佺粷瀵?URI銆乧hunked銆丆L+TE 鍐茬獊銆佽秴闀垮ご锛?4KB锛夈€侀噸澶?Host | JVM | 4xx/鍏抽棴杩炴帴锛屼笉宕╀笉鎸傝捣 |
| ROB-05 | slowloris锛氳繛鎺ュ悗姣?5s 鍙?1 瀛楄妭锛屾寔缁?30s | JVM | 鍒拌揪 readTimeout 鍚庝富鍔ㄥ叧闂紝绾跨▼鍥炴敹 |
| ROB-06 | 骞跺彂锛?0 瀹㈡埛绔悓鏃?CONNECT + 涓€旀柇寮€ | JVM | 鏃犲紓甯告硠婕忋€佺嚎绋嬫暟鍥炲埌鍩虹嚎卤灏戦噺 |
| ROB-07 | 寮曟搸鐢熷懡鍛ㄦ湡锛歴tart/stop 脳50锛涜繍琛屼腑 updateConfig 脳20锛泂top 鏈熼棿 start | JVM | 鏃犲兊灏哥嚎绋?鍗忕▼锛涙棤 crash锛涚姸鎬佸彲閲嶅叆 |
| ROB-08 | 閰嶇疆 fuzz锛氱己瀛楁/鏈煡瀛楁/null/瓒呭ぇ瀛楃涓诧紙1MB锛?閿欒绫诲瀷/JSON5 娉ㄩ噴/鍗曞紩鍙?| JVM | 涓嶅穿锛屽洖閫€榛樿鍊煎苟璁板綍 warning |
| ROB-09 | 璇佷功锛欳A 鐢熸垚/leaf 绛惧彂/閾炬牎楠?SAN/鏈夋晥鏈?骞跺彂绛惧彂缂撳瓨 | JVM | 涓?JDK 鏍￠獙涓€鑷达紱鏃犲唴瀛樻硠婕?|
| ROB-10 | 瑙勫垯鍖归厤琛ㄩ┍鍔細閫氶厤/姝ｅ垯/澶у皬鍐?瓒呴暱鍩熷悕/鐧藉悕鍗曚紭鍏堢骇 | JVM | 鍏ㄩ儴绗﹀悎涓婃父璇箟 |

### 3.2 T-PAR 琛屼负瀵归綈锛圝VM + 妯℃嫙鍣級

| ID | 鐢ㄤ緥 | 鐜 | 閫氳繃鏍囧噯 |
| --- | --- | --- | --- |
| PAR-01 | 鏈湴 fake DoH/DoT/UDP 涓婃父锛歱rovider 閫夋嫨/澶辫触鍒囨崲/瓒呮椂/缂撳瓨 TTL | JVM | 鏌ヨ璧版纭?provider锛涚紦瀛樺懡涓笉閲嶅璇锋眰 |
| PAR-02 | 闃叉薄鏌擄細涓婃父杩斿洖 0.0.0.0/127.0.0.1/宸茬煡鎶曟瘨 IP 鈫?鍒ゅ畾鏃犳晥骞跺皾璇曞叾瀹?provider | JVM | 杩斿洖鍙敤 IP 鎴栨槑纭け璐?|
| PAR-03 | intercepts 鍔ㄤ綔锛欴IRECT/MIRROR(sni/proxy)/REJECT/SYNTHETIC/requestReplace/responseReplace/script/cacheDays/status | JVM | 涓?01 鏂囨。绠楁硶涓€鑷达紱閿欒鍔ㄤ綔瀹夊叏闄嶇骇 |
| PAR-04 | config 瀵煎叆涓婃父 `running.json` 瀛愰泦 + 4 灞傚悎骞?+ 鏈煡瀛楁蹇界暐 | JVM | 瀛楁鏄犲皠姝ｇ‘锛涘啿绐佷紭鍏堢骇姝ｇ‘ |
| PAR-05 | 绔埌绔細鏈湴 HTTP/TLS 娴嬭瘯鏈嶅姟鍣ㄦā鎷熼暅鍍忕洰鏍囷紙鍚?SNI 鎹曡幏锛夛紝楠岃瘉 SNI 浼涓?MITM 鏀瑰啓 | JVM | 涓婃父鏀跺埌鐨?SNI = 閰嶇疆鍊硷紱鏀瑰啓/娉ㄥ叆鐢熸晥 |
| PAR-06 | 妯℃嫙鍣細DNS 鎷︽埅鍏ㄩ摼璺紙娴忚鍣ㄨВ鏋?鈫?tun 鏀跺埌 query 鈫?杩斿洖锛?| 妯℃嫙鍣?| App 鏃ュ織/dumpsys 鏈夎瘉鎹紱瑙ｆ瀽鎴愬姛 |
| PAR-07 | 妯℃嫙鍣細绯荤粺浠ｇ悊娉ㄥ唽锛坄dumpsys connectivity` 鍚?Proxy锛? Chrome 璇锋眰鍑虹幇鍦ㄤ唬鐞嗘棩蹇?| 妯℃嫙鍣?| 涓?03 鏂囨。 E1 鐩稿悓鏍囧噯 |
| PAR-08 | 妯℃嫙鍣細Private DNS/lockdown/鍏朵粬 VPN 妫€娴嬩笌 UI 璀﹀憡 | 妯℃嫙鍣?| 妫€娴嬪噯纭€乁I 鏈夋槑纭彁绀恒€佷笉宕╂簝 |

### 3.3 T-FEA 璇氬疄杈圭晫锛堟枃妗?+ 妯℃嫙鍣?鐪熸満锛?
| ID | 鐢ㄤ緥 | 鐜 | 閫氳繃鏍囧噯 |
| --- | --- | --- | --- |
| FEA-01 | API 26鈥?8锛氭棤绯荤粺浠ｇ悊锛孶I 鏄庣‘鎻愮ず"浠?DNS 妯″紡"锛堟ā鎷熷櫒鍙缓 API28 image 鎴栭潤鎬佸璁★級 | 闈欐€?妯℃嫙鍣?| 鎻愮ず瀛樺湪銆乻etHttpProxy 鏈皟鐢紙API guard锛?|
| FEA-02 | Private DNS=hostname 鏃?App 妫€娴嬪苟鎻愮ず"DNS 鎷︽埅琚粫杩? | 妯℃嫙鍣?鐪熸満 | 妫€娴?鎻愮ず |
| FEA-03 | 闈炰唬鐞?app锛圵ebView 澶栭儴/鍘熺敓 socket锛変笉鍙椾唬鐞嗗奖鍝嶏細README/UI 鏈夎竟鐣岃鏄?| 鏂囨。瀹℃煡 | 鏈夋槑纭竻鍗?|
| FEA-04 | MITM 榛樿鍏抽棴锛涘紑鍚悗 UI 璀﹀憡"澶氭暟 app 涓嶄俊浠荤敤鎴?CA" | 鎴浘/鐪熸満 | 鏈夎鍛娿€侀粯璁ゅ叧闂?|
| FEA-05 | CA 瀹夎鍚庢娴嬮€昏緫锛堣嚜绛?TLS 鎻℃墜锛夛紱鏈畨瑁呮椂鐘舵€佹纭?| Robolectric/妯℃嫙鍣?| 鐘舵€佸噯纭?|
| FEA-06 | 鏃?root 娓呭崟锛?2 搂7锛変笌 App 瀹為檯鑳藉姏涓€鑷达紝鏃犺秺鏉冩壙璇?| 鏂囨。瀹℃煡 | 涓€鑷?|

### 3.4 T-MD3 涓ユ牸 MD3 + 闈炵嚎鎬у姩鐢伙紙闈欐€?+ Robolectric 鎴浘锛?
| ID | 鐢ㄤ緥 | 鐜 | 閫氳繃鏍囧噯 |
| --- | --- | --- | --- |
| MD3-01 | `audit_md3.ps1` PASS锛堟棤 M2 import/纭紪鐮佽壊/LinearEasing/鑷畾涔夊瓧浣擄紱motion token 浣跨敤鈮?锛涘姩鐢烩墺15锛?| 闈欐€?| exit 0 |
| MD3-02 | 姣忎釜灞忓箷 脳 鐘舵€侊紙IDLE/PREPARING/RUNNING/STOPPING/ERROR + 绌烘暟鎹級Robolectric 鎴浘 | Robolectric | 鏃犺鍓?閲嶅彔/瀵规瘮搴﹂棶棰橈紱鎴浘瀛?`docs/qa/screenshots/` |
| MD3-03 | 鐘舵€佸彉鍖栧潎鏈夊姩鐢伙紙寮€濮?鍋滄/鐘舵€佸崱/鏁板瓧/瀵艰埅/鍒楄〃/灞曞紑锛?| 浠ｇ爜瀹℃煡+鎴浘搴忓垪 | 涓?docs/design/03-motion-spec.md 鏄犲皠琛ㄤ竴鑷?|
| MD3-04 | 鏃?M2 瑙嗚鐥曡抗锛圡aterial2 缁勪欢鏍峰紡/鏃?ripple 宸紓锛?| 鎴浘浜哄伐 | 绗﹀悎 MD3 |
| MD3-05 | 娣辫壊/娴呰壊/鍔ㄦ€佽壊涓夋€佹覆鏌?| Robolectric | 鍧囧彲璇汇€佹棤纭紪鐮佽壊寮傚父 |

### 3.5 T-PIPE 涓夌幆澧冩祦姘寸嚎锛堥潤鎬?+ 鎵ц锛?
| ID | 鐢ㄤ緥 | 鐜 | 閫氳繃鏍囧噯 |
| --- | --- | --- | --- |
| PIPE-01 | `_script/pipeline.ps1 dev` 浜у嚭 `out/dev/app-debug.apk` | 宸ヤ綔鍖?| 鏂囦欢瀛樺湪銆乧heck_apk(debug) PASS |
| PIPE-02 | `pipeline.ps1 test` 杩愯 core+app 鍗曟祴+闈欐€佸璁★紱浠讳竴澶辫触鍒欓€€鍑洪潪 0 涓斾笉浜у嚭 prod | 宸ヤ綔鍖?| 璐熷悜楠岃瘉锛氭晠鎰忕牬鍧忎竴涓祴璇?鈫?pipeline 澶辫触 |
| PIPE-03 | `pipeline.ps1 prod` 浜у嚭 `dist/`锛歳elease APK + manifest + sha256锛沝ebuggable=false | 宸ヤ綔鍖?| check_apk(release) PASS锛泂ha256 鍙绠?|
| PIPE-04 | 鏃?git 鎻愪氦/鏍囩鐢?Lead 鎺у埗锛圦A 鍙锛?| 宸ヤ綔鍖?| 涓嶄慨鏀?git |

### 3.6 T-APK 浜や粯瀹屾暣鎬э紙闈欐€侊級

| ID | 鐢ㄤ緥 | 鐜 | 閫氳繃鏍囧噯 |
| --- | --- | --- | --- |
| APK-01 | `check_apk.ps1`锛坉ebug/release 涓ゆ锛塒ASS | 闈欐€?| 鍖呭悕銆佺増鏈€乵inSdk=26銆乼argetSdk鈮?4銆佹潈闄愩€佸惎鍔?Activity |
| APK-02 | 鏃?WRITE_SECURE_SETTINGS 绛夎秺鏉冩潈闄愶紱鏃犲浣欏嵄闄╂潈闄?| 闈欐€?| 閫氳繃 |
| APK-03 | VpnService 澹版槑锛圔IND_VPN_SERVICE + action + FGS type + always-on meta-data锛?| 闈欐€?| aapt2 xmltree 閫氳繃 |
| APK-04 | NSC 鍚?user CA 淇′换 | 闈欐€?| 閫氳繃 |
| APK-05 | ABI 璇存槑锛氱函 JVM 鏃舵棤 native-code锛涘寮曞叆 .so 蹇呴』鍥?ABI 鎴栨槑纭鏄?| 闈欐€?| 閫氳繃 |
| APK-06 | 绛惧悕涓庣増鏈細debug 鍖?debuggable=true锛況elease 鍖?debuggable=false 涓?versionCode/Name 姝ｇ‘ | 闈欐€?| 閫氳繃 |

### 3.7 宕╂簝/ANR/鍐掔儫锛堟ā鎷熷櫒锛?
| ID | 鐢ㄤ緥 | 閫氳繃鏍囧噯 |
| --- | --- | --- |
| SMK-01 | 鍐峰惎鍔?鈫?棣栭〉娓叉煋 <3s锛屾棤 FATAL | logcat 鏃?FATAL/ANR |
| SMK-02 | 鍚姩 VPN锛堢偣鍑?+ 绯荤粺鎺堟潈锛夆啋 RUNNING 鈫?閫氱煡鍑虹幇 | dumpsys + 鎴浘 |
| SMK-03 | 鍋滄 VPN 鈫?缃戠粶鎭㈠锛坄dumpsys connectivity` 鏃?VPN锛?| 閫氳繃 |
| SMK-04 | 瀵艰埅鎵€鏈夐〉闈?杩斿洖/鏃嬭浆/娣辫壊妯″紡 | 鏃犲穿婧?|
| SMK-05 | kill app锛坄adb shell am force-stop`锛夊悗閲嶆柊鍚姩 鈫?鐘舵€佷竴鑷?鍙仮澶?| 鏃犲兊灏哥姸鎬?|

## 4. 鎵ц椤哄簭锛堟椂闂寸洅锛?
1. **P0 闈欐€侀棬绂?*锛圱-MD3 闈欐€併€乀-APK銆乀-PIPE 闈欐€侀儴鍒嗐€乧heck_requirements锛夆€斺€旈殢浠ｇ爜闅忔椂璺戯紙绉掔骇锛夈€?2. **T-ROB 瀵规姉娴嬭瘯**鈥斺€斿厛鍐?codec/proxy/config 鐨?fuzz锛堜笉渚濊禆 engine 瀹屾垚锛夛紝engine 瀹屾垚鍚庤ˉ鐢熷懡鍛ㄦ湡/骞惰仈娴嬭瘯銆?3. **Robolectric 鎴浘**锛圲I 灏辩华鍚庯紝鏈€楂樿瑙変环鍊硷級銆?4. **妯℃嫙鍣?*锛圓PK 鍙瀯寤哄悗锛夛細SMK 鈫?PAR-06/07/08 鈫?T-FEA锛涙埅鍥惧瓨 `docs/qa/screenshots/`銆?5. **Owner 鎵嬫満 M1/M2**锛圥lan A 鏍稿績鍋囪锛夆€斺€旀竻鍗曞凡浜や粯锛岀粨鏋滃洖鏉ョ珛鍗虫洿鏂?05 鎶ュ憡銆?6. 鍥炲綊锛氭瘡杞慨澶嶅悗鎸夌己闄?ID 閲嶈窇鐩稿叧鐢ㄤ緥銆?
## 5. 缂洪櫡鍒嗙骇涓庡鐞?
| 绾у埆 | 瀹氫箟 | 渚嬪瓙 | 澶勭悊 |
| --- | --- | --- | --- |
| S0 | 宕╂簝/鏁版嵁涓㈠け/鍏ㄨ澶囨柇缃?瀹夊叏锛堜唬鐞嗙洃鍚?0.0.0.0銆佺閽ユ硠婕忥級 | FGS 绫诲瀷寮傚父宕╂簝銆乸roxy 姝诲悗鏃犳硶鎭㈠缃戠粶 | 闃诲浜や粯锛孡ead 鍗忚皟 owner锛坅ndroid-dev/proxy-dev锛変慨 |
| S1 | 鏍稿績鍔熻兘澶辨晥/涓ラ噸浣撻獙 | 绯荤粺浠ｇ悊涓嶇敓鏁堛€丏NS 鎷︽埅澶辨晥銆丆A 妫€娴嬫案杩滃け璐?| 蹇呴』淇垨鏄庣‘闄嶇骇鍙ｅ緞 |
| S2 | 鏄庢樉鍔熻兘缂洪櫡 | 鏌愮被瑙勫垯涓嶇敓鏁堛€佺粺璁￠敊璇€佹埅鍥剧己闄?| 淇紙鏃堕棿涓嶅鍒欐枃妗ｈ褰曞凡鐭ラ棶棰橈級 |
| S3 | 杞诲井/鏂囨/瑙嗚 | 闂磋窛銆佹枃妗堥敊鍒瓧 | 璁板綍锛屾椂闂村厑璁镐慨 |

缂洪櫡缁熶竴璁板綍鍦?`docs/qa/05-test-report.md`锛圛D/鐜拌薄/澶嶇幇/鏃ュ織/绾у埆/璐熻矗 workstream/澶嶆祴鐘舵€侊級銆俀A 涓嶇洿鎺ユ敼鐢熶骇浠ｇ爜锛岄€氳繃 Lead 杞氦瀵瑰簲 dev銆?
## 6. 鍏ュ彛/鍑哄彛鍑嗗垯

- **杩涘叆娴嬭瘯**锛歚:app:assembleDebug` 鎴愬姛锛沗:core:test` 鍙繍琛岋紙鑷冲皯閮ㄥ垎锛夈€?- **閫€鍑猴紙鍙氦浠橈級**锛毬? 鍏ㄩ儴 P0 鐢ㄤ緥閫氳繃 + S0/S1 娓呴浂鎴栨槑纭檷绾у彛寰勶紱05 鎶ュ憡鍚瘉鎹紱闈欐€侀棬绂佸叏缁匡紱owner 鎵嬫満 M1/M2 宸插洖鎶ワ紙鎴栨槑纭?鏈獙璇?骞跺啓鍏ヤ氦浠樿鏄庯級銆?- **鍋滄鏉′欢**锛氳 03 搂5 Stop conditions锛圥lan A 琚瘉浼笖鏈惎鐢?fallback銆佸穿婧冧笉鍙閬裤€佹棤娉曟彁渚?E2E 璇佹嵁锛夈€?
## 7. 鎶ュ憡鑺傚

- 姣忓畬鎴愪竴缁勶細鍚?Lead 鍙戠煭娑堟伅锛堢粍 ID + 閫氳繃/澶辫触鏁?+ S0/S1 缂洪櫡锛夈€?- 闈欐€佸伐鍏峰氨缁€佹埅鍥?verdict銆佸鎶楀彂鐜般€佹渶缁堟姤鍛婂洓涓噷绋嬬蹇呮姤銆?- 05 鎶ュ憡闅忔瘡娆¤繍琛屾洿鏂帮紙鍚椂闂存埑/APK sha256/git 鐗堟湰锛夈€?
