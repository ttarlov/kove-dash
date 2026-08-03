package com.kovedash.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kovedash.app.BuildConfig

/**
 * Debug-only entry point to fire a hardcoded turn to the dash WITHOUT Google Maps, from adb:
 *
 *   adb shell am broadcast -n com.kovedash.app/.service.NavTestReceiver \
 *       --ei icon 3 --es road Pearl_Street --ei startM 850
 *
 * Underscores in `road` become spaces (adb arg-splitting hates spaces). Defaults reproduce
 * the fake-nav POC that rendered: icon=3 (right), "Pearl Street", 850 m counting down.
 *
 * SECURITY: this receiver is declared `exported=true` in the manifest so `adb` can reach it,
 * which also means any app on the device could broadcast to it. It is therefore HARD-GATED to
 * debug builds: [onReceive] returns immediately unless [BuildConfig.DEBUG] is set, so in a
 * release build it is an inert no-op and cannot drive the dash. Keep this guard first.
 */
class NavTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Debug-only backdoor — never act on a broadcast in a release build.
        if (!BuildConfig.DEBUG) return
        // Stop projection but keep BLE: `--ez stopproj true`
        if (intent.getBooleanExtra("stopproj", false)) {
            Log.i("KoveDash", "NavTestReceiver: stop projection (keep BLE)")
            DashService.stopProjection(context)
            return
        }
        // Altitude test: `--ez alt true [--ei altM 3000]`
        if (intent.getBooleanExtra("alt", false)) {
            val altM = intent.getIntExtra("altM", 3000)
            Log.i("KoveDash", "NavTestReceiver: pushing altitude=${altM}m")
            DashService.testAlt(context, altM)
            return
        }
        // Ride simulation: `--ez ride true [--el tickMs 1500] [--ei stepM 60]`
        if (intent.getBooleanExtra("ride", false)) {
            val tickMs = intent.getLongExtra("tickMs", 1500L)
            val stepM = intent.getIntExtra("stepM", 60)
            Log.i("KoveDash", "NavTestReceiver: simulated ride tick=${tickMs}ms step=${stepM}m")
            DashService.simRide(context, tickMs, stepM)
            return
        }
        val icon = intent.getIntExtra("icon", 3)
        val road = (intent.getStringExtra("road") ?: "Pearl_Street").replace('_', ' ')
        val startM = intent.getIntExtra("startM", 850)
        Log.i("KoveDash", "NavTestReceiver: firing icon=$icon road='$road' startM=$startM")
        DashService.testNav(context, icon, road, startM)
    }
}
