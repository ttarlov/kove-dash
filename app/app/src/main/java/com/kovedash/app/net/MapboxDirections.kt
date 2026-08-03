package com.kovedash.app.net

import android.util.Log
import com.mapbox.geojson.Point
import com.kovedash.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mapbox Directions API v5 client. Single origin → single destination, driving profile.
 *
 * Motorcycles don't have a dedicated profile so [driving] is the right call (driving-traffic
 * adds live congestion data but burns quota; we don't need that for V1). Returns the full
 * GeoJSON LineString as a list of [Point] plus the total distance/duration so the UI can
 * surface ETA.
 */
object MapboxDirections {

    data class Route(
        val coords: List<Point>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        val steps: List<Step>,
    )

    /**
     * One turn-by-turn maneuver from the Directions API. [location] is the point at which
     * the maneuver happens (the corner you turn at); [distanceMeters] is the length of the
     * road segment leading INTO this maneuver. So progress tracking is "how far am I from
     * the next step's location" — not the current step's distance field.
     *
     * [type] is one of: depart, turn, arrive, fork, merge, roundabout, exit roundabout,
     * on ramp, off ramp, end of road, continue, new name, notification, rotary, exit
     * rotary, roundabout turn, use lane.
     * [modifier] is one of: left, right, straight, slight left, slight right, sharp left,
     * sharp right, uturn — null on depart/arrive/continue.
     */
    data class Step(
        val instruction: String,
        val type: String,
        val modifier: String?,
        val distanceMeters: Double,
        val location: Point,
    )

    suspend fun fetch(origin: Point, destination: Point): Route? = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_PUBLIC_TOKEN
        if (token.isBlank()) {
            Log.w(TAG, "no MAPBOX_PUBLIC_TOKEN at runtime — directions disabled")
            return@withContext null
        }
        val coords = "${origin.longitude()},${origin.latitude()};${destination.longitude()},${destination.latitude()}"
        val url = URL("https://api.mapbox.com/directions/v5/mapbox/driving/$coords?geometries=geojson&overview=full&steps=true&access_token=$token")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "directions http $code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (t: Throwable) {
            Log.e(TAG, "directions failed", t)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun parse(body: String): Route? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val routes = root.optJSONArray("routes") ?: return null
        if (routes.length() == 0) return null
        val r0 = routes.optJSONObject(0) ?: return null
        val geom = r0.optJSONObject("geometry") ?: return null
        val arr = geom.optJSONArray("coordinates") ?: return null
        val pts = ArrayList<Point>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONArray(i) ?: continue
            if (c.length() < 2) continue
            pts += Point.fromLngLat(c.getDouble(0), c.getDouble(1))
        }
        if (pts.isEmpty()) return null
        return Route(
            coords = pts,
            distanceMeters = r0.optDouble("distance", 0.0),
            durationSeconds = r0.optDouble("duration", 0.0),
            steps = parseSteps(r0),
        )
    }

    private fun parseSteps(routeJson: JSONObject): List<Step> {
        val legs = routeJson.optJSONArray("legs") ?: return emptyList()
        val out = ArrayList<Step>()
        for (li in 0 until legs.length()) {
            val leg = legs.optJSONObject(li) ?: continue
            val stepsArr = leg.optJSONArray("steps") ?: continue
            for (si in 0 until stepsArr.length()) {
                val s = stepsArr.optJSONObject(si) ?: continue
                val maneuver = s.optJSONObject("maneuver") ?: continue
                val loc = maneuver.optJSONArray("location") ?: continue
                if (loc.length() < 2) continue
                out += Step(
                    instruction = maneuver.optString("instruction", ""),
                    type = maneuver.optString("type", "continue"),
                    modifier = maneuver.optString("modifier", "").ifBlank { null },
                    distanceMeters = s.optDouble("distance", 0.0),
                    location = Point.fromLngLat(loc.getDouble(0), loc.getDouble(1)),
                )
            }
        }
        return out
    }

    private const val TAG = "KoveDash"
}
