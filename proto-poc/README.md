# proto-poc — v0a Python proof of dash protocol

Minimal Python implementation of the dash WiFi projection protocol, derived from the decompiled ThinkerRide APK. No Android Studio needed.

## What's here

| File | Purpose |
|---|---|
| `send_frame.py` | Client. Connects to dash:15456, sends the 69-byte handshake, then a raw H.264 NALU file. |
| `stub_dash.py` | Listener that pretends to be the dash for bench testing. Parses the handshake and logs NALUs. |
| `assets/test_frame.h264` | One H.264 I-frame (SMPTE-like test pattern, 1280×640) for visual verification on the real dash. |

## Bench test (already validated, no bike needed)

```bash
# Terminal 1
python3 stub_dash.py --port 15456 --once

# Terminal 2
python3 send_frame.py --dash 127.0.0.1 --port 15456 --frame assets/test_frame.h264
```

Stub should print handshake decoded with `platform='android'`, `width=1280`, `height=640`, then list NALU types (AUD/SPS/PPS/SEI/IDR).

## Real-dash test (THE moment of truth)

1. Turn the bike ignition on so the dash powers up
2. On the Mac:
   - Wi-Fi: join `CQKY_XXXXXXXXX` / `12345678`
   - Internet: keep the Pixel USB tether plugged in (preserves internet without losing Wi-Fi to dash)
3. Verify dash is reachable: `ping -c 2 192.168.10.1`
4. Send the frame:
   ```bash
   python3 send_frame.py --dash 192.168.10.1 --frame assets/test_frame.h264 --repeat 5 --hold 3
   ```

**Expected outcome:** Test pattern (color bars) appears on the dash screen for a few seconds.

**Failure modes & what they tell us:**
- Connection refused → port 15456 isn't open. Dash may need a different bootstrap (e.g., BT side-channel ping first) or it only listens after the OEM app pokes it. We'd capture OEM-app traffic next.
- Connect succeeds, dash stays unchanged → handshake or codec params wrong. Capture OEM bytes and diff against ours.
- Connect succeeds, dash shows garbled output → resolution/aspect mismatch. Try 800×640 (ping-pong), 1280×720, etc.
- Pattern appears → 🎉 v0a complete. Move to v0.5.

## Protocol reference (from APK decompilation)

- **TCP** to `dash_ip:15456` (heartbeat on 15457, not needed for v0)
- **69-byte handshake:**
  - byte 0: flag (currently `0x00` — unknown, refine via capture if dash rejects)
  - bytes 1–64: platform UTF-8, zero-padded ("android")
  - bytes 65–66: width, big-endian uint16
  - bytes 67–68: height, big-endian uint16
- **Then:** raw H.264 NALU bytes, Annex-B framed (`00 00 00 01` start codes)
- Codec: H.264 AVC High profile, yuv420p, 30 fps, 1s I-frame interval
- No encryption, no auth token, no JNI

## Generating a fresh test frame

```bash
ffmpeg -f lavfi -i "testsrc2=s=1280x640:r=1:d=1" \
  -c:v libx264 -frames:v 1 -profile:v high -pix_fmt yuv420p \
  -bsf:v h264_metadata=aud=insert -f h264 assets/test_frame.h264
```

Change `testsrc2` to `color=c=red:s=1280x640:d=1` for a solid red frame, etc.
