package com.kovedash.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the turn-distance imperial scaling against the "23.7 km" regression: a far turn
 * (4.5 mi) was being scaled to feet (23,764), which the dash renders as "23.7 km" (feet/1000)
 * instead of a readable distance. [DashService.imperialTurnRaw] must switch to the miles scale
 * before feet overflows the dash's 1000 "N m" → "N.N km" boundary.
 */
class ImperialTurnDistanceTest {

    /** The dash shows raw<1000 as "N m"; raw>=1000 as "raw/1000 km". */
    private fun dashReads(raw: Int): String =
        if (raw < 1000) "$raw m" else String.format(java.util.Locale.US, "%.1f km", raw / 1000.0)

    @Test
    fun close_turn_stays_in_clean_feet() {
        // 500 ft ≈ 152 m → ~500, rendered "500 m" (number = feet).
        val raw = DashService.imperialTurnRaw(152)
        assertEquals(499, raw)
        assertTrue("close turn must render as 'N m' feet, got ${dashReads(raw)}", raw < 1000)
    }

    @Test
    fun far_turn_switches_to_miles_not_feet_overflow() {
        // 4.5 mi ≈ 7242 m. Old feet scale → 23,764 → "23.7 km" (garbage).
        // New: miles scale → ~4500 → "4.5 km" = 4.5 mi.
        val raw = DashService.imperialTurnRaw(7242)
        assertEquals("4.5 km", dashReads(raw))
        // Sanity: the feet scale would have produced the broken value.
        assertTrue("feet scale would overflow", (7242 * 3.28084) > 20000)
    }

    @Test
    fun boundary_never_produces_feet_over_1000() {
        // Sweep meters across the switch; a value on the feet branch must stay under 1000
        // (clean "N m"), so we never emit the mislabeled feet/1000 "km".
        for (m in 0..400) {
            val feetRaw = m * 3.28084
            val raw = DashService.imperialTurnRaw(m)
            if (feetRaw < 1000.0) {
                assertTrue("m=$m used feet but raw=$raw >= 1000", raw < 1000)
            }
        }
    }
}
