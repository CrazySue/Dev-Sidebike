# 05 路 Dev-Sidebike QA 娴嬭瘯鎶ュ憡锛堣凯浠ｆ洿鏂帮級

> 浣滆€咃細challenger锛堢嫭绔?QA锛夈€傛渶杩戞洿鏂帮細2026-10-25 04:28锛圓sia/Shanghai锛夈€?> 琚祴 APK锛歚out/dev/app-debug.apk`锛宻ha256 `4f4b4cbdb1cdcf2359b9193439730a38a3284592c81e7e9a91794dc4fec3c777`锛寁ersion `0.1.0-alpha-debug`锛実it `cf00d31`锛宐uiltAt 2026-10-25 04:10:40銆?> 鐜锛歐indows 11 + PowerShell 5.1锛涚绾?Android SDK 35锛沨eadless 妯℃嫙鍣?`qa35` = `system-images;android-35;google_apis_playstore;x86_64`锛圵HPX 鍔犻€燂紝1080x1920/420dpi锛孉ndroid 15锛夈€傛湰宸ヤ綔鍖烘棤鐪熸満锛坄adb devices` 浠呮ā鎷熷櫒锛夈€?> 璇佹嵁鐩綍锛歚docs/qa/screenshots/`锛堢湡 PNG锛宑md 浜岃繘鍒堕噸瀹氬悜閲囬泦锛夈€?
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

## 9. E5 故障注入 / 单测结果（最新 APK sha 304985… / git 1259ff8，2026-10-25 05:00）

### E5 / E5b（debug FaultInjectionReceiver）
| 用例 | 结果 | 证据 |
| --- | --- | --- |
| E5 KILL_PROXY | ✅ PASS | 故障前 VPN+DNS 正常（github 20.200.245.247）→ 广播后 engine/proxy 停止 → **tun0 消失、VPN 计数 0、普通 DNS 恢复**（example.com 172.66.147.243）；UI 进 ERROR，`lastError=本地代理已停止响应，已自动关闭 VPN 以恢复网络`（DUMP_STATE）；截图 `e5_killproxy_ui.png`；**重启 VPN 后恢复正常** |
| E5b CRASH_ENGINE | ✅ PASS | 同样自动 teardown + 网络恢复 |
| 配置落地（DUMP_STATE） | ✅ | interceptsDomains=69、interceptPaths=77、whiteList=5、preSetIpDomains=61、excludeIpList=152、dnsProviders=9、dnsMapping=25、domesticAllowList=1237、proxyPort=31181 |
| 代理流量证据 | ✅ | 日志：`clientservices.googleapis.com`/`www.googleapis.com` 等 SNI rewrite 规则命中（MITM 关闭 fallback direct） |

### QA 单测（app/src/test/java/dev/sidebike/app/qa/）
`VpnCodecFuzzTest` 8/8 PASS：5000 随机字节解析不抛异常；3000 随机包 classify 不抛；任意 0–600B payload UDP 往返 + IP 校验和；非 DNS/IPv6/截断包被忽略；合法 query → SERVFAIL 应答（校验和/端口/ID/RCODE）；resolver 响应 ID 被改写；**oversized 响应裁剪且 TC 永远为 0**（QA-003 修复的回归断言）。

### TC=1 修复确认（QA-003 → FIXED）
源码 `UdpDnsCodec` 已改为：EDNS0 UDP payload clamp 到 1200 → `trimResponseToFit` 保留 question+能放下的 answer 记录、TC=0（beta 无 TCP:53）。QA 测试已同步为 never-TC 断言。

### 已知问题（不阻塞 alpha 交付，记录并转 owner）
| ID | 级别 | 内容 | Owner |
| --- | --- | --- | --- |
| QA-009 | S2 | 统计"上行/下行流量"始终 0 B（连接数/DNS 计数正常） | proxy-dev/engine |
| QA-010 | S3 | tun 偶发 `忽略 tun 数据包: not-ipv4-or-malformed`（debug 日志有预算，不影响 DNS）；建议加按 reason 的计数器便于真机诊断 | android-dev |
| QA-008 | S3 | Chrome first-run 在模拟器卡 splash（环境限制，非 app 缺陷） | — |

## 10. 验收矩阵更新（05:00）

| Gate | 状态 | 证据 |
| --- | --- | --- |
| G1 构建/单测 | 🟡 | app 编译+QA fuzz 8/8 PASS；core 13 MITM/CA 测试 proxy-dev 修复中；UI 测试 ui-dev 修复中 |
| G2 安装/启动/五屏 | ✅ | 模拟器 + 截图 |
| G3 VPN/通知 | ✅ | 通知权限授予；VPN RUNNING/STOP/ERROR 状态机验证 |
| G4 系统代理 | ✅ | dumpsys HttpProxy + 连接数 + 规则命中日志 |
| G5 DNS 闭环 | ✅ | 解析/防污染/规则种子 |
| G6 失败安全 | ✅ | stop/force-stop/KILL_PROXY/CRASH_ENGINE 全部恢复网络+自动 teardown+可重启 |
| G7 FGS 合规 | ✅ | static + 实跑 |
| G8 边界诚实 | ✅ | banners/文档/UI |
| G9 MITM | ⏸ | 未测（默认关闭；可选增强） |
| G10 后台存活 | ⏸ | 待 always-on/锁屏长测 |
| G11 性能 | 🟡 | 首次冷启 ~15s（模拟器首启），温启 <1s；DNS 正常；空闲 CPU 待测 |
| G12 静态合规 | ✅ | audit_md3/check_apk PASS；check_requirements 45/46（git tag 由 Lead 交付时打） |

## 11. Robolectric 截图测试策略（2026-10-25 05:10）

`ScreenshotQaTest` / `ScreenshotQaTestDark`（app/src/test/java/dev/sidebike/app/qa/）已改为 **opt-in**：
- 默认 `testDebugUnitTest` 中通过 `Assume.assumeTrue` **SKIP**（Windows 上 Robolectric NATIVE-graphics 的 `captureToImage()` 会因无限动画 idling 抛 `ComposeTimeoutException`，不能污染默认套件）；
- 需要时运行 `_script/qa/run_screenshots.ps1`（设置 `DSH_QA_SCREENSHOTS=1` + `--no-daemon`，让 test worker 继承环境变量）；dark 变体的 qualifier 顺序 bug（`night` 需在 density 前）已修；
- alpha 验收的 UI 截图**以真机（模拟器 Android 15）证据为准**：`docs/qa/screenshots/` 共 37+ 张真 PNG（五屏 × IDLE/RUNNING/ERROR/环境警告 × 浅/深色 + E1/E2/E5 证据），不依赖 Robolectric 截图。

QA 包当前测试：`VpnCodecFuzzTest` 8 个用例（默认套件中运行，全 PASS）+ 2 个 opt-in 采集方法（默认 SKIP）。

## 12. 工具链事件：app 测试挂起（2026-10-25 05:06–05:15）

- 现象：一个无过滤的 `:app:testDebugUnitTest` run（PID 24152，05:06:09 启动）持续 >8 分钟不退出，并占住全局构建锁；同时 2 个等待构建锁的 job。此前 pipeline 的 app 测试 gate 也表现为 "77 tests, 0 failures 但 task FAILED"（部分结果 + 挂起/崩溃）。
- 根因（QA 侧已修复）：`ScreenshotQaTest` 在 Windows Robolectric NATIVE-graphics 下 `captureToImage()` 等 idle 超时（ComposeTimeoutException），dark 变体另有非法 qualifier 顺序。两者**已改为 opt-in（`Assume` + `DSH_QA_SCREENSHOTS=1`）**，默认套件 SKIP；`_script/qa/run_screenshots.ps1` 手动开启。
- 时序注意：挂起的 run 在修复写入前启动，跑的是旧测试代码，因此会复现该挂起；修复后的默认套件应全绿（待 Lead 重跑确认）。
- 结论：非产品缺陷；默认测试套件必须保持确定性，重 Robolectric 截图/长动画测试一律 opt-in 或隔离任务。

## 13. QA 交付物清单（dist 可直接引用）

| 交付物 | 路径 | 说明 |
| --- | --- | --- |
| QA 测试计划 | `docs/qa/04-test-plan.md` | 风险驱动、对应用户 6 项要求、缺陷分级、入口/出口 |
| QA 测试报告（本文件） | `docs/qa/05-test-report.md` | 验收矩阵 + 证据 + 已知问题 + 工具链事件 |
| 真机/模拟器证据截图 | `docs/qa/screenshots/`（37+ 张真 PNG） | 五屏 × 状态 × 浅/深色 + E1/E2/E5/SMK |
| 模拟器 smoke 证据 | `docs/qa/evidence/` | 自动脚本日志/截图 |
| 静态门禁 | `_script/qa/audit_md3.ps1` / `check_apk.ps1` / `check_requirements.ps1` / `run_qa_tests.ps1` | 全部 exit-code 门禁，PS5.1 + UTF-8 BOM |
| 对抗测试 | `app/src/test/java/dev/sidebike/app/qa/VpnCodecFuzzTest.kt` | 8 用例，默认套件全绿 |
| 模拟器工具 | `_script/qa/setup_emulator.ps1`、`launch_emulator.cmd`、`emulator_smoke.ps1` | 离线安装/启动/自动 smoke |
| owner 真机清单 | `_script/qa/device_verify.md` | M1–M6（系统代理/DNS/Private DNS/lockdown/CA/MIUI 存活） |

## 14. app 测试套件对账（05:09:45 产物）

| 套件 | 结果 | 说明 |
| --- | --- | --- |
| `qa.ScreenshotQaTest` / `ScreenshotQaTestDark` | **skipped**（opt-in 生效） | 默认套件不再因 Robolectric 截图超时污染 |
| `qa.VpnCodecFuzzTest` | 8/8 PASS | 对抗 fuzz/裁剪/never-TC |
| ui.*（其余套件） | 169 PASS 左右 | — |
| `ui.NavigationTest` | **5 FAIL** | assertExists / "Failed to inject touch input"（动画/idling 干扰） |
| `ui.SettingsScreenTest.proxyPortDialogSavesValidPort` | **1 FAIL** | `AppNotIdleException: Compose did not get idle after 24258 attempts in 60 SECONDS` → 疑似无限组合/动画循环（Settings 端口 Dialog），转 ui-dev |
| 合计 | 179 tests / 6 failures / 2 skipped | 归 ui-dev 修复后才能绿 app gate（G1） |

这些失败为测试环境/UI 动画问题，非 VPN/DNS/代理功能缺陷；功能验收（E1/E2/E5/SMK）不受影响。

## 15. 自动化 smoke 与交付门禁工具（2026-10-25 05:20）

- `_script/qa/emulator_smoke.ps1`：一条命令完成 install → 启动 → 授权 → E1（系统代理注册）→ E2（DNS 解析）→ Rules 种子数 → 停止恢复 → 截图/日志落盘 `docs/qa/evidence/`。最近一次运行结果：`vpn-established / E1-system-proxy-registered / E2-vpn-dns-configured / E2-dns-resolution(example.com=172.66.147.243, github.com=20.200.245.247) / E2d-rules-seeded / SMK-03` 均 PASS（stop 检查已改为最多 20s 轮询消除异步 teardown 竞态）。
- `_script/qa/check_delivery.ps1`（新增）：dist 交付物门禁——release/debug APK 存在、SHA256SUMS.txt 哈希逐项核对、delivery-manifest.json sha256/version 一致性、README/TEST-REPORT 存在、release APK 调 `check_apk -Release`（debuggable=false/权限/NSC/VPN 声明/版本/ABI）。
- `_script/qa/run_qa_tests.ps1`：静态 gate 编排放（requirements + md3；可 `-WithUnitTests -Apk ... -Release`）。

## 16. 自动化 smoke 全绿记录（05:15 运行）

`_script/qa/emulator_smoke.ps1 -Apk out/dev/app-debug.apk -SkipInstall` → **9/9 PASS, 0 failed**：
install → app-launch（COLD 12.4s，模拟器）→ vpn-established（VPN CONNECTED + tun0）→ E1-system-proxy-registered（`HttpProxy: [127.0.0.1] 31181`）→ E2-vpn-dns-configured（10.111.0.2）→ E2-dns-resolution（example.com=104.20.23.154；github.com=20.200.245.247）→ E2d-rules-seeded → SMK-03-stop-recovers（tun0 20s 轮询消失）→ SMK-03-dns-after-stop（解析恢复）。
证据：`docs/qa/evidence/smoke-20261025-051519.log` + 5 张 PNG。该脚本可作为 owner/未来回归的标准验收入口。

## 17. 最终 Release 验收（2026-10-25 05:27，dist 产物）

> **注（最终构建）**：以下哈希为 QA 门禁时的构建快照；最终交付哈希以仓库 `dist/SHA256SUMS.txt`、`dist/delivery-manifest.json` 与 GitHub Release Assets 为准。最终构建已重新执行 `pipeline.ps1 all`，`check_delivery.ps1` **PASS**（结构/权限/ABI/NSC/debuggable=false 结论不变，功能代码未变）。

### 交付物门禁（check_delivery.ps1 → PASS）
| 项 | 结果 |
| --- | --- |
| release APK | `dist/Dev-Sidebike-0.1.0-alpha-release.apk`（14.67 MB，sha256 `cdbf5a022c6269647af6a2217784658058029a3b3c2ef5d9927065a77f359108`） |
| debug APK | `dist/Dev-Sidebike-0.1.0-alpha-debug.apk`（21.13 MB，sha256 `f8127d78c950dee5949f5ed5a8bd202f1bea4914f6209a1d242cd26dc4b889b1`） |
| SHA256SUMS.txt | 两条记录逐一复核 **一致** |
| delivery-manifest.json | version=0.1.0-alpha、gitSha=**09e3696**、sha256 两个 APK 均 **一致** |
| README.md / TEST-REPORT.md | 存在且非空 |
| check_apk -Release | **PASS**：package=dev.sidebike.app（无 debug 后缀）、versionCode=1、versionName=0.1.0-alpha、debuggable=**false**、minSdk=26、targetSdk=35、native-code=**arm64-v8a+armeabi-v7a**（符合 ARM-only 交付口径）、INTERNET/FOREGROUND_SERVICE/POST_NOTIFICATIONS/RECEIVE_BOOT_COMPLETED、无 WRITE_SECURE_SETTINGS、BIND_VPN_SERVICE+VpnService action+foregroundServiceType(specialUse)+always-on meta-data+NSC trust user CA |

### Release APK 真机（模拟器 API35）验证
- 安装/启动/通知权限/VPN 授权：✅（release 包首次安装弹 VPN 授权，OK 后 RUNNING；`pm grant POST_NOTIFICATIONS` 后通知 channel importance=DEFAULT）
- E1：`dumpsys connectivity` VPN LinkProperties **`HttpProxy: [127.0.0.1] 31181`** + `DnsAddresses [10.111.0.2]` ✅
- E2：`ping github.com` → **20.200.245.247**（真实 IP，防污染）✅
- 代理功能实测：`nc 127.0.0.1 31181` 发 `GET http://example.com/` → **HTTP/1.1 200 OK**；`CONNECT example.com:443` → **200 Connection Established** ✅
- **QA-009（bytes=0）→ 已修复/关闭**：release 包统计显示 **上行流量 621 B / 下行流量 2.3 KB**（连接总数 24、DNS 5）✅
- 停止服务：tun0 消失、DNS 恢复正常（example.com 解析成功）✅
- 证据：`docs/qa/evidence/release_dumpsys-connectivity.txt`、`docs/qa/screenshots/release_{running,stats,stopped}.png`

### 最终验收矩阵（G1–G12）
| Gate | 状态 | 证据 |
| --- | --- | --- |
| G1 构建/单测/流水线 | ✅ **PASS** | pipeline all：core 174/0/1 skipped；app 181/0/2 skipped；静态 gates 全绿 |
| G2 安装/启动/五屏 | ✅ PASS | 模拟器 + release 包安装启动；真 PNG 截图 38+ |
| G3 VPN/通知 | ✅ PASS | RUNNING/ERROR/停止状态机；权限齐全；通知可见（importance=DEFAULT） |
| G4 系统代理闭环 | ✅ PASS | release dumpsys HttpProxy + 代理真实请求 200 + 连接计数 |
| G5 DNS 闭环 | ✅ PASS | 解析/防污染（20.200.245.247）/Private DNS 警告 |
| G6 失败安全 | ✅ PASS | stop/force-stop/KILL_PROXY/CRASH_ENGINE 全部自动 teardown + 网络恢复 + 可重启 |
| G7 FGS 合规 | ✅ PASS | specialUse + always-on meta-data + 实跑无异常 |
| G8 边界诚实 | ✅ PASS | 02/03/README 口径：ARM-only、MITM 默认关、API29+ 系统代理、MIUI 引导 |
| G9 MITM/CA | ⚠️ 未实测（默认关闭，非 alpha 阻塞） | CA/MITM 代码与单测在 core（MITM/CA 20 tests PASS）；真机 CA 安装+Chrome MITM 属 owner/后续 |
| G10 后台存活 | 🟡 模拟器 PASS；真机 M5 待 owner | 前台服务+always-on 静态/模拟器验证；MIUI/HyperOS 需真机复验 |
| G11 性能 | ✅ PASS（模拟器） | release 冷启 ~4.7s、debug 冷启 ~12.4s（首启 dex）；DNS 解析正常；空闲无异常 |
| G12 静态合规 | ✅ PASS | audit_md3 PASS；check_apk PASS；requirements 46/46；build-info/sha256 一致 |

### 已知问题（随交付说明，均非阻塞）
1. QA-010（S3）：tun 偶发 `忽略 tun 数据包: not-ipv4-or-malformed` debug 日志；不影响 DNS/代理，建议后续加 reason 计数器。
2. MITM/CA 未做真机端到端（默认关闭；Chrome 信任用户 CA 有源码证据，但未在真机走完安装+拦截流程）。
3. MIUI/HyperOS 后台存活、Private DNS/lockdown 真实系统组合、系统代理在真实运营商网络下的行为 → 需 owner 按 `_script/qa/device_verify.md` M1–M6 复验。
4. API 26–28 设备无系统 HTTP 代理能力（仅 DNS 模式），UI 已提示；交付说明已注明。

### 最终 QA 结论
**Dev-Sidebike 0.1.0-alpha 判定：QA 有条件通过（release gate PASS）。** 在 Android 15 (API 35) 模拟器上，交付 APK 的安装/启动/VPN 授权/前台服务/DNS 防污染/系统 HTTP 代理下发/规则种子(69 域名/77 路径)/代理真实请求(HTTP 200 + CONNECT 200)/统计字节/DNS fail-safe/停止与故障恢复全部通过，静态门禁与三环境 pipeline 全绿，dist 产物哈希/manifest 一致可交付。剩余风险均为"环境/真机差异"类（MIUI 保活、Private DNS/lockdown 组合、MITM 默认关闭），已写入交付说明与 owner 手机 M1–M6 清单；**不阻塞 alpha 交付，但 owner 首次真机验收应优先跑 M1（系统代理是否生效）与 M5（MIUI 存活）**。

### 门禁工具修正（05:29）
`run_qa_tests.ps1 -Release` 曾把 `-ExpectNoNative` 传给 `check_apk`（与"alpha release 故意 ARM-only"的交付口径冲突）。已修正：新增 `check_apk -ExpectArmOnly`，release 模式下断言 native-code 恰为 `arm64-v8a + armeabi-v7a`（多余/缺失都失败）。最终运行：`run_qa_tests.ps1 -Apk dist/...release.apk -Release` → **requirements PASS / md3-audit PASS / apk-check PASS / ALL QA GATES PASS**。

## 18. core 对抗测试补充（T7 deliverable #3 补齐，2026-10-25 05:33）

新增 `core/src/test/kotlin/dev/sidebike/core/qa/CoreAdversarialQaTest.kt`（9 用例，hermetic、无外网、~6s）：
| 用例 | 覆盖 | 结果 |
| --- | --- | --- |
| httpRequestHeadFuzzOnlyThrowsMalformedInputErrors | 4000 组随机字节解析 HTTP head，只允许 Malformed/EOF/IO 异常（防 OOB/NPE/StackOverflow） | PASS |
| oversizedHeaderBlockIsRejected | >64KB header block 必须拒绝 | PASS |
| obsoleteHeaderFoldingIsRejected | 过时 header 折行拒绝（请求走私防护） | PASS |
| badContentLengthIsRejected | 非法 Content-Length 拒绝 | PASS |
| connectTargetsAreParsedWithoutCrashing | CONNECT 目标（IPv6/超长/空格/Unicode）不崩、ASCII 原样保留 | PASS |
| json5LenientFuzzOnlyThrowsParseErrors | 2000 组随机 JSON5 片段，仅允许解析异常 | PASS |
| upstreamConfigImportMalformedInputsDoNotCrash | 空/非对象/错类型/200KB 字符串/JSON5 注释单引号等畸形上游配置导入不崩 | PASS |
| ruleEngineWeirdHostsNeverThrow | 空/空白/超长/Unicode/带空格 host 的 plan/match/whitelist/domestic 不抛异常 | PASS |
| invalidRegexAndWeirdInterceptPatternsDoNotCrashUpdate | 非法正则/怪 pattern intercepts 配置 updateConfig 不抛 PatternSyntaxException | PASS |

运行：`powershell -NoProfile -File _script\gradle.ps1 :core:test --tests "dev.sidebike.core.qa.*"` → **BUILD SUCCESSFUL，9/9 PASS**。
（至此 T7 deliverable #3 的 app 层 `VpnCodecFuzzTest` 8/8 + core 层 `CoreAdversarialQaTest` 9/9 均绿；release dist 产物未受影响。）
