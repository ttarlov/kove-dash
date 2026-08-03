package com.kovedash.app.nav

import android.util.Xml
import com.mapbox.geojson.Point
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Minimal GPX reader. Pulls the course geometry out of a .gpx file with Android's
 * built-in [XmlPullParser] (no dependency).
 *
 * GPX has three point containers:
 *   - `<trk><trkseg><trkpt>` — recorded tracks, usually dense (what adventure exports use)
 *   - `<rte><rtept>`         — planned routes, sparser
 *   - `<wpt>`                — standalone waypoints
 *
 * We collect every `<trkpt>` and `<rtept>` in document order (tracks are strongly
 * preferred; if a file has both, the track wins). Waypoints are ignored for the line.
 * lat/lon come from the element attributes; that's all we need to draw the course.
 */
object GpxParser {

    data class Course(
        val name: String?,
        val trackPoints: List<Point>,
        val routePoints: List<Point>,
    ) {
        /** The line to draw: the recorded track if present, else the planned route. */
        val coords: List<Point> get() = if (trackPoints.size >= 2) trackPoints else routePoints
    }

    fun parse(input: InputStream): Course? {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val trackPoints = mutableListOf<Point>()
        val routePoints = mutableListOf<Point>()
        var name: String? = null
        var inName = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "trkpt" -> readPoint(parser)?.let { trackPoints.add(it) }
                    "rtept" -> readPoint(parser)?.let { routePoints.add(it) }
                    // The first <name> we hit (track/route/metadata name) labels the course.
                    "name" -> if (name == null) inName = true
                }
                XmlPullParser.TEXT -> if (inName) {
                    name = parser.text?.trim()?.takeIf { it.isNotBlank() }
                }
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "name") inName = false
            }
            event = parser.next()
        }

        if (trackPoints.size < 2 && routePoints.size < 2) return null
        return Course(name, trackPoints, routePoints)
    }

    private fun readPoint(parser: XmlPullParser): Point? {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: return null
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: return null
        return Point.fromLngLat(lon, lat)
    }
}
