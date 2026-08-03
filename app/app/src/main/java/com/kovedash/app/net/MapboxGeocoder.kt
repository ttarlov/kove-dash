package com.kovedash.app.net

import android.util.Log
import com.kovedash.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Mapbox Search Box API client (`/search/searchbox/v1/`). Two-phase:
 *   1. [suggest] — typeahead, returns suggestion objects with a `mapboxId` but no coords.
 *   2. [retrieve] — fetches coords for a chosen `mapboxId`.
 *
 * Billing is session-based: one `session_token` UUID covers all `/suggest` calls + one
 * `/retrieve` within a 2-minute window. The caller owns the token (FullscreenSearch
 * regenerates one per search-overlay open).
 *
 * Migrated 2026-05-26 from `/search/geocode/v6/forward`. Reason: v6 forward doesn't
 * return POI results at all — searching "coffee" surfaced "Coffeeville, MS" instead
 * of nearby coffee shops. SearchBox supports `types=poi`.
 *
 * Free tier covers 2,500 sessions/month; for a single-user motorcycle app this is
 * deep in free territory.
 */
object MapboxGeocoder {

    /**
     * One typeahead result. [mapboxId] is the opaque token needed by [retrieve] —
     * coordinates are NOT included in suggest responses, only in retrieve responses.
     * [featureType] is one of `poi`, `address`, `place`, `locality`, `neighborhood`,
     * `street`, `country`, `region`, `postcode`, `district` — useful for icon/styling.
     */
    data class Suggestion(
        val mapboxId: String,
        val name: String,
        val context: String,
        val featureType: String,
    )

    /** Coordinates + canonical name/context for a chosen Suggestion. */
    data class RetrievedFeature(
        val name: String,
        val context: String,
        val lon: Double,
        val lat: Double,
    )

    suspend fun suggest(
        query: String,
        sessionToken: String,
        proximity: GpsFix?,
    ): List<Suggestion> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        val token = BuildConfig.MAPBOX_PUBLIC_TOKEN
        if (token.isBlank()) {
            Log.w(TAG, "no MAPBOX_PUBLIC_TOKEN at runtime — search disabled")
            return@withContext emptyList()
        }
        val q = URLEncoder.encode(query, "UTF-8")
        val prox = proximity?.let { "&proximity=${it.lon},${it.lat}" } ?: ""
        // POI first for "where to" intent. address+place+locality+neighborhood+street
        // cover the rest. Country/region/postcode/district intentionally omitted —
        // too broad to be navigation targets.
        val types = "&types=poi,address,place,locality,neighborhood,street"
        val url = URL(
            "https://api.mapbox.com/search/searchbox/v1/suggest" +
                "?q=$q$prox$types&language=en&limit=8" +
                "&session_token=$sessionToken&access_token=$token"
        )
        httpGet(url, "suggest q='$query'", ::parseSuggestions) ?: emptyList()
    }

    suspend fun retrieve(
        mapboxId: String,
        sessionToken: String,
    ): RetrievedFeature? = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_PUBLIC_TOKEN
        if (token.isBlank()) return@withContext null
        val id = URLEncoder.encode(mapboxId, "UTF-8")
        val url = URL(
            "https://api.mapbox.com/search/searchbox/v1/retrieve/$id" +
                "?session_token=$sessionToken&access_token=$token"
        )
        httpGet(url, "retrieve id='$mapboxId'", ::parseRetrieve)
    }

    private fun <T> httpGet(url: URL, tag: String, parse: (String) -> T?): T? {
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 7_000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "searchbox http $code for $tag")
                return null
            }
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Log.e(TAG, "searchbox failed for $tag", t)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun parseSuggestions(body: String): List<Suggestion> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("suggestions") ?: return emptyList()
        val out = ArrayList<Suggestion>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val mapboxId = s.optString("mapbox_id")
            if (mapboxId.isBlank()) continue
            val name = s.optString("name").ifBlank { s.optString("name_preferred") }
            if (name.isBlank()) continue
            val ctx = s.optString("place_formatted").ifBlank { s.optString("full_address") }
            val featureType = s.optString("feature_type", "")
            out += Suggestion(
                mapboxId = mapboxId,
                name = name,
                context = ctx,
                featureType = featureType,
            )
        }
        return out
    }

    private fun parseRetrieve(body: String): RetrievedFeature? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val features = root.optJSONArray("features") ?: return null
        if (features.length() == 0) return null
        val f = features.optJSONObject(0) ?: return null
        val props = f.optJSONObject("properties") ?: return null
        val name = props.optString("name")
        if (name.isBlank()) return null
        val ctx = props.optString("full_address").ifBlank { props.optString("place_formatted") }
        val geom = f.optJSONObject("geometry") ?: return null
        val coords = geom.optJSONArray("coordinates") ?: return null
        if (coords.length() < 2) return null
        return RetrievedFeature(
            name = name,
            context = ctx,
            lon = coords.getDouble(0),
            lat = coords.getDouble(1),
        )
    }

    private const val TAG = "KoveDash"
}
