# Kove dash — legacy TBT glyph map (SV=3.0.4)

Probing `icon` codes via NavTestReceiver → legacy msg_id:1 frame (what the dash renders).
Road name on dash = `IC-N` so the code is visible. Distance = 500m.

| code | dash glyph (observed) | notes |
|------|----------------------|-------|
| 1    | left turn            |       |
| 2    | left turn            |       |
| 3    | right turn           | known-good |
| 4    | slight/smooth left   | gentle curve |
| 5    | slight/smooth right  | gentle curve |
| 6    | sharp left           | past-90°, hairpin-ish |
| 7    | sharp right          | mirror of 6 |
| 8    | sharp-left, smooth/curved | like 6 but rounded — poss. u-turn-left or left curve |
| 9    | straight arrow       | continue/straight |
| 10   | arrive/destination   |       |
| 11   | roundabout, exit bearing RIGHT | photo-verified 2026-09-02 (curved chevron arc, exits up-right) |
| 12   | roundabout, exit STRAIGHT | photo-verified — clearest generic roundabout glyph |
| 13   | "P" + coffee cup     | rest area / service stop POI (not a maneuver) |
| 14   | hand/palm + Chinese chars | China POI — poss. toll booth / checkpoint / pay-station (not a maneuver) |
| 15   | checkered/finish flag | destination-finish marker |
| 16   | tunnel entrance      | POI |
| 17   | BLANK (no glyph)     | **empty slot** — maneuvers mapped here blank out |
| 18   | BLANK (no glyph)     | empty slot |
| 19   | BLANK (no glyph)     | empty slot |
| 20   | straight arrow, heavier head | thicker than 9 — poss. merge/enter-highway straight. **on-ramp candidate** |
| 21   | roundabout, exit slightly-right-of-straight | photo-verified — another roundabout-exit variant |
| 22   | rounded sharp-RIGHT, past-90° | curved mirror of 8. poss. u-turn-right / tight right loop |
| 23   | gentle curve right   | similar to 5 |
| 24   | gentle S-curve left  |       |
| 25   | BLANK (no glyph)     | **empty** — backlog's "dedicated on-ramp 25" guess DISPROVEN |
| 26   | BLANK (no glyph)     | re-verified 2026-09-02 — the earlier "lollipop" read was a misfire; 26 is empty |
| 27   | BLANK (no glyph)     | empty slot |
| 28   | BLANK (no glyph)     | empty slot |
| 29   | BLANK (no glyph)     | empty slot |
| 30   | BLANK (no glyph)     | empty slot |
| 31   | BLANK (no glyph)     | empty slot |
| 32   | BLANK (no glyph)     | empty slot |
| 33   | BLANK (no glyph)     | empty slot |
| 34-48 | BLANK (no glyph)     | all empty — swept 5s each, none drew |

## COMPLETE — full 1–48 sweep done (2026-09-02)

**Renderable glyph set is codes 1–26 only. Everything 27–48 is empty.**

## Summary (codes 1–26)

**Maneuver glyphs that render:**
- 1 left · 2 left · 3 right · 4 slight-left · 5 slight-right · 6 sharp-left · 7 sharp-right
- 8 curved-sharp-left (past-90) · 9 **straight**
- 11/12/21 **roundabout-exit variants** (11 bears right, 12 straight, 21 slightly-right) — photo-verified
- 20 heavy-straight (merge) · 22 curved-sharp-right (past-90) · 23 gentle-right · 24 gentle-S-left

**Non-maneuver / POI glyphs:** 10 destination-pin (arrive) · 13 rest-stop · 14 toll(?) · 15 finish-flag · 16 tunnel

**BLANK (empty slots — anything mapped here shows NO arrow):** 17, 18, 19, 25, **26**, **27–48 (all)**

## Key takeaways for the on-ramp fix
- The "dedicated on-ramp = 25" guess is **DISPROVEN** (25 is blank). **26 is also blank**
  (the earlier "roundabout lollipop" read on 26 was a misfire).
- On-ramps/merges blanked because the classifier sent codes in the 27–48 dead zone.
- **Render-safe targets (all photo-verified 2026-09-02):** merge → 20 (heavy straight);
  roundabout → 12 (or 11/21); off-ramp/fork/keep → 4/5 (slight L/R); arrive → 10 (destination pin).

## Classifier remap — SHIPPED (navshare/Maneuver.kt)
| maneuver | glyph | note |
|---|---|---|
| MERGE | 20 | heavy straight — verified |
| ROUNDABOUT | 12 | roundabout glyph — verified (26 blank) |
| OFF_RAMP | 5 | slight right (exits bear right) |
| FORK_LEFT / FORK_RIGHT | 4 / 5 | slight L/R |
| KEEP_LEFT / KEEP_RIGHT | 4 / 5 | slight L/R (unchanged — was already fine) |
| ARRIVE | 10 | destination pin — verified (old 21 is a roundabout) |
| all turns / u-turn / continue | 2–9 | unchanged |
| unknown / fallback | 9 | never-blank straight |

Guard test asserts every maneuver → 1–26 and never a blank slot.

## Remaining
- Cross-ref 1–26 against the decompiled semantic enum in `docs/re/nav_widget_thinkerride.md`.
- Ride-verify the real on-ramp/merge/roundabout case that started this.

