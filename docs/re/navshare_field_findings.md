# navshare: empirical field findings (our app on a live dash)

Date: 2026-08-05
App: `com.kovedash.app` · Dash: Kove 450 Rally TFT, `SV=3.0.4`

These are **empirical** observations from running our own app against a live dash and
watching the wire (`KoveWire`) + the dash screen — not decompile citations. They pin down
what the dash actually renders on its native side-by-side nav page, how current Google Maps
notifications are shaped, and why multi-frame nav intermittently wedges. Companion to
[`nav_widget_kove.md`](nav_widget_kove.md) (OEM/Eryanet decompile) — some of its open
questions are answered here.

## What the native nav page renders (and doesn't)

The dash's side-by-side nav page (left = nav, right = gauges) draws, from our BLE push:

| Widget element | Fed by (see `proto/DashMessages.kt`) | Source |
|---|---|---|
| Turn arrow (icon) | `icon` (glyph enum) | Maps maneuver → `Maneuver.dashIcon()` |
| Next road name | `next_road` | parsed instruction, abbreviated/clipped |
| Distance to next turn | `cur_retain_distance` / `path_cur_unittype` | Maps notification title |
| **Finish-flag distance** (distance to destination) | `path_retain_distance` | Maps progress bar — see below |

**It does NOT render an ETA / arrival time on this page.** We sent a correct
`remain_time` (unix-epoch arrival) on the wire and **nothing appeared** — there is no time
field on the native nav page. This confirms the OEM behavior: ETA is computed phone-side and
never shown by the dash natively here. So `remain_time` is a no-op for display on `SV=3.0.4`
(don't chase it — see the closed ETA attempt, issues #12 / PR #13).

## Google Maps notification shape (current ProgressStyle, 2026)

Modern Google Maps posts a **ProgressStyle** ("Live Updates") ongoing notification. Observed
extras during navigation:

```
android.title       = "0.1 mi · Turn right onto BLM Rd 3515/Bouldering Lp"   (next turn + distance)
android.text        = null                                                    (often empty)
android.subText     = "Arrive 17:07"                                          (arrival CLOCK — NO distance, NO duration)
android.progress    = 0                                                       (meters travelled)
android.progressMax = 7315                                                    (≈ total route length in METERS)
android.progressIndeterminate = false
```

Key changes from the older format (`"13 min · 4.6 mi · 11:55 ETA"`):

- **`subText` no longer carries a remaining-distance token** — only an absolute arrival clock
  (`Arrive HH:MM`). Parsing distance out of `subText` yields nothing on current builds.
- **`subText` no longer carries a duration** (`N min`) either — so remaining-time can only be
  derived by subtracting the arrival clock from now (and it's a display no-op anyway, above).

### Distance-to-destination lives in the progress bar

`android.progressMax` is the **total route length in meters** and `android.progress` is
**meters travelled**, so:

```
distanceToDestinationMeters = progressMax − progress          (clamp ≥ 0)
routeProgressPercent        = progress / progressMax × 100     (drives retain_rate)
```

Verified: `progressMax = 7315` matched the old text format's `4.6 mi` (≈7403 m), and
`progress` starts at 0 and climbs toward `progressMax` as you ride. This is the **only**
reliable distance-to-destination source on current Maps — implemented in
`NavNotificationParser.destinationMetersFromProgress()` (issue #14 / PR #15), with a fallback
to the legacy `subText` text distance when progress data is unusable (`max ≤ 0`,
indeterminate, or `progress` out of `[0, max]` during a reroute).

## Multi-frame nav wedges the dash after a single dropped frame

The dash tracks **one global, contiguous frame sequence from 0** per GATT connection. `ffe1`
writes are `WRITE_TYPE_NO_RESPONSE` (mandatory — `WRITE_TYPE_DEFAULT` is rejected with ATT
status 14), i.e. fire-and-forget with no link-layer retransmit. When a single frame of a
multi-frame message drops (RF noise while moving), the dash waits for that seq forever — our
sequence only moves forward, so the missing frame never re-sends:

```
RX ← ffe2  {"msg_id":10,"item":9,"packet_loss_index":0}     ← dash stuck, asking for frame 0
```

The dash then renders nothing and looks disconnected (the BLE link is still bonded/encrypted
and chattering) until a **power-cycle** resets its receive cursor.

**Do not** answer this with the OEM's whole-tail replay (`item:7` → resend `[N..cursor]`,
~40 frames): that burst re-congests the transparent link and breaks reassembly (the reason
the responder was originally disabled). The fix (issue #16 / PR #17) is a **bounded
single-frame responder**: answer `item:7`/`item:9` with exactly the one requested frame from a
small ring buffer, self-paced by the dash's own re-request cadence, guarded by per-seq dedup
and a per-window resend cap. See `net/DashBleClient.kt` `handleResendRequest` / `resendFrame`.

## Practical rules of thumb

- Native rendering needs a **quiet, bonded** BLE link AND the Wi-Fi/17818 control channel
  brought up once per power-cycle to activate rendering.
- The finish flag shows **distance**, never time. If you want arrival time on the dash, it
  isn't available on the native nav page — only inside full projection (where Maps draws its
  own UI into the video).
- When "nothing renders" but the link is up, check for a `packet_loss_index` loop first
  (wedged reassembly) before suspecting the render code.
