package com.kovedash.app.navshare

import android.content.Context
import android.util.Log
import com.kovedash.app.AppHost
import com.kovedash.app.net.MapboxGeocoder
import com.kovedash.app.service.ConnectionPhase
import com.kovedash.app.service.DashService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pushes the rider's CURRENT street to the dash's msg_id=7 location line WHILE NAVIGATING, so
 * the otherwise-empty notification area shows "the street you're on now" next to the turn card.
 * This is the OEM's actual use of msg_id=7 (reverse-geocoded current street, refreshed as you
 * move) — see docs/re/dash_render_map.md.
 *
 * A single long-lived loop: every [TICK_MS] it checks nav-active + connected, and if the rider
 * has moved at least [MIN_MOVE_M] since the last geocode it reverse-geocodes the fix and pushes
 * the street — but only when it CHANGED (the dash holds the last value, and identical sends are
 * a no-op). Geocoding needs network; no fix / no network / no result simply skips a tick.
 */
object StreetForwarder {

    private const val TAG = "KoveDash"
    private val USABLE = setOf(
        ConnectionPhase.READY,
        ConnectionPhase.PROJECTING,
        ConnectionPhase.DEVICE_DIALED,
    )
    private const val TICK_MS = 5_000L   // re-evaluate cadence (also paces the reverse-geocode)
    private const val MIN_MOVE_M = 40.0  // don't re-geocode until we've moved this far (idle guard)

    private var job: Job? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastLat = Double.NaN
            var lastLon = Double.NaN
            var lastStreet: String? = null
            while (isActive) {
                delay(TICK_MS)
                if (!NavForwarder.navSessionActive) {
                    lastStreet = null   // fresh start next nav session
                    continue
                }
                if (AppHost.state.value.phase !in USABLE) continue
                val fix = AppHost.gps.value ?: continue
                if (!lastLat.isNaN() && haversineMeters(lastLat, lastLon, fix.lat, fix.lon) < MIN_MOVE_M) {
                    continue // stationary / barely moved — skip the network call
                }
                val street = runCatching { MapboxGeocoder.reverse(fix.lat, fix.lon) }.getOrNull() ?: continue
                lastLat = fix.lat
                lastLon = fix.lon
                if (street == lastStreet) continue
                lastStreet = street
                Log.i(TAG, "street-fwd: current street → '$street'")
                DashService.sendStreet(ctx, street)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
