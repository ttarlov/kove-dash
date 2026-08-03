package com.kovedash.app.nav

import android.util.Log
import com.mapbox.geojson.Point
import com.kovedash.app.AppHost
import com.kovedash.app.net.MapboxDirections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Holds the in-app navigation state: the chosen destination, the fetched route geometry,
 * and the request status. The UI talks to this; [NavMap] reads [activeRoute] to render the
 * polyline on both the in-app map and the dash map.
 *
 * Deliberately does NOT pull in the Mapbox Navigation SDK yet. We only need the route
 * geometry to draw a line — Directions API as plain HTTP is enough. Nav SDK earns its
 * place when we add the turn-by-turn maneuver banner and off-route detection.
 *
 * GPS-late case: if a destination is set before the first GPS fix, [routeStatus] sits at
 * [RouteStatus.WaitingForGps] and a single-shot collector triggers the fetch the moment a
 * fix arrives.
 */
object Navigator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination

    private val _activeRoute = MutableStateFlow<ActiveRoute?>(null)
    val activeRoute: StateFlow<ActiveRoute?> = _activeRoute

    private val _routeStatus = MutableStateFlow<RouteStatus>(RouteStatus.Idle)
    val routeStatus: StateFlow<RouteStatus> = _routeStatus

    private val _progress = MutableStateFlow<RouteProgress?>(null)
    val progress: StateFlow<RouteProgress?> = _progress

    // A loaded GPX course to follow (adventure mode). Independent of the Directions
    // route: it's a fixed line the rider loaded, drawn in its own color. NavMap fits the
    // camera to it on load.
    private val _gpxCourse = MutableStateFlow<GpxCourse?>(null)
    val gpxCourse: StateFlow<GpxCourse?> = _gpxCourse

    fun setGpxCourse(course: GpxCourse?) {
        _gpxCourse.value = course
        gpxOffFixes = 0
        smoothedSpeedMps = 0.0
        _gpxProgress.value = null
        gpxCum = if (course != null && course.coords.size >= 2) cumulativeMeters(course.coords) else DoubleArray(0)
        Log.i(TAG, "gpx course ${if (course == null) "cleared" else "set: ${course.name} (${course.coords.size} pts, ${"%.1f".format(course.distanceMeters / 1000)}km)"}")
    }

    private var routeJob: Job? = null

    // Off-route hysteresis. Count of consecutive fixes that landed outside the route
    // corridor; reset to 0 on the first inside-corridor fix. We only trigger reroute
    // when this hits OFF_ROUTE_CONFIRM_FIXES, so a single bad GPS sample doesn't kick
    // off a Directions call. lastRerouteAtMs is a cooldown — after rerouting, ignore
    // off-route detections for OFF_ROUTE_COOLDOWN_MS so the new polyline has time to
    // become the reference and GPS jitter at the boundary doesn't thrash.
    private var offRouteFixCount = 0
    private var lastRerouteAtMs = 0L

    // Precomputed once when a route becomes active, for distance-along-route progress:
    //   cumMeters[i]   = cumulative distance from coords[0] to coords[i]
    //   stepAlong[k]   = along-route distance of step k's maneuver location
    // Proximity-only advance wedges permanently if the rider passes a maneuver outside
    // its snap radius (routine at highway speed: 36 m between fixes at 80 mph vs a 30 m
    // radius). Projecting the rider onto the polyline and advancing past every step
    // that's behind them can't wedge.
    private var cumMeters: DoubleArray = DoubleArray(0)
    private var stepAlong: DoubleArray = DoubleArray(0)

    // Reroute-failure retry. A failed Directions fetch (canyon, no cell) used to be
    // terminal: status went to Error and off-route monitoring stopped for the rest of
    // the ride. Instead we keep retrying with backoff while a destination is set, so
    // guidance recovers the moment signal returns. Backoff is a field so it grows
    // across successive failures and resets to the floor on any success.
    private var retryJob: Job? = null
    private var retryBackoffMs = ROUTE_RETRY_MIN_MS

    init {
        // GPS-late path: while waiting on a first fix, the moment one arrives, refetch.
        // Also feeds the live progress tracker once a route is active.
        scope.launch {
            AppHost.gps.collect { fix ->
                if (fix == null) return@collect
                if (_destination.value != null && _routeStatus.value == RouteStatus.WaitingForGps) {
                    refetchRoute()
                }
                advanceProgress(fix.lat, fix.lon)
                checkOffRoute(fix.lat, fix.lon)
                updateGpxProgress(fix.lat, fix.lon, fix.speedMps)
            }
        }
    }

    // --- GPX course following ---
    private var gpxCum: DoubleArray = DoubleArray(0)
    private var gpxOffFixes = 0
    private var smoothedSpeedMps = 0.0
    private val _gpxProgress = MutableStateFlow<GpxProgress?>(null)
    val gpxProgress: StateFlow<GpxProgress?> = _gpxProgress

    /**
     * Track the rider against a loaded GPX course: project them onto the line for
     * distance-along/remaining, measure perpendicular deviation for an off-course alert
     * (hysteresis — no reroute, the course is fixed), and estimate arrival from a smoothed
     * speed. Reuses [alongRouteMeters] / [routeDeviationMeters] / [cumulativeMeters].
     */
    private fun updateGpxProgress(lat: Double, lon: Double, speedMps: Double?) {
        val course = _gpxCourse.value
        if (course == null || course.coords.size < 2 || gpxCum.size != course.coords.size) {
            _gpxProgress.value = null
            return
        }
        val total = gpxCum.last()
        val along = alongRouteMeters(course.coords, gpxCum, lat, lon)
        val remaining = (total - along).coerceAtLeast(0.0)
        val deviation = routeDeviationMeters(course.coords, lat, lon)
        gpxOffFixes = if (deviation > GPX_OFF_COURSE_M) gpxOffFixes + 1 else 0
        val offCourse = gpxOffFixes >= GPX_OFF_COURSE_FIXES
        // Exponential moving average of speed so ETA doesn't jump with every GPS tick.
        val s = (speedMps ?: 0.0).coerceAtLeast(0.0)
        smoothedSpeedMps = if (smoothedSpeedMps <= 0.0) s else 0.7 * smoothedSpeedMps + 0.3 * s
        val eta = if (smoothedSpeedMps > 1.0) remaining / smoothedSpeedMps else 0.0
        _gpxProgress.value = GpxProgress(
            alongMeters = along,
            remainingMeters = remaining,
            deviationMeters = deviation,
            offCourse = offCourse,
            etaSeconds = eta,
            fractionComplete = if (total > 0.0) (along / total).coerceIn(0.0, 1.0) else 0.0,
        )
    }

    /**
     * Off-route detection. Computes the rider's perpendicular distance to the nearest
     * segment of the active polyline; if it exceeds [OFF_ROUTE_THRESHOLD_M] for
     * [OFF_ROUTE_CONFIRM_FIXES] consecutive fixes — and we're not already rerouting and
     * the cooldown has elapsed — refire Directions from the current location to the same
     * destination. Mapbox itself uses ~50m as the default off-route threshold; same here.
     *
     * Cheap O(N) per fix where N is polyline coord count (~thousands max for long routes).
     * Negligible at 1 Hz GPS cadence.
     */
    private fun checkOffRoute(lat: Double, lon: Double) {
        val route = _activeRoute.value ?: return
        if (_routeStatus.value != RouteStatus.Active) return
        if (System.currentTimeMillis() - lastRerouteAtMs < OFF_ROUTE_COOLDOWN_MS) return

        val deviation = routeDeviationMeters(route.coords, lat, lon)
        if (deviation > OFF_ROUTE_THRESHOLD_M) {
            offRouteFixCount += 1
            if (offRouteFixCount >= OFF_ROUTE_CONFIRM_FIXES) {
                Log.i(TAG, "off-route confirmed: ${"%.0f".format(deviation)}m from polyline, rerouting")
                offRouteFixCount = 0
                lastRerouteAtMs = System.currentTimeMillis()
                refetchRoute(rerouting = true)
            }
        } else {
            offRouteFixCount = 0
        }
    }

    /**
     * Recompute step progress against the active route, given the rider's current GPS.
     * Advances by distance-along-route: project the rider onto the polyline, then make
     * "upcoming" the first step whose maneuver lies ahead of the rider along the route
     * (with a small [MANEUVER_PASSED_TOLERANCE_M] slack so a maneuver counts as passed
     * a few meters before its exact point). Monotonic — never moves the index backward,
     * so GPS jitter near a maneuver can't flip the banner back to a turn already taken.
     */
    private fun advanceProgress(lat: Double, lon: Double) {
        val route = _activeRoute.value ?: run {
            _progress.value = null
            return
        }
        val steps = route.steps
        if (steps.size < 2 || stepAlong.size != steps.size || cumMeters.size != route.coords.size) {
            _progress.value = null
            return
        }
        val prev = _progress.value
        val minIdx = prev?.upcomingStepIndex ?: 1
        val totalMeters = cumMeters.last()
        if (minIdx >= steps.size) {
            _progress.value = RouteProgress(minIdx, steps.last(), 0.0, 0.0, 0.0)
            return
        }
        val riderAlong = alongRouteMeters(route.coords, cumMeters, lat, lon)
        // Advance past every step the rider is already beyond; never go below minIdx.
        var upcomingIdx = minIdx
        while (upcomingIdx < steps.size - 1 &&
            riderAlong >= stepAlong[upcomingIdx] - MANEUVER_PASSED_TOLERANCE_M
        ) {
            upcomingIdx += 1
        }
        // Distance shown in the banner is the remaining along-route distance to the
        // upcoming maneuver (never negative), which reads better than crow-flies when
        // the road curves.
        val dist = (stepAlong[upcomingIdx] - riderAlong).coerceAtLeast(0.0)
        // Trip-to-destination: distance is what's left of the polyline; ETA scales the
        // route's total duration by the fraction of distance remaining. Good enough for
        // a live readout without a per-segment speed model.
        val distRemaining = (totalMeters - riderAlong).coerceAtLeast(0.0)
        val etaSeconds = if (totalMeters > 0.0) route.durationSeconds * (distRemaining / totalMeters) else 0.0
        _progress.value = RouteProgress(upcomingIdx, steps[upcomingIdx], dist, distRemaining, etaSeconds)
    }

    fun setDestination(d: Destination) {
        Log.i(TAG, "destination set: ${d.name} (${d.point.longitude()},${d.point.latitude()})")
        _destination.value = d
        offRouteFixCount = 0
        lastRerouteAtMs = 0L
        retryJob?.cancel()
        retryBackoffMs = ROUTE_RETRY_MIN_MS
        refetchRoute()
    }

    fun clearDestination() {
        Log.i(TAG, "destination cleared")
        routeJob?.cancel()
        routeJob = null
        retryJob?.cancel()
        retryJob = null
        retryBackoffMs = ROUTE_RETRY_MIN_MS
        _destination.value = null
        _activeRoute.value = null
        _routeStatus.value = RouteStatus.Idle
        _progress.value = null
        offRouteFixCount = 0
        lastRerouteAtMs = 0L
        cumMeters = DoubleArray(0)
        stepAlong = DoubleArray(0)
    }

    private fun refetchRoute(rerouting: Boolean = false) {
        routeJob?.cancel()
        val dest = _destination.value ?: return
        val gps = AppHost.gps.value
        if (gps == null) {
            _routeStatus.value = RouteStatus.WaitingForGps
            return
        }
        val origin = Point.fromLngLat(gps.lon, gps.lat)
        _routeStatus.value = if (rerouting) RouteStatus.Rerouting else RouteStatus.Fetching
        routeJob = scope.launch {
            val route = MapboxDirections.fetch(origin, dest.point)
            if (route == null) {
                Log.w(TAG, "route fetch failed for ${dest.name}")
                _routeStatus.value = RouteStatus.Error
                // If we already have a route, keep navigating it (stale but better than
                // nothing) and keep advancing progress against it. Either way, schedule
                // a background retry so guidance self-heals when connectivity returns.
                scheduleRouteRetry(rerouting)
                return@launch
            }
            _activeRoute.value = ActiveRoute(
                destination = dest,
                coords = route.coords,
                distanceMeters = route.distanceMeters,
                durationSeconds = route.durationSeconds,
                steps = route.steps,
            )
            // Precompute along-route geometry for progress tracking.
            cumMeters = cumulativeMeters(route.coords)
            stepAlong = DoubleArray(route.steps.size) { k ->
                alongRouteMeters(route.coords, cumMeters, route.steps[k].location.latitude(), route.steps[k].location.longitude())
            }
            _routeStatus.value = RouteStatus.Active
            retryBackoffMs = ROUTE_RETRY_MIN_MS
            // Seed progress at step 1 (skip the "depart" pseudo-step) and refresh
            // immediately so the banner has data before the next GPS tick lands.
            _progress.value = if (route.steps.size >= 2) {
                RouteProgress(
                    upcomingStepIndex = 1,
                    step = route.steps[1],
                    distanceToManeuverMeters = haversineMeters(
                        gps.lat, gps.lon,
                        route.steps[1].location.latitude(),
                        route.steps[1].location.longitude(),
                    ),
                    distanceRemainingMeters = route.distanceMeters,
                    etaSeconds = route.durationSeconds,
                )
            } else null
            Log.i(TAG, "route active: ${route.coords.size} pts, ${route.steps.size} steps, ${"%.1f".format(route.distanceMeters / 1000)}km")
        }
    }

    /**
     * After a failed fetch, retry with exponential backoff until it succeeds or the
     * destination is cleared. refetchRoute() re-enters the normal path on success and
     * flips status back to Active; any newer refetch (new fix arriving, user changing
     * destination) cancels this via routeJob/retryJob so we don't stack retries.
     */
    private fun scheduleRouteRetry(rerouting: Boolean) {
        retryJob?.cancel()
        val delayMs = retryBackoffMs
        retryBackoffMs = (retryBackoffMs * 2).coerceAtMost(ROUTE_RETRY_MAX_MS)
        retryJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (_destination.value == null || _routeStatus.value != RouteStatus.Error) return@launch
            Log.i(TAG, "route retry (waited ${delayMs}ms)")
            refetchRoute(rerouting = rerouting)
        }
    }

    private const val TAG = "KoveDash"
    // Slack for "I've passed this maneuver" in the along-route model: count a maneuver
    // as passed once the rider is within this many meters of it along the route, so the
    // banner flips to the next instruction just before the turn rather than just after.
    private const val MANEUVER_PASSED_TOLERANCE_M = 15.0
    // Route-retry backoff bounds (failed Directions fetch → retry until signal returns).
    private const val ROUTE_RETRY_MIN_MS = 3_000L
    private const val ROUTE_RETRY_MAX_MS = 30_000L
    // Off-route corridor. Matches Mapbox Nav SDK's default. Parallel one-block-over
    // streets sit ~80–150m away, so this won't false-positive on those.
    private const val OFF_ROUTE_THRESHOLD_M = 50.0
    // Consecutive outside-corridor fixes before we commit to a reroute. At 1 Hz GPS
    // this means ~3 s of being clearly off the line — long enough to ignore a single
    // outlier sample, short enough to feel responsive when the rider actually misses
    // a turn.
    private const val OFF_ROUTE_CONFIRM_FIXES = 3
    // After firing a reroute, ignore subsequent off-route detection for this window so
    // the new polyline becomes the reference before GPS jitter at the boundary thrashes
    // us into a second reroute.
    private const val OFF_ROUTE_COOLDOWN_MS = 10_000L
    // GPX off-course: a bit tighter than the Directions corridor since a track is a
    // specific line, but 4 consecutive fixes (~4 s) to avoid false alarms on GPS noise.
    private const val GPX_OFF_COURSE_M = 45.0
    private const val GPX_OFF_COURSE_FIXES = 4
}

/** Live tracking against a loaded GPX course. */
data class GpxProgress(
    val alongMeters: Double,
    val remainingMeters: Double,
    val deviationMeters: Double,
    val offCourse: Boolean,
    val etaSeconds: Double,
    val fractionComplete: Double,
)

data class Destination(
    val name: String,
    val context: String,
    val point: Point,
)

data class ActiveRoute(
    val destination: Destination,
    val coords: List<Point>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<MapboxDirections.Step>,
)

/** A GPX course loaded from a file — a fixed line to follow. */
data class GpxCourse(
    val name: String?,
    val coords: List<Point>,
    val distanceMeters: Double,
)

/**
 * Snapshot of where the rider is along the active route. [step] is the NEXT maneuver
 * (the one being displayed in the banner). [distanceToManeuverMeters] is the haversine
 * distance from the rider to that maneuver's location — close enough to truth for
 * turn-by-turn UX given typical road geometry. When the upcoming index advances past
 * the last step the route is effectively complete.
 */
data class RouteProgress(
    val upcomingStepIndex: Int,
    val step: MapboxDirections.Step,
    val distanceToManeuverMeters: Double,
    // Trip-to-destination readouts: distance left on the route and an estimated time
    // to arrival. ETA scales the route's total duration by the fraction of distance
    // still ahead of the rider.
    val distanceRemainingMeters: Double = 0.0,
    val etaSeconds: Double = 0.0,
)

enum class RouteStatus { Idle, WaitingForGps, Fetching, Rerouting, Active, Error }

internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2).let { it * it }
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Minimum perpendicular distance from (lat, lon) to the route polyline, in meters.
 *
 * Projects lat/lon into a local equirectangular plane anchored at (lat, lon) — error
 * is well under 1% out to a few km, plenty good for a 50 m corridor check. Then runs
 * standard point-to-segment distance on each segment, returning the min.
 *
 * METERS_PER_DEG_LAT is constant; METERS_PER_DEG_LON shrinks with latitude (multiply
 * by cos(lat)). At Denver (~40°N) that's ~85,000 m/deg vs equatorial 111,320 m/deg —
 * exact enough for corridor detection.
 */
/**
 * Cumulative great-circle distance in meters from coords[0] to each vertex. Returned
 * array has the same length as [coords]; element 0 is always 0.
 */
internal fun cumulativeMeters(coords: List<Point>): DoubleArray {
    val out = DoubleArray(coords.size)
    for (i in 1 until coords.size) {
        out[i] = out[i - 1] + haversineMeters(
            coords[i - 1].latitude(), coords[i - 1].longitude(),
            coords[i].latitude(), coords[i].longitude(),
        )
    }
    return out
}

/**
 * Distance in meters from the route start to the projection of (lat, lon) onto the
 * nearest polyline segment. [cum] must be [cumulativeMeters] of [coords]. Uses the same
 * local-equirectangular projection as [routeDeviationMeters] to find the nearest segment
 * and the fractional position t along it, then returns cum[i] + t * len(segment i).
 */
internal fun alongRouteMeters(coords: List<Point>, cum: DoubleArray, lat: Double, lon: Double): Double {
    if (coords.size < 2) return 0.0
    val cosLat = cos(Math.toRadians(lat))
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cosLat
    var minDist = Double.MAX_VALUE
    var bestAlong = 0.0
    var i = 0
    while (i < coords.size - 1) {
        val ax = (coords[i].longitude() - lon) * mPerDegLon
        val ay = (coords[i].latitude() - lat) * mPerDegLat
        val bx = (coords[i + 1].longitude() - lon) * mPerDegLon
        val by = (coords[i + 1].latitude() - lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq > 0.0) (((-ax) * dx + (-ay) * dy) / lenSq).coerceIn(0.0, 1.0) else 0.0
        val px = ax + t * dx
        val py = ay + t * dy
        val d = sqrt(px * px + py * py)
        if (d < minDist) {
            minDist = d
            val segLen = cum[i + 1] - cum[i]
            bestAlong = cum[i] + t * segLen
        }
        i++
    }
    return bestAlong
}

internal fun routeDeviationMeters(coords: List<Point>, lat: Double, lon: Double): Double {
    if (coords.size < 2) return 0.0
    val cosLat = cos(Math.toRadians(lat))
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cosLat
    var minDist = Double.MAX_VALUE
    var i = 0
    while (i < coords.size - 1) {
        val ax = (coords[i].longitude() - lon) * mPerDegLon
        val ay = (coords[i].latitude() - lat) * mPerDegLat
        val bx = (coords[i + 1].longitude() - lon) * mPerDegLon
        val by = (coords[i + 1].latitude() - lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq > 0.0) ((-ax) * dx + (-ay) * dy) / lenSq else 0.0
        val tc = t.coerceIn(0.0, 1.0)
        val px = ax + tc * dx
        val py = ay + tc * dy
        val d = sqrt(px * px + py * py)
        if (d < minDist) minDist = d
        i++
    }
    return minDist
}
