# ThinkerRide BT Stack — Full RE Report

Decompile root analyzed: `<REPO>/phase1/apk/jadx_output/sources/`
Primary package: `com.whbluestar.thinkride` (the actual implementation), `oversea.whbluestar.thinkerride` (resources/manifest stub), and partner package `com.thinkerride.*`.

## 1. GATT layer

### Service UUIDs

- Primary "old" / SiQi service used by the 2022 Kove 450 dash dial:
  - `0000E0FF-3C17-D293-8E48-14FE2E4DA212`
  - Defined: `com/whbluestar/thinkride/ft/bluetooth/connect/constant/UUIDConstants.java:7`
  - Selected when `EcologyType == 0` and bond mode is "already-bonded": `BleConnectWrapper.java:508`
- "Pre-connect, no bond" variant: `0000e0ff-3c17-d293-8e48-14fe2e4da213` — `BleConnectWrapper.java:506`; detected by `BleUtil.isPreConnectNoBond(...)` at `com/whbluestar/thinkride/util/BleUtil.java:207-214`
- Sibling service UUIDs (not what the Kove uses): `0000e0ff-3d17-...` (ADAS, `BleUtil.java:67-73`), `0000e0ff-3e17-...` (Flex screen, `BleUtil.java:138-145` and `BleConnectWrapper.java:512`), `0000e0ff-4017-...` (Center-control, `BleUtil.java:107-114` and `BleConnectWrapper.java:514`), `0000e0ff-5817-...` (Upgrade, `BleUtil.java:216-223`).

### Characteristics

- **Write (write-no-response)**: `0000ffe1-0000-1000-8000-00805f9b34fb` — acquired at `com/whbluestar/thinkride/ft/bluetooth/connect/connect/BleConnectWrapper.java:429-435` (write type set to `1 = WRITE_TYPE_NO_RESPONSE`). All BLE writes go through `i` (`mWriteCharacteristic`) — **no separate write to `ffe3`** by this app.
- **Notify**: `0000ffe2-0000-1000-8000-00805f9b34fb` — acquired and `setCharacteristicNotification(true)` called at `com/whbluestar/thinkride/ft/bluetooth/connect/connect/BleServiceHelper.java:29-34`.
- **CCCD**: `00002902-0000-1000-8000-00805f9b34fb` written with `ENABLE_NOTIFICATION_VALUE` at `BleServiceHelper.java:38-50`.
- **`ffe3`** is referenced only inside `BleUtil.isADASData` for the `3d17` ADAS service — the ThinkerRide normal-data path never reads/writes `ffe3`.

### Connection-state machine

Class: `com.whbluestar.thinkride.ft.bluetooth.connect.connect.BleConnectWrapper` (subclass of `BluetoothGattCallback` via the anonymous field `V` of type `SqBluetoothGattCallback` at `BleConnectWrapper.java:185-442`).

Sequence:
1. `connect(...)` picks service UUID from ecology+bond mode (`BleConnectWrapper.java:504-516`), calls `connectGatt(ctx, false, mCallback, TRANSPORT_LE=2)` at line 2223.
2. `onConnectionStateChange` (lines 278-316). On `newState==2`: `bleConnectMode==0` → `onConnect`; `==1` → if bonded(12) `onConnect`, else `createBond()`; `==2` → `onConnect`.
3. `onConnect(...)` (lines 248-274) → `bluetoothGatt.discoverServices()` at line 272.
4. `onServicesDiscovered` (lines 404-441): finds service by `mServiceUuid` (line 419), grabs `ffe1` writeCharacteristic (line 429), sets write type 1 (line 435), calls `BleServiceHelper.isSupportService(...)` which subscribes `ffe2` and writes CCCD.
5. `onDescriptorWrite` (lines 326-351): on status==0 → schedules timeouts (msg 6 @ 3500ms, msg 7 @ 10000ms) and calls `requestMtu(247)` at line 350.
6. `onMtuChanged` (lines 354-375): sets `JsonManager.d = max(mtu - 62, 23)` at line 370 (OTA file chunk size), flips `N=true`, and if descriptor done, calls `createDataThread()`.
7. `createDataThread()` (lines 677-693): instantiates and starts `WriteThread`, then `queryPairStatus()` and `continuingTheProcess()` → `queryBaseInfo()`.

### MTU negotiation

App requests 247 at `BleConnectWrapper.java:350`; on failure self-retries with `mtu - mtu/10` (lines 1925-1938). Result drives **only** OTA chunk sizing — JSON BLE packets remain 104B.

### RX reassembly

`readMessage(byte[])` at `BleConnectWrapper.java:1805-1880` accumulates `W`, splits on `}` tokens, reconstructs JSON. The leading `0xFE seq seq` and trailing nibble-CRC `0xFF 0x00...` are non-JSON and get stripped during the split — framing is **textually self-synchronizing**. RX is **CRC-less** (the app never validates the inbound nibble sum).

## 2. Framing & CRC (byte-level reconstruction)

### TX framer (BLE → dash, `ffe1`)

Source: `WriteThread.addPackageToList(JSONObject)` at `com/whbluestar/thinkride/ft/bluetooth/connect/data/WriteThread.java:141-180`.

```
addPackageToList(json):
    payload = (json.toString() + "\0").getBytes()           // line 156
    catted  = ByteUtils.byteCat(payload)                    // appends 2-byte nibble-CRC overlay
    for i in 0 .. catted.length step 100:
        inner_len = min(100, catted.length - i)             // line 164
        bArr = new byte[104]                                // line 165 — ALWAYS 104 bytes
        bArr[0]            = 0xFE                           // line 166 (start sentinel)
        bArr[1..2]         = <reserved for seq>             // filled later, see writeSeqIntoData
        copy(catted[i..i+inner_len], bArr[3..3+inner_len])  // line 168
        bArr[3+inner_len]  = 0xFF                           // line 167 (end sentinel)
        // bytes [3+inner_len+1 .. 103] stay 0x00 (zero pad)
        enqueue PacketByteWrapper(bArr, packetGroupId)
```

Maximum-size chunk layout: `byte[0]=0xFE, byte[1..2]=seq, byte[3..102]=payload, byte[103]=0xFF`.

### Sequence numbering

Seq bytes at offsets 1..2 are filled at dispatch time, not enqueue:
- `WriteThread.run()` line 349: `packetByteWrapper.writeSeqIntoData(i2)`
- `PacketByteWrapper.writeSeqIntoData(int)` at `com/whbluestar/thinkride/ft/bluetooth/connect/data/PacketByteWrapper.java:32-37` writes BIG-ENDIAN u16 via `short2Bytes(short)` at lines 16-18: `{(byte)((s>>8) & 0xFF), (byte)(s & 0xFF)}`.

So **seq is BE u16 at byte offsets 1..2**. `AtomicInteger e` is the per-WriteThread global seq counter (`WriteThread.java:70`), incremented per-packet at lines 93-104, wrapped at 65535.

(Note: green_trip and cn_thinkerride ship with `writeSeqIntoData` absent; bytes 1-2 stay zero. ThinkerRide does fill them.)

### CRC algorithm — `byteCat` + `getCRCCode`

`ByteUtils.byteCat(byte[])` at `com/whbluestar/thinkride/ft/ota/ByteUtils.java:25-31`:

```java
byte[] crc = getCRCCode(bArr);                       // length 2
byte[] out = new byte[bArr.length + crc.length];     // payload.length + 2
System.arraycopy(bArr, 0, out, 0, bArr.length);
System.arraycopy(crc, 0, out, bArr.length - 1, crc.length);  // ← dst offset = length - 1
return out;
```

**Subtle**: destination offset is `bArr.length - 1`, NOT `bArr.length`. So CRC byte 0 **overwrites the `'\0'` terminator** appended by `addPackageToList`, and CRC byte 1 goes into out[length]. Net: total grows by exactly 2 bytes; the trailing `\0` is replaced by CRC[0], then CRC[1] is appended. Contrast `byteCat1` (lines 33-39) which uses offset `bArr.length` (true append) — but `byteCat1` is not used in the live JSON path.

`ByteUtils.getCRCCode(byte[])` at `ByteUtils.java:127-133`:

```java
byte sum = 0;
for (byte b : bArr) sum += b;                        // 8-bit wrap-around sum
return new byte[] {
    (byte) (((sum & 0xF0) >> 4) | 0x80),             // hi nibble of sum, OR'd with 0x80
    (byte) (( sum & 0x0F)       | 0x80)              // lo nibble of sum, OR'd with 0x80
};
```

This is **not a polynomial CRC** — simple modulo-256 byte sum split into two nibbles, each placed in low 4 bits of a byte with bit 7 set. Both CRC bytes always have `0x8?` form (out of JSON-printable range).

## 3. Message catalog

### 3a. BLE JSON `msg_id` table

Builders in `com/whbluestar/thinkride/manager/JsonManager.java` unless noted. `A→D` = app→dash; `D→A` = dash→app.

| msg_id | Name / purpose | Dir | Builder | Handler / consumer |
|--------|----------------|-----|---------|---------------------|
| 1  | sendNaviInfoOld (legacy navigation card) | A→D | `JsonManager.java:1416` | outbound only; icon, icon_bitmap, next_road, cur_retain_distance, path_retain_distance, remain_time |
| 2  | sendNotification | A→D | `JsonManager.java:1504` | app_name, title, content, package_name, icon(base64 PNG) |
| 3  | sendIncallInfo | A→D | `JsonManager.java:1229` | name, number |
| 4  | sendCross (intersection bitmap) | A→D | `JsonManager.java:2615` | icon (base64) |
| 6  | sendMMS | A→D | `JsonManager.java:1320` | title, content |
| 7  | sendLocation (street string) | A→D | `JsonManager.java:1283` | street; gated by `isActivate()` |
| 8  | sendWeather (legacy) | A→D | `JsonManager.java:2319` | weather, temperature; gated by `isActivate()` |
| 9  | sendHangup | A→D | `JsonManager.java:1201` | (empty) |
| 10 | item-multiplexed legacy bidirectional channel | ↔ | `JsonManager.java:521,733` (request{Mileage,Speed}) | `BleConnectWrapper.handleMessage:885-1126` |
| 11 | **sendCurrentDateTime** (TIME SYNC) | A→D | `JsonManager.java:1086` | time(`yyyy-MM-dd HH:mm:ss`), tag(int). Dash never replies with msg_id=11; it solicits via msg_id=10 item=4 |
| 12 | requestUniqueCode | A→D | `JsonManager.java:775` | (empty) |
| 13 | requestVersionCode | A→D | `JsonManager.java:808` | reply lands as msg_id=10/item=6 |
| 14 | disconnectBLE | A→D | `JsonManager.java:157` | (empty) |
| 15 | endNavi | A→D | `JsonManager.java:169` | (empty) |
| 16 | disableWifiAp | A→D | `JsonManager.java:142` | (empty) |
| 17 | resetSubMileage | A→D | `JsonManager.java:828` | (empty) |
| 18 | requestBleMac | A→D | `JsonManager.java:476` | (empty) |
| 21 | sendSubTotalMileage | A→D | `JsonManager.java:2119` | mileage(int) |
| 22 | sendCurrentSpeed | A→D | `JsonManager.java:1103` | cur_speed(int) |
| 23 | sendBatteryRemainingCapacity | A→D | `JsonManager.java:981` | power(int) |
| 24 | sendLinkInfo (phone identity) | A→D | `JsonManager.java:1257` | unique_info(string=nickname) |
| 25 | "diff-info" multiplexed channel (msg_type selector) | ↔ | many | `BleConnectWrapper.handleMessage:1128-1265` |
| 26 | requestProductType | A→D | `JsonManager.java:625` | (empty) |
| 27 | "func" multiplexed channel | ↔ | many | `BleConnectWrapper.handleMessage:1266-1448` + `MsgManager.isXxxFunc` at `com/whbluestar/thinkride/ft/unicode/MsgManager.java:197-287` |
| 50 | sendActivateVehicle | A→D | `JsonManager.java:847` | BID(string) |
| 54 | checkVehicleCurStatus | A→D | `JsonManager.java:110` | (empty) |

#### 3a-i. msg_id=10 (item-multiplexed) subtable

Source: `BleConnectWrapper.java:874-1126`. Most are D→A; a few have an A→D request form too.

| item | Dir | Meaning | Fields | Citation |
|------|-----|---------|--------|----------|
| 0 | D→A | dash → hangup-call | none | line 898 |
| 1 | D→A | speed sample | current, max, average | line 901 |
| 1 | A→D | requestSpeed | (request only) | `JsonManager.java:733` |
| 2 | D→A | odometer | total(int×10), subtotal(int×10) | line 904 |
| 2 | A→D | requestMileage | (request only) | `JsonManager.java:521` |
| 3 | D→A | car-info | tire_pressure, remaining_oil, endurance | `BleDeviceData.parseCar:409-421` |
| 4 | D→A | **time-sync solicitation** (`tag` is round-trip id) | tag | line 914 → `addPackageToList(JsonManager.sendCurrentDateTime(tag))` |
| 5 | D→A | unicode (TUC code reply) | code | line 916; `parseUnicode` at `BleDeviceData.java:461-464` |
| 6 | D→A | **firmware-version reply** (reply to msg_id=13) | version, sysversion, mcuversion, btversion, fasysversion, insidenaviversion, fainsidenaviversion, themeversion, ota (flex only), flash_ver (flex only) | lines 922-1023 |
| 7 | D→A | resend-request (continue from index N) | cur_package_index | line 1031-1039 (`writeThread.setResendPackStartIndex`) |
| 9 | D→A | single-packet-loss resend | packet_loss_index | line 1042-1046 (`writeThread.setPacketLossIndex`) |
| 10 | D→A | auto-connect approval | none | line 1048-1050 |
| 11 | D→A | diff-version capability bitmap | screen_info, ota, bt_set, bt_set_lang, bt_set_uint, transmission, dvr, form | lines 1051-1065; `parseDiffVersion` at `BleDeviceData.java:423-452` |
| 12 | D→A | OTA file-start CRC ACK | crc | lines 1067-1078 |
| 13 | D→A | OTA file-end / loss reply | file, loss[] (`[-1]`=ok) | lines 1079-1119 |
| 14 | D→A | resume-transfers signal | none | lines 1120-1122 |
| 53 | D→A | lock/active status | lock_status, need_active, bid | lines 888-895; `parseLock` at `BleDeviceData.java:454-459` → `LockStatusEvent` |

#### 3a-ii. msg_id=25 (msg_type-multiplexed "diff-info") subtable

Builders all set `msg_source=2` (= app→dash). When dash sends D→A, msg_source=1.

| msg_type | Dir | Meaning | Fields | Citation |
|----------|-----|---------|--------|----------|
| 1  | ↔ | ride state control + ride report | control_info(1=start,2=pause,3=stop,4=report), time, calorie, max_speed, ave_speed, total_deep, ave_altitude | `JsonManager.java:2154`(sendTimeFunction), `1958`(sendRideReport); RX `BleConnectWrapper.java:1131-1186` |
| 2  | D→A | stop navigation | start | `BleConnectWrapper.java:1187-1207` |
| 3  | A→D | mileage trip | mile_trip(double) | `JsonManager.java:1342` |
| 4  | A→D | total mileage | mile_trip(double) | `JsonManager.java:2291` |
| 5  | A→D | speed | speed, max_speed | `JsonManager.java:2100` |
| 6  | A→D | avg speed | ave_speed(double) | `JsonManager.java:918` |
| 7  | A→D | mobile signal | signal | `JsonManager.java:2054` |
| 8  | A→D | calorie | calorie | `JsonManager.java:997` |
| **9** | **A→D** | **ALTITUDE / ELEVATION** | altitude(int m), ave_altitude, max_altitude, pond_distance(double km), pond_time, head | `JsonManager.java:1129-1138` — see §6 |
| 10 | A→D | location picture | pic(base64) | `JsonManager.java:1304` |
| 11 | A→D | weather w/ wind | weather, temperature, wind_power | `JsonManager.java:2402` |
| 12 | A→D | weather warning | weather_waring | `JsonManager.java:2341, 2369` |
| 13 | A→D | set language | language(int) | `JsonManager.java:2021` |
| 14 | ↔ | unit change | unit | `JsonManager.java:2039`; RX line 1208-1213 |
| 15 | D→A | orientation | start | `BleConnectWrapper.java:1214-1219` |
| 15 | A→D | orientation (compass) | angle(float) | `JsonManager.java:1543` (sendOritation) |
| 18 | ↔ | language reply | language | `JsonManager.java:502`; RX line 1221-1229 |
| 19 | A→D | record time | time | `JsonManager.java:2493` |
| 20 | ↔ | record time query+reply | time | `JsonManager.java:668`; RX line 1231-1238 |
| 21 | A→D | set record status | status | `JsonManager.java:2475` |
| 22 | ↔ | record status query+reply | status | `JsonManager.java:650`; RX line 1240-1248 |
| 23 | A→D | mirror set | status | `JsonManager.java:2460` |
| 24 | ↔ | mirror status query+reply | status | `JsonManager.java:537`; RX line 1250-1264 |
| 25 | D→A | light status | lamp_type, status | line 1250-1255 (`LightStatusEvent`) |
| 26 | A→D | remove-bound | status | `JsonManager.java:1911` |

#### 3a-iii. msg_id=27 (func-multiplexed) subtable

Dispatched by `MsgManager.isXxxFunc(jo)` predicate cascade at `MsgManager.java:197-287` plus `BleConnectWrapper.handleMessage:1266-1448`. Func string ↔ handler:

| `func` | Acts seen | Builder citations | Parser citation |
|--------|-----------|--------------------|-----------------|
| `TUC` | `get` (query), push-update | `JsonManager.queryTucsJson:413` | `MsgManager.tuc:672-688` / `724-758` |
| `TTS` | (passthrough) | — | `MsgManager.tts:661` |
| `USER` | `RACING`, `RIDELIST`, `RIDECOUNT`, `CONTACTS` | `JsonManager.requestRacing:637`, `requestRiding:683`, `requestRidingStatistics:703`, `sendContacts:1044` | `MsgManager.user:688` |
| `SIM` | `REQUEST` | `JsonManager.requestSimICCID:717` | `MsgManager.sim:522` |
| `INSIDENAVI` | `query`, `CMD:pull_cityList/update_city/delete_city/remove_cache/inside_naviinfo/stop_navi/version` | many | `MsgManager.insideNavi:164` |
| `AUDIO` | `ret_status`, `send_text` | `DeviceMsgFactory.sendSpeechStatus/Text:247-263` | `MsgManager.audio:79` |
| `PAIR` | `get_pairinfo` | `JsonManager.queryPairStatus:362` | `MsgManager.pairInfo:368` |
| `OTA` | act=1..7 | `JsonManager.requestUpdateInfo:787`, `sendOtaUpdateTask:1865`, `checkUpdateStart:95`, `sendCancelUpdateTask:1012`, `sendOtaUpdateResult:1851`, `sendOffAutoPower:1529` | `BleConnectWrapper.handleOtaMessage:1494-1533` |
| `UPDATE` | `READY`, `RESULT`, `RESULT_CONFIRM`, `UPDATE_PROGRESS`, `TASK_TYPE` | `JsonManager.queryRtxOtaUpdateStatus:388`, etc. | `MsgManager.parseUpdateInfo:491` |
| `ADAS` | `get_connect_mode`, `get_connect_status`, `match_mode`, `remove_mode`, `set_adas_parameter`, `send_adas_data`, `get_adas_mac`, `get_adas_parameter` | `JsonManager` various | `MsgManager.parseAdasInfo:391` |
| `NAVI` | `act=1` (dest), `2` (retain), `3` (info-table) | `JsonManager.sendNaviDest:1393`, `sendNaviRetain:1437`, `sendNaviInfo:2665` | dash sends |
| `ROAD_NAVI` | many | many | `RoadNaviHelper.handleBleMsg` / `BleConnectWrapper.java:1367-1400` |
| `GPS` | `signal_status` | `JsonManager.sendGPSSignal:1169` | — |
| `KEY` | `act = <key>` | `JsonManager.sendKeyPress:1243` | — |
| `MUSIC` | `control`, `get_status`, `ret_msg`, `ret_status` | `JsonManager.sendMusicPlayInfo:1357`, `sendMusicStatus:1379` | `MsgManager.music:289` |
| `TIRE` | `ret_data`, `get_data`, `set_unit`, `set_limit`, `send_mac`, `tire_match_sta`, `get_tire_match_sta` | `JsonManager.sendTireData:2174`, etc. | `MsgManager.tire:638` |
| `THEME` | act=1..N, task | `JsonManager.sendThemeStatus:2634`, `sendThemeTask:2136` | `MsgManager.theme:549` |
| `LED` | `start_voice`, `get_status`, etc. | (FlexScreen) | line 1326-1339 |
| `BT_KEY` | `disconnect`, `distance`, `lock` | `JsonManager.requestBTKey{Disconnect:427, Distance:441, Lock:457}` | line 1340 |
| `COMBO` | `extend_func` | `JsonManager.sendExtendFunc:1154` | line 1402 |
| `COREDUMP` | `REQUEST` | `JsonManager.sendCoredumpResult:1063` | line 1408 |
| `SCPT` | `get_scpt` | `JsonManager.queryDeviceFunctionCompatibilityInfo:307` | `MsgManager.compatibilityInfo:89` |
| `SCREEN` | `screenchoose`, file-transfer | `JsonManager.sendScreenSaver:2006` | `ScreenSaverHelper`, line 1401-1447 |
| `MOTOR_SIGNAL` | (various) | — | `MsgManager.parseMotorSignal:482` |
| `AUTOOFF` | `get_auto_off_status` | `JsonManager.getAutoOffStatus:198` | line 1267 |
| `CAR_INFO` | `get_car_info` | `JsonManager.requestCarInfo:486` | — |
| `TBOX` | `get_sn` | `JsonManager.queryTBox:401` | `MsgManager.tBox:532` |
| `hanjd_test` | `connect`/`stop` (4G test) | `JsonManager.requestOfflineMap4G:552` | — |

### 3b. 17818 TCP binary `(type, sub)` table

Header: `[type:u8][sub:u8][len:u32_be][payload:len bytes]`. Built by `WifiMessageHead` at `com/whbluestar/thinkride/ft/ota/WifiMessageHead.java:9-30`.

Phone-side reader: `DeviceReader.read()` at `com/whbluestar/thinkride/ft/process/wifi/deivce/DeviceReader.java:62-93`.

| type | sub (hex) | Dir | Meaning | Builder | Parser |
|------|-----------|-----|---------|---------|--------|
| 1 | 0x01 | A→D | requestFirmwareVersion | `DeviceMsgFactory.java:65-68` | reply type=2 sub=1 → `parseVersionCode` |
| 1 | 0x0C | A→D | sendLinkInfo (phone nickname, 256-B fixed-width) | `DeviceMsgFactory.java:169-179` | dash-side |
| 1 | 0x0E | A→D | requestProductType | `DeviceMsgFactory.java:134-141` | reply type=2 sub=9 → `parseProductType` |
| 1 | 0x11 | A→D | requestMac | `DeviceMsgFactory.java:88-95` | reply type=2 sub=8 → `parseMac` |
| 1 | 0x13 | A→D | setLanguage(int) | `DeviceMsgFactory.java:286-294` | reply type=2 sub=10 |
| 1 | 0x14 | A→D | requestLanguage | `DeviceMsgFactory.java:79-86` | reply type=2 sub=10 |
| 1 | 0x16 | A→D | sendRemoveBound | `MsgFactory.java:28-35` | — |
| 1 | 0x17 | A→D | sendDeviceType (payload = BE int "2") | `MsgFactory.java:8-17` | — |
| 2 | 0x01 | A→D | sendHeart (keepalive, every 2s) | `DeviceMsgFactory.java:165-167` | — |
| 2 | 0x01 | D→A | version-code reply (1024-B payload) | dash | `parseVersionCode` |
| 2 | 0x07 | D→A | wifi-disconnect notification | dash | dispatched as `case 7` (log only) |
| 2 | 0x08 | D→A | mac reply (32-byte string) | dash | `parseMac` |
| 2 | 0x09 | D→A | product-type reply (4-byte BE int) | dash | `parseProductType` |
| 2 | 0x0A | D→A | language reply (4-byte BE int) | dash | `parseLanguage` |
| 3 | 0x01 | A→D | sendNaviInfo (274-byte binary) | `DeviceMsgFactory.java:181-196` | dash-side |
| 3 | 0x0D | A→D | sendEndNavi | `MsgFactory.java:19-26` | dash-side |
| 6 | * | D→A | "Up" channel (sub=7 = end-navi) | dash | `DeviceUpMsgManager` |
| 0xEE | 0xFD | ↔ | JSON-envelope channel | `DeviceMsgFactory.generateByteData:32-42` | trailing byte must be `0xFF` (`DeviceReader.java:79`); JSON parsed at `MobileDeviceJsonMsgManager.dispatchMsg:92-116` |

Port constants: 17818 = BLE-data over wifi/4G; 18888 = DVR; 15456 = projection; 15457 = projection heartbeat; 19000 = OTA. Phone is the **server**; the dash dials in.

### 3c. 17818 JSON `msg_id` table (the 0xEE 0xFD envelope)

Wire format: `0xEE 0xFD <payloadLen_be:4> <UTF-8 JSON> 0x00 0xFF`. The trailing `0xFF` is appended at `DeviceMsgFactory.java:40` and validated at `DeviceReader.java:79`.

Dispatcher: `MobileDeviceJsonMsgManager.dispatchMsg` at `com/whbluestar/thinkride/ft/process/mobile/device/MobileDeviceJsonMsgManager.java:92-116`. Only handles:

- **`msg_id==10`** → `handleIdOld(jo)` at lines 63-89. Inside, **only** `item==12` (OTA file-start CRC ack) and `item==13` (OTA file-end loss array) are handled. All other items (0..11, 14, 53) defined for BLE are **dropped** on the TCP path.
- **`msg_id==27`** → `handleIdNew(jo)` at lines 27-61. Predicate cascade: `TUC / TTS / USER / SIM / INSIDENAVI / AUDIO / THEME / OTA`, with fallback to `tBox`. **No handlers** for `PAIR / ADAS / SCPT / LED / BT_KEY / RoadNavi` on TCP — those are BLE-only.

So 17818 JSON is effectively for (1) OTA file transfers and (2) the modern `func`-multiplexed channel. Location, weather, altitude, time-sync are BLE-only.

## 4. Connect-time handshake sequence

### BLE side (ordered)

| # | Step | Dir | Citation |
|---|------|-----|----------|
| 1 | `BluetoothDevice.connectGatt(ctx, false, callback, TRANSPORT_LE)` | A→D radio | `BleConnectWrapper.java:2223,2253` |
| 2 | `onConnectionStateChange(STATE_CONNECTED)` → own `onConnect` → `gatt.discoverServices()` | — | line 272 |
| 3 | `onServicesDiscovered`: grab `ffe1` write char, `setWriteType(1)` | — | lines 419-435 |
| 4 | `BleServiceHelper.isSupportService(...)`: enable `ffe2` notify + write CCCD `0x0100` | A→D | `BleServiceHelper.java:28-50` |
| 5 | `onDescriptorWrite(status==0)` → schedules msg6@3500ms, msg7@10000ms timeouts; calls `requestMtu(247)` | A→D | `BleConnectWrapper.java:347-350` |
| 6 | `onMtuChanged` → sets `JsonManager.d` (OTA chunk size) | — | line 370 |
| 7 | If both notify-write-ok and MTU-ok → `createDataThread()` | — | line 373 |
| 8 | `createDataThread()` starts `WriteThread`, calls `queryPairStatus()` and `continuingTheProcess()` | — | lines 677-693 |
| 9 | `queryPairStatus()` → `{"msg_id":27,"func":"PAIR","act":"get_pairinfo"}` (only if needPair && unbonded) | A→D | line 1740-1746 |
| 10 | `continuingTheProcess() → queryBaseInfo() → queryVersion()` | — | line 1722-1734 |
| 11 | `queryVersion()` schedules `handlerAction(9, 500ms)` which then sends `{"msg_id":13}` | A→D | lines 1766-1802 + 1604 |
| 12 | If GPS location available AND `DeviceFunction.getLocation()` → `sendLocation(street)` (msg_id=7) | A→D | line 1727-1729 |
| 13 | If `DeviceFunction.getAltitude()` → **`sendElevationAndPond(altitude, 0, 0, 0, 0, 0)`** (msg_id=25, msg_type=9) | A→D | line 1730-1732 |
| 14 | Dash replies msg_id=10/item=6 → `parseVersion` extracts version, sysversion, etc. | D→A | line 922-1023 |
| 15 | **Post-firmware burst** (lines 934-963), in order: | A→D |  |
|    | a. `sendLocation(MultiPanelInfo.getCityName())` if non-empty | | line 934 |
|    | b. **`sendCurrentDateTime(-1)` (msg_id=11) — UNSOLICITED TIME SYNC** | | line 936 |
|    | c. `sendLinkInfo(AppUtil.getConnectNickname())` (msg_id=24) | | line 937 |
|    | d. `requestProductType()` (msg_id=26) | | line 938 |
|    | e. `queryHasAdasFunctionToCar()` (msg_id=27 func=ADAS act=get_connect_mode) | | line 939 |
|    | f. `handlerAction(8, 10000)` — re-poke ADAS after 10s if flagged | | line 940 |
|    | g. `queryDeviceFunctionCompatibilityInfo()` (msg_id=27 func=SCPT) | | line 963 |
| 16 | Once `isVersionReady()` (continues lines 1014-1023): | A→D |  |
|    | h. `checkVehicleCurStatus()` (msg_id=54) | | line 1014 |
|    | i. `queryActivateStatus(false)` → `queryTucsJson()` (msg_id=27 func=TUC) | | line 1019 |
|    | j. `checkOtaFunc(...)` | | line 1021 |
|    | k. `queryDevicePlayerVoiceStatus()` (msg_id=27 func=INSIDENAVI query=2) | | line 1022 |
|    | l. `queryInsideNaviStatus()` (msg_id=27 func=INSIDENAVI query=1) | | line 1023 |
| 17 | When dash sends msg_id=10/item=4 → app sends `sendCurrentDateTime(tag)` | A→D | line 914 |

### 17818 TCP side

`DeviceWriter.startTimer()` at `com/whbluestar/thinkride/ft/process/wifi/deivce/DeviceWriter.java:34-59` runs every 2s:
- If `ConnectedInfo.getInfo("version")==null` → send `requestFirmwareVersion()` (type=1 sub=1)
- If `ConnectedInfo.getInfo("mac")==null` → send `requestMac()` (type=1 sub=0x11)
- Once both non-null → switch to heartbeat `sendHeart()` (type=2 sub=1) on the same 2s cadence

There is **NO time-sync over TCP** — time-sync is BLE-only.

## 5. Time-sync codepath (full trace)

### Producer

`JsonManager.sendCurrentDateTime(int tag)` at `JsonManager.java:1079-1095`:

```java
public static JSONObject sendCurrentDateTime(int i) {
    String str = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    if (isDuplicateSendingInfo(str)) return null;
    JSONObject j = new JSONObject();
    j.put("msg_id", 11);
    j.put("time", str);
    j.put("tag",  i);
    return j;
}
```

- **Pattern**: `yyyy-MM-dd HH:mm:ss` — no timezone offset emitted. Uses device's default TimeZone via `Locale.getDefault()`. Dash receives naive local datetime.
- **`tag` semantics**: -1 = unsolicited; non-negative = echo of dash-requested `tag`.

### Triggers (2)

1. **On firmware-version ready (unsolicited)**: `BleConnectWrapper.java:936` calls `addPackageToList(JsonManager.sendCurrentDateTime(-1))` after dash replies with msg_id=10/item=6.
2. **On dash request**: `BleConnectWrapper.java:914` — in `handleMessage` when `msg_id==10 && item==4`: `addPackageToList(JsonManager.sendCurrentDateTime(jSONObject.optInt("tag")))`. Phone echoes the same `tag` back.

### Dispatch chain

`addPackageToList(jo)` at `BleConnectWrapper.java:2036-2105` → `WriteThread.addPackageToList(jo)` at `WriteThread.java:141-180` → 104-byte framing → seq write → `BleWriteCallBack.writeValue(bArr)` at line 352 → `BleConnectWrapper.writeValue` at line 2613-2615 → `sendmessage(byte[])` at lines 1940-1994 → `mWriteCharacteristic.setValue(bArr)` (line 1954, 1966) + `mGatt.writeCharacteristic(...)` (line 1972).

### ACK / response

The dash does NOT send an msg_id=11 ack back. The phone never tries to match an ACK. The dash uses the item=4 solicitation channel to ask for refreshes; the phone's reply IS the ACK.

The deduper `isDuplicateSendingInfo` is global single-slot — any other builder bumping the dedupe-key in between allows re-emission. Effectively only filters sub-second exact-repeat bursts.

## 6. Altitude codepath (full trace)

### Direction: PHONE → DASH (overlay channel)

The dash never sends altitude up. There is no `optString("altitude")` parser anywhere in the decompile (verified). The phone broadcasts altitude as a one-way overlay.

### Producer

`JsonManager.sendElevationAndPond(int alt, int aveAlt, double pondDist, int pondTime, int head, int maxAlt)` at `JsonManager.java:1113-1149`:

```java
JSONObject j = new JSONObject();
j.put("msg_id",        25);
j.put("msg_type",      9);
j.put("msg_source",    2);          // 2 = app→dash
j.put("altitude",      alt);        // INTEGER METERS (cast from double, truncated)
j.put("ave_altitude",  aveAlt);     // integer meters
j.put("max_altitude",  maxAlt);     // integer meters
j.put("pond_distance", pondDist);   // DOUBLE — kilometers of climb
j.put("pond_time",     pondTime);   // integer seconds spent climbing
j.put("head",          head);       // integer meters of total descent
```

**Hard gates** (lines 1115-1126):
1. `ConnectHelper.getInstance().getCurrentDataDevice() != null`
2. `currentDataDevice.isActivate() == true`
3. `!isDuplicateSendingInfo("sendElevationAndPond" + alt)` — only re-emit if altitude value changed

### Call sites (5)

1. **GPS update during navigation** — `com/whbluestar/thinkride/constants/GlobalData.java:1267-1270`:
   ```java
   if (ConnectHelper.getDeviceFunction().getAltitude() && System.currentTimeMillis() - this.b > 2000) {
       ConnectHelper.sendMessage(JsonManager.sendElevationAndPond((int) iSQLocation.getAltitude(), 0, 0.0d, 0, 0, 0));
       this.b = System.currentTimeMillis();
   }
   ```
   Throttled to once per 2000 ms. Gated by `DeviceFunction.getAltitude()` (`com/thinkerride/data/DeviceFunction.java:493-495` returns `this.altitude == 1` — server-derived per-device flag).

2. **Non-nav location callback** — `GlobalData.java:1697-1712`. Same 2s throttle, additionally requires `iSQLocation.getAltitude() != 0.0` (filters missing-GPS).

3. **MapBox location manager (oversea variant)** — `com/thinkerride/oversea/map/mapbox/location/MapBoxLocationManager.java:313, 474, 481`. Three points: bare on nav-loc update (313), full-stats on ride-update (474), bare on map-point (481).

4. **On-connect bootstrap** — `BleConnectWrapper.java:1730-1733` (inside `queryBaseInfo()`). As soon as a fresh BLE connection has a valid `SQNavigationManage.getNavigation().getLocation()`, a single bare-altitude push goes out — potentially before the version handshake completes.

5. **Ride-completion / ride summary** — `com/whbluestar/thinkride/ft/home/go/ride/Riding.java:400-402`:
   ```java
   if (ConnectHelper.getDeviceFunction().getAltitude()) {
       ConnectHelper.sendMessage(JsonManager.sendElevationAndPond(altitude2, recordAvgAltitude, d, climbTime, (int) totalDrop, (int) maxAltitude));
   }
   ```
   Only path that fills `pond_distance, pond_time, head, max_altitude` with real (non-zero) values from `SportDataHelper`.

### Encoding & units

- `altitude` — signed integer **meters above sea level**, cast from double via `(int)`, so fractional part is **truncated, not rounded**. No scaling factor.
- `ave_altitude`, `max_altitude` — same (integer meters).
- `pond_distance` — JSON double, **kilometers** of climbing. Built as `Double.parseDouble(DecimalUtil.double2String(1, climbling/1000.0))` (`Riding.java:396`), one decimal place.
- `pond_time` — integer seconds.
- `head` — integer meters of total descent (variable name is misleading; all callers wire `totalDrop` here).
- Encoded as textual JSON, no binary endianness considerations. Wrapped in the standard 0xFE/0xFF nibble-CRC framer.

### Reverse direction (D→A)?

**No.** The dash sends many msg_id=25 frames back (msg_source=1) for control_info, navigation, unit/language/time/status acks, light status — but never msg_type=9. There is no inbound altitude parser in the decompiled app code. Confirmed by grep across the entire source tree.

### TX path

`ConnectHelper.sendMessage(jo)` → `BleConnectWrapper.addPackageToList(jo)` → `WriteThread` → `ffe1`. No 17818-TCP equivalent (TCP JsonMsgManager doesn't handle msg_id=25).

## 7. All BLE write helpers

The single GATT-level writer is `BleConnectWrapper.sendmessage(byte[])`. Everything else feeds into it.

| Helper | Role | Citation |
|--------|------|----------|
| `BleConnectWrapper.sendmessage(byte[])` | **Sole** GATT writer. `mWriteCharacteristic.setValue(data)` + `mGatt.writeCharacteristic(mWriteCharacteristic)`. Retries on `false` with 1s sleep. | `BleConnectWrapper.java:1940-1994` |
| `BleConnectWrapper.writeValue(byte[])` | Implements `BleWriteCallBack.writeValue`. Calls `sendmessage`. | `BleConnectWrapper.java:2613-2615` |
| `WriteThread.run()` | Pulls next `PacketByteWrapper` from FIFO, assigns seq, writes via callback. | `WriteThread.java:347-356` |
| `WriteThread.addPackageToList(JSONObject)` | Frames JSON into 104-byte packets, enqueues. | `WriteThread.java:141-180` |
| `BleConnectWrapper.addPackageToList(JSONObject)` | App-level wrapper, applies activation/feature gates, delegates to `WriteThread.addPackageToList`. | `BleConnectWrapper.java:2036-2105` |
| `ConnectHelper.sendMessage(jo)` | Top-level static facade used by GlobalData, Riding, MapBox, etc. Routes through `BleConnectWrapper.addPackageToList`. | (many call sites) |
| `ScreenSaverBleWrapper.sendFile/sendFileStart` | Screen-saver file transfer. JSON via the same callback. | `WriteThread.java:334-376` |
| `RoadNaviBleWrapper.sendFile/sendFileStart` | Road-navi map data. Same pattern. | `WriteThread.java:314-331` |
| `LedSendManager`, `HeadSendManager` (Flex-only) | LED file transfers. Both use `byteCat` internally. | |
| `UpgradeWriteThread` | OTA-only parallel thread for the upgrade service UUID `0000e0ff-5817-...`. Not used on the Kove. | `UpgradeWriteThread.java` |

## 8. TUC / activation gate

`com/thinkerride/tbox/util/TucUtil.java:14-16`:

```java
public static boolean isEffectiveTuc(String str) {
    return (TextUtils.isEmpty(str) || "F".equalsIgnoreCase(str) || str.length() < 16) ? false : true;
}
```

Effective TUC iff non-empty, not `"F"` (case-insensitive), and length ≥ 16. Confirmed.

`hasActivationFunc(version)` at `TucUtil.java:7-12` — true iff the version string contains literal `"_TUC="`.

App-level `isActivate()` at `BleConnectWrapper.java:2319-2331`: if version contains `"_TUC="` → require `isActive()`; otherwise legacy device → always active. Then `addPackageToList` gates msg_ids 1..6 at line 2066-2069 (i.e., blocks navigation/notify/incall/cross/MMS when not activated). Altitude (msg_id=25/msg_type=9), location (msg_id=7), weather (msg_id=8) have **independent activation checks in their builders** (e.g., `JsonManager.java:1120-1122`).

## 9. Anything else surprising

1. **`byteCat` overwrites the last payload byte, not appends.** Destination offset is `bArr.length - 1`. The `\0` terminator gets clobbered by CRC[0]. A naive reimplementation will mis-frame.
2. **`getCRCCode` is a simple modulo-256 sum**, not polynomial. Output nibbles each OR'd with `0x80`. Any CRC-16 reimplementation will fail.
3. **CRC bytes are deliberately bit-7-set (`0x8?`)** so they can't collide with JSON's printable ASCII. The framing is **textually self-synchronizing** — RX parser just looks for `{...}`.
4. **MTU 247 requested, but JSON packets are always 104 B.** MTU only sizes OTA file chunks via `JsonManager.d = max(mtu-62, 23)`. JSON BLE writes never use bigger frames.
5. **Phone-side TX is single-threaded.** `WriteThread` runs a single loop with mode states (1=ini-file, 2=td-file, 3=file-content, 4=pause, 5=complete, 10=normal, 1000-1008=led/road-navi/screen-saver subflows).
6. **Seq is global per WriteThread, not per-message.** A 3-chunk JSON consumes 3 sequential seqs. Matches dash's `cur_package_index` (item=7) resend semantics.
7. **Rich resend support**: item=7 ("resume from N", gap-fills with placeholder `{}` JSONs if N > local cursor — `WriteThread.java:464-481`), item=9 ("single packet loss"), msg_id=10/item=13 ("OTA file loss array").
8. **Two parallel parsers for msg_id=27.** BLE has the FULL handler set; TCP is a strict SUBSET (no PAIR/ADAS/SCPT/LED/BT_KEY/RoadNavi).
9. **No timezone in time-sync.** `yyyy-MM-dd HH:mm:ss` in device-local time, no `Z`/offset suffix. Dash has no way to know phone TZ from msg_id=11 alone.
10. **`isDuplicateSendingInfo` is a global single-slot dedupe** — effectively a 1-deep filter; any consecutive different call re-enables the next.
11. **`flash_ver` and `ota` fields in firmware-version reply are only parsed for Flex (e==4)** — silently ignored for the BLE dial (e==0).
12. **`MsgManager.tBox(...)` is the catch-all** for msg_id=27 frames not matching any predicate.
13. **`sendCurrentDateTime` `tag` is signed int** — dash can theoretically send tags up to `2^31-1`. Observed tags are typically small positives or `-1`. No reorder/correlation logic on phone.

## 10. Gaps / could-not-determine

1. **Actual negotiated MTU on the Kove dial.** App requests 247 but the dash's response determines the actual value. Requires sniffing live traffic.
2. **Which msg_id=27 `func`s the Kove 450 dial actually emits.** App has handlers for ~25+ types but the active set depends on firmware.
3. **Whether the dash echoes altitude back.** No phone-side parser exists — but the dash COULD be sending altitude updates that the app silently drops.
4. **`head` field's exact UI semantics.** Variable is named "head" in Java but all callers wire `totalDrop` (descent meters). Possibly mistranslation of 海拔差 / 降幅.
5. **msg_id=10 item=8 meaning.** No case for it in the switch.
6. **ADAS connect-mode enum values returned by the Kove dial.**
7. **Whether the Kove dial uses `ffe3` at all.** App code never reads/writes `ffe3` for the `3c17` service.
8. **TCP bootstrap topology** — phone is the server, dash dials in.
9. **OTA file binary format ($FILE marker).** `BleServiceHelper.printWriteLog:60-118` shows 152-byte header. Outside report scope.
10. **Time-sync resolution.** `yyyy-MM-dd HH:mm:ss` — seconds only, no milliseconds.
