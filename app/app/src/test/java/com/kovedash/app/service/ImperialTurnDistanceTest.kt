package com.kovedash.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the turn-distance imperial scaling. It is MILES-ONLY: the old feet↔miles switch made
 * "600 m" mean both 0.6 mi (far) and 600 ft (close), with a jump at the boundary. Miles-only is
 * monotonic and collision-free — far turns read right ("4.5 km" = 4.5 mi), close turns are a
 * distinct small value, and the number never repeats for two different distances.
 */
class ImperialTurnDistanceTest {

    /** The dash shows raw<1000 as "N m"; raw>=1000 as "raw/1000 km". */
    private fun dashReads(raw: Int): String =
        if (raw < 1000) "$raw m" else String.format(java.util.Locale.US, "%.1f km", raw / 1000.0)

    @Test
    fun far_turn_reads_as_miles() {
        // 4.5 mi ≈ 7242 m → ~4500 → "4.5 km" = 4.5 mi (not the old feet-overflow "23.7 km").
        assertEquals("4.5 km", dashReads(DashService.imperialTurnRaw(7242)))
        // 1.0 mi ≈ 1609 m → ~1000 → "1.0 km".
        assertEquals("1.0 km", dashReads(DashService.imperialTurnRaw(1609)))
    }

    @Test
    fun no_feet_miles_collision() {
        // The reported bug: 0.6 mi and 600 ft both rendered "600 m". Miles-only separates them.
        val sixTenthsMile = DashService.imperialTurnRaw(966)   // 0.6 mi
        val sixHundredFeet = DashService.imperialTurnRaw(183)  // 600 ft
        assertEquals(600, sixTenthsMile)                        // 0.6 mi → "600 m"
        assertTrue("600 ft must NOT also be 600", sixHundredFeet != 600)
        assertEquals(114, sixHundredFeet)                       // 600 ft → "114 m", distinct
    }

    @Test
    fun monotonic_down_to_the_turn() {
        // Approaching a turn (distance shrinking), the raw value must never increase — no jump.
        var prev = Int.MAX_VALUE
        var m = 8000
        while (m >= 0) {
            val raw = DashService.imperialTurnRaw(m)
            assertTrue("non-monotonic at m=$m: $raw > $prev", raw <= prev)
            prev = raw
            m -= 25
        }
    }
}
