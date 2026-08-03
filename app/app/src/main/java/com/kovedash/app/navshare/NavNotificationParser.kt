package com.kovedash.app.navshare

import android.app.Notification
import android.os.Bundle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure parsing of a Google Maps navigation notification's extras into a [NavUpdate].
 * No Android-service coupling → unit-testable off-device (see NavNotificationParserTest,
 * mirroring ByteCatTest).
 *
 * Google Maps' ongoing navigation notification carries:
 *   android.title    → distance to the next turn, e.g. "100 yd", "0.2 mi", "500 m"
 *   android.text     → the turn instruction / next road, e.g. "Turn left onto Pearl St"
 *   android.subText  → "13 min · 4.6 mi · 11:55 ETA"  (kept raw; parsed in a follow-up)
 */
object NavNotificationParser {

    // number (with optional decimal/thousands) + unit. First match wins.
    private val DISTANCE_RE = Regex("""([\d][\d.,]*)\s*(mi|km|yd|ft|m)\b""", RegexOption.IGNORE_CASE)

    private const val MAX_ROAD_LEN = 24 // dash field is short; keep to the road name

    // Google Maps instructions are "<maneuver> <connector> <road>", e.g. "Turn left onto
    // Pearl St", "Head southeast on Oak Pl", "Slight right to stay on US-36". Strip the
    // maneuver prefix and keep the road (the text after the LAST connector). If there's no
    // connector (e.g. "Arrive at your destination"), keep the whole thing.
    private val ROAD_CONNECTORS = listOf(" onto ", " to stay on ", " on ", " toward ", " towards ")

    private fun extractRoad(instruction: String): String {
        var bestIdx = -1
        var road = instruction
        for (c in ROAD_CONNECTORS) {
            val idx = instruction.lastIndexOf(c, ignoreCase = true)
            if (idx > bestIdx) {
                bestIdx = idx
                road = instruction.substring(idx + c.length)
            }
        }
        return road.trim()
    }

    // Standard USPS-style abbreviations. The dash's road field is narrow and its font is
    // proportional, so long names clip — abbreviating the directional prefix + street-type
    // suffix (the longest, most compressible words) shortens most names without losing meaning.
    // Keyed by lowercased word; only whole words are replaced ("Westfield" is untouched).
    private val ROAD_ABBREV = mapOf(
        "north" to "N", "south" to "S", "east" to "E", "west" to "W",
        "northeast" to "NE", "northwest" to "NW", "southeast" to "SE", "southwest" to "SW",
        "street" to "St", "avenue" to "Ave", "boulevard" to "Blvd", "drive" to "Dr",
        "road" to "Rd", "lane" to "Ln", "court" to "Ct", "place" to "Pl",
        "highway" to "Hwy", "parkway" to "Pkwy", "terrace" to "Ter", "circle" to "Cir",
        "trail" to "Trl", "route" to "Rte", "square" to "Sq", "expressway" to "Expy",
        "freeway" to "Fwy", "turnpike" to "Tpke", "crossing" to "Xing", "junction" to "Jct",
    )

    fun abbreviateRoad(road: String): String =
        road.split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                ROAD_ABBREV[word.lowercase(Locale.US).trimEnd('.')] ?: word
            }

    /**
     * @return a [NavUpdate], or null if the notification carries nothing usable (so the
     *         caller can drop non-nav / empty frames).
     */
    fun parse(extras: Bundle, classifier: ManeuverClassifier): NavUpdate? {
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val maneuver = classifier.classify(title, text, subText)
        val distance = parseDistanceToMeters(title)

        // Nothing to say: no recognizable maneuver AND no distance → not a nav frame we care about.
        if (maneuver == Maneuver.UNKNOWN && distance < 0) return null

        // Trip-level values live in the subText: "13 min · 4.6 mi · 11:55 ETA".
        //   distance token  → distance remaining to destination
        //   "N hr M min"     → time remaining on the trip
        val destMeters = parseDistanceToMeters(subText)
        val remainSec = parseRemainingSeconds(subText)

        // The instruction text lives in different fields by notification format:
        //   classic  → title="500 m", text="Turn left onto Pearl St"
        //   ProgressStyle (Live Updates) → title="Turn left onto Pearl St", text=null
        // Prefer whichever field actually carries the instruction.
        val instruction = (text?.takeIf { it.isNotBlank() } ?: title ?: "").trim()
        // The dash draws the direction from the icon, so its (short) text field should be
        // just the road name, not the whole sentence: "Turn left onto Pearl St" → "Pearl St".
        // Abbreviate first (fits most names within the dash's narrow field), then hard-cap +
        // trim trailing space as a last resort so we never send a mid-word clip with a dangling
        // space.
        val road = abbreviateRoad(extractRoad(instruction))
            .let { if (it.length > MAX_ROAD_LEN) it.take(MAX_ROAD_LEN).trimEnd() else it }

        return NavUpdate(
            maneuver = maneuver,
            nextRoad = road,
            distanceToTurnMeters = distance,
            distanceToDestinationMeters = destMeters,
            remainingTimeSec = remainSec,
            rawTitle = title,
            rawText = text,
            rawSubText = subText,
        )
    }

    // "13 min", "1 hr 5 min", "2 hr", "45 min" → seconds. -1 when neither hr nor min appears.
    private val HR_RE = Regex("""(\d+)\s*h(?:r|rs|our|ours)?\b""", RegexOption.IGNORE_CASE)
    private val MIN_RE = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)

    /** Parse the "N hr M min" trip-time token out of the Maps subText → total seconds. */
    fun parseRemainingSeconds(subText: String?): Int {
        if (subText.isNullOrBlank()) return -1
        val hr = HR_RE.find(subText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val min = MIN_RE.find(subText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (hr == 0 && min == 0) return -1
        return (hr * 60 + min) * 60
    }

    /**
     * "100 yd" / "0.2 mi" / "500 m" / "1.2 km" / "250 ft" → meters (Int).
     * Returns -1 when there's no parseable distance (empty title, or the "0 m" flicker at
     * nav start) so the caller can still forward the maneuver with a clamped distance.
     */
    fun parseDistanceToMeters(title: String?): Int {
        if (title.isNullOrBlank()) return -1
        val m = DISTANCE_RE.find(title) ?: return -1
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
        val meters = when (m.groupValues[2].lowercase(Locale.US)) {
            "mi" -> value * 1609.344
            "km" -> value * 1000.0
            "yd" -> value * 0.9144
            "ft" -> value * 0.3048
            "m" -> value
            else -> return -1
        }
        return meters.roundToInt()
    }
}
