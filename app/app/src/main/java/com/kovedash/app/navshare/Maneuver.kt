package com.kovedash.app.navshare

/**
 * A normalized turn maneuver, decoupled from any single source (Google Maps notification
 * text today; an icon-bitmap classifier or Mapbox later). Mirrors the maneuver set
 * Gadgetbridge extracts from Google Maps navigation notifications.
 *
 * [dashIcon] maps each maneuver to the dash's native turn-glyph enum (1–48), documented
 * in docs/re/nav_widget_thinkerride.md §"Turn-icon enumeration". Keep this table the
 * single source of truth — a future [ManeuverClassifier] impl (icon matching) reuses it.
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

    /** Dash native glyph index (1–48). Fallback is 9 ("straight / continue / unknown"). */
    fun dashIcon(): Int = when (this) {
        TURN_LEFT -> 2
        TURN_RIGHT -> 3
        SLIGHT_LEFT -> 4
        SLIGHT_RIGHT -> 5
        SHARP_LEFT -> 6
        SHARP_RIGHT -> 7
        // No dedicated "keep left/right" glyph on the dash — slight is the closest.
        KEEP_LEFT -> 4
        KEEP_RIGHT -> 5
        UTURN -> 8
        MERGE -> 47            // merge-left glyph (dash has 47/48; we don't split side yet)
        FORK_LEFT -> 45
        FORK_RIGHT -> 46
        OFF_RAMP -> 43
        ROUNDABOUT -> 31       // base roundabout; exit-number variants (32–42) are follow-up
        ARRIVE -> 21
        CONTINUE, UNKNOWN -> 9
    }
}
