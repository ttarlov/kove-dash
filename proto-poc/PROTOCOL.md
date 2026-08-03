# Kove 450 Rally Dash — Protocol Reference

Reverse-engineered from ThinkerRide APK `oversea.whbluestar.thinkerride` (jadx-decompiled at `<REPO>/phase1/apk/jadx_output/sources/`) + live dash traffic on a Kove 450 Rally with firmware `UC=20221123121657816_PL=202006001_DA=240319_FL=01_SV=3.0.4`.

**Last refresh:** 2026-05-15 — **projection working end-to-end. Custom H.264 frames render on dash.**

---

## TL;DR — what makes it work

The full sequence (this got us streaming custom H.264 frames to the dash):

1. **Phone joins dash WiFi AP** `CQKY_XXXXXXXXX` (password on dash screen).
2. **BLE pair and OEM handshake** (msg_id 13 → 24 → 26 → 54 → INSIDENAVI queries). Telemetry starts flowing on `ffe2`.
3. **Phone-side TCP server listening** on all 5 ports bound to the phone's dash-subnet IP.
4. **Dash dials TCP 17818** → run the OEM bootstrap (queryActivateStatus, requestFirmwareVersion, sendDeviceType=2, sendLinkInfo, requestProductType, requestMac, queryInsideNaviStatus).
5. **RIDER long-presses the UP button on the dash itself.** ← This is the projection trigger. It is NOT in the decompiled APK source — it's documented in Kove owner manuals only.
6. **Dash dials TCP 15456 (video) + 15457 (heartbeat).**
7. **Phone sends 69-byte handshake** on 15456: `flag=0x00 + "android".padEnd(64,'\0') + width_be:2 + height_be:2`.
8. **Phone streams H.264 Annex-B frames** on 15456 at 30fps. Heartbeat `02 01 00 00 00 00` on 15457 every ~450ms. Dash decodes and renders.

The session-long mystery of "why doesn't the dash dial 15456?" was step 5. The OEM app's UI button does it via a software path; the rider's long-press does it via the dash firmware path. We never tried the manual gesture because no source agent told us about it. **Italian owner-manual was the source.**

---

## Wire format summary

- **Phone is TCP server.** Dash dials in. Five ports: 17818 (device control), 15456 (projection video), 15457 (projection heartbeat), 18888 (DVR), 19000 (OTA).
- **17818 speaks TWO wire formats**: bare binary `[type][sub][len_be][payload]` AND JSON envelope `0xEE 0xFD <len_be> <utf8_json> 0xFF`. They share the same socket.
- **15456 = raw H.264 NALU stream** after the 69-byte handshake. Annex-B framing (`00 00 00 01` start codes). 1280×640 confirmed working.
- **BLE wire framing is `0xFE | seq_be:2 | byteCat(json+'\0') | 0xFF` chunked to 100B**, padded to 104B per write. The `byteCat` "CRC" is a nibble-split byte checksum (`getCRCCode`) with 0x80 set in each nibble.
- **TUC activation forge** (`act:SAVE` + `act:STATUS tucs:1`) is sent by `dash_server.py` defensively but appears unnecessary for `SV=3.0.4` firmware (no `_TUC=` marker means `isActivate()` is implicitly true). Belt-and-suspenders kept in the script.

---

## Topology

```
Phone (Mac/Android) <----- TCP ports 17818, 15456, 15457, 18888, 19000 ----- Dash
       (server)                                                            (client)

   +  BLE GATT  (dash advertises 0000e0ff-..., phone is GATT client) +
```

- The **phone is the TCP server** for all 5 dash-protocol ports.
- The **dash is the TCP client** — it ARP-scans the local subnet and dials in.
- The dash dials 17818 within ~150 ms of joining the dash WiFi AP.
- **15456/15457 are dialed independently** by the dash once it considers itself "activated and ready" — there is **no out-of-band wire signal that triggers this**. The dash decides internally based on its own state.
- **Single-client AP:** the dash AP only accepts ONE WiFi client at a time.

## Network setup (Mac)

- Join dash AP — SSID `CQKY_*`. **Password is persistent across power cycles** (only changes on factory reset). Save it in macOS Keychain once and forget.
- Mac receives DHCP lease, becomes e.g. `192.168.10.2`
- Dash is AP and DHCP server, e.g. `192.168.10.1`
- **The Mac cannot be on the dash AP and home WiFi simultaneously** (single-radio constraint). For any bench session that needs internet (decompile lookups, pip installs, gh CLI, AI assistant access), tether internet from the **Pixel 9 Pro** — either USB-C tether (preferred — appears as `en7` and routes around the WiFi conflict) or Pixel hotspot over a 2nd Mac WiFi interface if available. The Mac's primary WiFi radio stays on the dash AP for the duration.
- **AP password:** OEM app gets it via QR-code scan only. No BLE characteristic delivers it. The QR on the dash screen is the source of truth on first setup.

## Port map

| Port  | Purpose            | OEM file                                    |
|-------|--------------------|---------------------------------------------|
| 17818 | Device control     | `DevicePortController.java`                 |
| 18888 | DVR (dashcam)      | `DvrPortController.java`                    |
| 19000 | OTA firmware       | `OtaPortController.java`                    |
| 15456 | Projection video   | `ProjectionNaviPortController.java`         |
| 15457 | Projection heartbeat | `ProjectionHeartPortController.java`      |

The dash currently dials only 17818 on its own in our experiments. 15456/15457 are the unsolved trigger.

---

## TCP 17818 — device control channel

**Two wire formats share this socket.**

### Format A — Binary

```
+--------+-------+--------+--------+--------+--------+-------------------+
| type:1 | sub:1 |  payload_length: u32 big-endian (4 bytes)             |
+--------+-------+--------+--------+--------+--------+-------------------+
|                payload (payload_length bytes)                          |
+------------------------------------------------------------------------+
```

### Format B — JSON envelope (NEW in this rev)

```
+------+------+-----+-----+-----+-----+----------------------+------+
| 0xEE | 0xFD |    payload_length: u32 big-endian (4 bytes)  | ...  |
+------+------+-----+-----+-----+-----+----------------------+------+
|       UTF-8 JSON body (payload_length bytes)                | 0xFF|
+-------------------------------------------------------------+-----+
```

Source: `DeviceMsgFactory.generateByteData()` (DeviceMsgFactory.java:32-42) and `UnicodeHelper.toWifiBytes()` (UnicodeHelper.java:180-192). All `msg_id 27 / func=...` messages travel in this envelope.

### Confirmed Format-A messages

| Direction | Bytes (hex) | Meaning | Source file:line |
|---|---|---|---|
| Phone → Dash | `01 01 00 00 00 00` | requestFirmwareVersion | DeviceMsgFactory.java:65-68 |
| Dash → Phone | `01 02 00 00 04 06 + ~1030 ASCII bytes` | Firmware response: `UC=<build>_PL=<platform>_DA=<date>_FL=<...>` | — |
| Phone → Dash | `01 0C 00 00 01 00 + 256B nickname` | sendLinkInfo (phone identifies) | DeviceMsgFactory.java:169-179 |
| Phone → Dash | `01 0E 00 00 00 00` | requestProductType | DeviceMsgFactory.java:134-141 |
| Phone → Dash | `01 11 00 00 00 00` | requestMac | DeviceMsgFactory.java:88-95 |
| Dash → Phone | `01 12 00 00 00 20 + 32B ASCII MAC` | MAC response — `XX:XX:XX:XX:XX:XX` zero-padded |
| Phone → Dash | `01 13 00 00 00 04 + 4B int` | setLanguage(int) | DeviceMsgFactory.java:285-294 |
| Phone → Dash | `01 14 00 00 00 00` | requestLanguage | DeviceMsgFactory.java:79-86 |
| Dash → Phone | `01 0F 00 00 00 04 + 4B int` | Language response (3 = our dash) |
| Dash → Phone | `01 15 00 00 00 04 + 4B int` | Status / state code (meaning unknown) |
| Phone → Dash | `01 16 00 00 00 00` | sendRemoveBound (unpair) | MsgFactory.java:28-35 |
| Phone → Dash | `01 17 00 00 00 04 00 00 00 02` | sendDeviceType — value 2 = phone | MsgFactory.java:8-17 |
| Phone ↔ Dash | `02 01 00 00 00 00` | Heartbeat (1 Hz both directions) | DeviceMsgFactory.java:165-167 |
| Phone → Dash | `03 01 00 00 01 0C + 268B struct` | sendNaviInfo: distance(4) + street(256) + icon(4) + time(4) | DeviceMsgFactory.java:181-196 |
| Phone → Dash | `03 0D 00 00 00 00` | sendEndNavi | MsgFactory.java:19-26 |

### Confirmed Format-B (JSON envelope) messages

All start with `EE FD <len_be:4>` and end with `FF`. Body is UTF-8 JSON, no trailing newline.

| Method | JSON body | Purpose | Source file:line |
|---|---|---|---|
| `queryActivateStatus` | `{"msg_id":27,"func":"TUC","act":"GET"}` | Ask dash for current TUC + tucs | JsonManager.java:410-422 |
| (dash → phone reply) | `{"msg_id":27,"func":"TUC","act":"SEND","tuc":"...","tucs":<int>}` | Dash's reported activation state | MsgManager.java:724-754 |
| `writeUniCode` | `{"msg_id":27,"func":"TUC","act":"SAVE","tuc":"..."}` | Phone tells dash "your TUC is this" | UnicodeHelper.java:241-249 |
| `writeActivateStatus` | `{"msg_id":27,"func":"TUC","act":"STATUS","tucs":1}` | Phone tells dash "you are activated" | UnicodeHelper.java:224-234 |
| `queryDevicePlayerVoiceStatus` | `{"msg_id":27,"func":"INSIDENAVI","query":2}` | Bootstrap step | JsonManager.java:317-329 |
| `queryInsideNaviStatus` | `{"msg_id":27,"func":"INSIDENAVI","query":1}` | Bootstrap step | JsonManager.java:345-357 |
| `requestUpdateInfo(0)` | `{"msg_id":27,"func":"OTA","act":1,"version":0}` | Fired only when TUC enabled | JsonManager.java:784-789 |
| `requestICCID` | `{"msg_id":27,"func":"SIM","act":"REQUEST"}` | Read SIM info | DeviceMsgFactory.java:70-77 |
| `requestCarInfo` | (see JsonManager:483) | Vehicle info |
| `sendThemeTask(s)` | (see JsonManager) | Switch dash theme |

## OEM bootstrap sequence (exact wire order)

This is what the OEM ThinkerRide app does. Reproducing this is the working hypothesis for triggering 15456.

**On 17818 socket accept** — `DeviceWrapper.onDeviceConnection()` (DeviceWrapper.java:283-300):

1. `queryActivateStatus` → `EE FD <len> {"msg_id":27,"func":"TUC","act":"GET"} FF`
2. `requestFirmwareVersion` → `01 01 00 00 00 00`
3. `sendDeviceType` → `01 17 00 00 00 04 00 00 00 02`
4. `sendLinkInfo` → `01 0C 00 00 01 00 + <256B nickname zero-padded>`
5. (if app is currently navigating:) `sendNaviInfo(1, "123", 2, 3)`
6. EventBus.post(WifiConnectionEvent(2))

**Dash replies with firmware version** (Format A `01 02 ...` 1030B ASCII). This fires `DeviceWrapper.onDeviceReplyFirmwareVersion()` (DeviceWrapper.java:311-368):

7. `requestProductType` → `01 0E 00 00 00 00`
8. `requestMac` → `01 11 00 00 00 00`
9. (sets versionString from reply, parses TUC out of it, posts WifiConnectionEvent(4) + (2))
10. `queryDevicePlayerVoiceStatus` → `EE FD <len> {"msg_id":27,"func":"INSIDENAVI","query":2} FF`
11. `queryInsideNaviStatus` → `EE FD <len> {"msg_id":27,"func":"INSIDENAVI","query":1} FF`
12. (if `ConnectHelper.isTucEnable(tuc)`:) `requestUpdateInfo(0)`

**Steady state:** mutual heartbeat `02 01 00 00 00 00` every ~1 s.

---

## Activation gate — THE projection blocker

### Where it's enforced

Phone-side check: `ProjectionService.isAllowShowProjection()` (ProjectionService.java:316-321) returns true iff `isActivate() || isWifiDevice`. `isActivate()` reads `tucs == 1` in RAM (DeviceWrapper.java:445-462). Without it, `prepareProjectionScreen()` logs `"不允许投屏"` ("projection not allowed") and bails.

The DASH side appears to do an equivalent check before dialing 15456. Symptom: even with a complete 17818 bootstrap, an un-activated dash never dials 15456.

### How the OEM activates a dash (the legitimate path)

Step-by-step from `ActivationFragment.java` / `UnicodeHelper.java`:

1. **App asks SiQi backend for a TUC for this dash**
   `POST https://iov.edaoduo.com/prod/gw/api/api/car/unique` form fields `token` + `version` (the full firmware string).
   Response: `data.tuc` (a server-issued string).
   Source: `IRequestApi.getCarUnique` (IRequestApi.java:161-162), base URL `BaseUrlManager.java:37`.

2. **App writes the TUC to the dash** via 17818:
   `EE FD <len> {"msg_id":27,"func":"TUC","act":"SAVE","tuc":"<server_value>"} FF`
   Source: `UnicodeHelper.writeUniCode` (UnicodeHelper.java:241-249).

3. **App tells backend to formally activate this TUC**
   `POST .../api/car/active` form fields `token` + `version` (with TUC spliced in).
   Source: `IRequestApi.requestCarActivation` (IRequestApi.java:431-433). HTTP 200 or domain code 30503 = success.

4. **App writes activation status to the dash** via 17818:
   `EE FD <len> {"msg_id":27,"func":"TUC","act":"STATUS","tucs":1} FF`
   Source: `UnicodeHelper.writeActivateStatus` (UnicodeHelper.java:224-234).

5. **App can later verify** by sending `act:GET`. Dash replies `act:SEND` with `tucs:1` and the stored `tuc`.

### The validator — and why we think we can forge

`TucUtil.isEffectiveTuc()` (com/thinkerride/tbox/util/TucUtil.java:14-16):

```java
public static boolean isEffectiveTuc(String str) {
    return (TextUtils.isEmpty(str) || "F".equalsIgnoreCase(str) || str.length() < 16) ? false : true;
}
```

**That is the entire check.** Any non-empty, non-"F", >=16-character string is "valid". No HMAC, no signature, no server validation in the dash itself.

The decompiled source path from phone → dash JSON contains NO challenge/response, NO MAC, NO key derivation. So the dash appears to trust whatever the phone writes.

**Forge candidate:** `"AAAAAAAAAAAAAAAA"` or `"KOVE450RHACK0123"`. Send via two writes:

```
EE FD <len> {"msg_id":27,"func":"TUC","act":"SAVE","tuc":"KOVE450RHACK0123"} FF
EE FD <len> {"msg_id":27,"func":"TUC","act":"STATUS","tucs":1} FF
```

Then re-query with `act:GET` and check the response. If dash returns `tucs:1` with our forged tuc, we've broken the gate — projection should follow.

This is what `dash_server.py` (post-2026-05-15 update) does automatically on 17818 connect.

### Bypass routes if forge fails

1. **mitmproxy the backend.** Two endpoints: `/api/car/unique` (return `{"code":200,"data":{"tuc":"deadbeef01234567"}}`) and `/api/car/active` (return `{"code":200}`). Point OEM app at proxy → one real activation cycle → dash stores valid TUC permanently → use legitimately from Python afterwards.
2. **Patch the OEM APK.** Smali edits to `BleConnectWrapper.isActivate()`, `DeviceWrapper.isActivate()`, `ProjectionService.isAllowShowProjection()` all → `return true`. Useful if we want the OEM app to bypass; doesn't help our Python client.

---

## Projection (port 15456) — known facts

From source (`ProjectionEncoder.java` lines 86, 210, 238–250):

- **Codec:** H.264 / `video/avc`, High profile, yuv420p
- **Frame rate:** 30 fps
- **I-frame interval:** 1 second
- **Bitrate:** ~3 × width × height on high-RAM devices
- **Virtual display DPI:** 320
- **69-byte handshake** sent by phone to dash on first frame:

```
+--------+------------------------------------------+---------+---------+
| flag:1 | platform_utf8: 64 bytes (zero-padded)    | width:2 | height:2|
+--------+------------------------------------------+---------+---------+
```

For Kove 450 Rally horizontal screen: `width=1280`, `height=640`. Platform = `"android"` (7 bytes + 57 zeros). Flag = `0x00`.

Then raw H.264 NALU bytes (Annex-B framed with `00 00 00 01` start codes).

Heartbeat on port 15457: `02 01 00 00 00 00` every ~450 ms (from `ProjectionHeartWriter.java`).

---

## Phone-side projection state machine

For reference (not needed by our Python client — we always have the socket open):

1. `WifiNetworkListenThread` detects WiFi network → binds the `:wifi` process to it → fetches phone IP via `WifiManager.getDhcpInfo().ipAddress` → `WifiNetManager.freshNetwork(ip, network)`.
2. `Account.isOnline()` flips true once the main process sends `CommonProcessMsg(act=3, mobile, token)` via AIDL.
3. `SocketListenThread.run()` (one per port) unblocks once BOTH conditions hold, then `new ServerSocket().bind(phoneIp, port).accept()`.
4. When dash dials 15456, `MobileMsgCenter.onPortConnected(15456)` → `ProjectionWrapper.setConnected(true)` → `ProjectionConnectionChangeEvent(1)` posted.
5. `ProjectionService` reacts: creates VirtualDisplay, starts MediaCodec, starts `StreamThread` which sends the 69-byte handshake then frames.

Phone-side gate fires on `WifiConnectionEvent(9)` (set after `ConnectHelper.updateFunction` runs with the dash's ecology/function type). Without it, `ProjectionService.k = false` and frames don't render onto the screen even if the TCP connection lands.

---

## BLE — the side channel

The dash exposes a BLE GATT server. The phone connects to it and gets a live telemetry feed *plus* an out-of-band command channel.

### Discovery

| Field | Value |
|---|---|
| Advertised name | `CQKY_XXXXXXXXX` (same as Wi-Fi SSID) |
| Advertised service UUID | `0000e0ff-3c17-d293-8e48-14fe2e4da212` (generic Chinese BLE SDK template — used by Instax, Supvan, xBloom) |
| Manufacturer data prefix | `c004208000` + 6-byte BT MAC big-endian |
| Public BT MAC (from Wi-Fi-side query) | `XX:XX:XX:XX:XX:XX` |

### GATT services & characteristics

| Service | Characteristics |
|---|---|
| `0000e0ff-...` | `ffe1` (write-no-response), `ffe2` (notify), `ffe3` (read/write/notify — but **OEM never touches ffe3**) |
| `0000180a-...` | Device Information (standard SIG) — mostly empty on this dash |
| `0000180f-...` | Battery Service — char `0x2a19` returns `0x00` |

### **BLE wire framing (CRITICAL — explains all our prior failed writes)**

Source: `WriteThread.addPackageToList()` (WriteThread.java:141-180) + `ByteUtils.byteCat()` + `ByteUtils.getCRCCode()`.

Each BLE write to `ffe1` is exactly **104 bytes** in this layout:

```
byte[0]   = 0xFE                       (frame start)
byte[1-2] = seq number (u16, BE)       (incremented per packet)
byte[3..3+chunk_len-1] = payload chunk (up to 100 bytes)
byte[3+chunk_len] = 0xFF              (frame end)
byte[3+chunk_len+1 .. 103] = 0x00     (zero padding)
```

The payload itself is `byteCat(json_str + '\0')`:

```python
def byte_cat(buf: bytes) -> bytes:
    """Replicates ByteUtils.byteCat. Overlays a 2-byte 'CRC' on the last byte."""
    crc = get_crc_code(buf)                  # 2 bytes
    out = bytearray(len(buf) + 2)            # zero-init, len + 2
    out[:len(buf)] = buf
    out[len(buf)-1:len(buf)+1] = crc         # overlaps last payload byte
    return bytes(out)

def get_crc_code(buf: bytes) -> bytes:
    """Nibble-split byte-sum with 0x80 high bit. NOT a real CRC."""
    total = 0
    for b in buf:
        total = (total + b) & 0xFF
    return bytes([((total & 0xF0) >> 4) | 0x80,
                  ( total & 0x0F)       | 0x80])
```

Effectively: `out = json_bytes[:-0]` + `crc_high` + `crc_low` + `0x00`. The trailing `\0` of `(json + '\0')` is replaced by `crc_high`, then `crc_low` and a final 0 are appended.

Chunking: payloads > 100 bytes get split across multiple 104-byte BLE frames, each with the same seq but incrementing position. (Detail: seq stays the same per `addPackageToList` call; written-out bytes increment counters `f` and `m` per call.)

**Without this framing, every BLE content-write is silently dropped.** This explains the dead-letter behavior we observed.

### Outgoing BLE messages (catalog)

Phone → dash on `ffe1`. All wrapped in the 104B frame above. Inner JSON:

| `msg_id` | Method | Trigger | Source |
|---|---|---|---|
| 2 | `sendNotification` | App notification reflected to dash | JsonManager:1499 |
| 13 | `requestVersionCode` | First write after BLE connect | JsonManager:799 |
| 14 | `disconnectBLE` | Phone-initiated disconnect | JsonManager:151 |
| 15 | `endNavi` | Stop navigation | JsonManager:166 |
| 16 | `disableWifiAp` | Tell dash to turn off its AP | JsonManager:136 |
| 17 | `resetSubMileage` | Reset trip mileage | JsonManager:822 |
| 24 | `sendLinkInfo` `{unique_info: "<nickname>"}` | After version reply | JsonManager:1254 |
| 26 | `requestProductType` | After sendLinkInfo | JsonManager:619 |
| 27 | `query*`/`send*` keyed by `func` (TUC, NAVI, OTA, etc.) | Generic bus | JsonManager:262+ |
| 50 | `sendActivateVehicle(bid)` | Server-issued BID activation | JsonManager:844 |
| 54 | `checkVehicleCurStatus` | After product type | JsonManager:107 |

### Initial BLE handshake (canonical order)

From `BleConnectWrapper.handleMessage(JSONObject)` lines 922-1024:

```
phone → dash:  {"msg_id":13}                                       requestVersionCode
dash  → phone: {"msg_id":10,"item":6,"version":"...","sysversion":...,...}
phone → dash:  {"msg_id":25,"msg_type":<date>,...}                 sendCurrentDateTime
phone → dash:  {"msg_id":24,"unique_info":"<nickname>"}            sendLinkInfo
phone → dash:  {"msg_id":26}                                       requestProductType
phone → dash:  {"msg_id":27,"func":"ADAS","act":"get_connect_mode"}
phone → dash:  {"msg_id":27,"func":"SCPT","act":"get_scpt"}
phone → dash:  {"msg_id":54}                                       checkVehicleCurStatus
phone → dash:  {"msg_id":27,"func":"INSIDENAVI","query":2}
phone → dash:  {"msg_id":27,"func":"INSIDENAVI","query":1}
… then dash starts streaming telemetry msg_id 10 items 1/2/3.
```

### Activation gate on BLE writes

`BleConnectWrapper.addPackageToList()` line ~2036 contains:

```java
if (this.e != 5 && !isActivate() && iOptInt >= 1 && iOptInt <= 6) {
    LogWrapper.e("BleConnectWrapper", "不允许发送消息");
    return;
}
```

Blocks BLE writes of msg_id 1-6 unless `isActivate()` is true. Our Python client bypasses this by not having that check — but if the DASH ALSO gates incoming msg_id 1-6 on its internal activation state, then activation forge (above) is also the unlock for BLE.

`isActivate()` itself: BleConnectWrapper.java:2319-2331 returns true unless `version` contains `_TUC=` AND `BleDeviceData.isActive()` is false.

### BLE telemetry catalog (observed on `ffe2` notifications)

All messages are JSON-with-tabs encoded as UTF-8. Format: `{\n\t"key":\tvalue,\n\t...}`.

| `msg_id` | `item`/`msg_type` | Fields | Meaning |
|---|---|---|---|
| 10 | 0 | — | hangup |
| 10 | 1 | `current`, `max`, `average` | Speed |
| 10 | 2 | `total`, `subtotal` | Mileage in decikm — observed: total=6050 → 605 km → ~376 mi |
| 10 | 3 | `tire_pressure`, `remaining_oil`, `endurance` | Tank/tires/range |
| 10 | 4 | `tag` | Time-sync request from dash |
| 10 | 5 | `code` | UniqueCode |
| 10 | 6 | `version`, `sysversion`, `mcuversion`, `btversion`, `fasysversion`, `insidenaviversion`, `fainsidenaviversion`, `themeversion`, `ota`, `flash_ver` | Version handshake reply |
| 10 | 53 | `lock_status`, `need_active`, `bid` | LockStatus — "I'm locked, please activate me" |
| 25 | (msg_type=17) | `msg_source`, `altitude` | GPS altitude in meters |
| 25 | type 1 control_info 1/2/3/4 | — | Riding state start/pause/end/lap |
| 25 | type 14 | `unit` | Unit change (mi/km) |
| 25 | type 22 | `status` | DVR record status |
| 25 | type 25 | `lamp_type`, `status` | Lighting status |
| 27 | `func` discriminated | many | Control plane (PAIR, TUC, NAVI, ADAS, LED, BT_KEY, etc.) |

Polling: messages 10/item:3 and 25/altitude repeat every ~1 s.

---

## What we've tried that did NOT trigger projection

(So the next session doesn't re-run these.)

- **17818 OEM-args bootstrap** (queryActivateStatus → requestFirmwareVersion → sendDeviceType → sendLinkInfo → sendNaviInfo (1,"123",2,3)) without TUC forge: dash chats happily, never dials 15456.
- **17818 binary `05 34 00 00 00 00`** (WifiMessage.requestMirrorStatus): silently dropped.
- **17818 JSON `{"msg_id":25,"msg_type":24,...,"status":1}`** (requestMirrorStatus JSON): silently dropped.
- **17818 JSON `{"msg_id":27,"func":"TUC","act":"GET"}`** alone: dash replies (we hope — confirm in next session) but doesn't dial.
- **BLE writes to ffe1/ffe3 with raw JSON** (no 104B framing, no byteCat CRC): all silently dropped at content layer. NOW EXPLAINED — see BLE framing section above.

## What we have NOT yet tried (these are the next experiments)

1. **TUC FORGE on 17818** (now implemented in `dash_server.py`): send `act:SAVE` + `act:STATUS tucs:1` unsolicited, then check if dash dials 15456. **HIGHEST PRIORITY.**
2. **BLE writes with correct 104B/byteCat framing**: previous BLE writes used raw JSON, which the dash drops. With proper framing, msg_id 1 (sendNaviInfoOld) should make the dash display turn-by-turn nav natively — solving the user's goal without needing projection at all.
3. **Bind serversockets to the specific phone interface IP** (not 0.0.0.0) — Agent 5 flagged this as a possible issue, but 0.0.0.0 should match all bindings on a Mac. Worth verifying with `ss -tln` during a failed test.

---

## Test artifacts

| File | Purpose |
|---|---|
| `dash_server.py` | **CURRENT** — full OEM bootstrap + TUC forge |
| `dash_server_v1.py` | Prior version, no TUC forge (baseline for diff) |
| `stub_dash.py` | Simple TCP listener for bench testing |
| `send_frame.py` | (legacy, wrong direction) — keep for reference |
| `listen_all.py` | Multi-port listener that revealed the inversion |
| `assets/test_frame.h264` | 1280×640 H.264 I-frame test pattern |
| `bt_msg_id_1_to_6.py` | **OUTDATED** — uses raw JSON, will be dropped. Needs rewrite with 104B framing. |
| `bt_*.py` | Various BLE probes, all without proper framing |

---

## Vendor / hardware identifiers

| Field | Value |
|---|---|
| WiFi OUI | Sigmastar Technology Ltd. (`14:c9:cf`) |
| Dash BT MAC | `XX:XX:XX:XX:XX:XX` |
| Dash SoC | Sigmastar SSD20X family |
| Dash OS | Almost certainly embedded Linux (Sigmastar BSP, kernel 4.9.x) |
| Firmware (this unit) | UC 2022-11-23, PL 2020-06-00, DA 2024-03-19 |
| OEM app developer | Chongqing SiQi Technology (思骑) / Wuhan BlueStar |
| App package (international) | `oversea.whbluestar.thinkerride` |
| Backend base URL | `https://iov.edaoduo.com/prod/gw/api/` |
| Activation endpoints | `POST /api/car/unique`, `POST /api/car/active` |

---

# Appendix: the SECOND protocol family (newer Kove dashes)

The Kove ecosystem has shipped two protocol generations. **Our 2022 dash speaks the OLDER ThinkerRide/SiQi protocol documented above.** Newer firmware ships with a re-architected Eryanet protocol used by the new official KOVE app (`com.eryanet.gkove`, released 2026-04-20). We have not tested it on our hardware; it likely requires a firmware upgrade we don't have. Documented here so future-us doesn't re-RE it.

## Eryanet wire-format snapshot

### Topology (inverted vs ThinkerRide)
- **Dash brings up WiFi AP at 192.168.43.1** (Android-tethering-shaped IP).
- **Phone is TCP CLIENT**, dash listens.
- **Phone-side ports**: dial dash `:11111` (mirror video) / `:11112` (upgrade) / `:11113` (messages) / `:11114` (drive-recorder).
- Mirror trigger is a **software opcode**, not a physical button press.

### Magic bytes
- Mirror/control frames: `0xAA 0xBB 0xCC <func:u8> 00 00 00 00 <len_be:u32> <payload>` (12-byte header)
- JSON frames: `"EY"` (0x45 0x59) + `len1_be:u32` + `len2_be:u32` + UTF-8 JSON + extras
- Heartbeat frames: `"EH"` (0x45 0x48) + ...
- Mirror-start variants on byte[0]: `0xAB` STARTNAVI, `0xAC` ENDNAVI, `0xAD` ARRIVE

### Function opcodes (`Parser.java:74-95`)
| Code | Name |
|---|---|
| 0 | VIDEO_DATA |
| 1 | AUDIO_DATA |
| 2 | WIFI_SSID |
| 3 | WIFI_PASSWORD |
| **4** | **MIRROR_START** |
| 5 | MIRROR_STOP |
| 6 | WIDTH_HEIGHT |
| 7 | JPEG |
| 15 | ACK |

### Mirror handshake (software, no rider gesture)
```
Phone → dash:  0xAA 0xBB 0xCC 0x04 00 00 00 00 <len_be=4> <w_be:u16> <h_be:u16>
Dash → phone:  "OK" + <portraitW:2> <portraitH:2> <landscapeW:2> <landscapeH:2> <fps:1> <bitrate:2>
```

Phone retries `MIRROR_START` at 1Hz, up to 10 times, until `OK`. Failure toast: "Use again after restarting the dashboard."

After handshake, video frames go phone→dash with a 4-byte timestamp prefix (`mergeByteArray(intTo4Bytes(now-startTime), bArr)`), Annex-B NALUs inside the standard `0xAA 0xBB 0xCC + VIDEO_DATA(0)` envelope.

### BLE service
- UUID: `0000aaa0-0000-1000-8000-00805f9b34fb` (SIG-style 16-bit aliased)
- Write char: `0000aaa9-...`
- Notify char: `0000aaaa-...`
- Framing: not yet fully traced; **no `byteCat` CRC** (zero grep hits)

### JSON keys observed (`com.eryanet.ite` namespace)
`fun`, `naviControl` (values: `exitNavi`, `showFirst`, `exitConnect`), `heart`, `kid`, `mac`, `carAppId`, `version`, `req`, `path`, `type`, `number`, `p2pName`, `bleName`, `key`, `portraitWidth/Height`, `landscapeWidth/Height`, `framerate`, `bitrate`.

**None of `msg_id`, `act`, `TUC`, `INSIDENAVI`, `ROAD_NAVI`, `tucs` exist.** The protocols are not source-compatible.

### Backend
- Auth: `POST cloud.eryanet.com/rcg/account/global/user/login/google` (Google ID token)
- Dash binding: `POST gcloud.eryanet.com/rcg/carauto/useable/device/bind` `{deviceId, userId, name, carAppId, qrCode}`
- OTA: `gcloud.eryanet.com/...`
- Convoy: `team.eryanet.com/carteam/motorcade/...`
- Theme webview: `web.eryanet.com/app/motor-kove-theme/`

### App-level architecture reference (worth copying)
The `com.eryanet.gkove` app uses the **`android.app.Presentation` + `VirtualDisplay` + MediaCodec** pattern for projection. UI rendered to a secondary Display, encoded directly from the GPU surface, no bitmap copies. Specific source files:

- `decompile/sources/com/eryanet/ite/recorder/ScreenRecorder.java:270-354` — encoder + VirtualDisplay setup
- `decompile/sources/com/eryanet/ite/socket/ClientSocket.java:1299-1311` — `makePackage` (12-byte header)
- `decompile/sources/com/eryanet/ite/socket/ClientSocket.java:1501-1529` — `sendStartMirror`
- `decompile/sources/com/eryanet/ite/socket/Parser.java:74-95` — func opcodes
- `decompile/sources/com/eryanet/ite/fragment/link/main/LinkFragment.java:1894,1899` — BLE UUIDs
- `decompile/sources/com/eryanet/ite/fragment/link/main/LinkFragment.java:882-906` — `startSendMirror` retry loop

Whether our specific Kove 450 Rally dash can ALSO speak this newer protocol (dual-stack firmware) or only the OLD one is unknown. We confirmed it speaks the old one. The newer would need an OTA upgrade we don't possess.
