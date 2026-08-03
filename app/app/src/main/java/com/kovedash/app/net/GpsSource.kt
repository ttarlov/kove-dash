package com.kovedash.app.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Wraps the FusedLocationProviderClient and emits [GpsFix]es at 1Hz with sub-500ms
 * minimum interval (so high-rate phones can ship faster fixes). Sender is responsible
 * for permission checks at app-launch time — this class no-ops silently if
 * ACCESS_FINE_LOCATION isn't granted at start() time.
 */
class GpsSource(private val context: Context) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var callback: LocationCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onFix: (GpsFix) -> Unit) {
        if (callback != null) {
            Log.i(TAG, "GpsSource.start: already running")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "GpsSource.start: ACCESS_FINE_LOCATION not granted, ignoring")
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2_000L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onFix(
                    GpsFix(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                        speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                        tsMillis = loc.time,
                    )
                )
            }
        }
        callback = cb
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        Log.i(TAG, "GpsSource started @ 1Hz HIGH_ACCURACY")
    }

    fun stop() {
        callback?.let {
            client.removeLocationUpdates(it)
            Log.i(TAG, "GpsSource stopped")
        }
        callback = null
    }

    companion object {
        private const val TAG = "KoveDash"
    }
}

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val bearingDeg: Double?,
    val speedMps: Double?,
    val accuracyMeters: Double?,
    val altitudeMeters: Double?,
    val tsMillis: Long,
)
