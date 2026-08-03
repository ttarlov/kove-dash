# KOVE → Dash: Turn-by-Turn Navigation Protocol
Date: 2026-05-19
App: com.eryanet.gkove (KOVE_v1.0.10260420, code namespace `com.eryanet.ite`)

All citations are relative paths under `<REPO>/phase2/kove_app/decompile/sources/`. Decompile location confirmed pre-existing — no jadx run needed.

## Bottom line

**Yes — we can replicate this from scratch, almost trivially.** KOVE's turn-by-turn push is a single JSON message: `{"fun":"navi","type":"0|1|2","data":{curStepRetainDistance, iconType, nextRoadName, pathRetainDistance, pathRetainTime}}` wrapped in the Eryanet `"EY"` envelope. Five string fields, no checksum, no sequencing, no encryption, no ACK. The icon enum is 8 values (1–8). **KOVE/Eryanet protocol is fundamentally different from ThinkerRide/SiQi** — different magic bytes (`EY` 0x45 0x59 vs `EE FD`), different BLE service (`aaa0` vs `e0ff`), different framing, zero field-name overlap. The only meaningful gap is whether our specific 2022 dash firmware actually parses Eryanet wire format at all — PROTOCOL.md states our dash speaks the OLD SiQi family, and Eryanet would likely need an OTA we don't have. A subtler caveat: in the **Google Maps path** (`NaviType=0`, default for non-Mapbox regions), KOVE sends only a single `type:"0"` arm message and then nothing — the dash gets TBT via the projected pixel stream (TCP 11111), not via JSON. **Only the Mapbox path emits real-time JSON nav updates.**

## Wire shape: full JSON envelope(s)

### The navi JSON payload

Defined as `BleNaviBean` at `com/eryanet/ite/ble/BleNaviBean.java:4-80`:

```json
{
  "fun":  "navi",
  "type": "<string: '0' | '1' | '2'>",
  "data": {
    "curStepRetainDistance": "<string: meters until next maneuver>",
    "iconType":              "<string: '1'..'8'>",
    "nextRoadName":          "<string: instruction/road name>",
    "pathRetainDistance":    "<string: meters remaining to destination>",
    "pathRetainTime":        "<string: seconds remaining to destination>"
  }
}
```

- `fun` initialized to `"navi"` via `com.eryanet.ite.newvoice.event.IDS.NAVI` constant (`IDS.java:7`, set as default in `BleNaviBean.java:6`).
- **Every field is `java.lang.String`** in the bean (`BleNaviBean.java:10-14`) — even numeric quantities are stringified. Gson emits with double-quotes.
- When `type="0"` or `type="2"`, **`data` is null/absent** (the DataBean is not populated): see `Main2Activity.java:2563-2565, 2572-2574`, `GPSGuidePresentation_Box.java:1534-1536, 1581-1583`, `GPSGuidePresentation_GoogleNavi.java:881-883`. Gson default emits `"data":null`.
- Serialized via `new com.google.gson.Gson().toJson(bleNaviBean)` at `LinkFragment.java:4199`.

### TCP envelope — TCP 11113 (`clientDataHandler.sendJsonData` path)

Built by `ClientSocket.sendTcpMessage(String, byte[])` at `com/eryanet/ite/socket/ClientSocket.java:1541-1564`:

```
offset bytes meaning
 0     2     "EY"        = 0x45 0x59
 2     4     jsonLen_be   (ByteUtil.bigIntTo4Bytes, big-endian uint32)
 6     4     binLen_be    (0 for navi push)
 10    N     UTF-8 JSON
```

No checksum, no sequence number, no terminator.

### BLE envelope — `aaa6` characteristic (`LinkFragment.makePkg` path, the default)

Built by `LinkFragment.makePkg(String)` at `com/eryanet/ite/fragment/link/main/LinkFragment.java:2334-2349`:

```
offset bytes meaning
 0     2     "EY"        = 0x45 0x59
 2     4     jsonLen_be   (big-endian uint32)
 6     N     UTF-8 JSON
```

Identical to TCP envelope minus the 4-byte `binLen` slot. No CRC. No `byteCat`. Relies on the 512-byte MTU negotiated at GATT connect (`LinkFragment.java:540`).

### Localhost-SDK envelope (TCP 18080, NOT the dash)

Used only when `NaviConfig.isThird==true`. Built by `ClientSocket.sendJsonSDK` (`ClientSocket.java:1454-1465`) using `makePackageNaviFun(Func.JSON, NaviFunc.JSON, body)` → 12-byte header `0xAF 0xBB 0xCC 0x0F 00 00 00 00 <len_be:4>` + UTF-8 JSON. Writes to `127.0.0.1:18080` (`ClientSocket.java:62`, `Constants.TCP_PORT_SDK=18080`). **Not addressable from the dash side; ignore.**

### Branching dispatcher

At `LinkFragment.java:4187-4204` (`handleNaviInfo` EventBus subscriber):

```java
if (NaviConfig.isThird) {
    clientSocket.sendJsonSDK(json);          // localhost 18080 — NOT the dash
    return;
}
if (NaviConfig.isTBTOpen && clientDataHandler != null) {
    clientDataHandler.sendJsonData(json);    // TCP 11113
} else {
    if (isP2PConnect) return;                // Wi-Fi Direct mode skips BLE
    packages.offer(makePkg(json));           // BLE aaa6 — DEFAULT PATH
}
```

`NaviConfig.isTBTOpen` is declared `false` at `NaviConfig.java:58` and **never assigned `true` anywhere in the codebase** (grep-confirmed; only references besides the declaration are log statements at `GPSGuidePresentation_Box.java:416` and `LinkFragment.java:4188`). So the **production path is BLE on `aaa6`**, despite the TCP fallback being identical-format and arguably preferable.

## Field semantics

All values flow through `GPSGuidePresentation_Box.sendTBT(...)` at `com/eryanet/ite/navi/presentation/GPSGuidePresentation_Box.java:774-787`.

| Field | Type | Units | Range | Source / citation | Notes |
|---|---|---|---|---|---|
| `fun` | string | n/a | always `"navi"` | `BleNaviBean.java:6`, `IDS.java:7` | Hardcoded discriminator |
| `type` | string | enum | `"0"` start / `"1"` update / `"2"` end | `Main2Activity.java:2563-2565,2572-2574`, `GPSGuidePresentation_Box.java:777-786,1534-1536,1581-1583`, `GPSGuidePresentation_GoogleNavi.java:881-883` | `"0"` re-emitted by Presentation onCreate after Main2Activity already sent one — dash receives `"0"` twice at start |
| `data.curStepRetainDistance` | string | **METERS** | "0" to ~1000+ | `GPSGuidePresentation_Box.java:417,420` — `list.get(0).getStepDistance().getDistanceRemaining() + ""` | Mapbox `Maneuver.getStepDistance().getDistanceRemaining()` (always meters, double). Stringified by `+""` |
| `data.iconType` | string | enum 1-8 | see icon table | `GPSGuidePresentation_Box.java:418` → `transportIcon(modifier)` at `:1124-1146` | Mapped from Mapbox `Maneuver.getPrimary().getModifier()` |
| `data.nextRoadName` | string | UTF-8 | unbounded | `GPSGuidePresentation_Box.java:420` — `list.get(0).getPrimary().getText()` | **NOT just a road name** — it's the full Mapbox instruction sentence (e.g. "Take exit 23 toward Boulder"). Field name is misleading |
| `data.pathRetainDistance` | string | **METERS** | meters | `GPSGuidePresentation_Box.java:399,420` — `tripProgressApi.getTripProgress(routeProgress).getDistanceRemaining()` | Mapbox MapboxTripProgressApi; meters double; stringified via `String.valueOf(double)` |
| `data.pathRetainTime` | string | **SECONDS** | seconds | `GPSGuidePresentation_Box.java:400,420` — `tripProgressApi.getTripProgress(routeProgress).getTotalTimeRemaining()` | **Not minutes**. Phone UI converts to minutes/hours at `setRemainData()` `:840+` |

### Fields NOT on wire (deliberate omissions)
- **Destination name** — `Main2Activity.DESTINATION` is a phone-side static (`Main2Activity.java:5,2569,2586`). Never serialized.
- **ETA (clock time)** — computed phone-side from `pathRetainTime` (`SimpleDateFormat("HH:mm")` at `GPSGuidePresentation_Box.java:846`). Not sent.
- **Lane info** — Mapbox exposes lane components; never read.
- **"Next-next" maneuver** — code uses only `list.get(0)`; `list.get(1)` is never accessed.
- **Roundabout exit number** — `Maneuver.getPrimary().getDegrees()` unused.
- **Maneuver-type beyond 8-icon set** — full Mapbox `type` is logged at `:423` but never transmitted.
- **Coordinates / heading / altitude** — none.
- **Unit selector** — no metric/imperial enum. Dash must convert based on its own settings.

## Turn-icon enumeration

`transportIcon(String)` at `com/eryanet/ite/navi/presentation/GPSGuidePresentation_Box.java:1124-1146`:

| iconType | Mapbox modifier (lowercased, spaces stripped) | Meaning | Citation |
|---|---|---|---|
| `"1"` | `"straightahead"` AND **default (any unmapped)** | Straight / continue / unknown | `:1142-1144` |
| `"2"` | `"left"` | Turn left | `:1136-1137` |
| `"3"` | `"right"` | Turn right | `:1138-1139` |
| `"4"` | `"slightleft"` (via `LanguageCmd.KO_KR="4"`) | Slight/bear left | `:1134-1135` |
| `"5"` | `"slightright"` (via `LanguageCmd.EN_US="5"`) | Slight/bear right | `:1132-1133` |
| `"6"` | `"sharpleft"` (via `LanguageCmd.FR_LU="6"`) | Sharp left | `:1128-1129` |
| `"7"` | `"sharpright"` (via `LanguageCmd.DE_DE="7"`) | Sharp right | `:1130-1131` |
| `"8"` | `"uturn"` (via `LanguageCmd.IT_IT="8"`) | U-turn | `:1140-1141` |

**Code smell to flag:** the developer reused string constants from `com.jieli.lib.dv.control.command.cmd.LanguageCmd` (`com/jieli/lib/dv/control/command/cmd/LanguageCmd.java:5-26`) — a Jieli DVR SDK language enum — as quick string-typed numeric literals. Names mean nothing; only underlying string values (`"4"..."8"`) matter. Not related to navigation.

### Dash's glyph atlas (inferred)
The dash firmware owns the glyph table. Phone-side widget at `com/eryanet/ite/navi/view/NextTurnTipView.java:14` reveals the AMap/AutoNavi-style index (`sou0..sou19` mipmaps, 21 slots) used in the AMap legacy path. **`[inferred]`** the dash supports at least iconType 1–8 and likely the full AMap range 1–20 — `NextTurnTipView.setIconType(int)` has bounds check `if (i <= 20)` at `:58`. KOVE never emits values >8.

## Trigger / throttle / lifecycle

### Trigger
Wired to Mapbox's `RouteProgressObserver.onRouteProgressChanged(RouteProgress)` at `GPSGuidePresentation_Box.java:1560`. Fires on every Mapbox-internal RouteProgress update, typically **~1 Hz** synced to GPS fixes. Not time-based — no Timer/Handler on the wire path. The call: `:412-431`, the `sendTBT` call site is `:420`.

### Throttle
**None at app level.** No debounce, no distance threshold, no min-interval timer. The only gate is `!NaviConfig.isJPEG` at `:419` (suppresses BLE push when the JPEG mirror fallback is active). BLE queue is a `LinkedBlockingDeque` with 100ms write retries and no back-pressure — high-rate updates grow the queue.

### Lifecycle
1. **Start `type:"0"`** — `Main2Activity.startNavi(NaviEndInfo)` (`Main2Activity.java:2559-2592`) creates a `BleNaviBean(type="0", data=null)` and posts to EventBus. Then constructs `GPSGuidePresentation_Box` which **emits a second `type:"0"`** from its show path (`GPSGuidePresentation_Box.java:1581-1583`). Net: dash receives `type:"0"` twice.
2. **Updates `type:"1"`** — each Mapbox `onRouteProgressChanged` callback → `sendTBT` → `BleNaviBean(type="1", data=...)`.
3. **Arrive** — when `routeProgress.getCurrentState()==COMPLETE` (`:432`), a 2-second delayed Runnable posts `NaviCompleteBean(false)` and `dismiss()`es the Presentation (`:433-439`).
4. **Stop `type:"2"`** — `OnDismissListener.onDismiss` (`:1530-1551`) posts `BleNaviBean(type="2", data=null)` at `:1534-1536`.

The Google Maps path (`GPSGuidePresentation_GoogleNavi`) emits **only the `type:"0"` arm message** at construction (`:881-883`). **No `type:"1"` updates and no `type:"2"` stop in that path.** Confirmed by grep — `sendTBT` exists only in `GPSGuidePresentation_Box`.

## Bootstrap / arm-the-widget sequence

No separate "enable widget" handshake. The lifecycle IS the bootstrap. Minimum sequence for a paired+activated dash:

```
1. (one-time prereq) Full Eryanet link: BLE GATT scan + connect to dash advertising
   service 0000aaa0-..., MTU=512, read aaa5 for MAC+activeKey, push hotspot creds to
   aaa2/aaa3/aaa4, read aaa1 for dash IP, run cloud activate (POST
   cloud.eryanet.com/.../activate/code/motor/iteration) + cloud bind (POST
   gcloud.eryanet.com/.../device/bind). See _re_report_eryanet.md §9.

2. Send {"fun":"navi","type":"0"} via BLE aaa6 EY envelope (or TCP 11113 if isTBTOpen).
   Wire: 45 59 <jsonlen_be:4> <utf8 json>

3. Send periodic {"fun":"navi","type":"1","data":{...}} (~1Hz, the rate your nav
   engine produces).

4. Tear down: {"fun":"navi","type":"2"}.
```

No challenge/response. No prior `naviControl` dance. No mode-set message.

## Transport details

| Aspect | BLE path (default) | TCP path (dead code) | Localhost-SDK (NOT dash) |
|---|---|---|---|
| Trigger | `!isP2PConnect && !isTBTOpen` | `isTBTOpen && clientDataHandler != null` | `NaviConfig.isThird==true` |
| Endpoint | service `0000aaa0-0000-1000-8000-00805f9b34fb`, characteristic `0000aaa6-0000-1000-8000-00805f9b34fb` (WRITE) | TCP 11113 (`Constants.TCP_PORT_MESSAGE=11113`) | TCP 127.0.0.1:18080 |
| Header | `EY + jsonLen_be:4 + json` (6B) | `EY + jsonLen_be:4 + binLen_be:4 + json` (10B) | `AF BB CC 0F 00 00 00 00 + len_be:4 + json` (12B) |
| MTU / fragmentation | 512B negotiated at connect (`LinkFragment.java:540`), no app-level fragmentation | TCP stream | TCP stream |
| Retry | `SendThread` retries failed writes every 100ms forever | Fire-and-forget via `offerTcpDatas` queue | Fire-and-forget |
| Citation | `LinkFragment.java:4196-4202, 2334-2349, 3731-3754` | `LinkFragment.java:4193-4194`, `ClientSocket.java:1541-1564` | `LinkFragment.java:4189-4191`, `ClientSocket.java:1454-1465` |

A representative `type:"1"` JSON is ~175 bytes UTF-8 + 6B envelope ≈ 181B per BLE write — well under the 512B MTU. Long Mapbox instruction strings or Chinese road names (3B/char) could push 300B; still fits.

## Inbound (dash → phone)

**Dash does not ACK navi pushes.** No `msg_id`, no reply handler. Grep on `BleNaviBean`, `"navi"`, `naviControl` confirms zero consumers of a dash-originated nav ACK or status.

Dash CAN originate `naviControl` messages on TCP 11113 (parsed at `com/eryanet/ite/socket/ClientDataHandler.java:104-112`):

| Inbound JSON | Meaning | Citation |
|---|---|---|
| `{"naviControl":"exitNavi"}` | Dash user pressed exit — phone tears down navigation | `ClientDataHandler.java:106-107` → `clientFuncInter.exitNaviEYLink()` (`LinkFragment.java:845`) |
| `{"naviControl":"showFirst"}` | (handler is empty branch — purpose unclear) | `:108` |
| `{"naviControl":"exitConnect"}` | Disconnect / kill the YiKu session | `:108-111` |

These are unsolicited notifications, not replies. They give the dash a way to say "stop guiding me".

## Maps SDK in use

KOVE ships **two** nav SDKs, selected at activation by an Eryanet backend flag.

### The selector
`com/eryanet/ite/activate/CheckManager.java:108-112`:
```java
if (jSONObjectOptJSONObject.optInt("mapSource") == 4) {
    NaviConfig.NaviType = 1;  // → Mapbox
} else {
    NaviConfig.NaviType = 0;  // → Google
}
```
`mapSource` comes from `POST https://cloud.eryanet.com/rcg/basic/useable/activate/code/motor/iteration` response — Eryanet's backend per-user/per-region. Mapbox for China-blocked regions or vice versa.

### Path A — Mapbox (`NaviType=1`)
`com/eryanet/ite/navi/presentation/GPSGuidePresentation_Box.java`. Uses:
- `com.mapbox.navigation.core.MapboxNavigation` for routing
- `com.mapbox.navigation.ui.maneuver.api.MapboxManeuverApi` (`:82`)
- `com.mapbox.navigation.core.trip.session.RouteProgressObserver` (~1Hz callback, `:115,394`)
- `com.mapbox.navigation.ui.tripprogress.api.MapboxTripProgressApi` (`:121`)
- `com.mapbox.navigation.ui.voice.api.MapboxSpeechApi` (TTS, `:120`)
- `com.mapbox.navigation.ui.maps.camera.NavigationCamera`

**This is the ONLY path that pushes JSON nav to the dash via the BLE/TCP wire.**

### Path B — Google Maps Platform Navigation SDK (`NaviType=0`)
`com/eryanet/ite/navi/presentation/GPSGuidePresentation_GoogleNavi.java`. Uses `com.google.android.libraries.navigation.NavigationView` / `Navigator`, plus `com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager` with Bundle/Messenger NavInfo handoff via `NavInfoReceivingService.java`. **The Presentation surface itself is what the dash sees via TCP 11111 projection** — the JSON channel carries only the one-time `type:"0"`.

### Path C — AMap / AutoNavi
Found only as legacy: `com.eryanet.ite.navi.view.NextTurnTipView` (the `sou*` icon table) and `com.eryanet.ite.navi.util.ChString`. No active AMap SDK driver in the wire path.

### Path D — Baidu / Gaode / other Chinese SDKs
Searched English + Chinese terms (导航/转弯/路口/方向/距离/路线/目的地/左转/右转/直行/掉头/出口/环岛). No active Chinese-SDK nav integration in `com.eryanet.ite`. The Chinese terms appear only in localized string resources.

## How this differs from the OLD (ThinkerRide) protocol

| Dimension | KOVE / Eryanet | ThinkerRide / SiQi |
|---|---|---|
| Magic bytes | `0x45 0x59` ("EY") | `0xEE 0xFD` |
| TCP header on the control channel | 10B (2 magic + 4 jsonLen + 4 binLen) | variable: binary `01 XX <len_be:4>` or JSON envelope `EE FD <len_be:4>...FF` |
| BLE service | `0000aaa0-...` (16-bit alias) | `0000e0ff-3c17-d293-8e48-14fe2e4da212` |
| BLE write char | `aaa6` (multi-channel JSON pipe) | `ffe1` (raw frames + `byteCat` CRC) |
| BLE framing overhead | 6 bytes (EY + jsonLen_be) | 104-byte fixed frames with `0xFE + seq_be:2 + payload + 0xFF + zeropad` + custom CRC |
| Nav payload | JSON `{"fun":"navi","type":"0/1/2","data":{5 strings}}` | Binary `03 01 <len_be:4>` + `distance:u32 + street:256B + icon:u32 + time:u32` (Format A) — or msg_id 1 JSON over BLE |
| Phone role | TCP CLIENT, dash is server | TCP SERVER, dash dials in |
| Activation gate | Eryanet cloud (carAppId + 32-char activeKey) | SiQi cloud (16+char TUC, validated only by `isEffectiveTuc()` length check) |
| Field-name overlap | zero | zero |

**Net:** a client built against KOVE's protocol will not work on our 2022 dash, and vice-versa. Zero wire-level overlap.

## Open questions

1. **Does our 2022 dash speak Eryanet "EY" at all?** PROTOCOL.md states our firmware `SV=3.0.4` speaks the OLD SiQi protocol. **Resolve:** scan our dash for service `0000aaa0-...` — if absent, KOVE's nav protocol is not addressable; stop pursuing. If present, write `45 59 00 00 00 0D 7B 22 66 75 6E 22 3A 22 6E 61 76 69 22 7D` to `aaa6` and observe.
2. **What does the dash actually render from `nextRoadName`?** The source feeds the full Mapbox `primary.getText()` instruction sentence — not a road name. Does the dash display the whole sentence? **Resolve empirically** — code can't tell us.
3. **What happens with `iconType` 9–20?** KOVE never emits >8, but `NextTurnTipView` caps at 20 (`:58`). Dash likely supports roundabout/exit/merge glyphs `[inferred]`. **Resolve empirically.**
4. **`pathRetainTime` as float vs int** — `String.valueOf(double)` produces `"1234.5"`. If the dash uses `atoi()`, decimal is silently truncated. Untestable from source.
5. **Gson `"data":null` on `type:"0"`/`type:"2"`** — does the dash parser tolerate explicit `null`? Untestable from source.
6. **Hidden unit-selector message?** Searched `unit`, `mile`, `km`, `metric`, `imperial` in `com.eryanet.ite` — only UI-side strings. Dash appears to interpret all wire numbers as meters/seconds and convert based on its own preference.
7. **`LinkFragment.handleJson(String)` failed to decompile** (`:1879-1888`, jadx instruction count 940). Could hide a dash→phone navi handler. Recovery: `jadx --show-bad-code` or baksmali. Low priority — outbound (phone→dash) is fully traced.
8. **`NaviConfig.isTBTOpen` never set true** in the codebase. May be flipped via intent broadcast (analogous to `LockNaviBroadcastReciver` flipping `isSDK`). Low priority — BLE path works regardless.
