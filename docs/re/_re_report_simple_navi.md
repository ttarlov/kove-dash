# Simple Navigation Mode — RE Findings

## TL;DR — which hypothesis won (H1/H2/H3) and why

**H1 wins, decisively. There is no second projection stream and no mode-toggle signaling between phone and dash.** The phone does not know — and does not need to know — whether the rider is in "Phone Screen Navigation" or "Simple Navigation" mode. The phone unconditionally pushes a fat catalog of structured BLE/WiFi-JSON messages (msg_id=1, 11, 25/*, 27 func=NAVI/ROAD_NAVI/INSIDENAVI, etc.) whenever the data changes, gated only by per-dash capability flags (`DeviceFunction.getX()`) and by `isInNaving()` for nav-specific fields. The dash's role is to (a) optionally dial the phone's 15456 server-socket if it wants the H.264 stream (Phone Screen Navi), or (b) stay off 15456 and render its own native UI from the structured data it's already receiving (Simple Navi).

Evidence:

1. **No second port, no second handshake.** Only 17818/15456/15457/18888/19000 exist; only one 69-byte handshake using ASCII "android" with byte[0]=0; only one set of resolutions (1280×640 default, 800×800/800×640/480×800 per `screenShape`, sized to dash-hardware not mode). `ProjectionEncoder.sendVideoSize()` is the only emitter; alt-prefix strings ("androidSimple", "androidSmall", "androidMini") don't exist in the codebase.
2. **No inbound `NAVI`-mode `func` from dash.** The full dash→phone func dispatcher (`MsgManager.java:197-287` and `BleConnectWrapper.handleMessage` 860-1414) handles AUTOOFF, ADAS, AUDIO, BT_KEY, COMBO, SCPT, COREDUMP, INSIDENAVI, LED, MOTOR_SIGNAL, MUSIC, OTA, PAIR, ROAD_NAVI, SCREEN, SIM, TTS, THEME, TIRE, TUC, UPDATE, UPDATE_BT, USER, TBOX — no `NAVI` inbound, no `MODE`, no `SCREENMODE`. Same exact list in `cn_thinkerride/defpackage/jb1.java:622-702`. Same in green_trip.
3. **`ProjectionWrapper.isConnected()` is only checked in 4 places, all inside the projection encoder/service.** Zero overlay messages in `JsonManager.java` or `GlobalData.java` gate on projection-connection state.
4. **The `isBluetoothFullScreenNav` field is a per-dash CAPABILITY (1=yes-supports-projection, 0=no), not a runtime mode flag.** Defined in `DeviceFunction.java:144,457,882` and `cn_thinkerride/.../DeviceFunction.java:125,426,827`; never read by any gating code — it's a forward-compat / DB-only field.

H2 (same stream, dash crops) ruled out: only one stream resolution per dash hardware shape, no cropping/scaling subregion logic visible.
H3 (smaller stream/different port) ruled out: no second port, no second handshake.

## 1. Alternate channels / ports / resolutions search — results

**Ports (exhaustive list across all three trees):**

- 17818 — main JSON/control messages — `phase1/.../WifiMsgCenter.java:158-180, 291`; `DevicePortController.java:11`
- 15456 — H.264 projection video stream — `WifiMsgCenter.java:263, 306, 326`
- 15457 — projection heartbeat — `WifiMsgCenter.java:268, 311, 330`; `ProjectionHeartPortController.java:11`
- 18888 — DVR — `WifiMsgCenter.java:184, 296`; `DvrPortController.java:22`
- 19000 — OTA — `WifiMsgCenter.java:187, 301`; `OtaPortController.java:13`

There is no 6th port. `restartServerSocket()` (`WifiMsgCenter.java:315-334`) only ever lists those five. No conditional `bind()` is gated on a "mode" check.

**Handshake variants:**

The 69-byte projection-init buffer is built only at `ProjectionEncoder.sendVideoSize` (`phase1/.../ProjectionEncoder.java:238-251`). The string literal is exactly `"android"` at offset 1, byte[0]=0, then UTF-8 truncation logic over offsets 1..64, then 2 big-endian shorts (width, height) at 65-66 and 67-68. Searched for "androidSimple", "androidSmall", "androidMini", `new byte[69]` variants, alternate flag values: **zero hits**.

**Alternate resolutions:**

`ProjectionEncoder` (`phase1/.../ProjectionEncoder.java:51-82`) selects W×H per dash hardware shape via `ProjectionUtil.isCircle / isPingPongScreen / isVerticalScreen / ...`. Values: 800×800, 800×640, 480×800, 600×1024, 1024×600, 800×480, 640×1284, 1280×640. These describe **the full dash panel pixel grid** in each form factor; there is no "smaller subregion" variant.

**Chinese mode-strings:**

None of: 简单导航 / 简易导航 / 分屏 / 双屏 / 半屏 / 小窗 / 小屏 / 路口放大 / 当前模式 / 导航模式 / 投屏模式 / 路口大图 / 裸屏 / 简洁导航 / 普通导航 / 全屏导航 / 全屏投屏 appear anywhere in any of the three decompiles.

## 2. Mode-state indicators — what the phone knows about which mode the dash is in

**Nothing. Empirically nothing.** The phone has zero runtime knowledge of whether the rider chose Simple or Full-Screen Navigation. The only nav-mode-adjacent state the phone tracks is:

- `ProjectionWrapper.isConnected` (boolean — is port 15456 currently accepting bytes?)
- `GlobalData.isInNavigating` — is the phone-side nav engine currently routing? — gates outbound NAVI messages
- `SQNavigationManage.getPageAction().isInNaving()` — same idea, checked by `sendNaviInfo` and `sendNaviInfoOld`

Critically, neither `ProjectionWrapper.isConnected` nor `isInNavigating` gates the structured overlay sends. The four `isConnected` callers are: (a) the encoder's `writeFully`, (b) the service's setup state-machine, (c) the projection-debug settings activity. **No call site of `JsonManager.send*` checks `isConnected`.**

There is one capability field whose name is suggestive but unused: `DeviceFunction.isBluetoothFullScreenNav`. It's initialized to 0, has a getter, and is serialized into `toString()` for logging. **No phone-side code reads it for control flow.** Forward-declared DB-schema field for a future capability that ThinkerRide planned but never wired up in this app version.

## 3. Long-press UP signal path (any phone-side detection?)

**Confirmed: 100% firmware-side.** No phone-side branch sees the long-press event.

- `func=KEY` exists *only as a phone→dash builder* (`JsonManager.sendKeyPress`, `JsonManager.java:1240-1252`). No inbound `KEY` handler.
- `func=BT_KEY` is a separate Bluetooth-keyfob feature — dispatched to `TBoxComponent.onRecievedBleMsg` (`BleConnectWrapper.java:1340-1342`); nothing nav-mode-relevant.
- `msg_id=10` inbound items: only 0 (hangup), 1 (speed change), 2 (mileage), 3 (car parse), 4 (request time), 5 (unicode), 6 (version), 7/9 (packet-loss), 10 (autoconnect), 11 (diff), 12-14 (OTA file), 53 (lock). **No item code for "rider chose Simple Navi".**

The **absence** of an inbound 15456 TCP connect (i.e., "dash never dialed in to my projection server") IS the de-facto signal that the dash chose Simple Navigation. This is the only side-channel the phone has — `ProjectionConnectionChangeEvent(status=1)` arrives when dash dials in for full-screen; if it never arrives during a nav session, dash is in Simple Navi (or no projection at all). **The phone does not react to that absence** — it keeps pushing structured data to BLE/17818 regardless.

## 4. Structured overlay message catalog (every push that feeds dash-native UI)

All file:line refs are `phase1/.../manager/JsonManager.java` unless noted otherwise.

| Field | msg_id | msg_type / func / act | Builder line | Trigger / Cadence | Gated by |
|---|---|---|---|---|---|
| Old nav info | 1 | (top-level: icon, icon_bitmap, next_road, cur_retain_distance, path_retain_distance, remain_time) | `sendNaviInfoOld:1409` | `SelfNaviListener.onNaviInfoUpdate` per nav-engine turn-update | `isInNaving()`; old firmware only (no CV present) |
| Cross / junction enlargement | 4 | (icon: base64 PNG) | `sendCross:2609` | `SelfNaviListener.showCrossView` when nav engine emits junction-enlargement | Always (when bitmap present) |
| Location / street | 7 | (street string) | `sendLocation:1267` | On location-change AND on connect with last-known city | `currentDataDevice.isActivate()` |
| Weather basic | 8 | (weather code, temperature) | `sendWeather:2303` | After weather refresh from cloud | `getDeviceFunction().getWeather()` |
| Hangup | 9 | — | `sendHangup:1195` | On phone call hangup | dedupe |
| Notification (app push) | 2 | app_name, title, content, package_name, icon | `sendNotification:1499` | On Android NotificationListener event | `isOpenAppNotify()` + needNotifyList filter |
| Current time | 11 | (time string, tag) | `sendCurrentDateTime:1079` | On connect with tag=-1; on dash-request (item=4 with TAG) | dedupe by formatted-second |
| Current speed | 22 | cur_speed | `sendCurrentSpeed:1097` | GPS-tick when `screenInfo==1` and `getSpeed()` cap | dedupe |
| Battery | 23 | power | `sendBatteryRemainingCapacity:975` | GPS-tick when `screenInfo==1` and `getBattery()` cap | dedupe |
| Sub mileage | 21 | mileage | `sendSubTotalMileage:2113` | GPS-tick when `screenInfo==1` and `getSubMileage()` cap | dedupe |
| Trip mileage | 25 | msg_type=3 | `sendMileage:1341` | trip-update | — |
| Total mileage (msg_id=25) | 25 | msg_type=4 | `sendTotalMileage:2291` | trip-update | — |
| Speed + max | 25 | msg_type=5 | `sendSpeed:2094` | GPS-tick if `getSpeed()` cap | dedupe |
| Avg speed | 25 | msg_type=6 | `sendAvgSpeed:918` | trip update | — |
| Mobile signal | 25 | msg_type=7 | `sendSignal:2051` | phone signal-strength changes (`MyPhoneStateListener:105`) | `getDeviceFunction().getMobileSignal()` |
| Calorie | 25 | msg_type=8 | `sendCalorie:991` | trip update | — |
| Altitude / elevation / pond | 25 | msg_type=9 | `sendElevationAndPond:1113` | `handleLocationChange` every 2s (`GlobalData.java:1267-1269`) | `getDeviceFunction().getAltitude()`; `isActivate()` |
| Location picture | 25 | msg_type=10 | `sendLocationPicture:1298` | when base64-encoded POI picture is set | — |
| Weather + wind power | 25 | msg_type=11 | `sendWeatherWithWindPower:2386` | `DiffVersionManager.sendWeather` | `getWeather()` cap + dedupe |
| Weather warning | 25 | msg_type=12 | `sendWeatherWarning:2353` | weather alarm pushed by `MapBoxLocationManager:189` | `getWeatherWarning()` cap |
| Language | 25 | msg_type=13 | `sendSetLanguage:2018` | language-change settings event | — |
| Unit | 25 | msg_type=14 | `sendSetUnit:2033` | unit settings change | dedupe |
| Orientation / compass | 25 | msg_type=15 | `sendOritation:1543` | every GPS tick (`GlobalData.java:1271-1273`) | `getDirection()` cap |
| Time function | 25 | msg_type=1 | `sendTimeFunction:2148` | timer functions | dedupe |
| Speech / TTS to dash | 27 | func=AUDIO act=send_text | `sendSpeech:2066` | nav voice-guidance | — |
| Music status | 27 | func=MUSIC act=ret_status | `sendMusicStatus:1372` | media player state-change | — |
| GPS signal | 27 | func=GPS act=signal_status | `sendGPSSignal:1166` | GPS fix change | — |
| **NAVI act=1 destination** | 27 | func=NAVI act=1 | `sendNaviDest:1390` | on nav route-set | dedupe by toString |
| **NAVI act=2 retain** | 27 | func=NAVI act=2 | `sendNaviRetain:1434` | nav retain-time tick | — |
| **NAVI act=3 rich** (modern) | 27 | func=NAVI act=3 | `sendNaviInfo:2658`, also `getNaviInfo:209` | `SelfNaviListener.onNaviInfoUpdate` | `isInNaving()`; newer firmware (CV present) |
| INSIDENAVI start | 27 | func=INSIDENAVI CMD=inside_naviinfo | `sendNavi_Inside_start:1449` | when phone-side nav starts AND dash supports inside-navi | — |
| INSIDENAVI stop | 27 | func=INSIDENAVI CMD=stop_navi | `sendNavi_Inside_stop:1486` | nav end | — |
| INSIDENAVI query 1/2 | 27 | func=INSIDENAVI query=1\|2 | `queryInsideNaviStatus:345`, `queryDevicePlayerVoiceStatus:317` | on connect | — |
| ROAD_NAVI poi_info | 27 | func=ROAD_NAVI act=poi_info | `sendBTNaviPoi:957` | GPS tick during dash-led navi (`RoadNaviHelper.java:1006`) | — |
| ROAD_NAVI change_map | 27 | func=ROAD_NAVI act=change_map | `sendChangeMap:1023` | when tile-index changes | — |
| ROAD_NAVI rerouting / request_start / close / get_status / file_* | 27 | func=ROAD_NAVI (various) | many | nav state changes / tile transfers | — |
| SCREEN / screen-saver | 27 | func=SCREEN act=screenchoose | `sendScreenSaver:2003` | ScreenSaverPresenter user picks image | — |
| THEME | 27 | func=THEME act=1..6 | `sendThemeStatus:2631`, `sendThemeTask:2133` | theme install-flow | — |
| TUC unicode get | 27 | func=TUC act=GET | `queryTucsJson:410` | on connect | — |
| Link info | 24 | unique_info | `sendLinkInfo:1254` | on connect | — |

Particularly likely Simple-Navi-overlay-feeders (the dash composites the small left region from these):
- `msg_id=1` (old) or `msg_id=27 func=NAVI act=3` (new) — turn icon + next-road text + distances + time
- `msg_id=4` — intersection enlargement bitmap (the dash shows this PNG in its native widget at junctions)
- `msg_id=7` — current street name
- `msg_id=11` — time (top bar)
- `msg_id=22, 23` — current speed, battery (lower bar)
- `msg_id=25 msg_type=9` — altitude
- `msg_id=25 msg_type=11/12` — weather + warning
- `msg_id=25 msg_type=15` — compass direction
- `msg_id=27 func=ROAD_NAVI` — map tiles for dash-rendered map widget
- `msg_id=27 func=INSIDENAVI` — destination + theme + dash-native navi engine driver

## 5. msg_id=27 func=NAVI act=3 deep dive

Builder locations:
- `JsonManager.getNaviInfo(int icon, String prev_road_unused, String next_road, int cur_unittype, String path_retain_str, int path_cur_unittype, int cur_retain_time, long remain_time_epoch, int retain_rate)` — `JsonManager.java:209-234`
- `JsonManager.sendNaviInfo(int icon, String unused, String next_road, int cur_retain_distance_meters, int path_retain_distance_meters, int sec_retain_time)` — `JsonManager.java:2658-2681`

The second one is the **only one actually called during normal navigation**; it wraps `freshMileAndTime` to format distances and computes `retain_rate` from `(1 - cur_retain / total_length) * 100`.

Field encoding:
- `msg_id` = 27, `func` = "NAVI", `act` = 3
- `icon` = int — turn-arrow icon ID (not a bitmap, just an integer)
- `next_road` = string — UTF-8 (transliterated to ASCII in oversea variant)
- `cur_retain_distance` = **string** — if <1000m, literal meters like "850"; else km with 1-decimal like "1.2"
- `cur_unittype` = int — 0 if meters, 1 if km
- `path_retain_distance` = **string** — same formatting
- `path_cur_unittype` = int — 0 / 1
- `cur_retain_time` = int — seconds remaining to next maneuver
- `remain_time` = long — UNIX-epoch seconds at which ETA happens = `System.currentTimeMillis()/1000 + cur_retain_time`
- `retain_rate` = int — 0..100 percent of route remaining

Branch logic at `GlobalData.java:2027-2048`: if `currentSqDevice.getCv()` is empty (old firmware) → `sendNaviInfoOld` (msg_id=1); else (newer firmware) → `sendNaviInfo` (msg_id=27 act=3). `cv` = "compatibility version" / firmware-feature-bitmap.

Comparison vs `sendNaviInfoOld` (msg_id=1):

| field | msg_id=1 (old) | msg_id=27 NAVI act=3 (new) |
|---|---|---|
| envelope | `{msg_id:1, ...}` | `{msg_id:27, func:"NAVI", act:3, ...}` |
| icon | int | int |
| icon_bitmap | optional base64 PNG | not in new version |
| next_road | string (raw) | string |
| cur_retain_distance | int (meters) | string + cur_unittype |
| path_retain_distance | int (meters) | string + path_cur_unittype |
| remain_time | int (seconds-to-next) | long (epoch-seconds-ETA) |
| cur_retain_time | — | int (seconds-to-next) |
| retain_rate | — | int (0..100 percent) |

## 6. msg_id=27 func=INSIDENAVI / ROAD_NAVI deep dive

### INSIDENAVI (offline / dash-native nav engine)

Phone→dash builders:
- `sendNavi_Inside_start(insideNaviInfo)` — `JsonManager.java:1449-1484`. Sends `{msg_id:27, func:"INSIDENAVI", CMD:"inside_naviinfo", start:"lat,lng", way0:"lat,lng" ... wayN:"...", waynum:int, end:"lat,lng", routeId, strategy, dst, naviType, carType, isRestricted, carNumber, carCC, themeMode}`.
- `sendNavi_Inside_stop()` — `JsonManager.java:1486-1497`. `{msg_id:27, func:"INSIDENAVI", CMD:"stop_navi"}`.
- `queryInsideNaviStatus()` — line 345. `{msg_id:27, func:"INSIDENAVI", query:1}`.

Dash→phone inbound (`MsgManager.insideNavi`, `MsgManager.java:164-195`):
- if `navi_status` field → setLocalNaving(naviStatus == 1) — **dash tells phone "I started/stopped inside-navi"**
- if `voice_status` → setNaviPlayerVoiceStatus
- if `act` present: 1/6/7 with cityName + msg/size

**Critical for Simple Navi:** if the rider's dash supports `INSIDENAVI`, the dash's "Simple Navigation" mode is likely rendered by the dash's own native nav engine driven by the `inside_naviinfo` start message.

### ROAD_NAVI (online: BT-pushed raster map for older dashes)

Phone→dash builders (acts):
- `first_map` — `sendBTNaviPic` line 930. JPEG bytes + cx/cy/angle/day_or_night/map_data(base64).
- `poi_info` — `sendBTNaviPoi` line 957. cx/cy/angle/map_index — periodic GPS-tick position update.
- `change_map` — `sendChangeMap` line 1023. cx/cy/angle/map_index for a different tile.
- `request_start` / `request_close` / `rerouting` / `get_status`
- `file_start` / `file_trans` / `file_status` — bulk binary tile transport

ROAD_NAVI is the BT-rendered map widget for dashes that don't have H.264 projection but DO have a native map-rendering capability. For the Kove KY800X, ROAD_NAVI is likely NOT the Simple-Navi-mode path. INSIDENAVI is more likely the SimpleNav driver, if the KY800X supports it; otherwise the dash composes its Simple-Navi UI entirely from `msg_id=1/27 NAVI/4/11/etc`.

## 7. msg_id=4 sendCross (intersection enlargement)

Builder: `JsonManager.sendCross(Bitmap bitmap)` at `JsonManager.java:2609-2625`.

Shape: `{msg_id:4, icon:<base64 PNG>}`. The bitmap is downsized via `translateBitmapToEncodedString8762(bitmap, BluetoothMsgParamUtil.getCrossSize())`. Default crossSize = 500 px (`DeviceFunction.java:15, 444`) but is per-dash via `DeviceFunction.crossSize`.

Trigger sites:
- `SelfNaviListener.showCrossView(SqNaviCross cross)` at `phase1/oversea/.../SelfNaviListener.java:145-147` — fires when the nav engine emits "show junction enlargement".
- Mode-cross variant at line 158: `JsonManager.sendCross(sqNaviModeCross.getBitmap())` (for "lane gate").
- `GlobalData.java:1575` calls bare `JsonManager.sendCross()` (with null bitmap — probably to clear the cross).
- Public no-arg `sendCross()` at `JsonManager.java:1075-1077` — sends `{msg_id:4, /*icon omitted*/}` to indicate "no cross to show now / hide the widget."

The dash UI renders this PNG in its **junction-enlargement widget**, which is the same widget visible in Simple Navigation mode at junctions per the manual. **This is one of the most important Simple-Navi-overlay feeders.**

## 8. Theme / screen-choose channels (any mode toggle?)

**Neither is a Simple-Navi toggle.**

`func=SCREEN act=screenchoose`:
- Builder `sendScreenSaver(int)` at `JsonManager.java:2003-2016`.
- Sole caller: `ScreenSaverPresenter.java:223` — the user picked screen-saver image #N.
- Inbound `func=SCREEN`: only `file_start` / `file_trans` / `file_status` for transferring the screen-saver image bytes.

`func=THEME`:
- Installs/tracks the dash UI theme (color palette, font, dial graphics), not the nav-mode toggle.

## 9. Cadence summary

Driver = main `handleLocationChange` in `GlobalData.java:1244-1306`, fired on every GPS update (~1 Hz typical).

**Every GPS tick** (1 Hz nominal):
- `sendOritation` (msg_id=25 msg_type=15) — bearing — if `getDirection()` cap
- `sendCurrentSpeed` (msg_id=22) — if `getSpeed()` cap AND `screenInfo==1` AND riding
- `sendBatteryRemainingCapacity` (msg_id=23) — if `getBattery()` cap AND `screenInfo==1` AND riding
- `sendSubTotalMileage` (msg_id=21) — if `getSubMileage()` cap AND `screenInfo==1` AND riding
- `sendSpeed` (msg_id=25 msg_type=5) — speed+max — if `getSpeed()` cap AND riding
- `sendTotalMileage` — if `getTotalMileage()` cap AND riding

**Every 2 seconds** (throttled inside handleLocationChange line 1267):
- `sendElevationAndPond` (msg_id=25 msg_type=9) — altitude

**Per nav engine update** (~every step / road-name change, typically 0.5-5 Hz):
- `sendNaviInfoOld` (msg_id=1) or `sendNaviInfo` (msg_id=27 NAVI act=3)
- Dedup'd by full JSON-toString comparison

**Per junction event (cross)**:
- `sendCross` (msg_id=4) with bitmap — on `showCrossView` (begin)
- `sendCross` (null) — on `hideCrossView` (end)

**On nav route-set / cancel**:
- `sendNaviDest` (msg_id=27 NAVI act=1)
- INSIDENAVI start/stop

**Every weather refresh** (DiffVersionManager pulls every N minutes; typical 15min):
- `sendWeather` (msg_id=8) + `sendWeatherWithWindPower` (msg_id=25 msg_type=11)
- `sendWeatherWarning` (msg_id=25 msg_type=12) when active

**Per phone signal change**:
- `sendSignal` (msg_id=25 msg_type=7)

**On location-name change (geocode)**:
- `sendLocation` (msg_id=7)

**On connect / handshake**:
- `sendCurrentDateTime` (msg_id=11) with tag=-1
- `sendLocation` (last city)
- `sendLinkInfo` (msg_id=24)
- `requestProductType` (msg_id=26)
- `queryHasAdasFunctionToCar`
- `queryDeviceFunctionCompatibilityInfo`
- `queryInsideNaviStatus`, `queryDevicePlayerVoiceStatus`
- `checkVehicleCurStatus`

**Dedup**: nearly every builder runs through `isDuplicateSendingInfo(key)` which compares `key` against the last-sent key (single string, last-write-wins).

## 10. Recommended implementation strategy

**Architectural principle:** Build a **single overlay-data publisher**. Push the structured data on the appropriate cadence whenever the dash is BLE/17818-connected and the corresponding feature is enabled per `DeviceFunction`. **Do not gate on `ProjectionWrapper.isConnected`. Do not try to detect Simple-vs-Full mode.** Whether the rider chose Simple or Full, the data feed is identical.

**Concretely:**

1. **Always-on structured-data sender** — fires whether or not 15456 is connected:
   - GPS-tick (1 Hz): orientation (m25/15), current speed (m22), speed+max (m25/5), battery (m23), sub-mileage (m21), total-mileage when riding
   - Every 2 s on GPS tick: altitude (m25/9)
   - Per nav-engine update: msg_id=27 func=NAVI act=3 (prefer modern; fall back to msg_id=1 if `cv` is empty)
   - Per junction event: msg_id=4 sendCross with downsized PNG (max width = dash's `crossSize`, default 500 px)
   - Weather refresh (15 min poll): msg_id=8 + msg_id=25 msg_type=11 as a pair, plus msg_id=25 msg_type=12 on warning
   - Phone-signal-strength change: msg_id=25 msg_type=7
   - On dash msg_id=10 item=4 request: msg_id=11 sendCurrentDateTime with the requested tag
   - On connect: link-info (m24), location (m7), product-type (m26), inside-navi-status query, current-time (m11 tag=-1)

2. **Field encoding (NAVI act=3) — exact**:
```kotlin
fun naviAct3(icon: Int, nextRoad: String, curMeters: Int, pathMeters: Int, secsToNext: Int, totalRouteLength: Int): JsonObject {
    val curStr  = if (curMeters  < 1000) curMeters.toString()  else "%.1f".format(floor(curMeters/100.0)/10.0)
    val pathStr = if (pathMeters < 1000) pathMeters.toString() else "%.1f".format(pathMeters/1000.0)
    val curUnit  = if (curMeters  < 1000) 0 else 1
    val pathUnit = if (pathMeters < 1000) 0 else 1
    val etaEpoch = System.currentTimeMillis()/1000 + secsToNext
    val retainRate = ((1.0 - (pathMeters.toDouble()/totalRouteLength)) * 100).toInt()
    return buildJson {
        "msg_id" to 27; "func" to "NAVI"; "act" to 3
        "icon" to icon; "next_road" to nextRoad
        "cur_retain_distance"  to curStr;  "cur_unittype"      to curUnit
        "path_retain_distance" to pathStr; "path_cur_unittype" to pathUnit
        "cur_retain_time" to secsToNext; "remain_time" to etaEpoch
        "retain_rate" to retainRate
    }
}
```

3. **Optional projection encoder** — if and only if the dash dials 15456:
   - Maintain the H.264 encoder exactly as already RE'd
   - The encoder fires only when port 15456 has a live connection
   - If the rider chose Simple Navi, the dash never dials → the encoder stays cold → zero CPU/battery cost
   - If the rider chose Full Screen, the dash dials → encoder spins up

4. **Do NOT implement**:
   - A mode-toggle MMI on the phone (there's no protocol slot for it)
   - A "send Simple-Navi-specific overlay" — the overlay is the same in both modes
   - A second port / handshake variant — none exist

5. **Cross-bitmap pipeline (msg_id=4)** — the highest-value Simple-Navi feeder besides NAVI act=3:
   - When the nav engine emits a junction-enlargement bitmap, downsize to `min(bitmap.width, dashCrossSize)` (default 500 px, square)
   - Base64-encode, send as `{msg_id:4, icon:"<base64>"}`
   - When the junction passes, send `{msg_id:4}` with no icon to clear

6. **INSIDENAVI handshake** (if KY800X reports support):
   - On nav-start, send `inside_naviinfo` with start/way/end/strategy/etc
   - Manage offline-city pulls if desired
   - Listen for `navi_status` field on inbound INSIDENAVI

7. **Capability-flag gating**: every send should be wrapped in a `DeviceFunction.getX()` check. The capability set is per-dash, queried via `msg_id=27 func=SCPT` on connect.

8. **Dedup**: implement equivalent of `isDuplicateSendingInfo` — for each builder, cache last-sent stringified payload, skip if identical.

## 11. Gaps / could-not-determine

- **Whether the dash actually composites a "left subregion overlay" in Simple Navi mode versus showing one of its native pages with embedded data.** The dash-side firmware behavior isn't visible in the phone-side decompile.
- **The exact KY800X firmware semantics for INSIDENAVI.** If it supports inside-navi the dash will render its own map widget; if not, the dash composes a non-map Simple-Navi UI from the NAVI act=3 + cross + speed/battery/altitude/weather/time data.
- **The exact dash-side rendering of `msg_id=4` cross when the rider is in Simple Navi.** It's clear the dash receives the bitmap; whether it pops a full-screen junction overlay or shows it inside the left subregion is dash-side.
- **What `msg_type=10 (sendLocationPicture)` is rendered as.** Looks like a POI thumbnail.
- **Cadence of `DiffVersionManager.sendWeather` polling.** Probably 15 min from cloud-weather-API.
- **`isBluetoothFullScreenNav` field**: defined and serialized but never read for control flow. Either dead code or read by a sibling SDK module.
