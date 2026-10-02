# Dash render map — which A→D frames the SV=3.0.4 firmware actually displays

On-dash probe sweep, 2026-10-01. Each frame fired single-shot on a quiet BLE link via the
debug `NavTestReceiver` harness (`--es notify …` and the generic `--es raw '<json>'`), then
eyeballed on the dash. This reverses the old "notifications dead-end" (PROTOCOL_MATRIX §4) —
that verdict tested the wrong frame (`msg_id=2`) on a noisy link.

## Renders ✅

| frame | what it draws |
|-------|----------------|
| **`{"msg_id":6,"title","content"}`** (OEM `sendMMS`) | **Yellow notification banner** — `title` in a teal header with a message icon, `content` as a scrolling body. **Dismissable / openable with the dash SELECT key.** Re-fires cleanly (new banner each push). THE notification channel. |
| **`{"msg_id":7,"street"}`** (OEM `sendLocation`) | The string as **plain text in the notification area, no header/glyph** — a PERSISTENT, scrolling info line. ⚠️ **No software clear exists** (see below); a `msg_id=6` banner overlays it and reveals it again on dismiss → the banner and the street line are separate LAYERS. |

## Does NOT render ❌ (fired correctly, quiet link)

| frame | note |
|-------|------|
| `{"msg_id":2,"app_name","title","content","package_name"}` app-notify | confirmed ×2. The earlier "looks the same" was a stale `msg_id=6` banner. |
| `{"msg_id":3,"name","number"}` incall | no call UI |
| `{"msg_id":27,"func":"MUSIC","act":"ret_msg",…}` now-playing | the other "dead end" — stays dead |
| `{"msg_id":23,"power"}` phone battery | no indicator |
| `{"msg_id":22,"cur_speed"}` | dash uses its own CAN speed |
| `{"msg_id":8,"weather"}` legacy weather | live weather is `msg_id=25/11` |
| `{"msg_id":25,"msg_type":5,…"speed","max_speed"}` | ride-stat, not displayed |
| `{"msg_id":25,"msg_type":8,"calorie"}` | ride-stat |

## Inconclusive / not run

- `{"msg_id":27,"func":"KEY","act":"down"}` (`sendKeyPress`) — no visible effect, but valid `act`
  strings aren't in the decompile, so this is a blind guess, not disproven. A phone→dash control
  channel may exist; needs the real `act` values (the dash sends `BT_KEY` the other way on
  physical presses). Worth a future dig.
- `{"msg_id":4,"icon":"<base64 bitmap>"}` junction enlargement — needs a real base64 image;
  impractical to fire from adb.

## Clearing the msg_id=7 street bar — no command found

The decompile has only `clearRidingData` (`27/func=RIDE/act=clean` — wipes *trip stats*) and
`endNavi` (`15` — no effect on the bar, tested). `sendLocation` has no empty/clear path; the bar
is meant to refresh continuously during nav and is never torn down. Clear it with a dash
page-cycle, an app reconnect (the re-handshake re-renders), or an ignition cycle.

## Decision for the notification feature

Forward phone notifications through **`msg_id=6` only** — it's transient and dismissable. Never
use `msg_id=7` (sticky, no clear). Compose `title` = sender/app name, `content` = message body;
keep `content` short-ish (the dash wraps at ~13 chars and breaks words mid-word). An allow-list
of apps gates what gets forwarded, and timing should avoid stepping on an active turn arrow.
