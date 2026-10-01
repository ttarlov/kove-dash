package com.kovedash.app.navshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Distance-adaptive nav cadence: the update interval tightens as the turn nears, but never
 * drops below the link's multi-frame reassembly floor (1.5s broke it; 4s is safe). Guards that
 * the close interval stays at/above that floor and that cadence is monotonic by distance.
 */
class NavCadenceTest {

    @Test
    fun close_is_fast_far_is_slow() {
        assertEquals(1500L, NavForwarder.throttleMsForMeters(100))   // ~330 ft
        assertEquals(1500L, NavForwarder.throttleMsForMeters(305))   // ~1000 ft (boundary)
        assertEquals(2500L, NavForwarder.throttleMsForMeters(500))   // ~0.3 mi
        assertEquals(4000L, NavForwarder.throttleMsForMeters(1609))  // 1 mi
        assertEquals(4000L, NavForwarder.throttleMsForMeters(8000))  // far
    }

    @Test
    fun never_below_reassembly_floor() {
        // 1.5s was already too fast per the RE notes; nothing we emit may go under it.
        for (m in 0..10_000 step 20) {
            assertTrue("m=$m cadence under floor", NavForwarder.throttleMsForMeters(m) >= 1500L)
        }
    }

    @Test
    fun cadence_never_slower_as_turn_nears() {
        // Approaching (distance shrinking), the interval must be non-increasing.
        var prev = 0L
        var m = 0
        while (m <= 10_000) {
            val c = NavForwarder.throttleMsForMeters(m)
            assertTrue("cadence dropped then rose at m=$m", c >= prev)
            prev = c
            m += 20
        }
    }
}
