# Architecture

How the app is put together. For the wire protocol itself (bytes on the link), see [`../proto-poc/PROTOCOL.md`](../proto-poc/PROTOCOL.md).

## Overview

The dash is a Wi-Fi access point, but the phone is the TCP server: the dash dials in as a client. The phone also holds the BLE client connection, which carries control and the native data push (turn-by-turn, weather, elevation, clock sync). Everything is coordinated by a single foreground service so the link survives screen-off and app-backgrounding.

```
                       ┌──────────────────── DashService (foreground) ────────────────────┐
                       │                                                                   │
   UI (Compose)        │   DashWifi ── auto-join dash AP (WifiNetworkSpecifier)            │
   ConnectScreen  ◄────┤   DashBleClient ── GATT: ffe1 write / ffe2 notify / ffe3          │
   MapTab / Diagnostics│   DashTcpServer ── listens 17818 / 15457 / 18888 / 19000          │
        ▲              │   ProjectionSession / LiveProjectionSession ── 15456 video        │
        │              │   Link supervisor ── watches BLE + Wi-Fi, exponential reconnect   │
   AppHost (state) ◄───┤                                                                   │
        │              └───────────────────────────────────────────────────────────────────┘
        │
   GpsSource, Navigator, Mapbox{Geocoder,Directions}, OpenMeteo
```

## Layers & key files

All under `app/app/src/main/java/com/kovedash/app/`.

### Entry & state
| File | Role |
|---|---|
| `MainActivity.kt` | Activity, runtime permissions, MediaProjection consent launcher, search overlay host |
| `AppHost.kt` | Singleton bridge between UI and service; owns app-wide state (GPS, settings, link state) as StateFlows |
| `service/DashService.kt` | Foreground service — owns the entire BLE/TCP/projection lifecycle + the link supervisor |
| `service/DashState.kt` | The connection state machine / phase enum |
| `service/KoveSettings.kt` | Persisted dash AP creds + prefs (SharedPreferences) |

### Networking & link (`net/`)
| File | Role |
|---|---|
| `DashWifi.kt` | `NetworkRequest` + `WifiNetworkSpecifier` auto-join; `bound`/`unavailable` StateFlows |
| `DashBleClient.kt` | GATT client; MTU 247 negotiation; `WRITE_TYPE_DEFAULT` with retry; per-packet seq counter; adapter-state receiver |
| `DashTcpServer.kt` | Listeners on 17818 (control), 15457 (heartbeat), 18888, 19000; 450 ms projection heartbeat |
| `ByteCat.kt` | The BLE frame checksum/encoding (`byteCat` / nibble-CRC). Unit-tested. |
| `DashJsonEnvelope.kt` | `0xEE 0xFD <len> <json> 0xFF` envelope encode/decode. Unit-tested. |
| `MapboxGeocoder.kt` | SearchBox suggest/retrieve (POI autocomplete) |
| `MapboxDirections.kt` | Route geometry + `steps=true` maneuver parsing |
| `GpsSource.kt` | Fused location → GPS StateFlow |
| `OpenMeteo.kt` | No-API-key weather client (for the dash weather push) |

### Protocol (`proto/`)
| File | Role |
|---|---|
| `DashMessages.kt` | Builders for the dash message catalog (msg_id 11 time, 25 altitude/weather, 27 queries, …) |
| `TelemetryProbe.kt` | Debug diagnostics probe: pokes the dash and records what comes back (mostly "no response" on this firmware) for the connection panel. Not a live vehicle-telemetry feed — the dash doesn't expose one to the phone here. |

### Projection (`project/`)
The rendering approach mirrors both OEM apps: the dash UI is rendered into a secondary `Presentation` display backed by a `VirtualDisplay`, which feeds a `MediaCodec` H.264 encoder through an input `Surface`; the Annex-B output goes down the 15456 socket. No bitmap copies, GPU end-to-end.

| File | Role |
|---|---|
| `LiveProjectionSession.kt` | The live path: `MediaProjection`/`VirtualDisplay` → `MediaCodec` → 15456. **Uses `MediaProjection.createVirtualDisplay()`** so the display survives screen-off (a `DisplayManager` VD dies when the panel blanks). |
| `ProjectionSession.kt` | The static-clip demo path (pre-encoded `.h264` asset) |
| `AnnexBParser.kt` | NALU framing / SPS-PPS handling |
| `DemoFrameSource.kt` | Pre-encoded frame feeder for the demo |

### UI (`ui/`)
- `ui/dash/` — what renders **on the dash**: `DashPresentation.kt` (the secondary-display root), `NavMap.kt` (Mapbox map + camera follow), `ManeuverBanner.kt`, `SmokeClockFace.kt` (encoder smoke-test face).
- `ui/` — phone-side screens: `ConnectScreen.kt`, `TelemetryPanel.kt`, `DestinationBar.kt` (+ fullscreen search), `SettingsScreen.kt`, `PasswordDialog.kt`.
- `ui/components/` — the Paris-Dakar arcade aesthetic widget kit.
- `ui/theme/` — colors, fonts, theme.

### Navigation (`nav/`)
| File | Role |
|---|---|
| `Navigator.kt` | Holds active route + `RouteProgress` StateFlow; advances maneuvers on GPS fixes (30 m radius); off-route detection (50 m corridor, 3 fixes) + auto-reroute with cooldown |

## Invariants

Things that took real debugging time to establish. Don't change them casually:

1. The BLE seq counter starts at 0, not 1. The dash tracks a contiguous-from-0 packet sequence and issues resend requests against it. Starting at 1 wedges the entire dash-to-phone setter channel; getting this right is what made time sync and the setter path work at all.
2. MTU 247 and `WRITE_TYPE_DEFAULT`. Negotiate MTU on services-discovered and gate writes on `onCharacteristicWrite`. The default 23-byte MTU silently truncates the 104-byte frames, and `NO_RESPONSE` writes drop under load.
3. Projection uses `MediaProjection`, not `DisplayManager`. Only `MediaProjection.createVirtualDisplay()` keeps rendering when the phone screen is off. This requires the `mediaProjection` foreground-service type and a per-session consent dialog (Android 14+ doesn't allow skipping re-consent).
4. The 15457 heartbeat must go out every ~450 ms or the dash drops the projection session.
5. The dash drives the handshake. It pushes `msg_id=10` frames and the phone reacts (`item=4` → echo time-sync, `item=6` → version burst). Don't treat the phone as the initiator.
6. The encoder settings are conservative on purpose: 30 fps, ~`3×W×H` bitrate, 1 s I-frame interval, `KEY_REPEAT_PREVIOUS_FRAME_AFTER`. Raising fps or bitrate makes things worse — the dash decoder is the bottleneck. Settings of record are in `proto-poc/PROTOCOL.md` and the projection-encoder report.

## Two protocol families

This app targets the SiQi/ThinkerRide family (the 2022 `SV=3.0.4` dash it was developed against). Newer dashes speak Eryanet, which is wire-incompatible: different envelope, different UUIDs, different port roles, and the phone is the client. The architectural pattern is the same, the bytes are not. The known wire deltas are tabulated in `proto-poc/PROTOCOL.md`; porting is the biggest open piece of work.

## Testing without hardware

- Unit tests: `app/app/src/test/` (`ByteCatTest`, `DashJsonEnvelopeTest`) — `./gradlew :app:testDebugUnitTest`.
- Encoder smoke test: drive `SmokeClockFace` through the projection pipeline on an emulator before adding Mapbox, to isolate encoder bugs from SDK bugs.
- Python `proto-poc/` is the executable spec when Kotlin and the docs disagree.
