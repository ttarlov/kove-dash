# Altitude Investigation Report
Date: 2026-05-19

## Bottom line

**Working hypothesis is REFUTED in part and CONFIRMED in part.** The dash IS the source of truth for the altitude field — but the value it's emitting on our firmware (`SV=3.0.4`) is a hard-coded sentinel `17`, not a real reading. Empirical evidence: every proto-poc spam filter in `<REPO>/proto-poc/` keys on the literal byte pattern `"altitude":\t17` co-occurring with `"msg_type":\t17` — meaning across every BLE capture we've ever done, the dash's unsolicited msg_id=25 msg_type=17 broadcast has carried `altitude=17` (identical to the msg_type value, almost certainly a magic-number placeholder). The OEM apps do NOT consume this back (no inbound parser exists in ThinkerRide, GreenTrip, cn_thinkerride, or KOVE/Eryanet), so their behavior tells us nothing about whether the dash's altitude pipeline works. The phone-pushed msg_type=9 is accumulated for the ride-summary report at ride-end (control_info=4) but is NOT used by the dash's live Elevation widget — the prior finding holds.

**The most likely root cause is that our 2022 dash unit was built without a working barometer / has firmware that never wires altitude into the live widget**, displaying `--` because the dash itself has no live reading to render. The phone-altitude pipeline is for trip-stats accounting only.

**Concrete next move:** before anything else, run a single bench experiment to disprove the alternative ("dash's altitude is real but reads only after a calibration / GPS-warm-up window"). Sit the bike outside in clear sky for >5 minutes with no phone connected and check the Elevation menu directly. If it still shows `--`, the dash's sensor is dead/unwired and this is firmware-by-design — no amount of phone-side work will fix it. See "Recommended next experiments" below.

## Empirical: what's in the dash's actual msg_type 17 broadcasts

The dash periodically (~1 Hz idle cadence per `<REPO>/proto-poc/PROTOCOL.md:397`) emits a `msg_source=1` frame:

```
{"msg_id":25,"msg_type":17,"msg_source":1,"altitude":17,...}
```

The value `17` is **literal and constant** across all captures we've taken. Evidence:

- `<REPO>/proto-poc/bt_probe_repl.py:307-313` explicitly tags this as "dash's idle altitude=17/msg_type=17 heartbeat" — `obj.get("msg_id") == 25 and obj.get("msg_type") == 17 and obj.get("altitude") == 17`.
- The identical filter pattern `'"altitude":\t17' in text and '"msg_type":\t17' in text` appears in 10 separate proto-poc scripts (`bt_keepalive.py:80`, `bt_nav_stream.py:83`, `bt_msg_id_1_to_6.py:123`, `bt_nav_simulation.py:77`, `bt_try_things.py:45`, `bt_shotgun.py:139`, `bt_bid_forge.py:67`, `bt_visible_probe.py:164`, `bt_probe_ack.py:28`, `bt_msg_id_1_to_6_v1_RAW.py:57`). The filter exists because the value is so constant and uninteresting it was clogging the bench logs.
- The user's V1 Kotlin app's TelemetryProbe docs the same observation at `<REPO>/app/app/src/main/java/com/kovedash/app/proto/TelemetryProbe.kt:54-57`: "The dash emits an unsolicited status message every few seconds that looks like `{"msg_id":25,"msg_type":17,"msg_source":1,"altitude":17,...}`."
- HANDOFF.md:198 confirms — "filter unsolicited dash heartbeats (`altitude:17 msg_type:17 msg_source:1`) so they don't get claimed as probe responses."

**The `altitude=17` is almost certainly a placeholder / sentinel.** Two strong reasons:
1. Value equals msg_type value — classic "unfinished implementation" stub.
2. Our actual location elevations are ~1600m (Denver) or higher in the mountains; the value never changes when we move the bike around the parking lot.

**No log file in `proto-poc/*.log` contains a captured msg_type=17 frame** — all of them are app→dash (msg_source=2) traffic from our scripts. The dash-emitted msg_type=17 frames were filtered out at capture time as spam. [inferred] We have **no captured payload that includes the full `...` field set of the msg_type=17 frame** — we don't know what else is in it besides `altitude:17`. This is a gap that an empirical follow-up could close in five minutes (see Experiment 2 below).

## ThinkerRide altitude flow (cross-check of prior findings)

Prior findings in `<REPO>/phase2/_re_report_thinkerride.md §6` are **fully confirmed**:

- **Builder:** `JsonManager.sendElevationAndPond(altitude, ave, pondDist, pondTime, head, max)` at `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/manager/JsonManager.java:1113-1149`. Emits `{"msg_id":25,"msg_type":9,"msg_source":2,...}`. Integer meters, double pond_distance km, head=descent meters.
- **Direction:** strictly phone→dash. No inbound parser for `altitude` exists in the ThinkerRide decompile. Verified empirically by grep across the entire `phase1/apk/jadx_output/sources/com/whbluestar/` and `com/thinkerride/` trees: `grep -rln 'optInt.*"altitude"\|getInt.*"altitude"'` returns zero hits.
- **Inbound msg_id=25 handler:** `BleConnectWrapper.java:1128-1264` handles only msg_types **1, 2, 14, 15, 18, 20, 22, 24, 25**. **msg_type 17 falls through unhandled and is silently dropped.**
- **Voice-command "current altitude" path:** `<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/speech/EventDispatcher.java:690-704` — `speakAltitude()` reads `SQNavigationManage.getNavigation().getLocation().getAltitude()` — i.e., the **PHONE's** GPS altitude, not anything from the dash. If `location.getAltitude() == 0.0d` the app logs "无海拔信息" (no altitude info) and speaks "unknown". The phone never asks the dash for altitude. This is significant: even the OEM's own voice assistant treats the phone as the altitude source of truth.

Call sites for `sendElevationAndPond` (5):
1. GPS update during nav (`GlobalData.java:1267-1270`) — 2s throttle, gated by `DeviceFunction.getAltitude()`.
2. Non-nav location callback (`GlobalData.java:1697-1712`) — same 2s throttle.
3. MapBox location manager oversea variant (`MapBoxLocationManager.java:313, 474, 481`).
4. **On-connect bootstrap** (`BleConnectWrapper.java:1730-1733`) — single bare-altitude push when a fresh BLE connection has a valid GPS location, before version handshake completes.
5. Ride-completion summary (`Riding.java:400-402`) — only call site that fills `pond_distance, pond_time, head, max_altitude` with real values from `SportDataHelper`.

The fact that path 4 (on-connect) fires before the version handshake completes — and path 5 (ride-completion) fills all the climbing/descent stats — strongly suggests the dash uses msg_type=9 for **trip-summary accounting**, not live widget rendering.

## KOVE app altitude flow (new ground)

**Negative result, definitively.** The newer Eryanet KOVE app (`com.eryanet.gkove` / `com.eryanet.ite`) **does not push altitude to the dash at all.**

- Existing report `<REPO>/phase2/_re_report_eryanet.md §7` already documented this:
  > "Negative result. Altitude is never pushed phone→dash in this codebase. `grep -rn 'altitude\|elevation\|海拔' com/eryanet/` — every hit is Material `elevation` (UI shadow Z-axis) in `R.java`. No data class has an altitude field."
- Independently re-verified: `grep -rln "altitude\|海拔\|气压" <REPO>/phase2/kove_app/decompile/sources/com/eryanet/` returns only the auto-generated `R.java` (Material shadow elevation) — no Eryanet-authored source mentions altitude.
- `BleNaviBean` (the structured nav-overlay-data class the new app pushes) has `curStepRetainDistance, iconType, nextRoadName, pathRetainDistance, pathRetainTime` — and nothing else (`com/eryanet/ite/ble/BleNaviBean.java:4-55`).
- `BleCarInfo` (dash→phone telemetry) carries speed/RPM/water-temp/oil/mileage/tire-pressure — and nothing else (`com/eryanet/ite/ble/BleCarInfo.java:8-19`). **No altitude back from the dash either.**

This is a **strong signal**: the entire newer protocol family was designed without phone→dash altitude pushes. Either the newer Eryanet dashes have working baro sensors that render altitude natively (no phone help needed), or the Eryanet dashes don't display altitude at all. Either way, **the OEM design treats altitude as a dash-internal concern in the new world.** Our 2022 dash predates this design.

The KOVE app **does** push timestamp+timezone (`com/eryanet/ite/ble/BleTime.java`), navi structured data, phone calls/notifications — but never altitude. If the older OEM thought altitude *had to come from the phone*, the newer OEM disagreed.

## Calibration / init / GPS bootstrap

**Searched both apps (and cn_thinkerride) for any calibration / barometric reference / sea-level / initial-altitude / first-fix / A-GPS / cold-start setup messages. ZERO HITS in the Kove altitude context.**

Specific search results:

- Chinese terms: 海拔 (altitude), 气压 (barometric pressure), 校准 (calibration), 海平面 (sea level), 高度 (height).
  - 海拔 / altitude appears in ThinkerRide only in voice-command speech files (`<REPO>/phase1/apk/jadx_output/resources/res/raw/speech_command.json:37-39`: "海拔", "海拔多少", "当前海拔" — the asr command vocabulary for the voice assistant) and in `EventDispatcher.java:698, 859` log lines. No setter, no calibration message.
  - 气压 / barometric pressure: **zero hits** in any of the three OEM decompiles. The Chinese OEMs never mention barometric pressure anywhere in the codebase.
  - 校准 / calibration: only one hit — `installation_parameter_calibration` (`<REPO>/phase1/apk/jadx_output/sources/com/whbluestar/thinkride/R.java:13762`) which is **ADAS radar mounting-angle calibration**, not altitude.
- English terms: `barometric / baro / sealevel / sea_level / pressureRef / altitudeCalibration / coldStart / aGPS / agps`: **zero hits** across all three OEM trees (ThinkerRide, GreenTrip, KOVE).
- No `msg_id` or `func` named anything like INIT_BARO, SET_SEA_LEVEL, CALIBRATE, GPS_BOOTSTRAP, etc.
- The full msg_id=27 `func` catalog (`<REPO>/phase2/_re_report_thinkerride.md §3a-iii`) is: TUC, TTS, USER, SIM, INSIDENAVI, AUDIO, PAIR, OTA, UPDATE, ADAS, NAVI, ROAD_NAVI, GPS, KEY, MUSIC, TIRE, THEME, LED, BT_KEY, COMBO, COREDUMP, SCPT, SCREEN, MOTOR_SIGNAL, AUTOOFF, CAR_INFO, TBOX, hanjd_test. **None are altitude / pressure / GPS-init related.**
- The closest thing to a GPS bootstrap is `msg_id=27 func=GPS act=signal_status` at `JsonManager.java:1166-1185` (`sendGPSSignal`). This is **not** A-GPS data — it sends a simple int signal-strength value. Phone→dash only, no coordinates.

**Inferred conclusion:** the dash's altitude pipeline (if real) is entirely self-contained. It has no init, no calibration message, no GPS-prime from the phone. Either it works out of the box from the dash's own GPS/baro hardware, or it doesn't work at all. There is no protocol channel for the phone to "wake up" the dash's altimeter.

## All msg_id=25 message types

Cross-validated against ThinkerRide (`<REPO>/phase2/_re_report_thinkerride.md §3a-ii`), GreenTrip (`<REPO>/phase2/_re_report_greentrip.md §6.1`, `defpackage/d72.java`), cn_thinkerride (`apks_for_diff/cn_thinkerride_decompile/sources/defpackage/of.java:939+` and `n01.java`).

| msg_type | msg_source | Direction | Meaning | Source citation |
|---|---|---|---|---|
| 1 | 2 (start) / 1 (echo) | ↔ | Ride state control + ride report (control_info 1=start, 2=pause, 3=stop, 4=report). Carries `time, calorie, max_speed, ave_speed, total_deep, ave_altitude`. | `JsonManager.java:1958, 2154`; RX `BleConnectWrapper.java:1131-1186` |
| 2 | 1 | D→A | Stop navigation (dash-initiated). | RX `BleConnectWrapper.java:1187-1207` |
| 3 | 2 | A→D | Mileage trip (`mile_trip: double`). | `JsonManager.java:1342` |
| 4 | 2 | A→D | Total mileage. | `JsonManager.java:2291` |
| 5 | 2 | A→D | Current speed (`speed, max_speed`). | `JsonManager.java:2100` |
| 6 | 2 | A→D | Average speed. | `JsonManager.java:918` |
| 7 | 2 | A→D | Mobile signal strength. | `JsonManager.java:2054` |
| 8 | 2 | A→D | Calorie. | `JsonManager.java:997` |
| **9** | **2** | **A→D** | **ALTITUDE push (live):** `altitude, ave_altitude, max_altitude, pond_distance(km), pond_time, head(descent m)`. Gated by `DeviceFunction.getAltitude()` + `isActivate()`. 2s throttle in GlobalData, 5s in qm0. **Consumed by dash for trip-summary stats only — does NOT drive live Elevation widget on our SV=3.0.4 firmware.** | `JsonManager.java:1129-1138`; `_re_report_greentrip.md §6` |
| 10 | 2 | A→D | Location picture (base64 POI thumbnail). | `JsonManager.java:1304` |
| 11 | 2 | A→D | Weather + wind power. | `JsonManager.java:2402` |
| 12 | 2 | A→D | Weather warning. | `JsonManager.java:2341, 2369` |
| 13 | 2 | A→D | Set language. | `JsonManager.java:2021` |
| 14 | ↔ | ↔ | Unit change (mi/km). | `JsonManager.java:2039`; RX `BleConnectWrapper.java:1208-1213` |
| 15 | 2 (push) / 1 (start cmd) | ↔ | Orientation: A→D push compass `angle:float`. D→A is dash-initiated "start orientation" (`start` int). | `JsonManager.java:1543`; RX `BleConnectWrapper.java:1214-1219` |
| **17** | **1** | **D→A** | **DASH-EMITTED unsolicited altitude broadcast.** Payload `{"msg_id":25,"msg_type":17,"msg_source":1,"altitude":17,...}`. **NO inbound parser exists in ThinkerRide, GreenTrip, or cn_thinkerride** — silently dropped. Observed value `altitude=17` is constant placeholder/sentinel, never reflects real elevation. ~1Hz idle cadence. | Empirical: `proto-poc/bt_probe_repl.py:310-312`, `proto-poc/PROTOCOL.md:390`. Confirmed unhandled in `BleConnectWrapper.java:1128-1264` (only msg_types 1,2,14,15,18,20,22,24,25 routed). |
| 18 | 1 | D→A | Language reply. | RX `BleConnectWrapper.java:1221-1229` |
| 19 | 2 | A→D | Record time. | `JsonManager.java:2493` |
| 20 | ↔ | ↔ | Record time query+reply. | `JsonManager.java:668`; RX `BleConnectWrapper.java:1231-1238` |
| 21 | 2 | A→D | Set record status. | `JsonManager.java:2475` |
| 22 | ↔ | ↔ | Record status query+reply. | `JsonManager.java:650`; RX `BleConnectWrapper.java:1240-1248` |
| 23 | 2 | A→D | Mirror set (DVR mirror status). | `JsonManager.java:2460` |
| 24 | ↔ | ↔ | Mirror status query+reply. | `JsonManager.java:537`; RX `BleConnectWrapper.java:1250-1264` |
| 25 | 1 | D→A | Light/lamp status (`lamp_type, status`). | RX `BleConnectWrapper.java:1250-1255` (LightStatusEvent) |
| 26 | 2 | A→D | Remove-bound (device unpair). | `JsonManager.java:1911` |

**Other altitude-adjacent dash-emitted msg_types under msg_id=25:** none. **Only msg_type=17 (with altitude) is unsolicitedly broadcast by the dash.** Nothing else carries altitude or pressure.

**Note on msg_type=16:** completely absent from the OEM decompile. Not documented anywhere. [inferred] May not exist.

## Recommended next experiments

Ordered by cost/value. All three are cheap and bench-safe.

### Experiment 1 — disprove the "dash baro just doesn't work" alternative (cheapest, highest value)

**Goal:** Verify whether the dash's Elevation menu *ever* shows a real number on our hardware, independent of the phone, with the bike just sitting outdoors.

**Procedure:**
1. Power the bike on with **no phone in range** (turn the phone off or leave it indoors at 30+ m distance).
2. Open the dash's Elevation menu.
3. Wait 5–10 minutes for the dash's own GPS to acquire (assuming there is one — `INSIDENAVI` reachability in our previous probes suggests yes).
4. Move the bike up/down a vertical step (drive to the parking-garage roof, or just up a flight of stairs in the yard) and check whether the value changes.

**Decision tree:**
- Elevation shows `--` indefinitely with no phone present → **dash hardware/firmware never produces a live altitude reading. Stop investigating; this is by design on SV=3.0.4.** The phone push goes into trip-stats only. Nothing the phone can do will populate the live widget.
- Elevation shows a number that doesn't change with vertical movement → dash has a constant fallback (lat/lon-derived from a city table?) but no real altimeter. Same conclusion — drop it.
- Elevation shows a number that **does** change with vertical movement → dash has a working source independent of the phone. The `altitude=17` we see in BLE is just a buggy/unfinished broadcast field but the dash UI works. Means the prior bench tests on V1 simply never gave the dash enough sky/time.

This experiment costs <15 minutes and tells you definitively whether to give up or keep investigating.

### Experiment 2 — capture a full msg_type=17 payload (verify the `...` we're missing)

**Goal:** Find out what fields besides `altitude` are in the dash's msg_type=17 broadcast. We've been filtering it out as spam in every script for 6+ months and never logged a full instance. If it carries `latitude`, `longitude`, `accuracy`, `gps_fix_status`, or similar, we'd know whether the dash's own GPS has a fix.

**Procedure:**
1. Run `bt_keepalive.py` but **temporarily comment out** line 80 (`'"altitude":\t17' in text and '"msg_type":\t17' in text`) so the spam filter is disabled.
2. Capture 30 seconds of `ffe2` notifications to a file.
3. Decode one full msg_type=17 frame from the log.

**Why this matters:** if msg_type=17 contains `{"gps_fix":0,"satellites":0,"altitude":17,...}` we learn the dash never gets a sky lock indoors → matches the user's reported "always `--`" symptom. If it contains real lat/lon but altitude=17, we learn the GPS works but the altimeter chain is broken — which kills any phone-side workaround idea.

Cost: 2 minutes. Value: closes the biggest data gap in this report.

### Experiment 3 — write `msg_id=25 msg_type=17 msg_source=2 altitude=<real>` from the phone (impersonation test)

**Goal:** Test whether the dash's Elevation widget is keyed off msg_type=**17** (not msg_type=9), and whether a phone can spoof the dash's own broadcast.

**Procedure:**
1. Send `{"msg_id":25,"msg_type":17,"msg_source":2,"altitude":1234}` via `ffe1` (correct 104-byte framing).
2. Watch the Elevation menu for ~10 seconds.
3. Variant: try `msg_source:1` (claim to be the dash itself).

**Hypothesis:** the dash listens for msg_type=17 inbound and renders that field directly. Risky / unlikely — the OEM apps don't send this and the dash is presumably the authority for its own broadcasts — but it's a cheap shot.

**Expected outcome:** Most likely silently dropped. If it works, you've found the actual altitude channel and it's been hiding in plain sight. Worth 5 minutes to confirm.

---

**Do experiments 1 and 2 first.** They give you a clean go/no-go on whether further phone-side work can possibly help. Experiment 3 is only worth doing if Experiment 1 shows the dash has a working live altitude source — i.e., there's actually something to compete with.
