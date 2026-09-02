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
| 11   | S-curve ending RIGHT | poss. keep-right / fork-right |
| 12   | S-curve ending LEFT  | poss. keep-left / fork-left |
| 13   | "P" + coffee cup     | rest area / service stop POI (not a maneuver) |
| 14   | hand/palm + Chinese chars | China POI — poss. toll booth / checkpoint / pay-station (not a maneuver) |
| 15   | checkered/finish flag | destination-finish marker |
| 16   | tunnel entrance      | POI |
| 17   | BLANK (no glyph)     | **empty slot** — maneuvers mapped here blank out |
| 18   | BLANK (no glyph)     | empty slot |
| 19   | BLANK (no glyph)     | empty slot |
| 20   | straight arrow, heavier head | thicker than 9 — poss. merge/enter-highway straight. **on-ramp candidate** |
| 21   | long S-curve RIGHT into distance | gentle merge-right (11 stretched). **on-ramp/merge candidate** |
| 22   | rounded sharp-RIGHT, past-90° | curved mirror of 8. poss. u-turn-right / tight right loop |
| 23   | gentle curve right   | similar to 5 |
| 24   | gentle S-curve left  |       |
| 25   | BLANK (no glyph)     | **empty** — backlog's "dedicated on-ramp 25" guess DISPROVEN |
| 26   | roundabout (lollipop, exit down-right) | verify vs stray "empty" next session |
| 27-48 | NOT YET PROBED      | resume here next session |

## Summary so far (codes 1–26)

**Maneuver glyphs that render:**
- 1 left · 2 left · 3 right · 4 slight-left · 5 slight-right · 6 sharp-left · 7 sharp-right
- 8 curved-sharp-left (past-90) · 9 **straight** · 11 S-right · 12 S-left
- 20 heavy-straight · 21 long-merge-right · 22 curved-sharp-right (past-90)
- 23 gentle-right · 24 gentle-S-left · 26 roundabout

**Non-maneuver / POI glyphs:** 10 arrive · 13 rest-stop · 14 toll(?) · 15 finish-flag · 16 tunnel

**BLANK (empty slots — anything mapped here shows NO arrow):** 17, 18, 19, 25

## Key takeaways for the on-ramp fix
- The "dedicated on-ramp = 25" guess is **DISPROVEN** (25 is blank).
- On-ramps/merges currently blank because our classifier sends a code in an empty slot.
- **Best render-safe fallbacks for merge/on-ramp maneuvers:** 20 (heavy straight),
  21 (long merge-right), or plain 9 (straight). Pick per turn direction.

## Next steps
1. Resume probe at code 27 → 48 (same harness: `glyph_probe.sh 27 48 0` single-steps, or one-at-a-time).
2. Re-verify code 26 (roundabout vs blank).
3. Build the legacy classifier: map Google-Maps maneuvers → these legacy codes
   (in navshare/Maneuver.kt / dashIcon), with on-ramp/merge → 20/21/9 fallback so an arrow ALWAYS shows.
4. Ride-verify the on-ramp case that started this.

