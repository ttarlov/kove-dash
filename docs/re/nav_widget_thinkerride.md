# ThinkerRide → Dash: Turn-by-Turn Navigation Protocol
Date: 2026-05-19
App: oversea.whbluestar.thinkerride (decompiled at <REPO>/phase1/apk/jadx_output/sources/)

## Bottom line

Yes — the dash widget can be driven from a custom app. ThinkerRide pushes turn-by-turn over BLE as JSON (BLE `ffe1`, 104-byte byteCat-framed) plus a redundant 274-byte binary frame on TCP 17818. There are two JSON shapes selected at runtime by `SqDevice.getCv()` (compatibility version): legacy `msg_id=1` (distances as ints) and modern `msg_id=27 func=NAVI act=3` (distances as preformatted strings + unit-enum). All fields are well-defined; the icon space is a closed enum of integers 1..48 that maps Mapbox maneuver drawables to dash glyph indices (the dash owns the glyph atlas — we send numbers, not bitmaps). There is no per-session "arm the widget" handshake — the phone simply starts pushing nav messages on each nav-engine turn-update, gated only by `SQNavigationManage.getPageAction().isInNaving()`. To stop the widget, send `{"msg_id":15}` (endNavi).

The single thing we still don't know from the code alone: which `cv`-versions does our specific 2022 firmware (`SV=3.0.4`) report — i.e., does our dash take the legacy `msg_id=1` branch or the modern `msg_id=27` branch? `SqDevice.getCv()` is populated from the dash's `msg_id=10 item=6` version reply (specifically a `cv` field), and we have no log of that field's value on our hardware. The safe move is to send both shapes simultaneously (the prototype `bt_nav_stream.py` already does this); the dash drops the one it doesn't recognize.

A secondary unknown (cosmetic): whether `next_road` UTF-8 with non-ASCII characters renders on the dash. The OEM `oversea` build hard-runs every road name through `Transliterator.getInstance("Any-Latin")` before sending (`SelfNaviListener.java:42-45`), implying the dash does NOT render non-ASCII glyphs reliably.

## Wire shape: full JSON envelope(s)

### A. `msg_id=1` — legacy `sendNaviInfoOld` (old firmware path)

Builder: `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/manager/JsonManager.java:1409-1432`.

```json
{
  "msg_id": 1,
  "icon": 1,
  "icon_bitmap": "<base64 png>",
  "next_road": "Pearl Street",
  "cur_retain_distance": 850,
  "path_retain_distance": 7200,
  "remain_time": 240
}
```

- `msg_id` (int) — message-class
- `icon` (int) — turn-icon enum (see §"Turn-icon enumeration")
- `icon_bitmap` (string, OPTIONAL) — only if `str != null` in builder
- `next_road` (string) — UTF-8 road name (transliterated to ASCII on oversea variant)
- `cur_retain_distance` (int) — METERS to next maneuver (no unit field — always meters)
- `path_retain_distance` (int) — METERS to destination
- `remain_time` (int) — SECONDS to next maneuver (NOT epoch)

Builder gate (lines 1410-1413): `if (!SQNavigationManage.getPageAction().isInNaving()) return null;` — fires only while phone-side nav is active.

Caller at `JsonManager.java:1426` also fires the parallel TCP-17818 binary frame: `DeviceWrapper.INSTANCE.sendNaviInfo(i, str2, i2, i3)` — see §"Transport details".

### B. `msg_id=27 func=NAVI act=3` — modern `sendNaviInfo` (new firmware path)

Two builder overloads both produce the same shape, with `freshMileAndTime` formatting distances.
- `JsonManager.sendNaviInfo(int icon, String unused, String nextRoad, int curMeters, int pathMeters, int curRetainSec)` — `JsonManager.java:2658-2681`
- `JsonManager.getNaviInfo(int icon, String prevRoadUnused, String nextRoad, int curUnittype, String pathRetainStr, int pathCurUnittype, int curRetainTime, long remainTime, int retainRate)` — `JsonManager.java:209-234` (takes preformatted strings)

Wire shape (from the 2658 path, the only one actually wired by `SelfNaviListener`):

```json
{
  "msg_id": 27,
  "func": "NAVI",
  "act": 3,
  "icon": 1,
  "next_road": "Pearl Street",
  "cur_retain_distance": "850",
  "cur_unittype": 0,
  "path_retain_distance": "7.2",
  "path_cur_unittype": 1,
  "cur_retain_time": 240,
  "remain_time": 1747633200,
  "retain_rate": 87
}
```

- `cur_retain_distance` — STRING; `"%d"` if curMeters<1000, else `"%.1f"` km (floor 0.1)
- `cur_unittype` — int; 0 if curMeters<1000 (meters), 1 otherwise (km)
- `path_retain_distance` — STRING; same rule
- `path_cur_unittype` — int; 0=meters, 1=km
- `cur_retain_time` — int; SECONDS to next maneuver
- `remain_time` — long; UNIX-EPOCH SECONDS for ETA of next maneuver (not duration!) — `System.currentTimeMillis()/1000 + cur_retain_time` (`JsonManager.java:190`). [inferred] dash treats as wall-clock seconds of the next maneuver.
- `retain_rate` — int 0..100; percent of route remaining

### C. `msg_id=27 func=NAVI act=1` — `sendNaviDest`

Builder `JsonManager.java:1390-1403`. `{"msg_id":27,"func":"NAVI","act":1,"dest":"Pearl Street, Boulder"}`. Builder exists but has NO caller in this decompile.

### D. `msg_id=27 func=NAVI act=2` — `sendNaviRetain`

Builder `JsonManager.java:1434-1447`. `{"msg_id":27,"func":"NAVI","act":2,"curtime":<sec>,"totaltime":<sec>}`. Also no caller.

### E. `msg_id=4` — `sendCross` (intersection enlargement)

Builder `JsonManager.java:2609-2625`. `{"msg_id":4,"icon":"<base64 PNG>"}`. Empty `{"msg_id":4}` clears. Triggered by `SelfNaviListener.showCrossView` (line 145-147). Bitmap downsized to dash's `crossSize` (default 500 px square).

### F. `msg_id=15` — `endNavi`

Builder `JsonManager.java:166-177`. `{"msg_id":15}`. Triggered at `onArrive` and `onNaviStop` (lines 84, 129). Also fires parallel TCP `03 0D 00 00 00 00`. Dash can also REQUEST stop via inbound `msg_id=25 msg_type=2 msg_source=1` (`BleConnectWrapper.java:1187-1207`).

### G. `msg_id=27 func=INSIDENAVI CMD=inside_naviinfo` — start dash's own engine

Builder `JsonManager.java:1449-1484`. Used only on dashes that report INSIDENAVI capability.

```json
{
  "msg_id": 27, "func": "INSIDENAVI", "CMD": "inside_naviinfo",
  "start": "40.0150,-105.2705", "way0": "...", "waynum": 0, "end": "40.0300,-105.2500",
  "routeId": 0, "strategy": 0, "dst": "<name>", "naviType": 0, "carType": 0,
  "isRestricted": 0, "carNumber": "...", "carCC": 0, "themeMode": 0
}
```

Stop: `{"msg_id":27,"func":"INSIDENAVI","CMD":"stop_navi"}` (`JsonManager.java:1486-1497`).

### H. `msg_id=27 func=ROAD_NAVI` (raster tile push for non-projection dashes)

Acts: first_map, poi_info, change_map, request_start, request_close, rerouting, get_status, file_start/trans/status. poi_info shape: `{msg_id:27, func:"ROAD_NAVI", act:"poi_info", cx,cy,angle,map_index}` (`JsonManager.java:957-973`). Likely NOT relevant for our H.264-capable KY800X.

## Field semantics

| Field | Type | Units | Range | Citation | Notes |
|---|---|---|---|---|---|
| msg_id | int | — | 1, 4, 15, 27 | JsonManager.java:1416,1504,169,1393 | message class |
| func | string | — | "NAVI", "INSIDENAVI", "ROAD_NAVI" | JsonManager.java:1394,1453,934 | for msg_id=27 |
| act | int | — | 1=dest, 2=retain, 3=info | JsonManager.java:1395,1439,2667 | for func=NAVI |
| icon (NAVI/act=3, msg_id=1) | int | — | 0..48 enum | JsonManager.java:1417,2668,215 | see Turn-icon enumeration |
| icon (msg_id=4) | string | — | base64 PNG | JsonManager.java:2618 | omit = clear widget |
| icon_bitmap (msg_id=1) | string | — | base64 PNG, optional | JsonManager.java:1419 | rarely sent |
| next_road | string | UTF-8 | unbounded | JsonManager.java:1421,2669 | oversea pre-transliterates to ASCII |
| cur_retain_distance (msg_id=1) | int | meters | 0..∞ | JsonManager.java:1422 | |
| cur_retain_distance (NAVI/act=3) | string | per cur_unittype | "%d" if <1000m, "%.1f" km else | JsonManager.java:185-186 | floor to 0.1 km |
| cur_unittype | int | enum | 0=m, 1=km | JsonManager.java:186 | |
| path_retain_distance (msg_id=1) | int | meters | 0..∞ | JsonManager.java:1423 | |
| path_retain_distance (NAVI/act=3) | string | per path_cur_unittype | same rule | JsonManager.java:187 | uses DecimalFormat("#.0") |
| path_cur_unittype | int | enum | 0=m, 1=km | JsonManager.java:188 | |
| cur_retain_time (NAVI/act=3) | int | seconds | 0..∞ | JsonManager.java:189 | to next maneuver |
| remain_time (msg_id=1) | int | seconds | 0..∞ | JsonManager.java:1424 | total trip seconds remaining |
| remain_time (NAVI/act=3) | long | UNIX-epoch sec | now..∞ | JsonManager.java:190 | nowEpoch + cur_retain_time — ETA-of-next-maneuver [inferred] |
| retain_rate | int | percent | 0..100 | JsonManager.java:2670 | (1 - pathMeters/totalRouteLen) * 100 |
| dest (NAVI/act=1) | string | UTF-8 | unbounded | JsonManager.java:1396 | unused by oversea OEM |
| curtime, totaltime (act=2) | int | seconds | — | JsonManager.java:1440-1441 | unused |
| start/wayN/end (INSIDENAVI) | string | "lat,lng" | WGS84 deg | JsonManager.java:1458,1464,1469 | uses Locale.getDefault() — comma-decimal risk in EU locales [inferred] |

ETA / total remaining distance: no dedicated total-trip-ETA field. `retain_rate` gives route-completion percent; `path_retain_distance` gives total remaining trip distance. Dash either computes total trip ETA on-device or doesn't render it.

Destination name: only via `sendNaviDest` (act=1) which has no caller. Widget shows next-road, not destination.

Lane info: `SelfNaviListener.showLaneView` (line 151) only logs — no wire message. NO lane-guidance channel.

Next-next maneuver: none. Single-step model.

Maneuver-type enum: conflated with icon enum; one integer encodes both maneuver class (turn/roundabout/arrive/depart/merge/fork/off-ramp) and direction (left/right/sharp/slight/straight/uturn).

## Turn-icon enumeration

Two builder functions in `<REPO>/phase1/apk/jadx_output/sources/com/thinkerride/map_mapboxlibrary/utils/MapUtils.java`:

1. `getNaviCode(modifier, type)` at MapUtils.java:257-368 — legacy SqNaviInfo path, used by `MapboxNaviHelper.java:241`.
2. `getNavigationTurnCode(drawableResId, shouldFlipIcon)` at MapUtils.java:370-469 — modern SqNaviInfoNew path, used by `MapboxNaviHelper.java:282/295/309`.

| Icon | Maneuver | Source |
|---|---|---|
| 1 | U-turn (legacy modifier=uturn) | MapUtils.java:341; NavSignalManager.java:42 ("掉头") |
| 2 | Turn LEFT | MapUtils.java:351,387; NavSignalManager.java:47 ("左转") |
| 3 | Turn RIGHT (or slight-right-via-fork) | MapUtils.java:354,390; NavSignalManager.java:52 ("右转") |
| 4 | Slight LEFT (or left-via-fork) | MapUtils.java:349,410; NavSignalManager.java:57 ("向左行驶") |
| 5 | Slight RIGHT | MapUtils.java:356,413; NavSignalManager.java:60 ("向右行驶") |
| 6 | Sharp LEFT | MapUtils.java:347,416 |
| 7 | Sharp RIGHT (or straight-via-legacy) | MapUtils.java:362,419 |
| 8 | U-turn (modern, not-flipped) | MapUtils.java:393 |
| 9 | Straight / fallback unknown | MapUtils.java:407,344,462 |
| 12 | Roundabout straight (not-flipped) | MapUtils.java:429 |
| 18 | Roundabout straight (flipped) | MapUtils.java:429 |
| 21 | Arrive (straight or generic) | MapUtils.java:371-372 |
| 22 | Arrive LEFT | MapUtils.java:374-375 |
| 23 | Arrive RIGHT | MapUtils.java:377-378 |
| 24 | Depart (straight or generic) | MapUtils.java:380-381 |
| 25 | On-ramp | MapUtils.java:383-384 |
| 26 | U-turn (modern, flipped) | MapUtils.java:393 |
| 27 | Depart LEFT | MapUtils.java:395-396 |
| 28 | Depart RIGHT | MapUtils.java:398-399 |
| 29 | End-of-road LEFT | MapUtils.java:401-402 |
| 30 | End-of-road RIGHT | MapUtils.java:404-405 |
| 31 | Roundabout LEFT (not-flipped) | MapUtils.java:422-423 |
| 32 | Roundabout LEFT (flipped) | MapUtils.java:422-423 |
| 33 | Roundabout RIGHT (not-flipped) | MapUtils.java:425-426 |
| 34 | Roundabout RIGHT (flipped) | MapUtils.java:425-426 |
| 35 | Roundabout slight-LEFT (not-flipped) | MapUtils.java:431-432 |
| 36 | Roundabout slight-LEFT (flipped) | MapUtils.java:431-432 |
| 37 | Roundabout slight-RIGHT (not-flipped) | MapUtils.java:434-435 |
| 38 | Roundabout slight-RIGHT (flipped) | MapUtils.java:434-435 |
| 39 | Roundabout sharp-LEFT (not-flipped) | MapUtils.java:437-438 |
| 40 | Roundabout sharp-LEFT (flipped) | MapUtils.java:437-438 |
| 41 | Roundabout sharp-RIGHT (not-flipped) | MapUtils.java:440-441 |
| 42 | Roundabout sharp-RIGHT (flipped) | MapUtils.java:440-441 |
| 43 | Off-ramp RIGHT (or generic non-flipped) | MapUtils.java:444,447-449 |
| 44 | Off-ramp (flipped) / Off-ramp LEFT | MapUtils.java:444-446,450-451 |
| 45 | Fork LEFT (or fork-generic flipped) | MapUtils.java:453-454,464-466 |
| 46 | Fork RIGHT (or fork-generic not-flipped) | MapUtils.java:451-452,456-457 |
| 47 | Merge LEFT | MapUtils.java:459-460 |
| 48 | Merge RIGHT | MapUtils.java:462 |
| 49..57 | Reserved for lane-gate `createLane()` enum at MapUtils.java:200-247; SEPARATE icon space not on this path [inferred] |

Fallback: when Mapbox produces a drawable the OEM hasn't mapped, both functions return 9 (straight). [inferred] dash treats 9 as generic "continue."

Glyph atlas location: dash firmware holds bitmaps; no dash-side icon assets in the APK. Mapbox R.drawable refs are PHONE-SIDE for rendering the on-phone projection-encoder view, not what the dash displays.

icon_bitmap fallback (msg_id=1): optional base64 PNG; always null from active callers in this codebase. Historical escape hatch [inferred].

## Trigger / throttle / lifecycle

Source: `<REPO>/phase1/apk/jadx_output/sources/com/thinkerride/oversea/map/listener/send/SelfNaviListener.java`.

1. `onNaviInfoUpdate(SqNaviText)` (line 91-97) — Mapbox callback; takes legacy `msg_id=1` branch if `getProtocolVersion()`/`SqDevice.getCv()` is empty.
2. `onNaviInfoUpdate(SqNaviTextNew)` (line 172-188) — same callback, modern struct; takes `msg_id=27 act=3` branch if `cv` non-empty.
3. `showCrossView(SqNaviCross)` (line 145-147) — fires `sendCross(bitmap)` (msg_id=4) on junction enlargement.
4. `showModeCrossView(SqNaviModeCross)` (line 156-158) — alternative cross.
5. `onArrive` (line 81-87) — fires `endNavi` (msg_id=15) on arrival + TCP `03 0D`.
6. `onNaviStop` (line 123-138) — same on user-cancel.
7. `onNaviStart` (line 100-119) — NO wire push; just flips `setInNavigating(true)` and posts StartNaviEvent.

Cadence: Mapbox-driven. Typically 1 Hz during normal driving, faster near maneuvers [inferred]. No fixed-interval throttle in this code.

Dedup: at GlobalData level (`GlobalData.java:2034-2037, 2045-2048`) — full `jSONObject.toString()` cached as `this.a`; identical JSON silently dropped. JsonManager-level dedup via `isDuplicateSendingInfo(str)` at `JsonManager.java:245-252` (single-slot last-write).

sendCross dedup: in-builder via `isDuplicateSendingInfo(bitmap.toString())` (line 2610). Bitmap.toString() is identity hash so different-instance-same-pixels still re-sends [inferred deliberate].

Lifecycle:
- onNaviStart → no wire push
- per Mapbox tick → msg_id=1 OR msg_id=27 act=3 (deduped)
- at junctions → msg_id=4 with bitmap; clear with empty msg_id=4
- onArrive / onNaviStop → msg_id=15 + TCP `03 0D 00 00 00 00`

## Bootstrap / arm-the-widget sequence

There is no widget-arm step. The dash widget appears as soon as it receives a valid `msg_id=1` or `msg_id=27 act=3` with `cur_retain_distance` set. The OEM does no "begin navigation" prefix before the first sendNaviInfo.

Preconditions:
1. Standard BLE handshake complete (msg_id=10 item=6 firmware version processed at `BleConnectWrapper.java:922-1023`). On `SV=3.0.4` `isActivate()` is implicitly true (no `_TUC=` marker), so msg_id=1 passes the activation gate at `BleConnectWrapper.addPackageToList:2066-2069`. msg_id=15 and msg_id=27 are never gated.
2. Phone-side `SQNavigationManage.getPageAction().isInNaving()` must be true (early-return null in builders at `JsonManager.java:1410, 2659`). Mapbox flips this on `onNaviStart`; a custom client must flip it equivalently, or send JSON directly without going through `JsonManager`.
3. Optional: dash INSIDENAVI capability negotiation. On connect, OEM sends `{"msg_id":27,"func":"INSIDENAVI","query":1}` and `query:2` (`JsonManager.java:317, 345`); dash replies `navi_status` and `voice_status`. Informational, not required.

Minimum sequence after a connected+handshook link to make widget appear:

PHONE → DASH (BLE ffe1, byteCat-framed):
```json
{"msg_id":27,"func":"NAVI","act":3,"icon":2,"next_road":"Pearl Street","cur_retain_distance":"850","cur_unittype":0,"path_retain_distance":"7.2","path_cur_unittype":1,"cur_retain_time":120,"remain_time":<epoch_now+120>,"retain_rate":15}
```

Repeat with updated values. To dismiss:
```json
{"msg_id":15}
```

## Transport details

### Primary: BLE on `ffe1`

- Service UUID: `0000e0ff-3c17-d293-8e48-14fe2e4da212` (bonded / EcologyType=0). Selected at `BleConnectWrapper.java:508`.
- Write characteristic: `0000ffe1-...`, write type `WRITE_TYPE_NO_RESPONSE` (`BleConnectWrapper.java:429-435`). Caveat: HANDOFF.md notes our working Kotlin client uses WRITE_TYPE_DEFAULT with retry — no-response silently dropped writes under load.
- Notify characteristic: `0000ffe2-...`.
- Framing: 104-byte chunk. byte[0]=0xFE, byte[1..2]=seq BE u16 (starts at 0), byte[3..3+innerLen-1]=byteCat(json+'\0'), byte[3+innerLen]=0xFF, byte[3+innerLen+1..103]=0x00. Built at `WriteThread.java:141-180`. Full byte-level recipe in `_re_report_thinkerride.md §2`.
- byteCat CRC: 2-byte nibble-split sum-mod-256 with 0x80 set on both bytes. NOT a polynomial CRC. `ByteUtils.java:25-31`, `getCRCCode:127-133`.
- MTU: app requests 247 (`BleConnectWrapper.java:350`); JSON BLE frames always 104B regardless.
- Fragmentation: payloads >100B are chunked across multiple 104-byte frames with incrementing seq numbers (one per BLE write — `WriteThread.java:347-352`). A 250-byte body becomes 3 chunks with seq N, N+1, N+2. Dash reassembles in `BleConnectWrapper.readMessage:1805-1880`.
- Seq counter must START AT 0 per HANDOFF.md line 328-331 — dash assumes cur_package_index begins at 0.

### Secondary: TCP 17818 binary (parallel push)

When TCP is connected, `JsonManager.sendNaviInfo` and `sendNaviInfoOld` ALSO fire the parallel binary frame via `DeviceWrapper.INSTANCE.sendNaviInfo(...)` (`JsonManager.java:1426, 2674`):

| type=0x03 | sub=0x01 | length_be:u32=268 |
| icon:u32_be (4 B) |
| next_road UTF-8, zero-padded to 256 B |
| cur_retain_distance:u32_be meters (4 B) |
| path_retain_distance:u32_be meters (4 B) |

Total wire length: 274 bytes. Built at `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/ft/process/mobile/device/DeviceMsgFactory.java:181-196`.

Notes:
- Only fires if `deviceWrapper.isOtaConnected()` AND current BLE address matches OTA device MAC (`JsonManager.java:2673`). "OTA" naming is misleading — it's the WiFi/TCP socket flag.
- Strict subset of BLE JSON: no cur_retain_time, no remain_time, no retain_rate, no unit fields (meters always).
- TCP `03 0D 00 00 00 00` is the binary endNavi.
- TCP 17818 JSON-envelope path does NOT handle `msg_id=27 func=NAVI`. The TCP dispatcher routes only TUC/TTS/USER/SIM/INSIDENAVI/AUDIO/THEME/OTA/tBox (`MobileDeviceJsonMsgManager.java:27-61`). NAVI on TCP-JSON silently dropped. Conclusion: NAVI act=3 is BLE-only; the binary `03 01` on TCP is the only TCP-side nav push and it's the legacy shape.

### Tertiary: INSIDENAVI also has a TCP path

`DeviceMsgFactory.sendNaviInsideStart/Stop` at `DeviceMsgFactory.java:198-236` build JSON-envelope TCP-17818 frames. `GlobalData.sendInsideNavi(info, z=true)` selects TCP, `z=false` selects BLE (`GlobalData.java:2010-2016`).

## Inbound (dash → phone)

The dash sends limited nav-related state back. None is a per-frame ACK.

| msg_id | msg_type/item | When | Meaning |
|---|---|---|---|
| 25 | msg_type=2, msg_source=1 | dash-initiated | "Stop navigation." Phone reacts: stopNavigation + endNavi echo. BleConnectWrapper.java:1187-1207 |
| 27 | func=INSIDENAVI, navi_status | dash-initiated | dash started/stopped inside-navi. MsgManager.insideNavi:164-195 |
| 27 | func=INSIDENAVI, voice_status | dash-initiated | TTS-mute status |
| 27 | func=INSIDENAVI, act 1/6/7 | dash-initiated | offline-city download requests |
| 27 | func=ROAD_NAVI, act=file_start/trans/status | dash-initiated | raster tile requests + CRC + loss arrays. BleConnectWrapper.java:1367-1400 |
| 10 | item=4 | dash-initiated periodically | time-sync solicit |

NO per-frame ACK. No "widget displayed" or "user dismissed" event. Closest signal: `msg_id=25 msg_type=2 msg_source=1` (user-requested stop) — unsolicited control message.

## Open questions

1. Which branch does `SV=3.0.4` take — `msg_id=1` legacy or `msg_id=27 act=3` modern? Selection at `GlobalData.java:2028` and `SelfNaviListener.java:92, 173`. `cv` is set when `BleConnectWrapper.handleMessage` processes msg_id=10 item=6. Experiment: capture our dash's raw msg_id=10 item=6 BLE notification and inspect for `cv` field. Safer: send both shapes (already what bt_nav_stream.py does).

2. Does the dash render non-ASCII `next_road`? Oversea OEM forcibly transliterates to ASCII via Transliterator (`SelfNaviListener.java:38, 42`). Experiment: send "Müller Straße" and inspect dash.

3. Kilometer formatting locale risk: `freshMileAndTime` line 181 uses `Math.floor(...) %.1f` (locale-dependent comma/dot); path branch line 187 uses `DecimalFormat("#.0")`. Experiment: send "1,2" comma-decimal and observe. Safer: always use Locale.US/ROOT.

4. Does the dash require TCP 17818 binary `03 01` in parallel with BLE JSON? OEM fires both. Experiment: send BLE-only for 60s and watch.

5. Pushing nav-info above 1Hz — what's the dash tolerance? Experiment: 1/2/5Hz cadences, watch for item=7/9 resend complaints.

6. Does `act=1 sendNaviDest` actually do anything? No OEM caller. Experiment: send on route-set, look for destination string in widget.

7. `icon_bitmap` on msg_id=1 with unknown `icon` value — does dash render our bitmap? OEM never uses this. Experiment.

8. What does `retain_rate` drive on dash UI? Possibly progress bar; possibly nothing. Experiment: sweep 0..100.

9. Searched and NOT found (don't re-investigate):
   - CJK: 导航/转弯/路口/方向/距离/路线/目的地/左转/右转/直行/掉头/出口/环岛 — only `导航` in log strings; no extra protocol fields hidden behind CJK
   - English: lane/lanes/lane_info/lane_count/lane_dir — none in JSON wire path
   - next_next/next_maneuver/step_after/secondary_step — none
   - arrival_time/eta/arrive_at/destination_eta — none (only `remain_time` overloaded)
   - speed_limit — SqNaviSpeedLimit interface exists but `showSpeedLimit` only logs
   - service area — showServiceArea only logs
   - mode/screenmode/screen_mode/nav_mode — none
