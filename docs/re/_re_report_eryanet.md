# Eryanet KOVE BT Stack — Full RE Report

NEWER family — `com.eryanet.gkove` / `com.eryanet.ite` Android app. Post-2023 protocol family. Our Kove 450 Rally dash speaks the OLD family (`0xEE 0xFD` envelope) so this is lower-priority, but the codebase is much less obfuscated than the OLD family — a clean reference for "what a healthy implementation looks like".

All cites are absolute paths under `<REPO>/phase2/kove_app/decompile/sources/`. Abbreviated as `<DECOMP>` below.

## 1. GATT layer

**Base service** is `0000aaa0-0000-1000-8000-00805f9b34fb` (16-bit alias UUID, "aaa0"). All control/data flows are characteristics under this one service. Peripheral name allowlist `iTE…/EY…/KOVE/QJMT/MLBS/LJ/BX/KTEY` at `<DECOMP>/com/eryanet/ite/fragment/link/main/LinkFragment.java:1959-1965`.

Characteristic map (field declarations in `LinkFragment.java:560-570`):

| UUID last-4 | field name | purpose | cite |
|---|---|---|---|
| `aaa0` | `serviceUUID` | wrapping service | `LinkFragment.java:560` |
| `aaa1` | `ipUUID` | dash IP (read) | `LinkFragment.java:561,3171` |
| `aaa2` | `ssidUUID` | phone hotspot SSID (write) | `LinkFragment.java:562,3139` |
| `aaa3` | `psdUUID` | phone hotspot password (write) | `LinkFragment.java:563,3148` |
| `aaa4` | `okUUID` | connect-trigger byte (write) / connect-type readback (-1/-3/-4) | `LinkFragment.java:564,3157,4783` |
| `aaa5` | `macUUID` | dash MAC + activation key (`<mac>&<32-char activeKey>`) | `LinkFragment.java:565,406-417,4767` |
| `aaa6` | `infoUUID` | **JSON-payload pipe (BLE replacement for TCP 11113)** | `LinkFragment.java:566,1277,3064,3087,3113` |
| `aaa7` | `carUUID` | CarInfo poll (read every 3s) | `LinkFragment.java:567,1741,401` |
| `aaa8` | `otaUUID` | upgrade info (read every 1s) | `LinkFragment.java:568,1755,384-397` |
| `aaa9` | `tcpUUID` | existence-flag: "I support TCP 11113 control" | `LinkFragment.java:569,1894-1903` |
| `aaaa` | `tcpMirror` | existence-flag: "I support TCP 11111 mirror" | `LinkFragment.java:570,3682-3686` |

`aaa9`/`aaaa` are checked only for *presence* — they aren't read or written. They are capability flags. Presence flips `hasTcpUUID()`/`useTCPMirror()` to true and the app routes data over TCP instead of UDP/BLE-only.

**Connect sequence** (`gattCallback` at `LinkFragment.java:380-551`):

1. `BlueService.startScan` scans for names starting with `"iTE"` (`<DECOMP>/com/eryanet/ite/BlueService.java:23,37`).
2. `STATE_CONNECTED` (state=2) → `bluetoothGatt.discoverServices()` (`LinkFragment.java:506`).
3. `onServicesDiscovered`: `requestMtu(512)` (`LinkFragment.java:540`), then `sendtoMac()` (`:548`).
4. `sendtoMac` (`LinkFragment.java:4730-4799`): reads `aaa5` until parsed; writes a zero byte to `aaa5` as "phone type"; reads `aaa4` for connect-type.
5. If `-1`/`-4`: `sendTo(ssid,password)` writes own AP creds to `aaa2`/`aaa3`/`aaa4`, then reads `aaa1` until valid IPv4, which becomes `Constants.SERVER_IP` (`LinkFragment.java:3130-3175,468`).
6. Then `startRecorder()` → TCP 11113 client opens.

**Notifications**: there is **no** `setCharacteristicNotification` or CCCD write anywhere. No `onCharacteristicChanged` override. The app uses polling exclusively (`aaa7`/`aaa8`).

**MTU**: hard-coded 512.

## 2. Wire envelope (EY/EH framing)

### TCP 11113 outbound (phone → dash)

Built by `sendTcpMessage(String json, byte[] extraBin)` at `<DECOMP>/com/eryanet/ite/socket/ClientSocket.java:1541-1564`:

```
offset  bytes   meaning
 0       2      "EY"           = 0x45 0x59
 2       4      json_len_be    (big-endian uint32 — ByteUtil.bigIntTo4Bytes)
 6       4      bin_len_be     (big-endian uint32; 0 when no binary)
 10      N      json (UTF-8)
 10+N    M      binary blob    (optional — drive-recorder file transfer)
```

**No CRC, no checksum, no sequence number, no flags.** Compare OLD-family `0xEE 0xFD … CRC16`.

### TCP 11113 inbound (dash → phone)

Read at `ClientSocket.AnonymousClass9.run()`, `ClientSocket.java:131-140`: same layout. Dispatch via `handler.obtainMessage(3, jsonStr)` (msg type 3 = `receiveJson`).

### EH ("heartbeat") variant

Only on the **localhost YKSDK socket (18080)**, NOT on the dash wire. `ClientSocket.java:73-81`:
- `'E','Y'` → JSON envelope (msg type 4)
- `'E','H'` → heartbeat envelope (msg type 5), payload `{"heart":"…"}` or `{"EYLINKheart":"Mapheart"|"GPSheart"}` (`ClientSocket.java:1076-1088`)

On 11113 the heartbeat is just a normal `EY` JSON with `"heart"` key (`ClientDataHandler.java:92`). The `EH` framing does NOT appear on any dash-facing wire.

### TCP 11111 mirror envelope (different framing)

Built by `makePackage()` at `ClientSocket.java:1299-1311`:

```
offset  bytes   meaning
 0       1      0xAA
 1       1      0xBB        (ByteSourceJsonBootstrapper.UTF8_BOM_2 = 0xBB)
 2       1      0xCC
 3       1      func code   (Parser.Func ordinal, e.g. 0x04 = MIRROR_START)
 4-7     4      reserved (zero)
 8       4      payload_len_be   (intTo4Bytes)
 12      N      payload
```

For video frames the payload is `<timestamp_ms_le:u32><NAL bytes with Annex-B start codes>` — timestamp from `ByteUtil.intTo4Bytes` (little-endian), inside a header where `payload_len` is written by `intTo4Bytes` too. (Endianness footnote in §11.)

Inbound on 11111: phone reads 2 bytes — either `'O','K'` (ACK header) or `0xAA 0xBB` (with `0x0F` as next func byte = ACK/heartbeat). See `ClientSocket.java:307-329`. The local server-side parser (`<DECOMP>/com/eryanet/ite/socket/ServerSocket.java:95-97`) uses the same `AA BB CC` magic.

## 3. `fun` catalog

The `"fun"` JSON key is the top-level discriminator on TCP 11113 and BLE `aaa6`. There is **no single switch dispatcher** that could fully recover — `LinkFragment.handleJson(String)` would be it but jadx failed to decompile (`LinkFragment.java:1879-1888`, "Method dump skipped, instruction units count: 940"). Producers/consumers survive.

| `fun` value | Model / origin | Direction | `data` shape | cite |
|---|---|---|---|---|
| `"time"` | `BleTime` | phone→dash | `{"timestamp":"<unix_sec>","timeZone":"<hours_offset>"}` (both string) | `<DECOMP>/com/eryanet/ite/ble/BleTime.java:6,9-10` |
| `"navi"` | `BleNaviBean` (IDS.NAVI=`"navi"`, `<DECOMP>/com/eryanet/ite/newvoice/event/IDS.java:7`) | phone→dash | `{curStepRetainDistance, iconType, nextRoadName, pathRetainDistance, pathRetainTime}` + top-level `"type":"0"|"1"` | `BleNaviBean.java:4-55`; producers `GPSGuidePresentation_Box.java:777-786,1534,1581`, `GPSGuidePresentation_GoogleNavi.java:881`, `Main2Activity.java:2563-2574` |
| `"phone"` | `BlePhoneBean` | phone→dash | `{phoneName, phoneNum}` + `"type"` | `BlePhoneBean.java:6,9-12` |
| `"switch"` | `BleSwitchBean` | phone→dash | `"type"` only | `BleSwitchBean.java:5-6` |
| `"upgrade"` | `BleNeedUpgrade` | phone→dash | `"type"` only | `BleNeedUpgrade.java:5-6` |
| `"activate"` | inline | phone→dash | `{"type":<status>}` | `LinkFragment.java:3045-3073` |
| `"recorder"` | inline | bidir (port 11114) | `{"req":"basic"|"detail"|"stop"|"play","number","type","path","total","sum","cur","index"…}` | `ClientSocket.java:394-400,416,485-505` |
| `"recorderUI"` | inline | phone→dash | UI nudge | `RecorderMainActivity.java:140` |
| `"otaRecord"` | inline | phone→dash | `{"type":"stop"|"ok"}` | `CarOtaActivity.java:351,2116` |
| `"theme"` | inline | phone⇄dash on 11112 | dash carThemeKid/type | `LinkFragment.java:2109-2114,2154` |
| `"ota"` | `BleOTA` / inline | phone⇄dash on 11112 | `{version, mode, status, index, type}` | `BleOTA.java`, `CarUpgradeManager.java:127-148`; `LinkFragment.java:2115,2157` |

Plus sentinels not under `"fun"`:
- `{"heart":…}` on TCP 11113 — dash→phone heartbeat, parsed at `ClientDataHandler.java:92-103`.
- `{"EYLINKheart":"Mapheart"|"GPSheart"}` on TCP 18080 (YKSDK localhost) — phone→local SDK at `ClientSocket.java:1076-1088`.
- `{"naviControl":"exitNavi"|"showFirst"|"exitConnect"}` — dash→phone navi control at `ClientDataHandler.java:104-112`.

`BleCarInfo` (dash→phone telemetry) carries `{speed, rotarySpeed, waterTemp, oil, mileage:{totalMileage,subMileage[],maintainMileage}, tirePressure[], curEvent[], hisEvent[], gear, timestamp}` — `fun` is set by the populator, not pre-baked. Consumer reads cached payload from SharedPreferences at `LinkFragment.java:1396-1399`.

**Dispatcher**: `ClientFuncInter` callbacks at `LinkFragment.AnonymousClass40` (`:850-915`). `receiveJson(String)` → `handleJson(String)` — the missing decomp at line 1883.

## 4. TCP port catalog

All ports: phone=CLIENT, dash=SERVER. All four confirmed.

| port | role | code |
|---|---|---|
| **11111** | Mirror — TCP video w/ 12-byte AA-BB-CC header. Default from `R2.style.Base_MaterialAlertDialog_MaterialComponents_Title_Panel = 11111` (`<DECOMP>/defpackage/R2.java:11160`). Field `port` (`ClientSocket.java:40`), `port1` (`LinkFragment.java:2082`). Connect: `startTCPMirrorClient(port)` `ClientSocket.java:1670-1678`. Also UDP fallback uses same port (`ServerSocket.java:10,16,153`). |
| **11112** | Upgrade — `TCP_PORT_UPGRADE=11112` (`<DECOMP>/com/eryanet/ite/constants/Constants.java:13`, `<DECOMP>/com/eryanet/ite/update/car/CarUpgradeManager.java:7`). Connect: `CarUpgradeManager.startClient(network, callback)` `:305-345`. **Same `EY` envelope as 11113** (`:271-280`). Carries `{"fun":"theme"}`/`{"fun":"ota"}`. |
| **11113** | Messages — `TCP_PORT_MESSAGE=11113` (`Constants.java:11`), per-call via `startTCPClient(int)` (`ClientSocket.java:1605-1610`). Main control channel — all `"fun"` JSONs (time, navi, phone, switch, activate, etc.). Default `port2 = R2.style.Base_TextAppearance_AppCompat = 11113` (`R2.java:11162`, `LinkFragment.java:2088`). |
| **11114** | Drive-recorder — `DriveRecordingPort = R2.style.Base_TextAppearance_AppCompat_Body1 = 11114` (`R2.java:11163`, `ClientSocket.java:89,1612-1668`). Same `EY` envelope. Log: `"11114 Success!!!"` (`:1631`). |
| (18080) | YKSDK localhost — `TCP_PORT_SDK=18080` (`Constants.java:12`), `127.0.0.1`, not on dash wire. Uses `EY`/`EH` framing. Code: `ClientSocket.java:57-88,1454-1465`. |

Mirror heartbeat: `0xAA 0xBB 0xCC 0x0F …` packets containing JSON `"version"`/`"ack"` (`ClientSocket.java:311-329`). 12-second timeout via `checkTimeout` (`:345-352,1685-1696`).

## 5. Mirror flow (architecture deep dive)

### `MIRROR_START` trigger (func ordinal 4 → byte 0x04)

`Parser.Func` enum (`<DECOMP>/com/eryanet/ite/socket/Parser.java:52-64`):
```
VIDEO_DATA(0), AUDIO_DATA(1), WIFI_SSID(2), WIFI_PASSWORD(3),
MIRROR_START(4), MIRROR_STOP(5), WIDTH_HEIGHT(6), JPEG(7), ACK(0x0F)
```
`parseByte(MIRROR_START)=4` (`:84-85`).

**Phone-initiated, NOT long-press.** `ClientFuncInter.startSendMirror()` → `LinkFragment$40.startSendMirror()` (`:881-906`) spawns a thread calling `clientSocket.sendStartMirror()` once per second up to 10 attempts.

`sendStartMirror()` (`ClientSocket.java:1501-1529`):
- Read window W×H, clamp each dim ≤2560, round even.
- Payload: `<width:u16_le><height:u16_le>` (4 bytes).
- `divideAndOffer(makePackage(MIRROR_START, payload))` → `0xAA 0xBB 0xCC 0x04 00 00 00 00 <len_be=4> <w_le> <h_le>`.

### Dash response — `OK` + RecordParams

Dash replies with 13-byte `'O','K', portraitW_be:u16, portraitH_be:u16, landscapeW_be:u16, landscapeH_be:u16, framerate:u8, bitrate_be:u16` (mirror of `ServerSocket.replyStartMirror`, `<DECOMP>/com/eryanet/ite/socket/ServerSocket.java:126-142`). Phone parses at `ClientDataHandler.java:30-64`. Note: each dim is rounded up to even (`if (x%2!=0) x++`).

### MediaCodec + VirtualDisplay

`ScreenRecorder.prepareEncode()` (`<DECOMP>/com/eryanet/ite/recorder/ScreenRecorder.java:270-353`):
- MIME `video/avc` hard-coded (`:290,298`). HEVC branch dead (`:359`).
- `color-format`: `2130708361` = `COLOR_FormatSurface` (`:292`).
- `bitrate`: `params.getBitRate() * 1000` (`:293`).
- `bitrate-mode`: `2` (CBR) (`:294`).
- `frame-rate`: from params (`:295`).
- `i-frame-interval`: 1s (`:296`).
- `createEncoderByType("video/avc")` (`:298`).
- `configure(format, null, null, CONFIGURE_FLAG_ENCODE)` (`:303`).
- `mSurface = mMediaCodec.createInputSurface()` (`:304`).

**VirtualDisplay** — two paths:
- Primary (full screen): `mMediaProjection.createVirtualDisplay("GlobalRecorder", W, H, 1, 1, surface, null, null)` (`:328`). MediaProjection from `MediaProjectionManager` at `:758`.
- Presentation overlay: `mDisplayManager.createVirtualDisplay("PresentationRecorder", W, H, 440, surface, FLAG_PRESENTATION=2)` (`:336`). Returned via `DisplayListener.notifyDisPlay(display)` (`:338-340`) → `LinkFragment$41.notifyDisPlay` (`LinkFragment.java:4977-4988`) → `new NaviPresentation_GoogleMap(activity, display).show()`.

### SPS/PPS prepend

`resetOutputFormat()` (`:356-362`) → `addSpsPps()` (`:114-138`):
- `csd-0` → `sps`, `sendFrame(sps)`.
- `csd-1` → `pps`, `sendFrame(pps)`.
- Cache `headData = sps || pps` for resend on reconnect.

### Frame-drop policy — `applyFrameFilterAbandon`

`ScreenRecorder.java:495-506`:
```java
protected void applyFrameFilterAbandon(byte[] bArr) {
    int nalType = nalType(bArr);
    AtomicBoolean ab = this.isAbandon;
    if (ab != null) {
        if (ab.get() || nalType != 5) return;  // drop non-IDR during abandon
        this.isAbandon = null;                  // first IDR: resume
    }
    this.recorderDataSender.sendMirrorData(bArr);
}
```

`abandon(true)` sets the flag and schedules `abandon(false)` in 700ms (`:478-489, 243`). Effect: drop everything until next IDR (NAL type 5) — avoids decoder hangs after wire glitches.

### Send

`applyFrameFilterAbandon → sendMirrorData → ClientSocket.sendMirrorBytes` (`:1467-1473`):
```java
byte[] ts = ByteUtil.intTo4Bytes((int)(System.currentTimeMillis() - this.startTime));
if (CarUpgrade.isOtaSending) return;
divideAndOffer(makePackage(VIDEO_DATA, mergeByteArray(ts, bArr)));
```

Final wire frame: `0xAA 0xBB 0xCC 0x00 00 00 00 00 <total_payload_len_be:u32> <timestamp_ms_le:u32> <NAL Annex-B>`.

### MIRROR_STOP / WIDTH_HEIGHT

- `sendStopMirror()` (`:1531-1534`): empty `0xAA 0xBB 0xCC 0x05 00 00 00 00 00 00 00 00`.
- `sendWH(w,h)` (`:1566-1575`): `<w:u16_le><h:u16_le>` under func 6 (only sent if `NaviConfig.isSendHeart`).

## 6. Time-sync full trace

**Anchor**: `<DECOMP>/com/eryanet/ite/fragment/link/main/LinkFragment.java:3099-3126`.

### Caller stack

Three callers, NOT raw `onConnected`. Time-sync fires after the mirror handshake completes:

1. `startScreenRecorder()` `:4992` — canonical. Called from `AnonymousClass40.startRecord()` (`:871`), which fires when dash sends `OK` mirror reply. Sequence: `MIRROR_START` → `OK` → `startRecord` → `setConnect(CONNECTED)` → `activate()` → `startScreenRecorder()` → `sendTime() + getCarVersion() + getCarInfo()`.
2. `updateOnlyBle()` `:3572` — fired when `aaa4` reads `-3` (BLE-only mode). Sent over BLE `aaa6` only.
3. **No periodic re-send.** One-shot per connection. No timer/scheduler refresh.

So the HANDOFF's "on CONNECTED" is approximately right but more precisely **immediately after the mirror handshake completes**.

### Payload construction (`LinkFragment.java:3099-3105`)

```java
public void sendTime() {
    BleTime bleTime = new BleTime();
    BleTime.DataBean dataBean = new BleTime.DataBean();
    dataBean.setTimestamp((System.currentTimeMillis()/1000)+"");  // unix SECONDS, STRING
    dataBean.setTimeZone(
      ((TimeZone.getTimeZone(TimeZone.getDefault().getDisplayName(false,0)).getRawOffset()
        - TimeZone.getTimeZone("GMT").getRawOffset()) / 3600 / 1000) + ""); // HOURS, STRING
    bleTime.setData(dataBean);
    String json = new Gson().toJson(bleTime);
    // → '{"data":{"timestamp":"1747...","timeZone":"-6"},"fun":"time"}'
```

Two corrections to the HANDOFF doc:
- **`timeZone` is in HOURS, not seconds.** `(rawOffsetMs - 0) / 3600 / 1000` divides ms by 3600 then 1000 → HOURS. For Denver UTC-6 DST → `"-6"`.
- Both fields are stringified ints.

`BleTime.fun` is initialized to `CrashHianalyticsData.TIME` (the Huawei constant = literal `"time"`, `BleTime.java:6`).

### Transport — BOTH BLE and TCP in parallel

```java
    if (!this.isP2PConnect) {
        new Thread() { run() {
            while (!isRelease) {
                if (writeBytes(serviceUUID, aaa6UUID, makePkg(json))) return;
                SystemClock.sleep(100L);
            }
        }}.start();                                  // BLE aaa6, retry every 100ms until success
    }
    clientDataHandler.sendJsonData(json);            // TCP 11113, fire-and-forget
```

If `isP2PConnect==true` (Wi-Fi Direct mode), BLE skipped — TCP only.

### BLE envelope (`makePkg`) — `LinkFragment.java:2334-2349`

```
offset  bytes   meaning
 0       2      "EY"
 2       4      json_len_be   (bigIntTo4Bytes — big-endian)
 6       N      utf-8 json
```

6-byte header — same magic and length as TCP envelope but no `binLen` field. So `EY <4 BE bytes> {…}` over BLE is decodable with the TCP code minus the bin slot.

### Reply / ACK?

**No explicit ACK.** No `msg_id`, no `{"fun":"time","type":"ok"}` reply handler in the codebase. Dash silently accepts. BLE side retries every 100 ms on write failure forever; TCP side is fire-and-forget.

## 7. Altitude full trace

**Negative result. Altitude is never pushed phone→dash in this codebase.**

- `grep -rn 'altitude\|elevation\|海拔' com/eryanet/` — every hit is Material `elevation` (UI shadow Z-axis) in `R.java`. No data class has an altitude field.
- `grep -rn 'getAltitude\|setAltitude\|"altitude"\|"alt"' com/eryanet/` — zero hits.
- Chinese 海拔: zero hits.

Phone-to-dash navigation push (`BleNaviBean`) carries only `curStepRetainDistance, iconType, nextRoadName, pathRetainDistance, pathRetainTime` (`BleNaviBean.java:10-15`) — no coordinates, no altitude.

`BleCarInfo` (dash→phone) carries speed/RPM/water-temp/oil/mileage/tire-pressure (`BleCarInfo.java:8-19`) — no altitude.

Conclusion: the NEW family's Simple Navigation overlay gets distance + road name only. Altitude (if shown by the dash) comes from the dash's own GPS receiver, not from the phone. If a future build adds it, look for a new field in `BleNaviBean.DataBean` or a new `fun` like `"gps"`/`"location"`.

(Side-note: phone latitude/longitude IS sent to Eryanet cloud activate endpoint via `latitude`/`longitude` doubles in the POST body — `<DECOMP>/com/eryanet/ite/activate/CheckManager.java:89-90` — but not to the dash.)

App build inspected: `KOVE_v1.0.10260420_release` (from Kotlin metadata at `<DECOMP>/com/eryanet/ite/navi/presentation/CustomMapboxNavigationViewportDataSource.java:5`).

## 8. BLE write helpers

### Raw helpers in `LinkFragment`

- `write(String svc, String chr, String value)` `:3704-3728` — `setValue(String)`, `writeCharacteristic`. For AP-mode SSID/password writes.
- `writeBytes(String svc, String chr, byte[] value)` `:3731-3754` — `setValue(byte[])`, `writeCharacteristic`. For all EY-envelope writes to `aaa6`.
- `read(String svc, String chr)` `:3171,4783` — `readCharacteristic`, result via `onCharacteristicRead`.

### Higher-level senders (all funnel through `writeBytes` on `aaa6`)

| sender | payload | cite |
|---|---|---|
| `sendTime()` | `{"fun":"time","data":{timestamp,timeZone}}` | `:3099-3126` |
| `sendActivate(String type)` | `{"fun":"activate","type":<type>}` | `:3045-3073` |
| `sendNeedUpgrade(String type)` | `{"fun":"upgrade","type":<type>}` | `:3077-3095` |
| `handleNaviInfo(BleNaviBean)` (EventBus) | `{"fun":"navi","type":"0"|"1","data":{…}}` → `packages.offer(makePkg(json))` | `:4187-4204` |
| `handlePhone(BlePhoneBean)` | `{"fun":"phone","type":…,"data":{…}}` → `packages.offer` | `:4207-4211` |
| `handleSwitch(BleSwitchBean)` | `{"fun":"switch","type":…}` → `packages.offer` | `:4214-4218` |
| `sendTo(ssid, password)` | `aaa2 ← ssid`, `aaa3 ← password`, `aaa4 ← "1"` (strings) | `:3130-3175` |
| `sendtoMac` | `aaa5 ← new byte[1]` (single 0x00) | `:4767` |
| `getCarInfo()` | poll `aaa7` every 3s | `:1736-1746` |
| `getCarVersion()` | poll `aaa8` every 1s | `:1748-1760` |

`packages` is a `LinkedBlockingDeque` drained by internal `SendThread` (`:1268-1285`) that writes to `aaa6` with 100 ms retry. No proper back-pressure — flooding from outside grows the queue.

### Full list of `writeCharacteristic` call sites

```
LinkFragment.java:3727  bluetoothGatt.writeCharacteristic(characteristic);  // from write(String,String,String)
LinkFragment.java:3744  bluetoothGatt.writeCharacteristic(characteristic);  // from writeBytes
```

Two — everything funnels through `write`/`writeBytes`. The diagnostic `BlueService.gattCallback` (`<DECOMP>/com/eryanet/ite/BlueService.java:47-77`) does not write.

## 9. Activation / bind path

### Step 1 — QR scan

URL-style QR: `…?device=<name>&mac=<mac>&key=<32-char activeKey>&ssid=<ssid>&pwd=<pwd>&name=<name>&IP=<ip>&w=<w>&h=<h>`. Parsed at `<DECOMP>/com/eryanet/ite/fragment/band/BandFragment.java:469-488`.

### Step 2 — Cloud activate check

POST `https://cloud.eryanet.com/rcg/basic/useable/activate/code/motor/iteration` (`<DECOMP>/com/eryanet/ite/activate/CheckManager.java:6,24`).

Headers: `Content-Type: application/json`, `appID: 21473499573fe5372ac19bf4690fc779` (`:32`), `userId: <user id>` if logged in.

Body (`getParams`, `:71-93`):
```json
{
  "tuid": "<terminalID>",
  "key":  "<activeKey from QR>",
  "program": "com.eryanet.kove",
  "wifi": "<connected ip>",
  "isBle": 0,
  "width": "<w>", "height": "<h>",
  "deviceName": "<phone device>",
  "province": "<>", "city": "<>", "district": "<>", "address": "<>",
  "latitude": <double>, "longitude": <double>
}
```

Response `data: {"carAppId": "<id>", "config": <int>, "mapSource": <int>}` — `mapSource=4` → Mapbox path (`NaviType=1`), else Google path. `carAppId` cached for step 3.

### Step 3 — Cloud bind

POST `https://gcloud.eryanet.com/rcg/carauto/useable/device/bind` (`<DECOMP>/com/reachauto/urlconstants/URLConstants.java:11`). Sender `BandFragment.startBindCarWeb()` (`:405-436`).

Body:
```json
{
  "deviceId": "<dash MAC>",
  "userId":   "<logged-in user id>",
  "name":     "<deviceName from QR>",
  "carAppId": "<from step 2>",
  "qrCode":   "<raw QR string>"
}
```

Auth headers from `RequestManager.sendPostUserRequest` (`<DECOMP>/com/eryanet/ite/navi/util/RequestManager.java:183`):
- `Content-Type: application/json`
- `Authorization: <user token>`
- `userId: <user id>`
- `deviceId: <phone device id>`
- `appID: 21473499573fe5372ac19bf4690fc779`

Success: `statusCode==200` OR `670008` (already-bound) — both treated as success (`BandFragment.java:426-430`).

Related: `QUERY_BIND_DEVICE = …/device/bind/query` (`URLConstants.java:50`), `UNBIND_DEVICE = …/device/unbind` (`:61`).

**The dash never knows it was bound.** Bind is purely phone⇄Eryanet cloud. The dash hands out `activeKey` from `aaa5` and the phone uses it as cloud lookup key.

## 10. How NEW differs from OLD

| dimension | NEW (Eryanet KOVE) | OLD (SiQi/ThinkerRide) |
|---|---|---|
| BLE service base | `0000aaa0-…` (16-bit alias) | proprietary 128-bit / vendor-specific |
| BLE TX char for JSON | `0000aaa6-…` | vendor char, raw struct (not JSON) |
| TCP envelope | `"EY"` magic + 4-byte BE `jsonLen` + 4-byte BE `binLen` + json + bin. `"EH"` variant on localhost SDK only. | `0xEE 0xFD` + len + opcode + body + CRC16 |
| Encoding | UTF-8 JSON (+ optional binary trailer) | binary structs |
| Time-sync | `{"fun":"time","data":{"timestamp":<unix_sec_str>,"timeZone":<hours_str>}}` — HOURS offset | `clock` opcode with packed Y/M/D/h/m/s, typically signed minutes for TZ |
| Time-sync transport | Both BLE `aaa6` AND TCP 11113 in parallel, one-shot after mirror handshake | BLE only, single opcode |
| Mirror trigger | Phone sends `MIRROR_START` (func=4) on TCP 11111. No long-press. | Long-press on dash; dash advertises |
| Mirror header | 12-byte `AA BB CC <func> 00 00 00 00 <len_be:u32>` + per-payload LE timestamp prefix | n/a |
| Sub-opcode | string `"fun"` JSON field | single opcode byte |
| Activation | Cloud HTTPS roundtrip (`useable/activate/code/motor/iteration` + `device/bind`), `activeKey` from BLE `aaa5` | dash-local |
| Heartbeat | `EY{"heart":…}` on 11113; `AA BB CC 0F {…version…/…ack…}` on 11111 | dedicated opcode ~1 Hz |
| TCP ports | 11111 mirror / 11112 upgrade / 11113 messages / 11114 drive-recorder; phone=client | none — BLE-only |
| Multi-transport JSON | many `"fun"` messages double-write BLE + TCP | n/a |
| CRC | **none** | yes (CRC16) |
| Obfuscation | minimal — `LinkFragment`, `ClientSocket`, `Parser.Func` survive | heavy ProGuard |

## 11. Anything surprising

1. **`makePkg` BLE envelope is a truncated TCP `EY` envelope.** Same `"EY"` magic, same BE jsonLen — but no `binLen` slot. Decode with TCP code minus 4 bytes. (`LinkFragment.java:2334-2349`)

2. **The dash does NOT ACK time-sync.** No reply handler, no `msg_id`. If you re-send 100× because BLE write failed, dash silently applies 100×.

3. **`timeZone` is HOURS not seconds, and it's a string.** Both `time` JSON fields are stringified. Strict-typed parsers would break on a numeric `1747...`.

4. **The `EH` framing is a red herring for the dash.** Only on the 18080 localhost SDK port. Dash heartbeats are normal `EY{"heart":…}` on 11113 and binary `AA BB CC 0F …` on 11111.

5. **Mirror envelope mixes endianness.** Header lengths are big-endian (`bigIntTo4Bytes`); payload timestamp prefixes are little-endian (`intTo4Bytes` — JNI-friendly). Same envelope. Watch this when writing a parser. (`ClientSocket.java:1299-1310 vs :1468`)

6. **Frame-drop "abandon" auto-rearms in 700 ms.** `mHandler.sendEmptyMessageDelayed(0, 700L)` in `abandon(true)` (`ScreenRecorder.java:487`). If actual recovery takes >700 ms, you re-arm too early and re-stuck on the next P-frame. Probably the source of "video briefly freezes on disconnect" reports.

7. **HEVC code path is dead.** `if ("video/hevc".equals("video/avc"))` at `ScreenRecorder.java:359` is constant-false. AVC only.

8. **`hasTcpUUID()` and `useTCPMirror()` are the capability negotiators.** Presence of `aaa9` → TCP control (11113); presence of `aaaa` → TCP mirror (11111); else BLE/UDP fallback. Dash never tells phone its capabilities explicitly. (`LinkFragment.java:1891-1905, 3674-3688`)

9. **`MOTORConnectType` SharedPreferences key locks reconnect behavior.** 0=scan, 1=P2P auto-reconnect (`LinkFragment.java:505,1945,2747`).

10. **`isOtaSending` silently drops mirror frames during OTA.** No error, no log (`ClientSocket.java:1469`).

11. **Drive-recorder uses chunked `EY` envelopes** on 11114 (`ClientSocket.java:1647-1657`): `{"fun":"recorder","data":{…,"sum":N,"index":i,"total":T}}` + binary slot per chunk. Reassembled via `RecorderFileUtil.addBufferToFile`.

## 12. Gaps / could-not-determine

1. **Main `handleJson(String)` dispatcher failed to decompile** (`LinkFragment.java:1879-1888`, instruction count 940). Try jadx `--show-bad-code` or baksmali to recover. Could hide additional `"fun"` values handled there but never produced locally — including any `time` ACK handler.

2. **`BleCarInfo` producer not traced.** Consumer reads from SharedPreferences (`LinkFragment.java:1399`). Producer probably in `handleJson` (missing decomp) or `analysisCarInfo` (`:401`) — didn't trace into it.

3. **`Parser.NaviFunc.STARTNAVI/ENDNAVI/ARRIVE/KILL/JSON`** (`Parser.java:66-72`) set the mirror header byte to `0xAB/0xAC/0xAD/0xAE/0xAF` instead of `0xAA` (`ClientSocket.java:1313-1340`). Code paths confirmed; dash-side semantics not. Only used on the 18080 localhost SDK socket.

4. **No PCAP-level confirmation.** All static analysis — confirm with btsnoop / tcpdump if you need byte-exact certainty on the mirror endianness or hidden CRC.

5. **`com.eryanet.gkove`** package — focused on `com.eryanet.ite`. `gkove` looks like resources/launcher, but quick verify: `find … com/eryanet/gkove -name '*.java' | grep -v 'R\.java\|R\$'` to be sure no protocol code hides there.

6. **String resources not grepped.** A scaffolded-but-removed altitude string could exist in `values/strings.xml` of the unpacked APK.

7. **`isP2PConnect` direct mode** (Wi-Fi Direct fallback) startup not fully traced. `LinkFragment.AnonymousClass13.permissionGranted` posts `StartP2PBean` (`:3025`) — consumer not followed.
