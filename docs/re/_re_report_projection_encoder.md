# OEM Projection Encoder Config — Full RE

All file paths are absolute. The **live** code path in ThinkerRide is the `com.whbluestar.thinkride.ft.projection.*` package; there's also a sibling `com.thinkerride.projection.*` library bundled in the same APK but never instantiated (verified — no `new ProjectionThread(...)` / `new HeartBeatThread(...)` callers anywhere). Treat the `com.thinkerride.projection.*` files as informative-only legacy SDK fossils.

## 1. 15456 socket pipeline (file:line)

- **ServerSocket bind** — generic for all ports, in `SocketListenThread.run()`:
  - `com/whbluestar/thinkride/ft/process/base/SocketListenThread.java:144-147` — `new ServerSocket(); setReuseAddress(true); bind(InetSocketAddress(WifiNetManager.getHost(), port))`
  - Line 164-167 — `if (port != 15456 && port != 15457) setSoTimeout(30000); accept()` (15456/15457 explicitly skip the 30s timeout so the server stays open indefinitely)
- **Port wiring**: `com/whbluestar/thinkride/ft/process/wifi/projection/navi/ProjectionNaviPortController.java:11` (`super(15456, ...)`)
- **Instantiation**: `com/whbluestar/thinkride/ft/process/wifi/WifiMsgCenter.java:122-123`
- **69-byte handshake writer**: `com/whbluestar/thinkride/ft/projection/ProjectionEncoder.java:238-251` (`sendVideoSize()`). Byte layout: `[0]=0x00 flag, [1..63]="android"+\0 padded to 64 bytes, [65..66]=width BE, [67..68]=height BE`. Uses `getUtf8TruncationIndex` to safely truncate UTF-8.
- **H.264 stream writer**: `com/whbluestar/thinkride/ft/projection/ProjectionEncoder.java:138-145` (`writeFully`) → `ProjectionWrapper.getInstance().sendProjectionData(bArr, z, str)` (`com/whbluestar/thinkride/ft/process/mobile/projection/ProjectionWrapper.java:41-51`) → `MobileMsgCenter.sendProjectionMsg(byte[])` (`com/whbluestar/thinkride/ft/process/mobile/MobileMsgCenter.java:218-220`) → `sendMsg(15456, bArr)` (line 54-60) → AIDL `SqWifiInterface.handleMsg(15456, bArr)` which eventually lands on `ProjectionNaviWriter.sendData()` (`com/whbluestar/thinkride/ft/process/wifi/projection/navi/ProjectionNaviWriter.java:22-23`).
- **Service / lifecycle controller**: `com/whbluestar/thinkride/ft/projection/service/ProjectionService.java` — creates `ProjectionEncoder`, `ProjectionScreen`, `StreamThread`.

The reader on 15456 does basically nothing: `ProjectionNaviReader.readNull()` reads 6 bytes / 500ms forever, but `dealMessage` is empty — i.e. the dash never sends anything meaningful on the 15456 socket back to the phone.

## 2. MediaCodec configuration table (per-app)

All values from `createFormat(int bitrate, int frameRate, int iFrameInterval)`:

| Key | ThinkerRide live (`com.whbluestar.thinkride.ft.projection.ProjectionEncoder`) | ThinkerRide legacy SDK (`com.thinkerride.projection.encoder.ProjectionEncoder`) | green_trip (`defpackage/z64.java`) | cn_thinkerride (`defpackage/st1.java`) |
|---|---|---|---|---|
| MIME | `"video/avc"` (`:86`) | `"video/avc"` (`:48`) | `"video/avc"` (`:55`) | `"video/avc"` (`:62`) |
| `bitrate` | `r * WIDTH * HEIGHT` where `r ∈ {1,2,3}` by RAM (`:210`) | `WIDTH * HEIGHT` (`:84`) | `WIDTH * 1 * HEIGHT` (`:76`) | `WIDTH * 3 * HEIGHT` (`:136`) |
| `frame-rate` | `30` (`:210`) | `30` (`:84`) | `30` (`:76`) | `30` (`:136`) |
| `color-format` | `2130708361` (`COLOR_FormatSurface`) (`:89`) | `2130708361` (`:51`) | `2130708361` (`:58`) | `2130708361` (`:65`) |
| `i-frame-interval` | `1` second (`:210`) | `1` second (`:84`) | `1` second (`:76`) | `1` second (`:136`) |
| `repeat-previous-frame-after` | `100000L` µs = 100 ms (`:91`) | `100000L` (`:53`) | `100000L` (`:60`) | `100000L` (`:67`) |
| `bitrate-mode` | `1` = **CBR** (`:92`) | (not set — encoder default = VBR) | (not set) | `1` = **CBR** (`:68`) |
| `profile` | `8` = **AVCProfileHigh** if supported (`:95`) | (not set) | (not set) | (not set) |
| `level` | `4096` = **AVCLevel41** if supported (`:96`) | (not set) | (not set) | (not set) |
| `max-fps-to-encoder` (API 29+) | `frameRate` (`:100`) | `frameRate` (`:55`) | `frameRate` (`:63`) | `frameRate` (`:71`) |

`COLOR_FormatSurface` (`2130708361` = `0x7F000789`) is mandatory because the encoder is fed by a `Surface` returned from `MediaCodec.createInputSurface()`.

## 3. Resolution: hardcoded or negotiated?

**Looked up per-device, not negotiated over 15456.** Source: `DeviceFunction.getScreenShape()` resolved during 17818 control-channel pairing (`ProjectionUtil.isCircle()` etc., `com/whbluestar/thinkride/ft/projection/ProjectionUtil.java:89-119`). Mapping in `ProjectionEncoder` constructor (`:52-76`):

- shape 2 (`isCircle`) → 800×800
- shape 3 (`isPingPongScreen`) → 800×640
- shape 1 (`isVerticalScreen`) → 480×800
- shape 6 (`isVerticalScreenBigTaiLin`) → 600×1024
- shape 7 (`isHorizontalScreenBigTaiLin`) → 1024×600
- shape 8 (`isHorizontalScreen480PTaiLin`) → 800×480
- shape 4 (`isVerticalScreenBig`) → 640×1284
- default (shapes 0/5 — horizontal "normal") → **1280×640** ← Kove 450 Rally dash

The 69-byte handshake on 15456 tells the dash the dimensions the phone chose — the dash doesn't get to vote.

## 4. Bitrate value(s) + computation

- **Live ThinkerRide** (`com/whbluestar/thinkride/ft/projection/ProjectionEncoder.java:200-210`):
  ```java
  float gb = AppUtil.getDeviceMemorySizeGB(...);
  if (gb < 6.0f)       r = 1;
  else if (gb <= 8.0f) r = 2;
  else                 r = 3;
  ... createFormat(r * this.a * this.b, 30, 1);
  ```
  For 1280×640 at the three tiers: **819,200 / 1,638,400 / 2,457,600 bps** (≈ 0.8 / 1.6 / 2.4 Mbps). Pixel 9 Pro is 16 GB → `r=3` → **~2.4 Mbps**.
- **cn_thinkerride** hardcodes `r=3` → always **2.4 Mbps** for 1280×640 (`defpackage/st1.java:136`).
- **green_trip** hardcodes `r=1` → always **~0.8 Mbps** (`defpackage/z64.java:76`).
- **Legacy SDK** is `WIDTH * HEIGHT` (`r=1` equivalent) → **~0.8 Mbps**.

**No runtime adaptation.** No `setParameters`/`Bundle("video-bitrate")` calls. Bitrate set once at `configure()` and never touched.

## 5. Frame rate value(s) + computation

Literal **30 fps**, every variant. `createFormat(... , 30, 1)`:
- `com/whbluestar/thinkride/ft/projection/ProjectionEncoder.java:210`
- `com/thinkerride/projection/encoder/ProjectionEncoder.java:84`
- `defpackage/z64.java:76`
- `defpackage/st1.java:136`

`max-fps-to-encoder` is also set to 30 on API 29+ to throttle the Surface drain rate. Not dash-dictated, not adjustable.

## 6. VirtualDisplay / MediaProjection setup

`com/whbluestar/thinkride/ft/projection/ProjectionEncoder.java:200-228`:

```java
this.d = MediaCodec.createEncoderByType("video/avc");
this.d.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE /*=1*/);
this.f = this.d.createInputSurface();
this.d.start();
this.g = this.e.createVirtualDisplay(
    "projection",
    this.a, this.b,          // 1280, 640
    q,                       // density = 320 dpi (static int q = 320, line 38)
    this.f,                  // encoder input Surface
    0                        // flags = 0 — NOT VIRTUAL_DISPLAY_FLAG_PUBLIC, NOT _PRESENTATION, NOT _AUTO_MIRROR
);
```

- **DPI / density**: `320` (literal `q = 320`, line 38).
- **VirtualDisplay flags**: `0` — private, non-mirroring, non-presentation.
- **Surface source**: This is **NOT** `MediaProjection.createVirtualDisplay()`. It's `DisplayManager.createVirtualDisplay()` with a **private** virtual display. The OEM never captures the user's actual phone screen.

## 7. Dynamic adaptation logic (if any)

**None.** Verified:
- No `MediaCodec.setParameters(...)` calls in any projection package.
- No `PARAMETER_KEY_VIDEO_BITRATE` / `"video-bitrate"` strings.
- No `PARAMETER_KEY_REQUEST_SYNC_FRAME` / `"request-sync"` strings.
- No packet-loss / heartbeat-RTT feedback loop into the encoder.

RAM-tier adaptation at *initialization* only.

## 8. 15457 heartbeat semantics

**Active production code** (`com/whbluestar/thinkride/ft/process/wifi/projection/heart/ProjectionHeartWriter.java:33-43`):

```java
this.f = new Timer();
this.g = new TimerTask() { public void run() { d.sendEmptyMessage(1); } };
this.f.schedule(timerTask, 10L, 450L);   // initial delay 10ms, period 450ms
```

Each tick calls `ProjectionHeartMsgFactory.sendHeart()` which returns `new WifiMessageHead((byte)2, (byte)1).getHeadBytes()` (`com/whbluestar/thinkride/ft/process/wifi/projection/heart/msg/ProjectionHeartMsgFactory.java:7-9`).

`WifiMessageHead.getHeadBytes()` (`com/whbluestar/thinkride/ft/ota/WifiMessageHead.java:15-21`):
```java
byte[] bArr = new byte[6];
bArr[0] = a;     // 0x02
bArr[1] = b;     // 0x01
ByteUtils.int2Bytes(0, bArr, 2);   // 4-byte payload-length = 0
```

6-byte heartbeat: **`02 01 00 00 00 00`** every 450 ms phone→dash on port 15457.

No bitrate/fps signaling embedded. The dash never receives configuration from the heartbeat.

## 9. Backpressure / drop policy

- **Send-side queue**: `ProjectionDataSendThread` — Unbounded `ArrayDeque`, no cap, no drop policy. Worker spins with `Thread.sleep(1L)`. Looks like a side-channel for record-and-replay; main video stream sends synchronously inside the encoder loop via `writeFully`.
- **Encoder back-pressure**: `dequeueOutputBuffer(bufferInfo, -1L)` (wait forever). When TCP send buffer fills, encoder blocks on socket; that back-pressures through `Surface` → stalls the VirtualDisplay. Classic surface-mode Android back-pressure. **No explicit drop.**
- **Suspect "near-end zero-byte" detector**: `ProjectionEncoder.a(byte[])` (`:147-158`) — counts zero bytes in last 100 of any output buffer; if >80 of 100 trailing bytes are zero, returns true. Safety check for malformed/stuck encoder output.
- **IDR detection helper**: `printH264NaluTypes(byte[])` (`:123-131`) scans Annex-B start codes — purely for logging.
- **No drop-non-IDR window. No re-request flow. No IDR-on-loss.**

## 10. Profile / level / color format

- **Profile**: `AVCProfileHigh = 8` *if supported* (`com/whbluestar/thinkride/ft/projection/ProjectionEncoder.java:93-97` + `CodecSupportChecker.isAVCHighProfileSupported()` at `:17-34`). Falls through to encoder default (Baseline) if not. cn_thinkerride and green_trip do not set profile/level.
- **Level**: `AVCLevel41 = 4096` (paired with High).
- **Color format**: `COLOR_FormatSurface = 2130708361` in all variants.

## 11. SPS/PPS prepend logic

**No explicit SPS/PPS prepend.** No code reads `MediaFormat.getByteBuffer("csd-0")` / `"csd-1"`. No `addSpsPps()` analog.

The encoder is in **Surface input mode**, so SPS/PPS NALUs are emitted by MediaCodec as the *very first output buffer*, flagged with `BUFFER_FLAG_CODEC_CONFIG (0x2)`. The `encode()` loop does not filter that flag out (compare to `ImageToVideoConverter.java:184-185` which explicitly ignores `flags & 2`). So SPS+PPS travel **inline** with the rest of the Annex-B stream as the first byte buffer the dash receives after the 69-byte handshake.

**Critical for reimplementation: send the very first MediaCodec output buffer (the one with `BUFFER_FLAG_CODEC_CONFIG`) over the wire verbatim**, do NOT skip it.

## 12. Long-press UP trigger — code or firmware?

**100% firmware-side.** Zero references to "long press / long-press / 长按" in projection or Wi-Fi packages. The phone app:

1. Binds ServerSocket on 15456 after Wi-Fi pairing.
2. Sits idle in `accept()`.
3. When rider long-presses UP, **dash firmware** opens TCP connection to 15456.
4. Phone's `accept()` returns → `onSocketConnect(15456)` → `ProjectionWrapper.setConnected(true)` → `ProjectionConnectionChangeEvent(1)` → `ProjectionService.lambda$onCreate$1` message 1 fires → creates encoder, starts StreamThread, calls `sendVideoSize()` then `encode()` loop.

There is no phone-side hand-off message. The connect event *is* the trigger.

## 13. Cross-app validation

| Aspect | ThinkerRide (our dash) | green_trip | cn_thinkerride |
|---|---|---|---|
| Encoder class | `com.whbluestar...ProjectionEncoder` | `defpackage.z64` | `defpackage.st1` |
| Bitrate formula | `r * W * H` (RAM-adaptive) | `W * H` (r=1) | `3 * W * H` (r=3) |
| FPS | 30 | 30 | 30 |
| GOP (i-frame-interval) | 1 s | 1 s | 1 s |
| Bitrate mode | CBR (1) | (default = VBR) | CBR (1) |
| Profile/Level | High/4.1 if supported | (default) | (default) |
| `repeat-previous-frame-after` | 100 ms | 100 ms | 100 ms |
| Heartbeat period | 450 ms | (same wrapper) | 450 ms |
| 69-byte handshake | identical | identical | identical |
| Color format | `2130708361` | `2130708361` | `2130708361` |
| VirtualDisplay density | 320 dpi | 300 dpi | 320 dpi |
| Surface flags | 0 | 0 | 0 |

All three converge on: H.264 Annex-B, 30 fps, 1 s GOP, surface-mode encoder, 100 ms frame-repeat.

## 14. Recommended settings for our Kotlin app (concrete numbers)

```kotlin
val WIDTH = 1280
val HEIGHT = 640
val FPS = 30
val IFRAME_INTERVAL_SEC = 1
val BITRATE = 3 * WIDTH * HEIGHT          // 2,457,600 bps (~2.4 Mbps). ThinkerRide r=3 tier.
val DENSITY_DPI = 320
val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
    setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC)
    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)  // 100 ms
    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
    if (Build.VERSION.SDK_INT >= 29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FPS.toFloat())
}

val codec = MediaCodec.createEncoderByType(MIME)
codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
val inputSurface: Surface = codec.createInputSurface()
codec.start()

val vd = displayManager.createVirtualDisplay(
    "projection", WIDTH, HEIGHT, DENSITY_DPI, inputSurface, /*flags=*/ 0
)
```

**Wire protocol order on 15456**:
1. After `accept()`, write the 69-byte handshake exactly.
2. Pump every MediaCodec output buffer raw (including the very first one with `BUFFER_FLAG_CODEC_CONFIG` — SPS/PPS).
3. **Do NOT filter `BUFFER_FLAG_CODEC_CONFIG`.** MediaCodec already produces Annex-B with `00 00 00 01` prefixes.
4. Send every ~450 ms on 15457: `byte[] {0x02, 0x01, 0x00, 0x00, 0x00, 0x00}`. Single-thread `Timer`. Stop on socket error.

**Conservative fallback** if 2.4 Mbps proves too aggressive: drop `r` to 2 → 1.6 Mbps. Matches ThinkerRide 6-8 GB RAM tier.

## 15. Gaps / could-not-determine

1. **Body of `ProjectionEncoder.encode()`** — JADX failed. The legacy and green_trip versions decompiled cleanly and show identical structure (`dequeueOutputBuffer(-1) → writeFully → releaseOutputBuffer`). Live ThinkerRide version highly likely the same plus the zero-tail filter and IDR logging. Close gap with `jadx --show-bad-code` or smali.
2. **`BUFFER_FLAG_CODEC_CONFIG` pass-through** — inferred from absence of `flags & 2` check in clean decompiles + absence of any SPS-PPS-rebuild logic. If reimpl fails at the dash because of no SPS/PPS, fallback: cache `format.getByteBuffer("csd-0")` and `"csd-1"` from `INFO_OUTPUT_FORMAT_CHANGED` and prepend before first IDR.
3. **Dash 15456 read direction** — `ProjectionNaviReader.readNull()` reads 6 bytes per 500 ms forever and discards. `dealMessage(Message)` is empty. Safe to assume 15456 is phone→dash only after the handshake.
