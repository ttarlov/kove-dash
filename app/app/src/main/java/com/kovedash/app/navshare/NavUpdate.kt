package com.kovedash.app.navshare

/**
 * A single parsed turn-by-turn update ready to forward to the dash. Mirror of Gadgetbridge's
 * NavigationInfoSpec. Next-turn fields come from the notification title/text; the trip-level
 * fields (distance to destination + time remaining) come from the subText
 * ("13 min · 4.6 mi · 11:55 ETA").
 *
 * All distances are METERS and times are SECONDS (SI) — the dash converts to the rider's unit
 * (imperial → feet/miles) on display, same as the altitude field. -1 means "not parseable".
 *
 * @param distanceToTurnMeters meters to the next maneuver, or -1 if not yet parseable
 *        (Google Maps flickers empty / "0 m" for a moment at nav start).
 * @param distanceToDestinationMeters meters remaining to the destination (subText), or -1.
 * @param remainingTimeSec seconds remaining on the trip (subText), or -1.
 */
data class NavUpdate(
    val maneuver: Maneuver,
    val nextRoad: String,
    val distanceToTurnMeters: Int,
    val distanceToDestinationMeters: Int = -1,
    val remainingTimeSec: Int = -1,
    val rawTitle: String? = null,
    val rawText: String? = null,
    val rawSubText: String? = null,
) {
    /**
     * Coarse identity for de-duplication. Google Maps re-posts the ongoing notification
     * often; we only want to hit the wire when something meaningful changed. Distance is
     * bucketed so small ticks don't spam the dash: exact under 200 m (where precision
     * matters approaching the turn), 50 m buckets beyond.
     */
    fun dedupKey(): String {
        val d = distanceToTurnMeters
        val bucket = when {
            d < 0 -> -1
            d < 200 -> d
            else -> (d / 50) * 50
        }
        return "${maneuver.name}|$nextRoad|$bucket"
    }
}
