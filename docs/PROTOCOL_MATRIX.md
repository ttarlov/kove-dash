# Kove 450 Rally Dash — Communication Protocol Matrix

**Purpose:** the single quick-reference for how the phone talks to the dash. Distilled from
the decompiled OEM apps (ThinkerRide / green_trip / cn_thinkerride — see `docs/re/`) **and**
from what we've actually proven on our own hardware (Pixel 9 Pro ↔ Kove dash, firmware
`SV=3.0.4`). When those two disagree, the empirical column wins.

**How to read the status tags:**

| Tag | Meaning |
|-----|---------|
| ✅ **PROVEN** | Verified on our dash, on the wire (`KoveWire` logcat) — it does what we say. |
| 🟡 **RE-DERIVED** | Straight from OEM decompilation; plausible but not yet confirmed on our unit. |
| 🔬 **PARTIAL** | Tried on our dash; works sometimes / with caveats / not fully understood. |
| ❌ **FAILED** | Tried; does not render / not accepted as-is. |
| ❔ **UNKNOWN** | Not yet attempted. |

> **The one-paragraph mental model.** The dash renders **single-frame** structured messages
> unconditionally (weather always shows). **Multi-frame** messages (native turn-by-turn) need
> a **quiet BLE link** so the frames can reassemble — and that turned out to be the whole
> answer. Native turn-by-turn now renders reliably (**✅**). The recipe: keep the link quiet
> (no `startRide`, no telemetry probe sweep, and do **not** answer the dash's `item=7/9`
> retransmit requests — those replays are what *caused* the congestion), send **both** BLE
> shapes (`msg_id=1` + `msg_id=27`) one-shot per turn, and pace updates ~4 s apart. The nav
> problem was our own link noise, not message *shape*. See §5.

---

## 1. Transport layers

Two independent transports. **Structured live data (weather, altitude, time, turn-by-turn)
is BLE-only.** Wi-Fi/TCP is for video projection, OTA, and the modern `func` channel.

| Layer | Carries | Power | Status |
|-------|---------|-------|--------|
| **BLE GATT** (`ffe1`/`ffe2`) | handshake, time-sync, weather, altitude, speed, native nav, music | LOW | ✅ working |
| **TCP 17818** | dash bootstrap chatter, OTA, modern `func` JSON envelope | LOW (Wi-Fi idle) | ✅ working |
| **TCP 15456** | H.264 video projection (full map) | **HIGH** (encoder) | ✅ working |
| **TCP 15457** | projection heartbeat | — | ✅ |
| **TCP 18888** | DVR | — | ❔ |
| **TCP 19000** | OTA firmware | — | ❔ |

Phone is the **TCP server**; the dash dials in. Ports are fixed (no per-mode variants — the
"simple navi" vs "phone-screen navi" distinction is dash-side only; the phone pushes the same
catalog either way).

### ⚠️ Rendering activation gate (PROVEN, non-obvious)
The dash will **not** render any phone-pushed native widget over BLE until its **Wi-Fi control
channel (TCP 17818) has come up at least once since power-on.** After that it stays armed until
the next power-cycle. Pure BLE-from-cold-boot renders nothing. → Startup must bring up the full
control link (Wi-Fi + 17818 + BLE + handshake), not BLE-only. Video (15456 + encoder) stays
off; that's the real low-power mode. See `memory: kove_dash_wifi_activates_rendering`.

---

## 2. BLE GATT specifics

| Item | Value | Status |
|------|-------|--------|
| Service UUID | `0000e0ff-3c17-d293-8e48-14fe2e4da212` (short `e0ff`) | ✅ |
| Write char | `0000ffe1-…` — properties `0x0C` (WRITE + WRITE_NO_RESPONSE) | ✅ |
| Notify char | `0000ffe2-…` — dash → phone JSON | ✅ |
| Control/large notify | `0000ffe3-…` — we enable it; OEM enables only `ffe2` | 🟡 |
| CCCD | `00002902-…`, write `ENABLE_NOTIFICATION_VALUE` | ✅ |
| MTU | request 247; **not load-bearing** — JSON frames are always 104 B; MTU only sizes OTA chunks | ✅ |
| Device name prefix | `CQKY_` | ✅ |

### 🔑 THE write-type rule (PROVEN — cost us hours)
**All `ffe1` writes MUST use `WRITE_TYPE_NO_RESPONSE`.** `WRITE_TYPE_DEFAULT` (ACK'd write) is
rejected by the dash's GATT server with **ATT `status=14` on every write** — silently dropping
everything. The OEM sets write type `1` (`BleConnectWrapper.java:429-435`). Match it.
*Corollary (§5):* no-response writes are fire-and-forget — no link-layer retransmit — so volume
directly causes packet loss.

### Connection robustness (PROVEN)
- **Connect by saved MAC** (`getRemoteDevice` + `connectGatt`), not by scanning — Android
  throttles repeated BLE scans to zero results.
- If scanning, **pick the strongest RSSI** — multiple stale `CQKY_` bonds advertise; a ghost
  gives a healthy link that renders nothing.

---

## 3. BLE wire framing — `byteCat`

Every BLE JSON message is chunked into fixed **104-byte** frames on `ffe1`.

```
Frame layout (104 bytes, zero-padded):
  [0]        0xFE            start sentinel
  [1..2]     seq (BE u16)    global per-connection counter, wraps at 65535
  [3 .. 3+n-1]  payload chunk (n ≤ 100 bytes of the catted body)
  [3+n]      0xFF            end sentinel
  [rest]     0x00            zero pad
```

**Body construction (before chunking):**
```
body   = utf8(json) + 0x00            # null terminator appended
catted = byteCat(body)               # = json + CRC0 + CRC1 + 0x00
                                     #   (CRC0 OVERWRITES the appended \0, then CRC1, then a new \0)
chunks = catted split into ≤100-byte pieces, each wrapped in a frame above
```

**CRC** (not polynomial — a nibble-split byte sum):
```
sum  = (Σ body bytes) & 0xFF
CRC0 = ((sum & 0xF0) >> 4) | 0x80     # always 0x8?
CRC1 =  (sum & 0x0F)       | 0x80     # always 0x8?
```
CRC bytes are always `0x80–0x8F`, and JSON is ASCII (`<0x80`), so **`0xFF` never appears in a
payload** → the end-sentinel scan is unambiguous. ✅ Framing verified correct (single-frame
weather validates it end-to-end).

### 🔑 Sequence numbering (PROVEN)
- Seq is **global per connection**, **starts at 0**, increments **per frame** (a 3-chunk message
  consumes seq N, N+1, N+2). The dash's loss detector expects contiguous-from-0.
- Reset seq → 0 and clear the resend buffer on **every fresh GATT link** (the dash resets its
  receive cursor to 0). Our `DashBleClient` does this in `onServicesDiscovered`.
- Bug history: stamping every chunk of a multi-frame message with the *same* seq → dash saw
  gaps → `item=9` NAKs → multi-frame never reassembled. Fixed to per-frame seq.

---

## 4. Outbound message catalog (phone → dash)

`A→D` unless noted. "Builder" = our `DashMessages.kt` function. Full JSON shapes live in that
file; this is the index + status.

| msg_id | Name | Builder | Shape (key fields) | Status |
|--------|------|---------|--------------------|--------|
| **1** | legacy native TBT | `naviLegacy` | `icon, next_road, cur_retain_distance, path_retain_distance, remain_time` | ✅ renders (multi-frame, 6 fields) — sent as one of the two shapes on a quiet link; see §5 recipe |
| **11** | **time sync** | `setTime` | `time:"yyyy-MM-dd HH:mm:ss", tag` | ✅ dash drives its clock; echo `tag` on `item=4` solicit |
| **13** | requestVersionCode | `requestVersionCode` | `{}` — reply arrives as `msg_id=10 item=6` | ✅ |
| **15** | endNavi | `endNavi` | `{}` — clears the native nav widget | 🟡 |
| **22** | current speed | `currentSpeed` | `cur_speed` | ❔ |
| **24** | sendLinkInfo (phone identity) | `sendLinkInfo` | `unique_info` (nickname) | ✅ handshake |
| **25/1** | ride state control | `startRide` | `control_info(1=start,2=pause,3=stop,4=report)` + stats | ✅ arms widget layer |
| **25/9** | altitude push | `setAltitude` | `altitude, ave_altitude, max_altitude, head(=descent!)` | ✅ RENDERS — dash elevation field (trip/odo), meters in → feet shown |
| **25/11** | **weather push** | `setWeather` | `weather(int code), temperature(str), wind_power(str)` | ✅ **renders reliably** (single frame) |
| **26** | requestProductType | `requestProductType` | `{}` | ✅ handshake |
| **27** | **`func`-multiplexed** channel | many | `func:"…", act:…` — see §4a | mixed |
| **50** | activateVehicle (BID) | `bidForge` | `bid` — irrelevant on SV=3.0.4 | 🟡 |
| **54** | checkVehicleCurStatus | `checkVehicleCurStatus` | `{}` — dash gates 17818 dial on this | ✅ handshake |

**Notifications/calls (msg_id=2/3): PROBABLE dead-end (2026-07-30).** Built + tested on hardware — dash gives ZERO response to well-formed msg_id=2 (SMS) and msg_id=3 (incall); our JSON is byte-identical to the OEM, no capability gate, no enable message exists (2-agent + code confirm). Same "code in shared firmware, model doesnt expose it" pattern as music. Definitive check = run the real ThinkerRide app on this dash. Not pursued.

**Defined by OEM but not yet built by us** (🟡 RE-DERIVED, all A→D): `2` sendNotification,
`3` incall, `4` sendCross (intersection bitmap), `6` MMS, `7` sendLocation (street; gated by
`isActivate()`), `8` legacy weather, `9` hangup, `12` requestUniqueCode, `14` disconnectBLE,
`16` disableWifiAp, `17` resetSubMileage, `18` requestBleMac, `21` subtotal mileage,
`23` battery.

### 4a. `msg_id=27` — the `func` channel (PROVEN send; native TBT renders)

Sent as `{"msg_id":27,"func":"<NAME>","act":<n>|"<str>", …}`.

| func / act | Builder | Meaning | Status |
|------------|---------|---------|--------|
| `NAVI` act=3 | `naviModern` | modern native TBT: `icon, next_road, cur/path_retain_distance(str), cur/path_unittype, cur_retain_time, remain_time(epoch), retain_rate` | ✅ renders (multi-frame, 3f) — sent alongside `msg_id=1` on a quiet link; see §5 recipe |
| `CAR_INFO` act=`get_car_info` | `requestCarInfo` | query capability flags (`altitude:1`, `weather:1`…) | ✅ handshake |
| `INSIDENAVI` query=1/2 | `queryInsideNaviStatus` / `…VoiceStatus` | nav/voice status probes | ✅ |
| `MUSIC` act=`ret_msg`/`ret_status` | `musicPlayInfo`/`musicStatus` | now-playing / play-state | ❌ NO music screen on Kove 450 Rally (manual + OEM confirm); dash never requests it, cant be forced. Dead end. |

| `GPS/THEME/ROAD_NAVI/KEY/USER` act=0 | `probeFunc` | generic GET probes | ✅ (probe only) |

> **Native nav-icon enum** (`MapUtils.getNavigationTurnCode`): 2=left, 3=right, 4=slight-L,
> 5=slight-R, 6=sharp-L, 7=sharp-R, 8=uturn, **9=straight/fallback**, 21=arrive, 31-42=roundabout
> (by exit), 43-44=off-ramp, 45-46=fork, 47-48=merge.

---

## 5. 🔑 Multi-frame reassembly — the nav crux (SOLVED ✅)

This is why native turn-by-turn was hard and weather was easy — and why it now works.

**Facts (PROVEN):**
1. **Single-frame message → renders unconditionally.** Weather (`25/11`) is one frame; nothing
   to reassemble; always shows.
2. **Multi-frame message → needs a quiet link to reassemble.** The dash parks its receive cursor
   until it has one clean contiguous package, and if it sees gaps it asks the phone to retransmit:
   - `msg_id=10 item=7 {cur_package_index:N}` — "resend from frame index N".
   - `msg_id=10 item=9 {packet_loss_index:N}` — "resend the single lost frame N".
   The OEM's `WriteThread` answers these by replaying buffered frames. **We deliberately do NOT.**
3. **`WRITE_TYPE_NO_RESPONSE` has no delivery guarantee.** Under load the BLE stack silently drops
   frames → dash sees gaps → resend requests → *if we replay*, more writes → more drops
   (**congestion collapse**). Answering the retransmit poll is what *caused* the storm, not what
   cured it. Leaving the link quiet lets the dash reassemble on its own.

**The fix (proven 2026-07-30).** Native turn-by-turn renders reliably when the BLE link is kept
quiet. Concretely:
- **No `startRide`** in the handshake (it flips the dash off the nav page and adds noise).
- **No telemetry probe sweep** after connect (its `NAVI`/`ROAD_NAVI`/`INSIDENAVI` probes both
  congest the link and can reset the nav widget).
- **Do not answer `item=7/9` retransmit requests** — the replay bursts are the congestion source.
  The responder + frame buffer were removed; `handleResendRequest` just logs.
- Send **BOTH** shapes per turn — `naviModern` (msg_id=27, ~3f) + `naviLegacy` (msg_id=1, ~2f) —
  one-shot, and let the dash use whichever its `cv` branch parses.
- **Pace ~4 s** between updates (`NavForwarder` throttle): a new maneuver forwards immediately, a
  distance-only tick on the same maneuver is throttled. ~1.5 s re-congests the link and reassembly
  breaks; ~4 s gives each multi-frame message a clean window.

Upstream dedup (`NavForwarder`) keeps it effectively one-shot-per-turn, so the low volume mirrors
the OEM's ~1 Hz pacing without ever needing the retransmit dance.

---

## 6. Inbound `msg_id=10` — item subtable (dash → phone)

The dash's item-multiplexed channel. Source `BleConnectWrapper.java:874-1126`.

| item | Meaning | Fields | Status |
|------|---------|--------|--------|
| 0 | hangup call | — | 🟡 |
| 1 | speed sample | current, max, average | 🟡 |
| 2 | odometer | total(×10), subtotal(×10) | ✅ seen |
| 3 | car-info | tire_pressure, remaining_oil, endurance | ✅ seen |
| **4** | **time-sync solicit** — echo `setTime(tag)` | tag | ✅ we answer |
| 5 | unicode (TUC) reply | code | 🟡 |
| **6** | **firmware-version reply** (to `msg_id=13`) | version, sysversion, btversion, … | ✅ seen (`SV=3.0.4`) |
| **7** | **resend from index N** | cur_package_index | 🟡 logged, **deliberately not answered** — replaying re-congests the link (see §5) |
| **9** | **single packet loss N** | packet_loss_index | 🟡 logged, **deliberately not answered** — see §5 |
| 10 | auto-connect approval | — | 🟡 |
| 11 | diff-version capability bitmap | screen_info, ota, bt_set, transmission, dvr, form | ✅ seen |
| 12/13/14 | OTA file CRC / loss / resume | crc, file, loss[] | ❔ |
| 53 | lock/active status | lock_status, need_active, bid | 🟡 |

---

## 7. `msg_id=25` — msg_type-multiplexed ("diff-info")

`msg_source=2` = A→D; dash replies with `msg_source=1`.

| msg_type | Meaning | Fields | Status |
|----------|---------|--------|--------|
| 1 | ride state + ride report | control_info, time, calorie, max_speed, ave_speed, total_deep, ave_altitude | ✅ `startRide` |
| 9 | altitude/climb push | altitude, ave/max_altitude, pond_*, head(=descent) | 🔬 |
| 11 | weather push | weather(code), temperature, wind_power | ✅ **renders** |
| 17 | (dash→app broadcast, e.g. altitude echo) | altitude, … | ✅ seen inbound (benign) |

---

## 8. TCP 17818 — binary + JSON envelope

**Binary header:** `[type:u8][sub:u8][len:u32_be][payload]`. **JSON envelope:**
`0xEE 0xFD <len:be4> <UTF-8 JSON> 0x00 0xFF` (trailing `0xFF` mandatory).

| type/sub | Dir | Meaning | Status |
|----------|-----|---------|--------|
| `2/01` (`02 01 00 00 00 00`) | A→D | **heartbeat**, every 2 s | ✅ |
| `1/01` | A→D | requestFirmwareVersion → reply `2/01` | ✅ |
| `1/0E` | A→D | requestProductType → reply `2/09` | ✅ |
| `1/11` | A→D | requestMac → reply `2/08` | ✅ |
| `3/01` | A→D | sendNaviInfo (274-byte binary nav) | ❔ alt nav path |
| `3/0D` | A→D | sendEndNavi | ❔ |
| `EE/FD` | ↔ | JSON envelope — only `msg_id=10` (OTA items 12/13) + `msg_id=27` func handled on TCP | 🟡 |

> **17818 JSON is effectively OTA + the modern `func` channel only.** Location, weather, altitude,
> time-sync are **BLE-only** — do not expect them to work over TCP.

---

## 9. Connect-time handshake (the order we send — PROVEN)

BLE, after `ffe2` notify + CCCD + MTU are up:

```
1.  msg_id=13   requestVersionCode           → dash replies 10/item=6
2.  (wait ~2 s for version reply)
3.  msg_id=11   setTime                       (post-version burst; tag=-1)
4.  msg_id=24   sendLinkInfo
5.  msg_id=26   requestProductType
6.  msg_id=27   CAR_INFO get_car_info
7.  msg_id=54   checkVehicleCurStatus
8.  msg_id=27   INSIDENAVI query=2, then query=1
9.  msg_id=25/1 startRide  (control_info=1 — arms live-widget rendering)
10. msg_id=25/11 setWeather (real Open-Meteo data, on connect)
```
In parallel: TCP 17818 heartbeat (`02 01 …`) every 2 s; answer `10/item=4` time-sync solicits by
echoing `setTime(tag)`.

---

## 10. Gotcha checklist (the expensive lessons)

| # | Lesson | Status |
|---|--------|--------|
| 1 | `ffe1` writes **must** be `WRITE_TYPE_NO_RESPONSE` (else ATT status=14, silent drop) | ✅ PROVEN |
| 2 | Native rendering only arms after **17818 comes up once** per power-cycle | ✅ PROVEN |
| 3 | Seq **per-frame, from 0**, reset on each GATT link | ✅ PROVEN |
| 4 | Single-frame renders always; **multi-frame needs the `item=7/9` retransmit dance** | ✅ PROVEN |
| 5 | No-response writes drop under load → **pace ~1 Hz, don't flood** | ✅ PROVEN |
| 6 | Connect by **saved MAC**; if scanning, **strongest RSSI** | ✅ PROVEN |
| 7 | Distances in nav JSON must be `Locale.US` (dot-decimal; dash mis-parses comma) | 🟡 RE-DERIVED |
| 8 | Weather `code` int mapping is firmware-defined; avoid 0/low codes (clear→2 renders) | 🔬 PARTIAL |
| 9 | `endNavi` = `{"msg_id":15}` clears the widget | 🟡 |

---

## 11. Open questions (for next hardware session)

*Native turn-by-turn is SOLVED (see §5): quiet link + both shapes + ~4 s pacing renders it
reliably. The multi-frame/reassembly questions that used to live here are resolved.*

1. Which shape actually drives the glyph — legacy `1` or modern `27`? We send both (works); we
   haven't isolated which the `SV=3.0.4` dash uses, since one-shot-both was enough.
2. Actual negotiated MTU on the dash dial (we request 247).
3. Does the dash ever use `ffe3` for us? (OEM never does for the normal-data path.)

---

*Sources: `docs/re/_re_report_thinkerride.md` (message tables, framer, CRC), `_re_report_simple_navi.md`
(port/mode analysis), `app/.../proto/DashMessages.kt` (our builders), `app/.../net/DashBleClient.kt`
+ `ByteCat.kt` (our transport), and live `KoveWire` logcat captures 2026-07-27. Empirical status
reflects our `SV=3.0.4` unit as of 2026-07-27.*
