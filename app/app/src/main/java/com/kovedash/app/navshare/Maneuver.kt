package com.kovedash.app.navshare

/**
 * A normalized turn maneuver, decoupled from any single source (Google Maps notification
 * text today; an icon-bitmap classifier or Mapbox later). Mirrors the maneuver set
 * Gadgetbridge extracts from Google Maps navigation notifications.
 *
 * [dashIcon] maps each maneuver to the dash's native turn-glyph enum. The decompiled OEM
 * enum spans 1–48 (docs/re/nav_widget_thinkerride.md), but a live sweep of THIS firmware
 * (SV=3.0.4) proved only codes 1–26 actually render — everything 27–48 draws nothing, and
 * 17/18/19/25 are blank too. See docs/re/glyph_map.md for the ground-truth table. So this
 * mapping stays strictly inside the codes that glyph; anything pointed at the 27–48 dead
 * zone (the old MERGE/FORK/OFF_RAMP/ROUNDABOUT targets) blanked the arrow on real rides —
 * that was the on-ramp-blanking bug. Keep this table the single source of truth — a future
 * [ManeuverClassifier] impl (icon matching) reuses it.
 */
enum class Maneuver {
    CONTINUE,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    UTURN,
    MERGE,
    FORK_LEFT,
    FORK_RIGHT,
    OFF_RAMP,
    ROUNDABOUT,
    ARRIVE,
    UNKNOWN;

    /**
     * Dash native glyph index — RESTRICTED to the codes that actually render on SV=3.0.4
     * (1–26, minus the blanks 17/18/19/25). Never-blank fallback is 9 ("straight"). Every
     * arm below is a verified-rendering glyph from docs/re/glyph_map.md.
     */
    fun dashIcon(): Int = when (this) {
        TURN_LEFT -> 2         // left turn
        TURN_RIGHT -> 3        // right turn
        SLIGHT_LEFT -> 4       // slight/smooth left
        SLIGHT_RIGHT -> 5      // slight/smooth right
        SHARP_LEFT -> 6        // sharp left (past-90 angular)
        SHARP_RIGHT -> 7       // sharp right
        // "Keep/bear left|right" → the S-curve glyphs (bear to that side at a split).
        KEEP_LEFT -> 12        // S-curve ending left
        KEEP_RIGHT -> 11       // S-curve ending right
        UTURN -> 8             // curved-sharp-left, past-90 (u-turn)
        // Highway maneuvers — old targets (47/45/46/43/31) were all in the 27–48 dead zone
        // and blanked the arrow. Remapped into rendering glyphs:
        MERGE -> 20            // heavy straight arrow (merge / continue onto motorway)
        FORK_LEFT -> 12        // S-curve ending left (bear left at fork)
        FORK_RIGHT -> 11       // S-curve ending right (bear right at fork)
        OFF_RAMP -> 21         // long S-curve right into distance (reads as an exit ramp)
        ROUNDABOUT -> 26       // roundabout (lollipop); exit-number variants don't render here
        ARRIVE -> 10           // arrive / destination glyph (was 21, which is the ramp glyph)
        CONTINUE, UNKNOWN -> 9 // straight — the never-blank fallback
    }
}
