package com.kovedash.app.navshare

import org.junit.Assert.assertEquals
import org.junit.Test

class NavNotificationParserTest {

    @Test
    fun distance_units() {
        assertEquals(91, NavNotificationParser.parseDistanceToMeters("100 yd"))   // 100 * 0.9144
        assertEquals(322, NavNotificationParser.parseDistanceToMeters("0.2 mi"))  // 0.2 * 1609.344
        assertEquals(500, NavNotificationParser.parseDistanceToMeters("500 m"))
        assertEquals(1200, NavNotificationParser.parseDistanceToMeters("1.2 km"))
        assertEquals(76, NavNotificationParser.parseDistanceToMeters("250 ft"))   // 250 * 0.3048
    }

    @Test
    fun distance_thousands_separator() {
        assertEquals(1000, NavNotificationParser.parseDistanceToMeters("1,000 m"))
    }

    @Test
    fun distance_missing_or_flicker_returns_sentinel() {
        assertEquals(-1, NavNotificationParser.parseDistanceToMeters(""))
        assertEquals(-1, NavNotificationParser.parseDistanceToMeters(null))
        assertEquals(-1, NavNotificationParser.parseDistanceToMeters("ETA 11:55"))
        assertEquals(0, NavNotificationParser.parseDistanceToMeters("0 m"))
    }

    @Test
    fun subtext_destination_distance() {
        // First distance token in the subText is the distance-to-destination.
        assertEquals(7403, NavNotificationParser.parseDistanceToMeters("13 min · 4.6 mi · 11:55 ETA"))
        assertEquals(152, NavNotificationParser.parseDistanceToMeters("2 min · 500 ft"))
        // "min" must NOT be misread as a distance unit (mi / m).
        assertEquals(-1, NavNotificationParser.parseDistanceToMeters("13 min"))
    }

    @Test
    fun destination_meters_from_progress() {
        // progressMax = total route meters, progress = meters travelled → remaining = max - progress.
        assertEquals(7315, NavNotificationParser.destinationMetersFromProgress(0, 7315))     // at start
        assertEquals(5315, NavNotificationParser.destinationMetersFromProgress(2000, 7315))  // mid-route
        assertEquals(0, NavNotificationParser.destinationMetersFromProgress(7315, 7315))     // arrived
    }

    @Test
    fun destination_meters_from_progress_rejects_unusable() {
        assertEquals(-1, NavNotificationParser.destinationMetersFromProgress(0, 0))       // no route yet
        assertEquals(-1, NavNotificationParser.destinationMetersFromProgress(0, -5))      // bad max
        assertEquals(-1, NavNotificationParser.destinationMetersFromProgress(-10, 7315))  // bad progress
        assertEquals(-1, NavNotificationParser.destinationMetersFromProgress(8000, 7315)) // progress > max (reroute)
    }

    @Test
    fun road_abbreviation() {
        assertEquals("W Pennsylvania Ave", NavNotificationParser.abbreviateRoad("West Pennsylvania Avenue"))
        assertEquals("N Broadway St", NavNotificationParser.abbreviateRoad("North Broadway Street"))
        assertEquals("SE Marine Dr", NavNotificationParser.abbreviateRoad("Southeast Marine Drive"))
        // whole-word only — "Westfield" and "Streetsboro" must survive untouched
        assertEquals("Westfield Rd", NavNotificationParser.abbreviateRoad("Westfield Road"))
        assertEquals("Streetsboro Blvd", NavNotificationParser.abbreviateRoad("Streetsboro Boulevard"))
        // already-short / highway names pass through
        assertEquals("US-36", NavNotificationParser.abbreviateRoad("US-36"))
    }

    @Test
    fun subtext_remaining_time() {
        assertEquals(13 * 60, NavNotificationParser.parseRemainingSeconds("13 min · 4.6 mi · 11:55 ETA"))
        assertEquals((60 + 5) * 60, NavNotificationParser.parseRemainingSeconds("1 hr 5 min · 62 mi"))
        assertEquals(2 * 3600, NavNotificationParser.parseRemainingSeconds("2 hr · 120 mi"))
        assertEquals(-1, NavNotificationParser.parseRemainingSeconds("4.6 mi"))
        assertEquals(-1, NavNotificationParser.parseRemainingSeconds(""))
        assertEquals(-1, NavNotificationParser.parseRemainingSeconds(null))
    }
}
