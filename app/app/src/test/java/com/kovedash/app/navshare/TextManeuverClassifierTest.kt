package com.kovedash.app.navshare

import org.junit.Assert.assertEquals
import org.junit.Test

class TextManeuverClassifierTest {

    private val c = TextManeuverClassifier()

    private fun classify(text: String) = c.classify(null, text, null)

    @Test
    fun bare_turns() {
        assertEquals(Maneuver.TURN_LEFT, classify("Turn left onto Pearl St"))
        assertEquals(Maneuver.TURN_RIGHT, classify("Turn right onto Broadway"))
    }

    @Test
    fun compound_before_bare() {
        // The whole point of the ordering: "slight left" must not fall through to TURN_LEFT.
        assertEquals(Maneuver.SLIGHT_LEFT, classify("Slight left to stay on US-36"))
        assertEquals(Maneuver.SLIGHT_RIGHT, classify("Slight right onto the ramp"))
        assertEquals(Maneuver.SHARP_LEFT, classify("Sharp left onto Canyon Blvd"))
        assertEquals(Maneuver.SHARP_RIGHT, classify("Sharp right ahead"))
        assertEquals(Maneuver.KEEP_LEFT, classify("Keep left at the fork"))
        assertEquals(Maneuver.KEEP_RIGHT, classify("Keep right to continue"))
    }

    @Test
    fun special_maneuvers() {
        assertEquals(Maneuver.UTURN, classify("Make a U-turn"))
        assertEquals(Maneuver.ROUNDABOUT, classify("At the roundabout, take the 2nd exit"))
        assertEquals(Maneuver.MERGE, classify("Merge onto I-25 N"))
        assertEquals(Maneuver.OFF_RAMP, classify("Take the exit toward Denver"))
        assertEquals(Maneuver.ARRIVE, classify("Arrive at your destination"))
    }

    @Test
    fun continue_and_unknown() {
        assertEquals(Maneuver.CONTINUE, classify("Continue straight"))
        assertEquals(Maneuver.CONTINUE, classify("Head north on Main St"))
        assertEquals(Maneuver.UNKNOWN, classify(""))
        assertEquals(Maneuver.UNKNOWN, classify("Recalculating"))
    }

    @Test
    fun dash_icon_mapping() {
        assertEquals(2, Maneuver.TURN_LEFT.dashIcon())
        assertEquals(3, Maneuver.TURN_RIGHT.dashIcon())
        assertEquals(8, Maneuver.UTURN.dashIcon())
        assertEquals(9, Maneuver.UNKNOWN.dashIcon())
        assertEquals(9, Maneuver.CONTINUE.dashIcon())
        assertEquals(21, Maneuver.ARRIVE.dashIcon())
        assertEquals(31, Maneuver.ROUNDABOUT.dashIcon())
    }
}
