package com.kovedash.app.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Fetches current weather from Open-Meteo (free, no API key) to push to the dash.
 *
 * Resilient to thin/absent connectivity: [fetchCurrent] retries a few times, and if the
 * network is down (no cell service, satellite-only, or on the dash's no-internet AP) it
 * serves the last good reading (flagged stale) so the dash widget keeps showing weather
 * instead of dropping out. Weather drifts slowly, so a recent cached value is fine.
 */
object WeatherSource {

    private const val TAG = "KoveDash"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 1_500L
    private const val MAX_STALE_MS = 6 * 60 * 60 * 1000L // 6h — beyond this the cache is untrustworthy

    data class Weather(
        val tempF: Int,
        val windMph: Int,
        val dashCode: Int,
        /** True when this is a served-from-cache reading (a live fetch just failed). */
        val stale: Boolean = false,
    )

    // Last successful reading, kept so we can keep feeding the dash when the network drops
    // out. An `object` singleton, so the cache survives across connects for the app lifetime.
    @Volatile private var cached: Weather? = null
    @Volatile private var cachedAtMs: Long = 0L

    /**
     * Fetch current weather with a short retry loop, falling back to the last good reading.
     * Returns a fresh value on success (and caches it); on failure returns the cached value
     * flagged [Weather.stale] if it's still within [MAX_STALE_MS]; null only if we've never
     * gotten a reading (or the cache is too old to trust). Runs on the IO dispatcher.
     */
    suspend fun fetchCurrent(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        repeat(MAX_ATTEMPTS) { attempt ->
            fetchOnce(lat, lon)?.let { fresh ->
                cached = fresh
                cachedAtMs = System.currentTimeMillis()
                return@withContext fresh
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS * (attempt + 1))
        }
        val c = cached
        if (c != null && System.currentTimeMillis() - cachedAtMs <= MAX_STALE_MS) {
            Log.i(TAG, "weather: live fetch failed — serving cached reading (stale)")
            return@withContext c.copy(stale = true)
        }
        Log.w(TAG, "weather: live fetch failed and no usable cache")
        null
    }

    /** One network attempt. Blocking; call on IO. Null on any failure. */
    private fun fetchOnce(lat: Double, lon: Double): Weather? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                // Generous timeouts: high-latency links (e.g. satellite) can still land a
                // reading given a little patience.
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val cur = JSONObject(body).getJSONObject("current")
            Weather(
                tempF = cur.getDouble("temperature_2m").roundToInt(),
                windMph = cur.getDouble("wind_speed_10m").roundToInt(),
                dashCode = wmoToDash(cur.optInt("weather_code", 1)),
            )
        }.onFailure { Log.w(TAG, "weather fetch attempt failed", it) }.getOrNull()
    }

    /**
     * WMO weather code (Open-Meteo) → dash weather-glyph int. The glyph set was recovered from
     * the OEM app decompile (green_trip `MyAmapRouteActivity.f()` + `icon_weather_1..8` drawables):
     * codes 1..8 are a direct index into the 8 glyphs; anything <1 or >8 HIDES the widget.
     *   1 = sun (clear)          5 = sun-behind-cloud (partly cloudy)
     *   2 = cloud + rain         6 = cloud + lightning (thunderstorm)
     *   3 = plain cloud          7 = dark cloud (heavy overcast)
     *   4 = snowflake            8 = cloud + fog lines (fog/haze)
     * (Pending a dash 1..8 probe sweep to confirm the firmware mirrors the app icons 1:1.)
     */
    private fun wmoToDash(wmo: Int): Int = when (wmo) {
        0, 1 -> 1              // clear / mainly clear → sun
        2 -> 5                 // partly cloudy → sun behind cloud
        3 -> 3                 // overcast → cloud
        in 45..48 -> 8         // fog → fog glyph
        in 51..67 -> 2         // drizzle / freezing rain → rain
        in 71..77 -> 4         // snow → snowflake
        in 80..82 -> 2         // rain showers → rain
        in 85..86 -> 4         // snow showers → snowflake
        in 95..99 -> 6         // thunderstorm → lightning
        else -> 3              // unknown → plain cloud (visible, non-alarming)
    }
}
