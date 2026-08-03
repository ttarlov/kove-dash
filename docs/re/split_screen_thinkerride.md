# ThinkerRide → Dash: Split-Screen / Display-Mode Control
Date: 2026-05-29
App: oversea.whbluestar.thinkerride

## Bottom line

**The "nav widget" and "split-screen mode" are NOT distinct concepts at the wire-protocol level — and "split-screen" is not phone-controlled at all.** Exhaustive enumeration of every JsonManager builder (~110 functions), every msg_id (1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 21, 22, 23, 24, 25, 26, 27, 50, 54), every `func` literal (NAVI, ROAD_NAVI, INSIDENAVI, AUDIO, PAIR, OTA, USER, COREDUMP, COMBO, GPS, RIDE, KEY, MUSIC, ADAS, UPDATE, AUTOOFF, SCREEN, BT_KEY, CAR_INFO, hanjd_test, SIM, TIRE, THEME, TBOX, SCPT, TUC), and every inbound dispatch in `BleConnectWrapper.handleMessage` confirms: **there is no field, no message, no `act`, and no `item` value anywhere in the ThinkerRide wire protocol that controls the dash's split-screen vs full-screen layout.** The dash itself owns the rendering-mode decision — `<REPO>/HANDOFF.md:358,416` and `<REPO>/phase2/_re_report_simple_navi.md:1-12,39,55-61` already established this by an independent sweep of all three apps (ThinkerRide / GreenTrip / Eryanet-Kove); this fresh search confirms it for ThinkerRide. The prior "first nav frame = widget appears" claim in `nav_widget_thinkerride.md` is **correct as far as the phone is concerned**; what makes the widget appear in a split-screen layout vs a full-screen layout is a dash-side rendering decision driven by rider gesture on the dash itself, not by any phone push. The phone unconditionally streams structured nav data — the dash chooses how to render it.

## Mode-control message(s) found

**None.** No message at any msg_id in the outbound JsonManager catalog (`<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/manager/JsonManager.java:92-2690`) carries a layout/screen/widget-mode field. The closest semantic candidates and their actual semantics:

- `msg_id=27 func=SCREEN act=screenchoose` — `JsonManager.sendScreenSaver:2003-2016`. Selects which screen-saver image is active. Unrelated to runtime split-screen.
- `msg_id=27 func=THEME` — `JsonManager.sendThemeStatus:2129-2146, 2631-2646; sendThemeTask:2133-2146`. Theme-bundle installer protocol (file transfer of dash theme assets via `MsgManager.theme:549-619`). Unrelated to runtime layout.
- `themeMode` field inside `msg_id=27 func=INSIDENAVI CMD=inside_naviinfo` — `JsonManager.java:1478` and `DeviceMsgFactory.java:226`. Source: `InsideNaviInfo.getThemeMode()`. Used only when the dash runs its own INSIDENAVI engine (which is a different mode from Mapbox-driven turn-by-turn); even there it's a day/night theme flag passed at session start, not a runtime split-screen toggle. [inferred — consistent name pattern with `getDayAndNightMode` at PageAction.java:64]
- `msg_id=27 func=SCREEN act={file_start,file_trans,file_status}` — `BleConnectWrapper.java:1401-1447`. Screen-saver file transfer (dash → phone request for screensaver assets via `ScreenSaverHelper`). Not layout.

## Phone-side state flags around nav

Three flags exist; none gate or influence dash layout:

- `GlobalData.isInNavigating` — boolean. Set by `setInNavigating` at `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/constants/GlobalData.java:1051-1059`. Only effect is adding/removing a phone-side signal listener; **no wire push**.
- `SQNavigationManage.getPageAction().isInNaving()` — boolean. Builder-side gate at `JsonManager.java:1410,2659` — early-returns null if false.
- `GlobalData.setLocalNaving(boolean)` — sets via `MsgManager.insideNavi:168` when dash sends `navi_status`. Side effect at `GlobalData.java:1061-1063`: starts/stops `AsrService` for offline-mode voice. **No wire push, no layout effect.**

No `isInSplit`, `isInWidget`, `isInPanel`, `isInScreenMode`, `setNavMode`, or similar flag exists anywhere in `GlobalData.java`, `SQNavigationManage.java`, `PageAction.java`, or `BleDeviceData.java`. Searched and confirmed absent.

The version-string parser (`BleDeviceData.parseVersion:466-501`) extracts only `UC, VE, MO, PL, FL, DA, XV, TV, SV, OV, TUC, CV` — no layout-mode capability flag.

## Settings UI surface

**No user-facing toggle for "split-screen nav" or "dash widget mode" exists in the ThinkerRide app.** Enumerated every Activity/Fragment under `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/ft/settings/` (SettingsActivity, NotifyActivity, UnitSettingActivity, VoiceAssistantActivity, SecurityWarningSettingActivity, AppNotifyActivity, AppManagerActivity, AboutActivity, ProfileActivity, FeedbackActivity, AccountSecurityActivity, SkillCenterFragment) and under `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/ft/home/device/` (DeviceSettingActivity, DeviceBaseSetActivity) — none surface a split-screen or dash-layout toggle. The settings that DO exist and send wire messages:

- Language: `msg_id=25 msg_type=13` (`JsonManager.sendSetLanguage:2018-2031`)
- Unit (metric/imperial): `msg_id=25 msg_type=14` (`sendSetUnit:2033-2049`)
- Time format: `msg_id=25 msg_type=1 control_info=N time=M` (`sendTimeFunction:2148-2165`)
- Mirror status: `msg_id=25 msg_type=24` (`setMirrorStatus:2454`)
- Record status / time: `msg_id=25 msg_type=22,20` (`setRecordStatus:2472, setRecordTime:2487`)
- Screen-saver selection: `msg_id=27 func=SCREEN act=screenchoose` (`sendScreenSaver:2003`)
- Theme install task: `msg_id=27 func=THEME` (`sendThemeStatus, sendThemeTask`)

None of these toggle a split-screen layout. Theme-mall (`ft/theme/ui/`) is a marketplace for swappable dash-firmware UI bundles — install-time changes the dash skin, not runtime layout mode.

## Dash → phone control messages

The dash sends limited control messages back. None imply dash-driven layout-mode reporting:

| msg_id | item / func / msg_type | Meaning | File:line |
|---|---|---|---|
| 10 | item=0..14,53 | various device telemetry/status updates | BleConnectWrapper.java:885-1126 |
| 25 | msg_type=1 control_info=N msg_source=1 | rider pressed dash button (1=start ride, 2=pause, 3=end ride, 4=lap) | BleConnectWrapper.java:1128-1186 |
| 25 | msg_type=2 msg_source=1 | rider stopped navigation on dash | BleConnectWrapper.java:1187-1207 |
| 25 | msg_type=14,15,18,20,22,24,25 | unit/orientation/language/time/dvr-status/lamp updates | BleConnectWrapper.java:1208-1264 |
| 27 | func=INSIDENAVI navi_status / voice_status | dash-internal nav started/stopped, TTS mute changed | MsgManager.insideNavi:164-195 |
| 27 | func=ROAD_NAVI act=file_start/trans/status | dash requesting raster nav tiles | BleConnectWrapper.java:1367-1400 |
| 27 | func=SCREEN act=file_start/trans/status | dash requesting screen-saver tiles | BleConnectWrapper.java:1401-1447 |
| 27 | func=THEME act=1..6 | theme installer state machine | MsgManager.theme:549-619 |

**No inbound `func=LAYOUT`, `func=DISPLAY`, `func=PANEL`, `func=WIDGET`, `func=NAVI` (NAVI is outbound-only), no inbound `MODE`/`SCREEN_MODE`/`VIEW_MODE` field on any msg_id.** The closest "layout" signal is `msg_id=25 msg_type=15 start=N` (an OritationEvent at `BleConnectWrapper.java:1214-1219`) — that's screen-rotation orientation, not split-screen.

The dash NEVER sends an inbound "start sending nav" or "stop sending nav" or "I'm in split mode now" message. The closest is `msg_id=25 msg_type=2 msg_source=1` — rider cancelled navigation, phone responds with `endNavi`.

## What we searched and did NOT find

Searched the entire `com/whbluestar/thinkride/` and `com/thinkerride/oversea/` trees:

- English JSON-key literals: `screen_mode`, `display_mode`, `view_mode`, `ui_mode`, `layout`, `panel`, `split`, `split_screen`, `dual`, `dual_screen`, `half`, `halfscreen`, `secondary`, `widget_mode`, `theme_mode`, `style`, `page`, `tab`, `card`, `dashboard_style`, `nav_mode`, `nav_page`, `screen_layout`, `meter_layout`, `cluster_mode`, `info_panel`, `instrument_mode`, `isInSplit`, `isInWidget`, `isInPanel`, `naviWidget`, `widget_show`, `hide_widget`, `showNavi`, `hideNavi` — **zero protocol hits**. (Only Android view-system tags or layout XML refs match the regex; no JSON-wire usage.)
- camelCase: `themeMode` (sole hit: INSIDENAVI session bootstrap only, not runtime layout), `screenMode`, `displayMode`, `viewMode`, `layoutMode`, `panelMode`, `widgetMode`, `cardMode`, `navMode`, `navPage`, `screenLayout`, `clusterMode`, `infoPanel`, `dashboardStyle` — **zero protocol hits except themeMode**.
- Chinese: 分屏 (split-screen), 双屏 (dual-screen), 横屏 (landscape), 全屏 (full-screen), 布局 (layout), 视图 (view), 风格 (style), 简单导航, 简易导航, 半屏, 小窗, 小屏, 路口放大, 当前模式, 导航模式, 投屏模式, 路口大图, 裸屏, 简洁导航, 普通导航, 全屏导航, 全屏投屏 — **all absent** (only Chinese log strings like 切换语言/添加地图视图 appear, none in the protocol layer). Prior `_re_report_simple_navi.md:39` confirms the same negative across all three apps.
- All inbound dispatch keys in `MsgManager.java:197-287` — no `LAYOUT/PANEL/MODE/SPLIT/VIEW/WIDGET` func handler exists.
- All msg_id integer literals 1..54 across JsonManager — no builder produces a layout-toggle JSON shape.
- `SelfNaviListener.onNaviStart` (`SelfNaviListener.java:100-120`) and `GlobalData.AnonymousClass4.onStartNavi` (`GlobalData.java:1733-1752`): **confirmed no preceding wire push before the first nav-info frame.** `onNaviStart` only flips `setInNavigating(true)` and posts EventBus events. `onStartNavi` calls `sendHideLaneInfo(z2)` — but `OverseaNavigation.sendHideLaneInfo:521-523` and `DefaultNavigationImpl.sendHideLaneInfo:241-243` both **return null** (no message ever sent). `ProjectionMessage(0)` is purely an in-app event-bus message, never serialized to wire. So the original `nav_widget_thinkerride.md` conclusion stands: **the first valid `msg_id=27 act=3` (or `msg_id=1`) is the only thing the dash sees at the start of a nav session.**
- BleConnectWrapper bootstrap (`BleConnectWrapper.java:922-1023`, item=6 firmware-version handshake): sends `sendLocation, sendCurrentDateTime, sendLinkInfo, requestProductType, queryHasAdasFunctionToCar, queryDeviceFunctionCompatibilityInfo, checkVehicleCurStatus` — **none carry a layout/widget-mode hint to the dash.** The dash doesn't even learn whether the phone has nav capability at handshake time; it discovers it implicitly when nav messages start arriving.

## Open questions

1. **Is the dash's split-screen vs full-screen layout truly fully autonomous to the dash, with the phone having zero awareness?** Code says yes (HANDOFF.md:358,416 already concluded this for all three apps; this report confirms it for ThinkerRide). The only experiment that would falsify: capture BLE traffic during a manual rider-gesture between full-screen and split-screen on the dash. If a fresh outbound or inbound message appears at that exact moment, code missed it. [inferred high confidence the experiment will show nothing.]
2. **Does the dash use the presence of `msg_id=4` cross bitmaps to switch into a junction-overlay layout that LOOKS like split-screen?** `msg_id=4` shows a junction-enlargement PNG and could be the visual mechanism by which the screen appears "split" — left side dash data, right side enlarged turn arrow — when the rider's view is full-screen-with-overlay. Experiment: send only `msg_id=27 NAVI act=3` (no msg_id=4) and watch the layout; then add msg_id=4 bursts and watch again. If split-screen only appears when msg_id=4 is active, the "split" is actually the junction-enlargement overlay, not a separate mode.
3. **Does `themeMode` in INSIDENAVI's bootstrap (`JsonManager.java:1478`) affect layout?** It's documented as day/night, but the value space wasn't enumerated in `InsideNaviInfo`. Experiment: send `inside_naviinfo` with `themeMode=0,1,2,...` and look for a layout change. Low-priority because INSIDENAVI requires dash-side offline-map capability which the KY800X may not have.
