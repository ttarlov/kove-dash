package com.kovedash.app.net

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Fetches current weather from Open-Meteo (free, no API key) to push to the dash on
 * connect. Called on the IO dispatcher; best-effort — returns null on any failure.
 *
 * Note: only works when the phone has real internet (BLE-only mode). On the projection
 * path the phone is on the dash's no-internet AP, so the fetch fails and weather is simply
 * not (re)sent — it was already pushed during BLE-only startup.
 */
object WeatherSource {

    private const val TAG = "KoveDash"

    data class Weather(val tempF: Int, val windMph: Int, val dashCode: Int)

    fun fetch(lat: Double, lon: Double): Weather? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
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
        }.onFailure { Log.w(TAG, "weather fetch failed", it) }.getOrNull()
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
