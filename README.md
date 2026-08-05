# KoveDash

Android app that pushes navigation and ambient data to the stock TFT dash on a Kove 450 Rally, using the dash's own BLE and Wi-Fi links. No OEM app, no cloud account, no activation server.

There are two ways it drives the dash:

- **Native data over BLE (default, low power).** The app sends small JSON frames and the dash renders them itself — a turn-by-turn arrow, weather, elevation, and clock sync — with no video pipeline running. This is the primary mode and it's cheap on battery.
- **Full-map video over Wi-Fi (optional, higher power).** On demand, the app renders a Mapbox map to a virtual display, encodes it to H.264, and streams it to the dash as a full-screen picture.

The repository also contains a full write-up of the reverse-engineered wire protocol, which is probably the most useful part if you don't own this exact bike.

**Status: experimental, single-bike project.** Developed and tested against one 2022 dash running firmware `SV=3.0.4`. It works on my bike. Yours may differ — see [Compatibility](#compatibility). Some paths (turn-advance at highway speed, GPX follow while moving) are validated on the bench or stationary but not yet on a full moving ride; those are called out below and in the [roadmap](docs/ROADMAP.md).

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

## What it does

**Native, over BLE (no video, low power):**
- **Turn-by-turn on the dash's own nav page.** You navigate in Google Maps as usual; the app reads Maps' ongoing navigation notification and relays each maneuver to the dash over BLE, which draws the arrow, road name, and distance natively. (Gadgetbridge-style relay — Maps does the routing; the app is a thin bridge. Requires granting Notification access.)
- **Weather** near the dash clock (from the phone's location).
- **Elevation** in the trip field.
- **Clock sync** during the connection handshake.

**Optional, over Wi-Fi (video projection):**
- **Full Mapbox map** rendered on the phone and streamed to the dash as H.264. Includes destination search with autocomplete and Mapbox routing. Turn-advance / off-route / reroute logic is implemented and bench-validated; on-road behavior at speed is still being confirmed.
- **GPX course loading and following** — load a `.gpx` track, see it drawn on the map, and follow it with distance-remaining / off-course readouts. Validated stationary; the moving "progress ticking down" path is not yet road-tested.


<img width="4080" height="3072" alt="PXL_20260805_160031324" src="https://github.com/user-attachments/assets/288146c8-c289-4032-b05f-5fcb7af6dc7d" />
<img width="4080" height="3072" alt="PXL_20260805_160013337" src="https://github.com/user-attachments/assets/887909aa-7555-4045-a823-7939583a2256" />
<img width="4080" height="3072" alt="PXL_20260805_155954768" src="https://github.com/user-attachments/assets/07f63268-71fa-4918-9f60-9f0b33e3ca28" />


**Connection model:**
- Connect brings Wi-Fi up once to activate the dash's native rendering (a control channel that must come up once per power-cycle), then **parks Wi-Fi and runs BLE-only** for the low-power steady state. A single **Project** toggle brings Wi-Fi back up and starts video on demand, and drops it again when you turn projection off.
- Auto-reconnects after a key-off/key-on cycle. (Reconnect after a BLE drop can currently take a couple of minutes — tightening that is on the roadmap.)

A Python proof of concept in `proto-poc/` predates the app. It remains the reference implementation for the wire protocol and is handy for bench testing.

**Not supported / known non-features** (documented so you don't chase them): live vehicle telemetry from the dash (speed, odometer, fuel, range) is **not** read or displayed — the dash doesn't expose usable telemetry to the phone on this firmware. Tire-pressure (TPMS) display is gated behind a per-VIN cloud capability flag and does not work without it, even though the push protocol is understood (see [roadmap](docs/ROADMAP.md)). Music and call/notification mirroring get no response from this dash.

## Compatibility

There are two incompatible protocol families in the Kove dash ecosystem:

- **SiQi/ThinkerRide** — older dashes, including the 2022 `SV=3.0.4` unit this was built against. This is what the app speaks.
- **Eryanet** — newer dashes. Different envelope, different BLE UUIDs, reversed TCP roles. Not supported yet, though the wire format is partially documented in [`proto-poc/PROTOCOL.md`](proto-poc/PROTOCOL.md).

If you don't have the older-family hardware, the app won't talk to your dash end-to-end. Porting to Eryanet is the biggest open piece of work.

If you have any Kove dash, a hardware report helps regardless of firmware: the firmware string, a BLE scan (service `0000e0ff-...` means SiQi, `0000aaa0-...` means Eryanet), and the dash AP address. There's an [issue template](.github/ISSUE_TEMPLATE/) for it.

## How it works

```
  Phone (this app)                              Kove dash (Wi-Fi AP @ 192.168.10.1)
  ┌────────────────────────────┐               ┌───────────────────────────────────┐
  │ BLE client (ffe1/ffe2)     │ ◄── BLE ────► │ native widgets: turn arrow,        │
  │                            │               │ weather, elevation, clock sync     │
  │                            │ ── TCP 17818 ─►│ control channel (activates native  │
  │ TCP servers (phone=server) │               │ rendering once per power-cycle)    │
  │                            │               │                                   │
  │ optional video path:       │               │                                   │
  │  Mapbox map → Presentation │               │                                   │
  │   → VirtualDisplay         │ ── TCP 15456 ─►│ H.264 decoder → full-screen map    │
  │   → MediaCodec (H.264)     │ ◄─ TCP 15457 ─►│ heartbeat                          │
  └────────────────────────────┘               └───────────────────────────────────┘
```

The default steady state uses **only the BLE link** — the dash renders the widgets itself. The Wi-Fi/TCP path is brought up only for video projection. The phone is the TCP server; the dash dials in as a client.

One step isn't obvious from the protocol: to start video projection, the rider long-presses **UP** on the dash to put it into projection mode, which is what makes it connect to the phone's video socket. That's in the Kove owner's manual but not in any of the companion apps. Details in [`proto-poc/PROTOCOL.md`](proto-poc/PROTOCOL.md).

## Building

You need JDK 17, Android Studio or the command-line tools, and Mapbox tokens (the free tier is more than enough for personal use).

```bash
# 1. Create tokens at https://account.mapbox.com/ :
#    a public token (pk.*) and a downloads token (sk.*) with DOWNLOADS:READ scope.
# 2. Configure:
cp app/local.properties.template app/local.properties
#    edit app/local.properties: both tokens + your SDK path.

# 3. Build and install:
cd app
./gradlew :app:installDebug

# 4. Unit tests (no hardware needed):
./gradlew :app:testDebugUnitTest
```

Most of the app can be developed and tested without the bike — UI, geocoding, routing, protocol encode/decode, and the encoder pipeline all run on an emulator. Hardware is only needed for the final dash round-trip. See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Repository layout

| Path | Contents |
|---|---|
| `app/` | The Android app (Kotlin, Jetpack Compose) |
| `proto-poc/` | Python proof of concept and bench tools; protocol reference implementation |
| `proto-poc/PROTOCOL.md` | The reverse-engineered wire protocol, in full |
| `docs/ARCHITECTURE.md` | How the app is structured |
| `docs/ROADMAP.md` | Planned work and open questions |
| `docs/re/` | Reverse-engineering reports (ThinkerRide, GreenTrip, Eryanet, projection encoder) |

## Safety and legal

This software drives a screen on a moving motorcycle. A frozen, wrong, or distracting display can contribute to a crash. Use it at your own risk, don't rely on it as your only navigation, and keep your eyes on the road. No warranty — see [LICENSE](LICENSE) §7–8.

This is an independent interoperability project, not affiliated with or endorsed by Kove, SiQi, BlueStar, or Eryanet. The protocol was documented by observing traffic and analyzing publicly distributed companion apps, to interoperate with hardware I own. No proprietary or decompiled OEM code is included or redistributed — see [NOTICE](NOTICE).

I have not damaged a dash doing any of this, but sending unexpected input to embedded firmware always carries some risk. That risk is yours.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [roadmap](docs/ROADMAP.md). The most useful things right now are Eryanet protocol support, hardware reports from other firmware revisions, faster BLE reconnect, and confirming the on-road nav paths (turn-advance at speed, GPX follow while moving).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
