# KoveDash

Android app that streams live navigation to the stock TFT dash on a Kove 450 Rally, using the dash's own Wi-Fi and BLE link. No OEM app, no cloud account, no activation server. The repository also contains full documentation of the reverse-engineered wire protocol, which is probably the most useful part if you don't own this exact bike.

Status: experimental. Developed and tested against a single 2022 dash running firmware `SV=3.0.4`. It works on my bike. Yours may differ — see [Compatibility](#compatibility).

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

## What it does

- One-tap connect: joins the dash's Wi-Fi AP, runs the OEM BLE handshake, starts the TCP listeners the dash expects. Survives a key-off/key-on cycle without intervention.
- Live navigation on the dash: phone GPS → Mapbox route → rendered to a secondary `Presentation` display → H.264 → streamed over TCP. Maneuver banner, off-route detection, automatic rerouting.
- Destination search with autocomplete (Mapbox SearchBox).
- Sets the dash clock from the phone.

The dash also broadcasts telemetry (speed, odometer, tire pressure, fuel level, range) over BLE. The frames are documented in the protocol notes; decoding and displaying them in the app is on the [roadmap](docs/ROADMAP.md).

A Python proof of concept in `proto-poc/` predates the app. It remains the reference implementation for the wire protocol and is handy for bench testing.

## Compatibility

There are two incompatible protocol families in the Kove dash ecosystem:

- SiQi/ThinkerRide — older dashes, including the 2022 `SV=3.0.4` unit this was built against. This is what the app speaks.
- Eryanet — newer dashes. Different envelope, different BLE UUIDs, reversed TCP roles. Not supported yet, though the wire format is partially documented in [`proto-poc/PROTOCOL.md`](proto-poc/PROTOCOL.md).

If you don't have the older-family hardware, the app won't talk to your dash end-to-end. Porting to Eryanet is the biggest open piece of work.

If you have any Kove dash, a hardware report helps regardless of firmware: the firmware string, a BLE scan (service `0000e0ff-...` means SiQi, `0000aaa0-...` means Eryanet), and the dash AP address. There's an [issue template](.github/ISSUE_TEMPLATE/) for it.

## How it works

```
  Phone (this app)                                  Kove dash (Wi-Fi AP @ 192.168.10.1)
  ┌───────────────────────────┐                    ┌──────────────────────────────┐
  │ Mapbox nav Composable     │                    │                              │
  │   → Presentation display  │                    │                              │
  │   → VirtualDisplay        │                    │   H.264 decoder → TFT panel  │
  │   → MediaCodec (H.264)    │ ── TCP 15456 ────► │                              │
  │                           │ ── TCP 15457 ◄───► │   heartbeat                  │
  │ TCP servers (phone=server)│ ◄── 17818 ───────  │   device control / telemetry │
  │ BLE client (ffe1/ffe2)    │ ◄── BLE ─────────► │   telemetry + time sync      │
  └───────────────────────────┘                    └──────────────────────────────┘
```

The phone is the TCP server; the dash dials in as a client. The one step that isn't obvious from the protocol: the rider has to long-press UP on the dash itself to put it into projection mode, which is what makes it connect to the phone's video socket. That's in the Kove owner's manual but not in any of the companion apps. Details in [`proto-poc/PROTOCOL.md`](proto-poc/PROTOCOL.md).

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

See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [roadmap](docs/ROADMAP.md). The most useful things right now are Eryanet protocol support, hardware reports from other firmware revisions, and a handful of bench-doable issues (telemetry UI, camera bounds fit, voice prompts).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
