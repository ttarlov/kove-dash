# Roadmap

Planned work and open questions. For anything sizable, open an issue first so effort doesn't get duplicated.

## Good first issues (no hardware needed)

- Show inbound telemetry in the Telemetry tab. The dash emits `msg_id=10 item=1/2/3` frames (speed `current/max/average`, odometer `total/subtotal`, and `tire_pressure`/`remaining_oil`/`endurance`). The wire format is documented in `proto-poc/PROTOCOL.md`; a decoder for these frames needs to be written (`proto/` is the natural home) and the values surfaced as live rows in `TelemetryPanel.kt`.
- Camera bounds fit on route set. When a destination is picked, fit the route bounds briefly before resuming GPS follow. Today the polyline runs off-screen until the rider recenters manually. (`NavMap.kt`, `Navigator.kt`)
- Hide or label the handshake progress pill. The header shows an unlabeled `00/06`; either hide it until it means something or label it.

## Features

- Voice prompts. Feed `Navigator.progress` into `TextToSpeech` ("In 500 feet, turn left onto Main Street"). The maneuver data is already there; needs a TTS engine plus a debounce so a step isn't spoken twice.
- Mirror mode: phone screen to dash via `MediaProjection`, as an alternative to the dedicated nav Composable. Parked for now; the projection plumbing is reusable.

## Protocol work (needs the dash, or protocol interest)

- Re-verify `msg_id=27` query replies (firmware version, NAVI status, GPS signal, theme). These returned null before the seq-counter fix and haven't been retried since. If they respond now, changing dash settings from the phone becomes possible.
- Decode the unsolicited `msg_id=25 msg_type=17` push. The dash periodically sends a frame carrying `altitude=17` (a sentinel value). Capturing a full payload would show what else rides in it — GPS fix state, satellite count, position?
- Per-packet resend. The dash can request retransmission (`item=7`, `item=9`). The app currently logs these requests but doesn't honor them; a small buffer of recently sent packets would fix that.

## Larger projects

- Eryanet protocol support (newer Kove dashes). Newer firmware speaks a wire-incompatible protocol family: different envelope, different BLE UUIDs, and the phone is the TCP client rather than the server. The architectural pattern is the same. The known wire deltas are tabulated in `proto-poc/PROTOCOL.md`. The only hard requirement is owning a newer dash.

## Known limitations

These are investigated dead ends, not open bugs:

- The Elevation widget can't be driven from the phone on `SV=3.0.4`. The dash broadcasts a constant sentinel (`altitude=17`) and no phone-pushed value renders in the Elevation menu. See `docs/re/altitude_investigation.md`. Newer firmware may behave differently.
- The 12h/24h clock format is firmware-locked. Time sync itself works (the dash clock follows the phone), but the display format isn't toggleable over the wire on this firmware.

## Needs field verification

The current nav stack is bench-validated; the on-bike pass is still outstanding: projection surviving screen-off, the maneuver banner advancing through real turns, off-route detection firing within a few seconds of a missed turn, and POI search returning sensible destinations. Reports from real rides on any Kove dash are welcome.
