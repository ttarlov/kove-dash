# Contributing

## What needs hardware and what doesn't

The app was reverse-engineered against one dash (Kove 450 Rally, firmware `SV=3.0.4`, SiQi/ThinkerRide protocol). You don't need that hardware for most contributions:

| Needs the dash | Bench-only |
|---|---|
| Final projection round-trip | UI / Compose work |
| Live BLE handshake timing | Mapbox geocoding and routing |
| Telemetry capture | Protocol encode/decode (unit-tested) |
| Eryanet wire validation | Encoder pipeline (emulator) |
| Field nav testing | Docs, protocol analysis |

## Setup

1. JDK 17 and Android Studio (or `android-commandlinetools`).
2. Mapbox tokens (free tier): a `pk.*` public token and an `sk.*` downloads token with `DOWNLOADS:READ` scope, from https://account.mapbox.com/.
3. Copy the template and fill it in:
   ```bash
   cp app/local.properties.template app/local.properties
   ```
4. Build and run the tests — no hardware required:
   ```bash
   cd app
   ./gradlew :app:testDebugUnitTest
   ```

`local.properties` is gitignored. Never commit tokens, your dash SSID, or BLE MAC addresses. The proto-poc scripts use placeholder identifiers (`CQKY_XXXXXXXXX`, `XX:XX:XX:XX:XX:XX`); set yours locally and don't commit them back.

## Testing without a bike

- Protocol logic is covered by unit tests in `app/app/src/test/`. Add tests there for any encode/decode change (`ByteCatTest.kt` and `DashJsonEnvelopeTest.kt` show the pattern).
- The encoder pipeline can be smoke-tested on an emulator with the static clock Composable (`SmokeClockFace.kt`) before involving Mapbox. This separates encoder bugs from SDK bugs.
- The Python code in `proto-poc/` mirrors the wire protocol and is the reference when the Kotlin and the docs disagree.

## Branching and commits

Trunk-based; `main` stays green. Land routine work on `main` via PR, and branch only for risky experiments (`experiment/<slug>`).

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat`, `fix`, `docs`, `refactor`, `test`, `chore`; scopes `app`, `proto`, `re`):

```
feat(app): TTS voice prompts on maneuver advance
docs(re): document Eryanet aaa6 BLE framing
```

## Encoding demo clips

The static-clip projection path streams a pre-encoded H.264 file at the dash's native 1280×640:

```bash
ffmpeg -y -i INPUT \
  -vf "scale=1280:640:force_original_aspect_ratio=increase,crop=1280:640,fps=30" \
  -c:v libx264 -profile:v baseline -level 3.1 \
  -preset ultrafast -tune zerolatency \
  -pix_fmt yuv420p -bf 0 \
  -g 1 -keyint_min 1 \
  -x264-params "annexb=1:repeat-headers=1" \
  -f h264 app/app/src/main/assets/motion.h264
```

Any video or GIF works as `INPUT`. Annex-B framing with repeated headers is required; the dash expects SPS/PPS before each IDR. See `docs/ARCHITECTURE.md` and `proto-poc/PROTOCOL.md`.

## Android Studio

Open `app/` (not the repo root) as the project. Compose previews render normally; filter Logcat by `package:com.kovedash.app`.

## Pull requests

- One logical change per PR.
- If you touched protocol code, say how you validated it: unit test, emulator, or real hardware (and which firmware).
- Update the relevant doc (`proto-poc/PROTOCOL.md`, `docs/ARCHITECTURE.md`) when behavior changes.
- Contributions are licensed under Apache-2.0 (LICENSE §5).

## Reverse-engineering ground rules

- Do not commit decompiled OEM source, APKs, or smali. They're gitignored deliberately. Reports may describe and cite observed behavior, but don't paste proprietary code.
- Document findings as protocol behavior — bytes on the wire — not as copied implementation.
- Regenerate any decompilation locally from binaries you lawfully obtained.

## Hardware reports

If you have a Kove dash, even a quick report helps build the compatibility map:

- Firmware string (Settings/About on the dash)
- BLE scan output (service `0000e0ff-...` = SiQi, `0000aaa0-...` = Eryanet)
- Dash AP address (`192.168.10.1` = SiQi-era, `192.168.43.1` = Eryanet-era)

Use the "hardware report" issue template.

## Questions

Open an issue or a GitHub Discussion. This is a spare-time project, so expect some latency.
