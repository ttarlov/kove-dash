# KOVE → Dash: Split-Screen / Display-Mode Control
Date: 2026-05-29
App: com.eryanet.gkove (KOVE_v1.0.10260420)

## Bottom line

**Split-screen is NOT a separately controlled dash mode in the KOVE protocol — it is implicit in the nav lifecycle.** There is no display-mode toggle, no pre-arming message, no settings sync, no widget-on/widget-off discriminator on the wire. The "split-screen" layout the rider sees on the dash is one of two things: (a) **a pure dash-firmware reaction to the `{"fun":"navi","type":"0"}` arm message** — the dash decides locally to repartition its screen the moment it receives the first navi push, and tears the partition down on `type:"2"`; or (b) **a phone-rendered composite frame** that the dash blits whole, because for the Google Maps path the Presentation is composed phone-side at the dash-dictated resolution and pushed as TCP 11111 video (the dash never "knows" it's split — it's just a video frame). Either way, the **only knob the phone has is the `navi` lifecycle**; everything else is dash-internal. No `screen_mode` / `layout` / `panel` / `widget_mode` / `display_mode` / `split` / `dual` / `cluster` / `instrument` / `view_mode` keyword has any wire presence in `com.eryanet.ite`.

## Mode-control message(s) found

**None.** Exhaustive enumeration of every `"fun"` value emitted by `com.eryanet.ite` to a dash-facing port (BLE `aaa6`, TCP 11113, TCP 11112):

| `fun` | Purpose | Carries layout/mode field? |
|---|---|---|
| `navi` | TBT push (start/update/end) | No — just 5 nav fields per prior report |
| `time` | Clock sync | No |
| `phone` | Caller name/number | No |
| `switch` | declared in `BleSwitchBean` but **DEAD CODE** — no producer | n/a |
| `upgrade` | "phone needs to update" notice | No |
| `activate` | Phone→dash activation handshake | No layout flags |
| `recorder`, `recorderUI`, `otaRecord` | Drive-recorder control | No |
| `theme` | Wallpaper/skin file transfer over TCP 11112, chunked binary | **No.** Despite the name, `CarThemeData` (`com/eryanet/ite/theme/bean/CarThemeData.java:9-55`) carries `{kid, index, sum, totalSize, md5}` — file metadata for streaming an image blob to the dash. Not a layout-mode discriminator. |
| `ota` | OTA progress | No |
| `message` (`MessageBean`) | Generic Android notification mirror | No |
| `heart` | TCP 11113 heartbeat | No |

`BleSwitchBean` (`com/eryanet/ite/ble/BleSwitchBean.java:4-23`) is the only object with a name that even hints at "mode toggle". It has `fun="switch"`, a single `type` string, and a `handleSwitch` EventBus consumer that forwards it to BLE `aaa6` (`LinkFragment.java:4214-4218`). **No producer anywhere in the codebase** — grep for `new BleSwitchBean` / `BleSwitchBean(` returns only the class definition itself. It is dead infrastructure, likely vestigial from an earlier protocol revision.

## NaviConfig flag inventory

All public static fields on `com/eryanet/ite/navi/util/NaviConfig.java`, with the actual gating effect. None of these controls dash-side split-screen layout; they all gate phone-side rendering routes or transport selection.

| Field | Declared | Writer(s) | What it gates |
|---|---|---|---|
| `NaviType` | `:15`, default `0` | `CheckManager.java:108-112` (set from cloud `mapSource`: 4→1, else→0) | `0` = Google Maps SDK path (`GPSGuidePresentation_GoogleNavi`), `1` = Mapbox path (`GPSGuidePresentation_Box`). Selects nav engine, not dash mode |
| `isTBTOpen` | `:58`, default `false` | **Never written `true`** | If `true`, routes nav JSON through TCP 11113 instead of BLE `aaa6` (`LinkFragment.java:4193`). Dead branch |
| `isJPEG` | `:41`, default `false` | `LinkFragment.java:872` (true when `bitrate==420|422`); cleared at `:2813` | When `true`, suppresses nav JSON push (`GPSGuidePresentation_Box.java:419` guard). JPEG fallback mode for dashes that can't decode H.264 |
| `isThird` | `:60`, default `false` | `LockNaviBroadcastReciver.java:16` (intent extra `"isThird"`) | If `true`, nav JSON goes to localhost YKSDK (127.0.0.1:18080) instead of dash. Phone-as-passthrough mode |
| `isSDK` | `:54`, default `false` | `LockNaviBroadcastReciver.java:8` (intent extra `"isSDK"`); cleared at `LinkFragment.java:2902,2969` | YiKu third-party SDK integration mode. Gates Wi-Fi/permission flows |
| `isHeart` | `:40`, default `false` | (searched — no writes found in `com.eryanet.ite`) | Probably dead |
| `isP2PConnect` | (instance field on LinkFragment, not NaviConfig) `LinkFragment.java:100,500,3510,3913` | `true` when Wi-Fi Direct active | Routes nav over TCP-only; skips BLE |
| `isJPEGConnect` | instance field `LinkFragment.java:95,3514,3863` | | JPEG fallback mode tracker |
| `isNavi`, `isNaviP`, `isNaviShow`, `isMarker`, `isLocation`, `isLinkF`, `isPShow`, `isMapNight`, `isSearchOffline`, `isNeedFollow`, `isNeedHandle`, `isNeedNavi`, `isSetDestination`, `haveConnected`, `isWXLocation`, `isWXLogin`, `isFirstGetUi`, `isFirstUploadConnectLocation`, `isFirstUploadPhoneLocation`, `isAlreadyGetGoogleStatus`, `isEcard`, `isEcardRunning`, `mMainisResume`, `isRequestSDKXuanPermission`, `isSendHeart`, `isTeamGroup` | various | various | All phone-side UI/lifecycle flags. None affects what is pushed to the dash |
| `curLandWH` (string, e.g. `"640*480"`, `"480*560"`, `"448*720"`, `"800*352"`) | `:28` | `ScreenRecorder.java:286` from `recorderWidth*recorderHeight` (dash-dictated via `RecordParams` mirror reply) | Picks the right per-dash UI layout JSON keyed `<WxH>_<activeKey>` from SharedPrefs (`GPSGuidePresentation_Box.java:559`, `NaviPresentation_GoogleMap.java:915`). **This is the per-dash layout selector — but it selects PHONE-SIDE layout for the phone's Presentation surface, not a dash mode** |
| `mapType`, `myAltitude`, `mySpeed`, `myBrearing`, `SDKLatitude/Longitude/PoiName/UUID`, `carWidth/carHeight`, `carDeviceName`, `carAppId`, `CarAppid`, `deviceId`, `MapboxPubKey`, `GOOGLEMAP_KEY`, `myUUID`, `myDeviceID`, `myQRCode`, `customDayNight`, `jpegZipRate` | various | various | Config data only |

**No `NaviConfig.isSplit`, `isWidget`, `isPanel`, `isDual`, `isHalfScreen`, or similar field exists.** No public static field on `NaviConfig` controls dash-side split-screen.

The instance fields `carThemeKid` and `carThemeType` (`:74-75`) come from a **dash→phone** message on TCP 11112 (`LinkFragment.java:2109-2112`, dash returns `{"fun":"theme","type":<int>,"kid":<str>}`). These tell the phone what wallpaper theme the dash is currently using (so the phone's "change theme" UI can preselect it). Not a layout-mode discriminator.

## Settings UI surface

No user-facing toggle in the KOVE app for "show nav on dash", "split-screen", "widget mode", or "instrument layout". Surveyed:
- `com/eryanet/ite/mine/activity/SettingActivity.java` — account/log/about settings only.
- `com/eryanet/ite/fragment/` (link/mine/travel/band/active/yt) — no display-mode toggle.
- `com/eryanet/ite/fragment/link/bean/SettingBean.java` — this `SettingBean` is for Android battery-optimization / Doze permission warnings fetched from cloud at `RequestManager.java:55-71` (URL `URLConstants.LING_QI_MOTO_ROUTE_URL`). Carries `{settingsId, settings, name}` — completely unrelated to dash UI.
- `com/eryanet/ite/theme/CarThemeActivity.java` — wallpaper/skin picker; sends a binary `theme` file via TCP 11112, not a layout-mode signal.
- `com/eryanet/ite/mine/activity/LabActivity.java`, `CustomActivity.java` — searched, no nav-display toggle.

The closest surface is the **map source / `NaviType` selection** (Google vs Mapbox) — and even that is **not user-controllable**. It comes from the Eryanet cloud `activate/code/motor/iteration` response field `mapSource` (`CheckManager.java:108-112`).

## Dash → phone control messages

Re-examined `ClientDataHandler.java:104-115`. The complete catalog of dash-originated control messages on TCP 11113:

| Inbound | Effect | Citation |
|---|---|---|
| `{"heart": ...}` | Heartbeat. If misses, disconnect | `:92-103` |
| `{"naviControl":"exitNavi"}` | Phone tears down nav (calls `exitNaviEYLink` → dismisses Presentation) | `:106-107` |
| `{"naviControl":"showFirst"}` | **Empty handler** — falls through `else if (!"showFirst".equals(...))`; net effect is nothing happens | `:108` |
| `{"naviControl":"exitConnect"}` | Phone disconnects YiKu session | `:108-111` |

The `showFirst` branch reads literally: `else if (!"showFirst".equals(strOptString) && "exitConnect".equals(strOptString))`. The `!showFirst` clause is logically equivalent to "and `showFirst` is NOT this value, AND it is exitConnect" — i.e. `showFirst` is silently consumed with no action. **No dash-originated layout/mode notification exists.** Dash never tells the phone "I am ready in split-screen mode" or "I have switched display modes" or anything analogous.

The `LinkFragment.handleJson(String)` at `:1883-1888` is still undecompiled by jadx (940 instruction units, "Method dump skipped"). It is theoretically possible (low probability) that it parses an inbound layout/mode notification not visible here. This was an open question in the prior report and remains so. To recover: `jadx --show-bad-code` or baksmali.

## What we searched and did NOT find

Negative results — these keywords returned **zero hits** in `com/eryanet/ite/`:

- `"screen_mode"`, `"display_mode"`, `"view_mode"`, `"ui_mode"`, `"layout"` (as JSON key), `"panel"` (as JSON key, except `flexPanelN` UI bean class names which are phone-side layout data structures), `"split"`, `"split_screen"`, `"dual"`, `"dual_screen"`, `"half"`, `"secondary"`, `"widget_mode"`, `"theme_mode"`, `"page"`, `"tab"`, `"card"`, `"dashboard_style"`, `"nav_mode"`, `"nav_page"`, `"screen_layout"`, `"meter_layout"`, `"cluster_mode"`, `"info_panel"`, `"instrument_mode"`, `"navOn"`, `"navOff"`, `"showNavi"`, `"isWidget"`, `"carWidget"`.
- Chinese: 分屏, 双屏, 横屏 — zero hits anywhere in `com.eryanet.ite`. 切换/布局/主题/视图/风格/全屏 — only in log strings, comments, and the wallpaper-theme subsystem; no JSON-key usage.
- `new BleSwitchBean`, `BleSwitchBean(` — only the class declaration; **never instantiated, never posted to EventBus**, so `handleSwitch` at `LinkFragment.java:4214-4218` is unreachable.
- `setFun("switch")` and any other `setFun` to a layout-related value — zero hits.

Searched but matched only unrelated UI:
- `"layout"` — only Android view-tag and `R.id.layout_*` references on phone side.
- `flexPanelN` (1-11) classes (`com/eryanet/ite/navi/bean/uiBean/`) are phone-side parsed JSON that describes the **phone's Presentation overlay layout** at each dash resolution (`uiJson` loaded from SharedPrefs at `NaviPresentation_GoogleMap.java:915`, key `<curLandWH>_<activeKey>`). The dash sees the rendered video, not these structures.

## The "first navi push = widget appears" claim — stress-tested

Traced `Main2Activity.startNavi(NaviEndInfo)` (`:2559-2592`) fully:

1. `:2563-2565` or `:2572-2574`: build `BleNaviBean(type="0")`, EventBus.post → `LinkFragment.handleNaviInfo` (`:4186-4204`) → BLE `aaa6` write (default path). **Nothing else sent first.**
2. `:2566-2567` or `:2577-2584`: instantiate `GPSGuidePresentation_Box(activity, display).show()` (or Google equivalent on API>30). The Presentation `onCreate`/show path runs internal Mapbox setup and then **emits a second `type:"0"`** at `GPSGuidePresentation_Box.java:1581-1583` — also just a `BleNaviBean` with `type="0"`, no extra fields.
3. No `BleSwitchBean`, `BleNeedUpgrade`, `theme`, `recorderUI`, or any other layout-priming message is sent between `startNavi` and the first `type:"1"` update.
4. Searched `Main2Activity.java` for any send-to-dash call (`packages.offer`, `sendJsonData`, `sendTcpMessage`, `EventBus.getDefault().post(new Ble*`) in proximity to `startNavi` — only `BleNaviBean` and `BlePhoneBean` (caller name, unrelated to nav layout) are posted in the file.

**Confirmed: no pre-arming or layout-priming message exists in the KOVE codebase.** The dash decides its own split-screen layout in response to receiving a `navi` `type:"0"` (or in response to a video frame arriving from a paired phone whose Presentation overlay contains nav widgets — in the Google path).

## Open questions

1. **`LinkFragment.handleJson(String)` is undecompiled** (`:1883-1888`, 940 instructions). It is the inbound `fun`-dispatcher on TCP 11113. A theoretical dash→phone "layout/mode" notification (e.g. `{"fun":"display","mode":"split"}`) could be parsed there and we'd not see it. Probability is low: every other observed inbound goes through `ClientDataHandler.handleMessage(msg.what==5)` (parsed naviControl/heart) or surfaces in the partially-traced `receiveJson → handleJson` chain whose consumer classes we have read. Experiment to resolve: `jadx --show-bad-code` against `classes3.dex` for `LinkFragment.handleJson`, or capture BLE/TCP traffic during a real KOVE session and look for unknown `"fun"` values.

2. **Empirical: does our 2022 dash actually split-screen on receiving `EY <jsonLen_be:4> {"fun":"navi","type":"0"}` over BLE `aaa6`?** PROTOCOL.md flags that our SV=3.0.4 firmware speaks the OLD SiQi family (`0xEE 0xFD` envelope), not Eryanet. If the dash doesn't parse `EY`, it certainly doesn't switch modes. Experiment: BLE-write `45 59 00 00 00 0D 7B 22 66 75 6E 22 3A 22 6E 61 76 69 22 7D` to `aaa6` on our dash and watch for any split-screen transition. Same experiment with SiQi's `01 01 …` envelope would also be informative.
