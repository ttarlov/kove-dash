# Roadmap

Planned work and open questions. For anything sizable, open an issue first so effort doesn't get duplicated.

## Good first issues (no hardware needed)

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

- Live vehicle telemetry from the dash (speed, odometer, fuel, range) is not available to the phone on `SV=3.0.4`. The dash's outbound BLE traffic on this firmware is its own housekeeping chatter — a hardcoded `altitude=17` sentinel and packet-resend solicits — not a readable telemetry stream. Nothing here decodes or displays dash telemetry, and there's no evidence the values are exposed to pull.
- Tire pressure (TPMS) is gated behind a per-VIN cloud capability flag (`is_tire_pressure`) that the OEM app fetches over HTTP. Until the dash believes it's TPMS-capable, it stays completely silent to `func:"TIRE"` pushes — never renders, never polls. The push protocol is fully reverse-engineered and a POC exists (`feature/tpms-poc` in the project's history), so this becomes a pure sensor-decode task *if* the flag can be flipped — via a dash settings toggle, if one exists, or by intercepting the cloud response. It is a capability gate, not a wire bug.
- The 12h/24h clock format is firmware-locked. Time sync itself works (the dash clock follows the phone during the handshake), but the display format isn't toggleable over the wire on this firmware.

## Needs field verification

Video projection has been validated on a real ride (it survives screen-off via a keep-display-awake trick, confirmed over 20k+ continuous frames). Still outstanding on the road: the maneuver banner advancing through real turns, off-route detection firing within a few seconds of a missed turn, GPX course-follow while actually moving, and confirming the pushed distance/ETA/progress fields render in imperial. One caveat found: on battery with the screen fully off, Doze can suspend projection after ~10–30 s — a battery-optimization exemption is the fix. Reports from real rides on any Kove dash are welcome.
