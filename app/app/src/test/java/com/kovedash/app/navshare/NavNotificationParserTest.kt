package com.kovedash.app.navshare

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

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

    @Test
    fun arrival_clock_minutes() {
        // 24-hour ("Arrive 15:15") — the current Maps ProgressStyle format.
        assertEquals(15 * 60 + 15, NavNotificationParser.parseArrivalClockMinutes("Arrive 15:15"))
        // 12-hour with AM/PM.
        assertEquals(15 * 60 + 15, NavNotificationParser.parseArrivalClockMinutes("Arrive 3:15 PM"))
        assertEquals(9 * 60 + 5, NavNotificationParser.parseArrivalClockMinutes("Arrive 9:05 AM"))
        assertEquals(0, NavNotificationParser.parseArrivalClockMinutes("Arrive 12:00 AM"))   // midnight
        assertEquals(12 * 60 + 30, NavNotificationParser.parseArrivalClockMinutes("Arrive 12:30 PM")) // noon+30
        // Classic subtext still yields its ETA clock (parse() only reaches here when the
        // duration token is absent, so this documents the fallback, not a regression).
        assertEquals(11 * 60 + 55, NavNotificationParser.parseArrivalClockMinutes("13 min · 4.6 mi · 11:55 ETA"))
    }

    @Test
    fun arrival_clock_minutes_rejects_garbage() {
        assertEquals(-1, NavNotificationParser.parseArrivalClockMinutes("Arrive at your destination"))
        assertEquals(-1, NavNotificationParser.parseArrivalClockMinutes(""))
        assertEquals(-1, NavNotificationParser.parseArrivalClockMinutes(null))
        assertEquals(-1, NavNotificationParser.parseArrivalClockMinutes("Arrive 10:75")) // bad minute
        assertEquals(-1, NavNotificationParser.parseArrivalClockMinutes("Arrive 25:00")) // bad 24h hour
        assertEquals(-1, NavNotificationParser.parseArrivalClockMinutes("Arrive 13:00 PM")) // bad 12h hour
    }

    @Test
    fun seconds_until_clock() {
        val z = ZoneOffset.UTC
        val now = Instant.parse("2026-08-05T14:00:00Z").toEpochMilli()
        // 15:15 is 1h15m ahead of 14:00.
        assertEquals(75 * 60, NavNotificationParser.secondsUntilClockMinutes(15 * 60 + 15, now, z))
        // 09:00 already passed today → tomorrow 09:00 = 19h ahead.
        assertEquals(19 * 3600, NavNotificationParser.secondsUntilClockMinutes(9 * 60, now, z))
    }

    @Test
    fun seconds_until_clock_just_passed_is_now_not_tomorrow() {
        val z = ZoneOffset.UTC
        val now = Instant.parse("2026-08-05T14:00:00Z").toEpochMilli()
        // Same minute → ~0, must NOT roll to +24h.
        assertEquals(0, NavNotificationParser.secondsUntilClockMinutes(14 * 60, now, z))
        // 2 min past (within the 10-min grace) → clamped to 0, still today.
        assertEquals(0, NavNotificationParser.secondsUntilClockMinutes(13 * 60 + 58, now, z))
        // 15 min past (beyond grace) → tomorrow 13:45 = 23h45m ahead.
        assertEquals((23 * 60 + 45) * 60, NavNotificationParser.secondsUntilClockMinutes(13 * 60 + 45, now, z))
    }

    @Test
    fun seconds_until_clock_crosses_midnight() {
        val z = ZoneOffset.UTC
        val now = Instant.parse("2026-08-05T23:50:00Z").toEpochMilli()
        // Arriving 00:05 (tomorrow) is 15 min away.
        assertEquals(15 * 60, NavNotificationParser.secondsUntilClockMinutes(5, now, z))
    }
}
