package com.kovedash.app.navshare

import android.content.Context
import android.util.Log
import com.kovedash.app.AppHost
import com.kovedash.app.service.ConnectionPhase
import com.kovedash.app.service.DashService

/**
 * Thin coordinator between the notification listener and the BLE send path. Holds no BLE
 * reference — it gates on connection state and fires [DashService] intents, so every send
 * inherits the single BLE owner + mutex serialization in DashBleClient.
 *
 * The two seams to the rest of the app: reads [AppHost.state] (phase gate), calls the
 * [DashService] static helpers (write). Nothing else.
 */
object NavForwarder {

    private const val TAG = "KoveDash"

    // Phases where the dash link is up and can render a native widget.
    private val USABLE = setOf(
        ConnectionPhase.READY,
        ConnectionPhase.PROJECTING,
        ConnectionPhase.DEVICE_DIALED,
    )

    // The dash renders native turn-by-turn ONLY if each multi-frame update lands on a QUIET
    // BLE link — proven cadence is ~4s between updates (1.5s re-congests the transparent
    // link and reassembly breaks; see memory kove_dash_wifi_activates_rendering). Google Maps
    // re-posts the same instruction 5-6× in a few seconds, so we MUST pace it:
    //   - a NEW maneuver (turn type or road changed) forwards IMMEDIATELY — turns matter.
    //   - a distance-only update on the SAME maneuver is throttled to THROTTLE_MS.
    private const val THROTTLE_MS = 4000L
    private var lastManeuverKey: String? = null
    private var lastDistBucket = -1
    private var lastForwardMs = 0L

    // Largest distance-to-destination seen this nav session ≈ the total route length. The Maps
    // notification only ever reports REMAINING distance, so we infer the total from its peak
    // (captured at the start when it's biggest; bumped up if a reroute makes remaining grow).
    // retain_rate = percent of route travelled = (1 - remaining/total) * 100. Reset on onEnded.
    private var routeMaxMeters = -1

    fun onUpdate(ctx: Context, update: NavUpdate) {
        val phase = AppHost.state.value.phase
        if (phase !in USABLE) {
            Log.i(TAG, "navshare: drop — dash not usable (phase=$phase)")
            return
        }

        val icon = update.maneuver.dashIcon()
        val curMeters = update.distanceToTurnMeters.coerceAtLeast(0) // clamp -1 flicker to 0
        val destMeters = update.distanceToDestinationMeters
        if (destMeters > 0 && destMeters > routeMaxMeters) routeMaxMeters = destMeters
        val retainRate = if (routeMaxMeters > 0 && destMeters in 0..routeMaxMeters) {
            (((routeMaxMeters - destMeters).toLong() * 100L) / routeMaxMeters).toInt().coerceIn(0, 100)
        } else -1
        val maneuverKey = "$icon|${update.nextRoad}"
        val distBucket = curMeters / 25
        val now = elapsedRealtime()
        val maneuverChanged = maneuverKey != lastManeuverKey

        if (!maneuverChanged) {
            if (distBucket == lastDistBucket) {
                return // identical — pure dedup, no log spam
            }
            if (now - lastForwardMs < THROTTLE_MS) {
                Log.i(TAG, "navshare: throttled (same maneuver, ${now - lastForwardMs}ms < ${THROTTLE_MS}ms)")
                return
            }
        }

        lastManeuverKey = maneuverKey
        lastDistBucket = distBucket
        lastForwardMs = now
        val why = if (maneuverChanged) "maneuver-change" else "distance-tick"
        Log.i(TAG, "navshare forward ($why): icon=$icon road='${update.nextRoad}' curM=$curMeters " +
            "destM=$destMeters remainS=${update.remainingTimeSec} retain%=$retainRate (${update.maneuver})")
        DashService.forwardTbt(
            ctx, icon, update.nextRoad, curMeters,
            destMeters, update.remainingTimeSec, retainRate,
        )
    }

    fun onEnded(ctx: Context) {
        Log.i(TAG, "navshare forward: nav ended → endNavi")
        lastManeuverKey = null
        lastDistBucket = -1
        lastForwardMs = 0L
        routeMaxMeters = -1
        DashService.endTbt(ctx)
    }

    // SystemClock.elapsedRealtime() indirection kept trivial so the object stays unit-testable
    // and free of a hard android.os import at call sites.
    private fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}
