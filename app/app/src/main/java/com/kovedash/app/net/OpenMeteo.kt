package com.kovedash.app.net

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Open-Meteo is a free, keyless weather API. Pulls current conditions for a lat/lon and
 * normalizes them into a shape we can push to the dash. We use Fahrenheit + mph by
 * default since the rider is US-based; trivially swappable.
 *
 * Weather code is WMO (0=clear, 1-3=cloud levels, 45/48=fog, 51-67=rain variants,
 * 71-77=snow, 80-82=showers, 95-99=thunder). The dash expects its own integer icon
 * code — we map WMO → dash-icon-guess. Real mapping has to come from observation
 * (the OEM resource files don't enumerate the dash icons we have access to).
 */
object OpenMeteo {

    data class Current(
        val tempFahrenheit: Double,
        val windMph: Double,
        val wmoCode: Int,
    )

    suspend fun fetch(lat: Double, lon: Double): Current? = withContext(Dispatchers.IO) {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,wind_speed_10m" +
                "&temperature_unit=fahrenheit&wind_speed_unit=mph"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "open-meteo http $code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (t: Throwable) {
            Log.e(TAG, "open-meteo failed", t)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun parse(body: String): Current? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val cur = root.optJSONObject("current") ?: return null
        return Current(
            tempFahrenheit = cur.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null,
            windMph = cur.optDouble("wind_speed_10m", 0.0),
            wmoCode = cur.optInt("weather_code", 0),
        )
    }

    /**
     * Best-guess WMO → dash icon code mapping. Will need tuning once we see what the
     * dash actually renders for each integer value. Bucketed to the canonical glyph
     * set common across automotive dashes: clear / cloudy / rain / snow / thunder.
     */
    fun toDashIcon(wmo: Int): Int = when (wmo) {
        0 -> 0
        in 1..3 -> 1
        45, 48 -> 2
        in 51..67, in 80..82 -> 3
        in 71..77, 85, 86 -> 4
        in 95..99 -> 5
        else -> 1
    }

    fun formatTemp(tempF: Double): String = "${tempF.roundToInt()}°F"
    fun formatWind(mph: Double): String = "${mph.roundToInt()}mph"

    private const val TAG = "KoveDash"
}
