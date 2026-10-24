# 05 路 Dev-Sidebike QA 娴嬭瘯鎶ュ憡锛堣凯浠ｆ洿鏂帮級

> 浣滆€咃細challenger锛堢嫭绔?QA锛夈€傛渶杩戞洿鏂帮細2026-10-25 04:28锛圓sia/Shanghai锛夈€?> 琚祴 APK锛歚out/dev/app-debug.apk`锛宻ha256 `4f4b4cbdb1cdcf2359b9193439730a38a3284592c81e7e9a91794dc4fec3c777`锛寁ersion `0.1.0-beta.1-debug`锛実it `cf00d31`锛宐uiltAt 2026-10-25 04:10:40銆?> 鐜锛歐indows 11 + PowerShell 5.1锛涚绾?Android SDK 35锛沨eadless 妯℃嫙鍣?`qa35` = `system-images;android-35;google_apis_playstore;x86_64`锛圵HPX 鍔犻€燂紝1080x1920/420dpi锛孉ndroid 15锛夈€傛湰宸ヤ綔鍖烘棤鐪熸満锛坄adb devices` 浠呮ā鎷熷櫒锛夈€?> 璇佹嵁鐩綍锛歚docs/qa/screenshots/`锛堢湡 PNG锛宑md 浜岃繘鍒堕噸瀹氬悜閲囬泦锛夈€?
## 1. 缁撹鎽樿

| 闂ㄧ | 鐘舵€?| 璇佹嵁/澶囨敞 |
| --- | --- | --- |
| 闈欐€侊細MD3 鍚堣 | 鉁?PASS | `audit_md3.ps1`锛?5 涓?ui .kt锛屽姩鐢?72 澶勶紝M3Motion token 浣跨敤 36 澶?|
| 闈欐€侊細APK 缁撴瀯 | 鉁?PASS | `check_apk.ps1`锛坉ebug锛夛細鍖呭悕/鐗堟湰/minSdk26/targetSdk35/鏉冮檺/launchable/debuggable/VPN 澹版槑/FGS type/always-on meta-data/NSC user CA锛沶ative-code=arm64-v8a+armeabi-v7a锛堣 QA-007锛?|
| 闈欐€侊細浠撳簱/闇€姹?| 馃煛 40/42 | 浠呯己 docs/qa/05-test-report.md锛堟湰鏂囦欢锛変笌 git tag锛圠ead 浜や粯鏃舵墦锛?|
| 妯℃嫙鍣細瀹夎/鍚姩 | 鉁?PASS | `adb install -r -t` Success锛涘喎鍚姩 <5s锛涢椤?瑙勫垯/鏃ュ織/璁剧疆/璇佷功 浜斿睆娓叉煋姝ｅ父 |
| 妯℃嫙鍣細VPN 鍚姩 | 鉁?PASS | 鎺堟潈寮圭獥 鈫?OK 鈫?RUNNING锛涢€氱煡/鐘舵€佹甯革紱`dumpsys connectivity` 瑙?VPN network |
| **E1锛氱郴缁熶唬鐞嗕笅鍙?* | 鉁?**PASS** | VPN network LinkProperties 甯?`HttpProxy: [127.0.0.1] 31181`锛屼笖 VPN 闈為粯璁ょ綉缁滐紙cellular 浠嶉粯璁わ級锛汚pp 缁熻 杩炴帴鎬绘暟 130 |
| **E2锛欴NS 鎷︽埅闂幆** | 鉂?**BLOCKED锛圫0 缂洪櫡 QA-001锛?* | 褰撳墠 APK 鐨?engine 浠嶆槸 `TemporarySystemDnsResolver`锛歚resolveRawQuery` 杩斿洖 unavailable 鈫?tun DNS 鍏ㄩ儴 SERVFAIL 鈫?鍏ㄨ澶?DNS 澶辫触锛坄adb shell ping example.com` = unknown host锛?|
| MITM/CA | 鈴?鏈祴锛堝綋鍓?APK CA 鏈敓鎴愶紱MITM 榛樿鍏筹級 | 寰呮柊 APK + 寮€鍚?MITM 鍚庢祴 |
| 鍚庡彴/always-on/lockdown | 鈴?鏈祴 | 寰呮柊 APK 绋冲畾鍚庢祴 E3/E4 |
| 瀵规姉鍗曟祴锛圦A 鏂板锛?| 馃煛 宸茬紪鍐欙紝闅?pipeline 璺?| `app/src/test/java/dev/sidebike/app/qa/`锛歏pnCodecFuzzTest + ScreenshotQaTest锛圧obolectric 鎴浘锛?|
| 鐪熸満 M1鈥揗6 | 鈴?寰?owner | `_script/qa/device_verify.md` |

**褰撳墠 beta 闃诲椤癸細QA-001锛圖NS 鍏ㄦ柇锛?*銆侲1 鏄噸澶у埄濂斤細Plan A 鐨?鍒嗛毀閬?+ setHttpProxy"鍦ㄧ湡瀹?Android 15 涓婃垚绔嬶紝涓嶉渶瑕?F1 鍐呯疆娴忚鍣ㄥ洖閫€锛堜粛淇濈暀闄嶇骇 UI 浣滀负鐪熸満鍘傚晢宸紓鍏滃簳锛夈€?
## 2. E1 瀹炴祴璇佹嵁锛堜豢鐪熷櫒锛孉PI 35锛?
鍛戒护锛歚adb shell dumpsys connectivity`锛圴PN 娈佃妭閫夛級锛?
```
NetworkAgentInfo{network{102} ni{VPN CONNECTED extra: VPN:dev.sidebike.app.debug}
  lp{{InterfaceName: tun0 LinkAddresses: [ 10.111.0.1/32 ]
      DnsAddresses: [ /10.111.0.2 ] MTU: 1500
      Routes: [ 10.111.0.2/32 -> 0.0.0.0 tun0, ::/0 unreachable,
                10.111.0.1/32 -> 0.0.0.0 tun0 ]
      HttpProxy: [127.0.0.1] 31181 }}
  nc{ Transports: CELLULAR|VPN ... TransportInfo: <VpnTransportInfo{type=1,
      sessionId=Dev-Sidebike, bypassable=false}> Uids: <{0-99999}> OwnerUid: 10209 }}
```

- 榛樿缃戠粶浠嶆槸 `network{101} MOBILE/CELLULAR`锛圴PN 鏈垚涓洪粯璁ょ綉缁滐級鈥斺€?*杩欐鏄?Plan A 鐨勫亣璁惧満鏅?*銆?- 浣?VPN 鐨?LinkProperties 鏄庣‘鎼哄甫 `HttpProxy: [127.0.0.1] 31181` 鈫?绯荤粺宸叉帴鍙?`setHttpProxy`锛屼笖鎸夊畼鏂硅涔夊鎵€鏈?app "鎺ㄨ崘"銆?- App 瀹炴椂缁熻锛堣繍琛?~2 鍒嗛挓锛夛細**杩炴帴鎬绘暟 130銆丏NS 鏌ヨ 72銆佸钩鍧囧欢杩?81ms**锛沄PN 宸茶繛鎺ャ€佷唬鐞?:31181銆丮ITM 宸插叧闂€備唬鐞?accept 璁℃暟澧為暱璇存槑 app 灞傦紙Chrome first-run/GMS/绯荤粺缁勪欢锛夌‘瀹炶繛鎺ヤ簡鏈湴浠ｇ悊銆?- 鎴浘锛歚emulator_home.png`锛圛DLE锛夈€乣emulator_consent.png`锛圴PN 鎺堟潈锛夈€乣emulator_home_after_chrome.png`锛圧UNNING/杩炴帴45锛夈€乣emulator_home_stats.png`锛堣繛鎺?30/DNS72/81ms锛夈€乣emulator_rules.png`銆乣emulator_settings.png`銆乣emulator_certguide.png`銆乣emulator_logs2.png`銆乣emulator_chrome.png`銆?
## 3. 缂洪櫡娓呭崟锛堟寜涓ラ噸搴︼級

### QA-001 銆怱0 路 鍏ㄨ澶?DNS 涓柇銆慹ngine 浣跨敤涓存椂绯荤粺瑙ｆ瀽鍣紝VPN 寮€鍚悗 DNS 鍏ㄩ儴澶辫触
- 鐜拌薄锛歏PN RUNNING 鏃?`adb shell ping example.com` 鈫?`unknown host`锛汚pp 鏃ュ織椤靛叏鏄?  `DEBUG engine.dns resolveRawQuery(47B) unavailable: temporary system resolver active`銆?- 鏍瑰洜锛歚The current APK uses TemporarySystemDnsResolver`锛圖efaultSidebikeEngine 涓?`TODO(integration)`锛夛紝
  `resolveRawQuery` 瀵?tun 璺緞杩斿洖 null 鈫?SidebikeVpnService 鍙兘鍚堟垚 SERVFAIL 鈫?绯荤粺瑙ｆ瀽鍣ㄥ叏閮ㄥけ璐ャ€?- 褰卞搷锛歏PN 寮€鍚?= 鍏ㄨ澶囷紙鎵€鏈?app + shell锛塂NS 澶辫触銆傛瘮"娌″紑"鏇寸碂銆?- 璇佹嵁锛氭埅鍥?`emulator_logs2.png`锛沗DefaultSidebikeEngine.kt` L56 `?: TemporarySystemDnsResolver(...)`銆?- 瑕佹眰锛堜笉鍙?绛?core 瀹屾垚"锛夛細**VpnService 鍦?establish() 鍓嶅繀椤绘牎楠?engine 鐨?DNS 鐪熷疄鍙敤**锛堜緥濡?engine readiness / 涓€娆℃湰鍦?raw-query 鎺㈤拡锛夛紱涓嶅彲鐢ㄥ垯鎷掔粷 establish锛堟垨鍙捣浠ｇ悊涓嶆帴绠?DNS锛孶I 鎶ラ敊锛夆€斺€旈伩鍏?鍚姩鍗冲叏璁惧 DNS 鏂?銆傝繖鏄?fail-safe 闇€姹傦紝搴斿啓鍏ヤ唬鐮佷笌娴嬭瘯銆?- 鐘舵€侊細OPEN锛堥鏈熼殢 core/dns 闆嗘垚 + 鏂?APK 淇锛決A 灏嗗湪鏂?APK 涓婂娴?E2锛?
### QA-002 銆怱1 路 閫氱煡涓嶅彲瑙併€慞OST_NOTIFICATIONS 鏃犺繍琛屾椂璇锋眰
- manifest 澹版槑 `POST_NOTIFICATIONS`锛屼絾鍏ㄥ伐绋嬫棤杩愯鏃惰姹傦紙`grep POST_NOTIFICATIONS app/src` 鍙湁 manifest锛夈€?- 褰卞搷锛欰ndroid 13+ FGS 閫氱煡涓嶈繘閫氱煡鏍忥紙浠呬换鍔＄鐞嗗櫒鍙锛夆啋 鐢ㄦ埛鐪嬩笉鍒?VPN 鐘舵€?鍋滄鍏ュ彛锛沘lways-on 浣撻獙鍙楁崯銆?- 鐘舵€侊細OPEN锛堣浆 android-dev/ui-dev锛?
### QA-003 銆怱2 路 鐗瑰畾鍩熷悕 DNS 瓒呮椂銆戣秴 MTU 搴旂瓟缃?TC=1 浣嗘棤 TCP:53
- `DnsTunHandler.buildReply`锛氬簲绛?> maxDnsPayload 鏃惰繑鍥?TC=1锛泃un 璇诲惊鐜彧澶勭悊 IPv4/UDP锛屾棤 TCP:53 鈫?resolver TCP 閲嶈瘯蹇呯劧瓒呮椂銆?- 锛堟敞锛歚DefaultDnsResolver.kt` 娉ㄩ噴澹扮О"涓嶈缃?TC 浣嶃€佹寜瀹㈡埛绔?EDNS0 瑁佸壀 answer"锛屼絾 **tun 渚?handler 浠嶆槸 TC=1**锛涗袱澶勫疄鐜颁笉涓€鑷达紝闇€缁熶竴銆傦級
- 鐘舵€侊細OPEN锛圠ead 宸茶浆 android-dev锛決A 鍗曟祴 `VpnCodecFuzzTest.oversizedResolverResponseBehaviourIsDocumented` 鍥哄寲褰撳墠琛屼负锛屼慨澶嶅悗闇€鏀规柇瑷€涓?姘镐笉 TC"锛?
### QA-004 銆怱2 路 鍔熻兘鏁版嵁缂哄け銆戣鍒欓〉鏄剧ず 0 涓煙鍚?- 鐜拌薄锛歊ules 椤?鍏?0 涓煙鍚?路 0 鏉¤矾寰勮鍒?路 0 涓洿杩炴斁琛?锛屾悳绱?鍒楄〃涓虹┖銆?- 棰勬湡锛氬簲鍔犺浇鍐呯疆涓婃父瑙勫垯蹇収锛坕ntercepts锛夋垨鑷冲皯榛樿绀轰緥锛涜嫢棣栧惎纭疄涓虹┖锛岄渶 UI/鏂囨。璇存槑銆?- 鐘舵€侊細OPEN锛堥渶纭 bundled rules 鏄惁鍔犺浇澶辫触 / 榛樿 config 鏄惁绌猴級

### QA-005 銆怱3 路 鏂囨/鐘舵€併€慠UNNING 鐘舵€佷粛鏄剧ず"灏氭湭鐢熸垚锛岃鍏堝紑鍚?MITM 骞跺惎鍔ㄦ湇鍔?
- Home RUNNING 鍗＄墖鍐呯殑 CA hint 鍦?CA 鏈敓鎴愭椂鏄剧ず璇ュ彞锛?骞跺惎鍔ㄦ湇鍔?涓庡凡 RUNNING 鐭涚浘锛屾槗璇銆?- 鐘舵€侊細OPEN锛堣浆 ui-dev锛?
### QA-006 銆怱3 路 鍐椾綑鏉冮檺銆慦AKE_LOCK 澹版槑浣嗗叏宸ョ▼鏈娇鐢?- 鐘舵€侊細OPEN锛堝缓璁垹闄わ紝鍑忓皯鏉冮檺闈級

### QA-007 銆怱1 路 浜や粯鍙ｅ緞銆?绾?Kotlin 鈬?涓?ABI 鏃犲叧"鐨勫０鏄庝笌瀹為檯 APK 涓嶇
- 瀹為檯 APK 鍚?native锛歚lib/arm64-v8a/libandroidx.graphics.path.so`銆乣lib/arm64-v8a/libdatastore_shared_counter.so`锛? armeabi-v7a锛夈€?- `native-code: 'arm64-v8a' 'armeabi-v7a'`锛坉ebug锛況elease 鍚岄厤缃級銆倄86/x86_64 璁惧**涓嶈兘瀹夎**锛圛NSTALL_FAILED_NO_MATCHING_ABIS锛夈€傛湰娆℃ā鎷熷櫒涔嬫墍浠ュ畨瑁呮垚鍔燂紝鏄洜涓?google_apis_playstore x86_64 闀滃儚鐨?abilist 鍚?`arm64-v8a`锛堝唴缃?ARM 鍘熺敓妗ワ級銆?- 褰卞搷锛氫笌 02 鏂囨。 搂6/architecture 搂1 "涓?ABI 鏃犲叧銆佸崟 APK 閫氬悆"鐨勫绉板啿绐侊紱浜や粯璇存槑蹇呴』鏀逛负"鏀寔 arm64-v8a / armeabi-v7a锛圓RM锛?锛屾垨 release 淇濈暀鍥?ABI銆?- 鐘舵€侊細OPEN锛圠ead 宸茬煡锛屾鍦ㄩ噸寤?debug 甯?x86_64锛況elease 鍙ｅ緞闇€鍦ㄤ氦浠樻枃妗ｆ敼锛?
### QA-008 銆怱3 路 鐜銆慍hrome 棣栨杩愯鍗″湪 splash锛堟ā鎷熷櫒锛?- 鐜拌薄锛歚am start VIEW https://example.com` 鎵撳紑 Chrome FirstRunActivity锛屽彧鏄剧ず splash/spinner锛堟ā鎷熷櫒鏃?Google 璐﹀彿缃戠粶銆乫irst-run 闇€鑱旂綉锛夈€傞潪 app 缂洪櫡锛汦1 褰掑睘璇佹嵁鏀圭敤浠ｇ悊杩炴帴璁℃暟 + App 鏃ュ織銆?- 鐘舵€侊細DEFERRED锛堟崲鍙帶瀹㈡埛绔?鐪熸満楠岃瘉锛?
## 4. 鎴浘绱㈠紩锛坉ocs/qa/screenshots/锛?
| 鏂囦欢 | 鍐呭 | 缁撹 |
| --- | --- | --- |
| emulator_home.png | 棣栭〉 IDLE | MD3 姝ｅ父锛?灏氭湭鐢熸垚"CA 鎻愮ず鍦?IDLE 涓嬪悎鐞?|
| emulator_consent.png | 绯荤粺 VPN 鎺堟潈寮圭獥 | 姝ｅ父 |
| emulator_home_after_chrome.png | 棣栭〉 RUNNING锛堣繛鎺?45锛?| 鐘舵€?缁熻姝ｅ父锛涙枃妗?QA-005 |
| emulator_home_stats.png | 棣栭〉 RUNNING 缁熻锛堣繛鎺?130/DNS 72/81ms锛?| 姝ｅ父 |
| emulator_logs2.png | 鏃ュ織椤?| **QA-001 璇佹嵁**锛坱emporary resolver锛?|
| emulator_rules.png | 瑙勫垯椤?| 娓叉煋姝ｅ父锛?*QA-004 0 鍩熷悕** |
| emulator_settings.png | 璁剧疆椤碉紙绯荤粺 HTTP 浠ｇ悊/DNS 鎺ョ/DNS 妯″紡锛?| 娓叉煋姝ｅ父 |
| emulator_certguide.png | 璇佷功椤碉紙灏氭湭鐢熸垚 CA锛?| 娓叉煋姝ｅ父 |
| emulator_chrome.png | Chrome first-run splash | QA-008 鐜闂 |

瑙嗚鎬讳綋锛歁D3 閰嶈壊/鎺掔増/瀵艰埅姝ｅ父锛屾棤瑁佸壀/閲嶅彔锛涙繁鑹叉ā寮忔埅鍥惧緟 Robolectric 鎴浘娴嬭瘯浜у嚭锛坄ScreenshotQaTest`锛夈€?
## 4b. 琛ュ厖瀹炴祴锛?026-10-25 04:35锛屽悓涓€鏃?APK锛?
| 鐢ㄤ緥 | 缁撴灉 | 璇佹嵁 |
| --- | --- | --- |
| SMK-03 鍋滄/杩涚▼姝讳骸鍚庣綉缁滄仮澶?| 鉁?PASS | `am force-stop` 鍚?tun0 娑堝け锛沗adb shell ping example.com` 鈫?`104.20.23.154`锛堣В鏋愭仮澶嶏級銆乣github.com` 鈫?`20.205.243.166`锛堢湡瀹?GitHub IP锛?|
| FEA-02 Private DNS 妫€娴?| 鉁?PASS | 璁剧疆 `private_dns_mode=hostname`+`private_dns_specifier=dns.google` 鍚庡惎鍔細UI 鏄剧ず绾㈣壊璀﹀憡"绯荤粺 Private DNS锛圖oT锛夊凡寮€鍚紝浼氱粫杩?VPN 鐨?DNS 鎷︽埅鈥?锛堟埅鍥?`emulator_privdns_running.png`锛夛紱涓旂郴缁熻嚜韬?logcat 鍑虹幇 `NetworkNotificationManager: showNotification ... event=PRIVATE_DNS_BROKEN transport=VPN`锛岃瘉鏄?Private DNS 涓?VPN 鍐茬獊鐪熷疄瀛樺湪 |
| FEA-03 lockdown 妫€娴?| 鉁?PASS | 璁剧疆 `always_on_vpn_lockdown=1` 鍚?UI 鏄剧ず绾㈣壊璀﹀憡"妫€娴嬪埌銆屽缁堝紑鍚?VPN + 闃绘鏃?VPN 杩炴帴銆嶏紝涓?DNS-only 鍒嗛毀閬撴ā寮忓啿绐佲€?锛堝悓涓€鎴浘锛夛紱娉ㄦ剰锛氬綋鍓嶅疄鐜版槸"璀﹀憡浣嗕粛鍚姩"锛岄渶 android-dev 鍐冲畾鏄惁鏀逛负"鎷掔粷鍚姩"锛堟垜寤鸿鎷掔粷锛屽惁鍒欑敤鎴峰紑 lockdown 鍗冲叏璁惧鏂綉锛?|
| QA-002 閫氱煡涓嶅彲瑙侊紙鍙栬瘉锛?| 鉁?CONFIRMED | `dumpsys package`锛歚POST_NOTIFICATIONS: granted=false`锛沗dumpsys notification`锛歚AppSettings: dev.sidebike.app.debug importance=NONE` 鈫?Android 13+ 閫氱煡鏍忕湅涓嶅埌 VPN 閫氱煡/鍋滄鍏ュ彛 |
| 娣辫壊涓婚 5 灞忔埅鍥?| 鉁?宸查噰闆?| `emulator_dark_{home_running,rules,settings,certguide,logs}.png` |
| 鍋?VPN 鍚?DNS 鎭㈠锛堟甯稿仠姝㈣矾寰勶級 | 鉁?楠岃瘉 | 绗簩娆″仠姝㈠悗 `ping example.com` 瑙ｆ瀽鎴愬姛锛圛CMP 100% loss 灞炴ā鎷熷櫒鐜姝ｅ父鐜拌薄锛孌NS 宸叉仮澶嶏級 |
| QA-009 stats 瀛楄妭璁℃暟 | 馃煛 缂洪櫡 | RUNNING 鏃?涓婅/涓嬭娴侀噺=0 B"浣嗚繛鎺ユ暟 130/DNS 72 鍦ㄦ定 鈫?proxy 瀛楄妭鏁版湭姹囨€昏繘 EngineStats锛堣浆 proxy-dev/engine锛?|

**閫氱煡妯箙/鐜璀﹀憡 UI 瑙嗚缁撹**锛氫袱鏉¤鍛?banner锛圥rivate DNS / lockdown锛夐厤鑹层€佸浘鏍囥€佹枃妗堝潎姝ｇ‘锛屾棤閲嶅彔/瑁佸壀锛涚鍚?MD3銆俁UNNING 鍗＄墖涓?灏氭湭鐢熸垚锛岃鍏堝紑鍚?MITM 骞跺惎鍔ㄦ湇鍔?鍦?RUNNING 鏃朵粛鏄剧ず锛岀‘璁や负 QA-005锛圫3 鏂囨锛夈€?
## 4c. 鎴浘娓呭崟锛?3 寮狅紝docs/qa/screenshots/锛?
- 鐘舵€佹祦锛歚emulator_home.png`(IDLE)銆乣emulator_consent.png`/`emulator_consent2.png`(VPN鎺堟潈)銆乣emulator_home_after_chrome.png`(RUNNING 杩炴帴45)銆乣emulator_home_stats.png`(杩炴帴130/DNS72/81ms)銆乣emulator_after_stop.png`銆乣emulator_after_forcestop.png`銆乣emulator_idle_after_stop.png`
- 椤甸潰锛歚emulator_rules.png`銆乣emulator_settings.png`銆乣emulator_certguide.png`銆乣emulator_logs2.png`(涓存椂瑙ｆ瀽鍣ㄦ棩蹇楄瘉鎹?銆乣emulator_current.png`
- 鐜璀﹀憡锛歚emulator_privdns_running.png`銆乣emulator_privdns_idle.png`
- 娣辫壊锛歚emulator_dark_home_running.png`銆乣emulator_dark_rules.png`銆乣emulator_dark_settings.png`銆乣emulator_dark_certguide.png`銆乣emulator_dark_logs.png`
- 鐜锛歚emulator_chrome.png`(Chrome first-run splash锛孮A-008)

## 5. 寰呮墽琛岋紙鏂?APK 鍒拌揪鍚庯級

1. 閲嶈鏂?debug APK锛圠ead 閲嶅缓 x86_64+arm锛屽惈鐪熷疄 DefaultDnsResolver + DNS readiness fail-safe锛沜onfig seeding 淇 QA-004锛夛紝澶嶆祴 **E2**锛?   - (a) `adb shell ping -c1 example.com` 瑙ｆ瀽鍑虹湡瀹?IP锛?   - (b) App DNS 鏌ヨ鏁板闀夸笖鏃ュ織鏃?`resolveRawQuery unavailable`锛?   - (c) `adb shell ping github.com` 杩斿洖鍚堢悊鏈薄鏌?IP锛堝鐓у綋鍓嶆棤 VPN 鍩虹嚎 `20.205.243.166`锛夛紱
   - (d) Private DNS=hostname 鏃剁孩鑹茶鍛婁粛鍑虹幇锛堝凡 PASS锛屽洖褰掑嵆鍙級銆?2. **E1 褰掑睘鍔犲己**锛欰pp 鏃ュ織椤靛嚭鐜板叿浣?`CONNECT host:443` 璁板綍锛堜唬鐞嗘棩蹇楋級锛岀‘璁?Chrome/OkHttp 璇锋眰琚唬鐞嗐€?3. **SMK-03/SMK-05** 宸?PASS锛堣 搂4b锛夛紝鏂?APK 鍥炲綊銆?4. **E3**锛歅OST_NOTIFICATIONS 淇鍚庨€氱煡鍙鎬э紱always-on 寮€鏈鸿矾寰勶紱lockdown 鏀逛负鎷掔粷鍚姩鍚庣殑琛屼负銆?5. **E5**锛歬ill proxy 鏁呴殰娉ㄥ叆锛堣嫢 android-dev 鏆撮湶 debug hook锛夆啋 鏂█ watchdog 鍋?VPN + 缃戠粶鎭㈠銆?6. **CA/MITM**锛氱敓鎴?CA 鈫?`KeyChain.createInstallIntent` 鈫?瀹夎 鈫?Chrome MITM锛堝彲閫夛紝P1锛夈€?7. Robolectric 鎴浘锛?2 寮狅細5 灞?脳 鐘舵€?+ 娣辫壊锛夎惤鐩樺苟浜哄伐澶嶆牳锛堣澶囨埅鍥惧凡瑕嗙洊澶ч儴鍒嗭紝Robolectric 鐢ㄤ簬鑷姩鍖栧洖褰掞級銆?8. **QA 鍗曟祴**锛歜uild 閿侀噴鏀惧悗璺?`:app:testDebugUnitTest --tests "dev.sidebike.app.qa.*"`锛汿C 淇鍚庢妸 `oversizedResolverResponseBehaviourIsDocumented` 鏂█鏀逛负"姘镐笉缃?TC"銆?9. 璺?`_script/qa/run_qa_tests.ps1 -Apk out/dev/app-debug.apk` 涓?release APK gate锛坃script/qa/run_qa_tests.ps1 -Release锛夈€?10. 鏇存柊鏈姤鍛?+ 鍚?Lead 鎶ラ噷绋嬬銆?
## 6. 楠屾敹鐭╅樀锛圥0 gate 褰撳墠鐘舵€侊紝闅忔柊 APK 鏇存柊锛?
| Gate | 鍐呭 | 鐘舵€?| 璇佹嵁 |
| --- | --- | --- | --- |
| G1 | 鏋勫缓/鍗曟祴 | 馃煛 閮ㄥ垎 | `:app:compileDebugKotlin` 鎴愬姛锛決A 鍗曟祴寰呴攣閲婃斁锛沜ore 闆嗘垚涓?|
| G2 | 瀹夎/鍚姩/浜斿睆 | 鉁?PASS | 妯℃嫙鍣ㄥ畨瑁呮垚鍔熴€佸喎鍚姩銆佷簲灞忔埅鍥?|
| G3 | VPN 鍚姩/閫氱煡 | 馃煛 閮ㄥ垎 | VPN RUNNING + dumpsys 姝ｇ‘锛涢€氱煡涓嶅彲瑙侊紙QA-002锛?|
| G4 | 绯荤粺浠ｇ悊闂幆 | 鉁?PASS锛堟ā鎷熷櫒锛?| dumpsys HttpProxy + 杩炴帴鏁?130锛汣hrome 鍏蜂綋 CONNECT 褰掑睘寰呮棩蹇楅〉琛ュ厖 |
| G5 | DNS 闂幆 | 鉂?FAIL锛圦A-001锛?| 涓存椂瑙ｆ瀽鍣?鈫?鍏ㄨ澶?DNS 鏂紱寰呮柊 APK |
| G6 | 鍋滄/澶辫触瀹夊叏 | 鉁?PASS | 鍋滄 + force-stop 鍚庣綉缁?DNS 鎭㈠锛沰ill-proxy 鏁呴殰娉ㄥ叆寰呭仛 |
| G7 | FGS 鍚堣 | 馃煛 閮ㄥ垎 | specialUse/always-on meta-data 闈欐€?PASS锛涢€氱煡鏉冮檺缂哄け锛圦A-002锛?|
| G8 | 杈圭晫璇氬疄 | 鉁?PASS | Private DNS/lockdown banner 姝ｇ‘锛沀I 鏄庣ず"浠呴伒寰郴缁熶唬鐞嗙殑搴旂敤"锛涙枃妗?02/03 瀹屾暣 |
| G9 | MITM锛堥粯璁ゅ叧锛?| 鈴?鏈祴 | 寰?CA 鐢熸垚/瀹夎 |
| G10 | 鍚庡彴瀛樻椿 | 鈴?鏈祴 | 寰?always-on/閿佸睆闀挎祴 |
| G11 | 鎬ц兘 | 馃煛 閮ㄥ垎 | 鍚姩 <5s锛汥NS 骞冲潎寤惰繜 81ms锛堝け璐ヨ矾寰勶紝涓嶅彲鐢級锛涚┖闂叉棤鏄庢樉寮傚父 |
| G12 | 闈欐€佸悎瑙?| 鉁?PASS | audit_md3 PASS锛沜heck_apk PASS锛沜heck_requirements 40/42锛堝墿 git tag锛?|

## 4d. 工具/基础设施问题记录（非产品缺陷，影响 QA 执行）

| ID | 问题 | 状态 |
| --- | --- | --- |
| TOOL-01 | `_script/gradle.ps1` 全局命名 Mutex 在前一持有者被 kill 后进入 abandoned 状态，`WaitOne` 抛 `AbandonedMutexException` 且不 Release → 所有 agent 构建永久失败 | ✅ Lead 已修复（catch AbandonedMutexException 视为 acquired；test task 10 分钟超时） |
| TOOL-02 | `app/build.gradle.kts` 的 `it.timeout.set(java.time.Duration...)` 在 Kotlin DSL 中 `java` 被解析为 JavaPluginExtension → 脚本编译失败（瞬态，Lead 已改为 `import java.time.Duration`） | ✅ 已修复 |
| TOOL-03 | E5 kill-proxy 故障注入钩子 `am start-foreground-service ... VPN_DEBUG_FAULT` 因 service `exported=false` 被拒（adb shell uid 2000 无法访问；`run-as` 同样失败） | ⏳ 待 android-dev 增加 debug-only exported 入口（或改用 broadcast receiver）后重测 |
| TOOL-04 | 模拟器 x86_64 google_apis_playstore 镜像 abilist 含 arm64-v8a（ARM 原生桥），故 ARM-only APK 也可安装；但 UI/性能与真机有差异，最终仍需 owner 真机 M1/M2 | 已知限制 |


## 7. E2 复测结果（DNS-capable APK，2026-10-25 04:58）

**判定：✅ PASS**（APK sha256 feefac97… / git dbb23d3 / 4 ABI）

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| (a) 域名解析 | ✅ | `adb shell ping -c1 example.com` → `104.20.23.154` |
| (b) DNS 拦截/统计 | ✅ | App 统计 DNS 查询=8（增长）；日志页无 "temporary resolver"/SERVFAIL/`resolveRawQuery unavailable` |
| (c) 防污染 | ✅ | `ping github.com` → **20.200.245.247**（真实 GitHub 段；无 VPN 基线 20.205.243.166）；`ping www.google.com` → **142.251.189.206**（真实 Google IP，非投毒） |
| (d) 规则种子 | ✅ | Rules 页 `共 69 个域名 · 77 条路径规则`（QA-004 修复） |
| (e) 代理全链路归属 | ✅ | App 日志：`proxy WARN domain firebaseinstallations.googleapis.com has a sni rewrite rule but MITM is disabled; falling back to a direct tunnel` —— 第三方 app 请求进入本地代理并被规则处理 |
| 系统代理注册 | ✅ | `dumpsys connectivity`：VPN network `HttpProxy: [127.0.0.1] 31181` + `DnsAddresses [10.111.0.2]` + tun0 |
| 状态文案 | ✅ | 首页 RUNNING 显示"DNS 防污染已生效 · 系统代理已注册 · MITM 已关闭"（QA-005 修复） |
| 通知权限 | ✅ | 新 APK 首启弹系统权限弹窗；`pm grant POST_NOTIFICATIONS` 后 `granted=true`（QA-002 修复） |
| SMK-03 停止恢复 | ✅ | 停止后 tun0 消失、VPN network=0、`ping example.com` 通过普通 DNS 解析成功 |

截图：`docs/qa/screenshots/e2_home_running.png`、`e2_home_stats.png`（连接62/DNS8）、`e2_rules.png`（69域名/77规则）、`e2_logs.png`、`e2_settings_running.png`、`e2_certguide_running.png`、`e2_stopped_final.png`。

仍存问题（不阻塞 E2）：QA-003（TC=1；大应答场景）、QA-009（上行/下行流量仍 0 B）、S3 `忽略 tun 数据包: not-ipv4-or-malformed` 偶发 debug 日志。

## 8. 验收矩阵最终状态（截至 04:58）

| Gate | 状态 | 证据 |
| --- | --- | --- |
| G1 构建/单测 | 🟡 | 编译通过；core 13 个 MITM/CA 测试失败（proxy-dev 修复中）；QA fuzz 测试已修测试 bug 待复跑 |
| G2 安装/启动/五屏 | ✅ PASS | 模拟器安装、启动、五屏+深色截图 |
| G3 VPN 启动/通知 | ✅ PASS | RUNNING/通知权限已授予（修复后）；通知可见性待截图确认 |
| G4 系统代理闭环 | ✅ PASS | dumpsys HttpProxy + 连接数 62 + SNI 规则日志 |
| G5 DNS 闭环 | ✅ PASS | 见 §7 |
| G6 停止/失败安全 | 🟡 PASS | 停止/force-stop 恢复网络 ✅；kill-proxy 故障注入 BLOCKED-BY-TOOLING（等 debug receiver） |
| G7 FGS 合规 | ✅ PASS | specialUse/always-on meta-data 静态 + 实跑无异常 |
| G8 边界诚实 | ✅ PASS | Private DNS/lockdown banner、UI 明示代理生效范围、02/03 文档 |
| G9 MITM（默认关） | ⏸ 未测 | 待 TC 修复后或时间允许做 CA/MITM |
| G10 后台存活 | ⏸ 未测 | 待 always-on/锁屏长测（模拟器） |
| G11 性能 | 🟡 | 冷启首次 ~15s（模拟器首启/dex），温启 <1s；DNS 解析正常；空闲 CPU 待测 |
| G12 静态合规 | ✅ PASS | audit_md3 PASS；check_apk PASS；check_requirements 45/46（仅 git tag） |
