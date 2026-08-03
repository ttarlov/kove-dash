package com.kovedash.app.navshare

/**
 * Turns the raw fields of a Google Maps navigation notification into a [Maneuver].
 *
 * This interface is the ONLY seam a future icon-bitmap classifier needs: swap the impl
 * on [NavNotificationListener] and nothing else in the silo changes. Text parsing (the
 * MVP) is English-only and brittle to Maps wording; icon matching (Gadgetbridge-style,
 * a Hamming match on the maneuver bitmap) is locale-independent and is the planned upgrade.
 */
interface ManeuverClassifier {
    fun classify(title: String?, text: String?, subText: String?): Maneuver
}

/**
 * English keyword classifier over the notification text. Google Maps puts the turn
 * instruction in `android.text` (e.g. "Turn left onto Pearl St", "At the roundabout,
 * take the 2nd exit", "Slight right to stay on US-36").
 *
 * Ordering is deliberate: compound phrases ("slight left", "sharp right", "keep left")
 * are tested BEFORE the bare "left"/"right" so they never fall through to a plain turn.
 */
class TextManeuverClassifier : ManeuverClassifier {

    override fun classify(title: String?, text: String?, subText: String?): Maneuver {
        // The instruction lives in `text`; fall back to title just in case.
        val s = (text ?: title ?: "").lowercase()
        if (s.isBlank()) return Maneuver.UNKNOWN

        return when {
            // Arrival / destination — check first; "arrive" can co-occur with a side.
            s.contains("arrive") || s.contains("destination") || s.contains("you have reached") -> Maneuver.ARRIVE

            // U-turn.
            s.contains("u-turn") || s.contains("uturn") || s.contains("make a u") -> Maneuver.UTURN

            // Roundabout / rotary (side/exit handled later as a follow-up).
            s.contains("roundabout") || s.contains("rotary") || s.contains("traffic circle") -> Maneuver.ROUNDABOUT

            // Compound directional — the actual turn geometry. MUST precede both the bare
            // turns AND the ramp/exit/merge type checks (an instruction like "Slight right
            // onto the ramp" is a slight-right, not a generic off-ramp).
            s.contains("sharp left") -> Maneuver.SHARP_LEFT
            s.contains("sharp right") -> Maneuver.SHARP_RIGHT
            s.contains("slight left") -> Maneuver.SLIGHT_LEFT
            s.contains("slight right") -> Maneuver.SLIGHT_RIGHT
            s.contains("keep left") -> Maneuver.KEEP_LEFT
            s.contains("keep right") -> Maneuver.KEEP_RIGHT

            // Ramps / merges / forks — maneuver TYPES that apply when no explicit
            // slight/sharp/keep direction was given above.
            s.contains("merge") -> Maneuver.MERGE
            s.contains("fork") && s.contains("left") -> Maneuver.FORK_LEFT
            s.contains("fork") && s.contains("right") -> Maneuver.FORK_RIGHT
            s.contains("exit") || s.contains("off-ramp") || s.contains("off ramp") || s.contains("ramp") -> Maneuver.OFF_RAMP

            // Bare turns.
            s.contains("turn left") || s.contains("left onto") || s.contains("left toward") || s.contains("left to ") -> Maneuver.TURN_LEFT
            s.contains("turn right") || s.contains("right onto") || s.contains("right toward") || s.contains("right to ") -> Maneuver.TURN_RIGHT

            // Continue / head / straight.
            s.contains("continue") || s.contains("head ") || s.contains("go straight") || s.contains("straight") || s.contains("stay on") -> Maneuver.CONTINUE

            // Last resort: a lone "left"/"right" mention.
            s.contains("left") -> Maneuver.TURN_LEFT
            s.contains("right") -> Maneuver.TURN_RIGHT

            else -> Maneuver.UNKNOWN
        }
    }
}
