package com.kovedash.app.ui.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.kovedash.app.AppHost
import com.kovedash.app.nav.ActiveRoute
import com.kovedash.app.nav.GpxCourse
import com.kovedash.app.nav.Navigator
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts
import kotlinx.coroutines.delay

/**
 * Mapbox MapView wrapped for both the dash Presentation and the in-app tab. Reads
 * [AppHost.gps] for phone GPS fixes and eases the camera to follow.
 *
 * [keepAlive] enables a tiny corner pip that toggles every 33ms. On the dash, the
 * H.264 encoder needs continuous pixel deltas — Mapbox's render thread idles when
 * the map is visually static, which would starve the encoder and time the dash
 * decoder out. The pip forces SurfaceFlinger to keep compositing fresh frames into
 * the encoder's input Surface. On the in-app tab Compose drives invalidation
 * naturally, so the pip stays off.
 */
@Composable
fun NavMap(
    modifier: Modifier = Modifier,
    keepAlive: Boolean = false,
    autoFollow: Boolean = false,
) {
    // collectAsState (not collectAsStateWithLifecycle) because the dash Presentation's
    // lifecycle is manually driven and can stall mid-ride — observed symptom: map froze
    // after a few seconds of riding. Composition lifetime is the correct scope here; the
    // composition is alive exactly when the dash Surface is alive, which is what we want.
    val gpsFix by AppHost.gps.collectAsState()
    val activeRoute by Navigator.activeRoute.collectAsState()
    val gpxCourse by Navigator.gpxCourse.collectAsState()
    val dashView by AppHost.dashView.collectAsState()
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var snappedToFirstFix by remember { mutableStateOf(false) }
    // Inside the dash Presentation, LocalLifecycleOwner.current resolves to the
    // DashPresentation (it sets ViewTreeLifecycleOwner on its decor view) — which
    // stays RESUMED until dismiss(). In the in-app tab it's the host Activity.
    // Either way, stamp this owner on the MapView before attach so Mapbox's
    // ViewTreeLifecycleOwner lookup can't race onAttachedToWindow and fall through
    // to a parent owner that drops to STOPPED when the user switches apps.
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera follow policy:
    //   - First fix always snaps the camera so the map opens centered on the rider.
    //   - After that, [autoFollow] decides. The dash side passes true (no manual pan
    //     possible — the rider needs the camera locked). The in-app map passes false so
    //     the user can pan freely; the recenter button is the explicit return path.
    //   - Bearing only updates when GPS course is reliable (speed >= threshold) so the
    //     map doesn't spin at stoplights. Pitch isn't touched after the first snap so
    //     a two-finger tilt persists.
    LaunchedEffect(gpsFix) {
        val fix = gpsFix ?: return@LaunchedEffect
        val mv = mapView ?: return@LaunchedEffect
        val movingFastEnough = (fix.speedMps ?: 0.0) >= MIN_BEARING_SPEED_MPS
        // Auto-tilt: on a 3D view the pitch tracks speed — flat/top-down when stopped
        // (so you see the surrounding area), tilting up to the view's full angle by
        // cruising speed. NAV_2D (pitch 0) stays flat.
        val pitch = targetPitch(dashView.pitch, fix.speedMps)
        if (!snappedToFirstFix) {
            val camBuilder = CameraOptions.Builder()
                .center(Point.fromLngLat(fix.lon, fix.lat))
                .zoom(16.0)
                .pitch(pitch)
            if (movingFastEnough) fix.bearingDeg?.let { camBuilder.bearing(it) }
            else camBuilder.bearing(0.0)
            mv.mapboxMap.setCamera(camBuilder.build())
            snappedToFirstFix = true
            return@LaunchedEffect
        }
        if (!autoFollow) return@LaunchedEffect
        val camBuilder = CameraOptions.Builder()
            .center(Point.fromLngLat(fix.lon, fix.lat))
            .pitch(pitch)
        if (movingFastEnough) fix.bearingDeg?.let { camBuilder.bearing(it) }
        mv.mapboxMap.easeTo(camBuilder.build(), MapAnimationOptions.mapAnimationOptions { duration(900L) })
    }

    // Route polyline: re-render whenever the active route changes (set / cleared / new
    // destination). `getStyle` defers until the style is loaded, so we don't race the
    // initial loadStyle call in the AndroidView factory.
    LaunchedEffect(activeRoute, mapView) {
        val mv = mapView ?: return@LaunchedEffect
        mv.mapboxMap.getStyle { style -> renderRouteLine(style, activeRoute) }
    }

    // GPX course line (adventure mode). Re-render on load/clear. On the in-app map
    // (!autoFollow) also fit the camera to the whole course so the rider can preview it;
    // on the dash the GPS-follow camera stays in charge.
    LaunchedEffect(gpxCourse, mapView) {
        val mv = mapView ?: return@LaunchedEffect
        mv.mapboxMap.getStyle { style -> renderGpxLine(style, gpxCourse) }
        val course = gpxCourse ?: return@LaunchedEffect
        if (autoFollow || course.coords.size < 2) return@LaunchedEffect
        // Let the MapView finish laying out before fitting — cameraForCoordinateBounds
        // needs a measured viewport, and computing it mid-layout (e.g. the map still
        // re-rendering after a reconnect) yields a wildly wrong zoom.
        delay(250)
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        course.coords.forEach {
            minLat = minOf(minLat, it.latitude()); maxLat = maxOf(maxLat, it.latitude())
            minLon = minOf(minLon, it.longitude()); maxLon = maxOf(maxLon, it.longitude())
        }
        val bounds = CoordinateBounds(
            Point.fromLngLat(minLon, minLat),
            Point.fromLngLat(maxLon, maxLat),
        )
        val cam = mv.mapboxMap.cameraForCoordinateBounds(
            bounds, EdgeInsets(80.0, 60.0, 80.0, 60.0), null, null,
        )
        mv.mapboxMap.easeTo(cam, MapAnimationOptions.mapAnimationOptions { duration(700L) })
    }

    // View change (rider cycled style/pitch): reload the new style, re-apply the puck
    // and route line (a style swap resets style-owned layers/sources), then ease the
    // camera to the view's pitch. Skipped on first composition — the factory already
    // loaded the initial view's style.
    var appliedViewOnce by remember { mutableStateOf(false) }
    LaunchedEffect(dashView, mapView) {
        val mv = mapView ?: return@LaunchedEffect
        if (!appliedViewOnce) { appliedViewOnce = true; return@LaunchedEffect }
        mv.mapboxMap.loadStyle(dashView.styleUri) { style ->
            applyPuck(mv)
            renderRouteLine(style, activeRoute)
        }
        gpsFix?.let { fix ->
            mv.mapboxMap.easeTo(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(fix.lon, fix.lat))
                    .pitch(targetPitch(dashView.pitch, fix.speedMps))
                    .build(),
                MapAnimationOptions.mapAnimationOptions { duration(600L) },
            )
        }
    }

    // Encoder keep-alive tick — only when used in the dash projection pipeline.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(keepAlive) {
        if (!keepAlive) return@LaunchedEffect
        while (true) {
            tick++
            delay(33L)  // ~30 Hz Compose redraws to match encoder's 30 fps target
        }
    }

    Box(modifier = modifier.fillMaxSize().background(KoveColors.Void)) {
        // Dash side gets the compact maneuver banner overlaid at the top; in-app side
        // renders the full banner stacked above the map via MapTab so it doesn't
        // overlap the map's interactive surface.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).also { mv ->
                    mv.setViewTreeLifecycleOwner(lifecycleOwner)
                    // No initial camera — the first GPS fix snaps the camera (see the
                    // LaunchedEffect above). Mapbox defaults to a world view in the
                    // sub-second gap before the first fix arrives.
                    // Load the currently-selected view's style (Navigation Day, Outdoors,
                    // …). Nav Day's bold roads / low label clutter survive H.264 encoding
                    // and read at a glance; Outdoors adds terrain + trails for backcountry.
                    mv.mapboxMap.loadStyle(dashView.styleUri)
                    applyPuck(mv)
                    mapView = mv
                }
            },
        )
        if (keepAlive) {
            FrameKeepAlivePip(tick = tick)
            // In full projection the dash keeps its own status bars (top clock, bottom
            // fuel/gear) composited over our frame — so anything at the extreme top or
            // bottom is hidden behind them. Inset all overlays into the central band the
            // dash actually shows. Values are eyeballed against the 1280×640 panel; tune
            // DASH_SAFE_* if the dash chrome covers more/less on your unit.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = DASH_SAFE_TOP,
                        bottom = DASH_SAFE_BOTTOM,
                        start = DASH_SAFE_SIDE,
                        end = DASH_SAFE_SIDE,
                    ),
            ) {
                // Directions turn-by-turn HUD (shows only with a computed route).
                ManeuverBanner(
                    modifier = Modifier.align(Alignment.TopCenter),
                    compact = true,
                )
                // Big upcoming-turn arrow where the speed HUD used to be (the dash's own
                // speedo already reads mph). ETA/distance-remaining gets its own corner so
                // it persists when the turn banner is hidden between maneuvers.
                TurnArrow(modifier = Modifier.align(Alignment.BottomStart))
                DashTripHud(modifier = Modifier.align(Alignment.BottomEnd))
                // GPX course-following HUD (shows only with a loaded course). Mutually
                // exclusive with the Directions HUD above — you follow one or the other.
                OffCourseBanner(modifier = Modifier.align(Alignment.TopCenter))
                CourseHud(modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
        if (!keepAlive) {
            RecenterButton(
                enabled = gpsFix != null,
                onClick = {
                    val fix = gpsFix ?: return@RecenterButton
                    val mv = mapView ?: return@RecenterButton
                    val movingFastEnough = (fix.speedMps ?: 0.0) >= MIN_BEARING_SPEED_MPS
                    val camBuilder = CameraOptions.Builder()
                        .center(Point.fromLngLat(fix.lon, fix.lat))
                        .zoom(16.0)
                    if (movingFastEnough) fix.bearingDeg?.let { camBuilder.bearing(it) }
                    // Pitch intentionally omitted — keep whatever tilt the user gestured to.
                    mv.mapboxMap.easeTo(
                        camBuilder.build(),
                        MapAnimationOptions.mapAnimationOptions { duration(600L) },
                    )
                },
            )
            ViewCycleButton(label = dashView.label, onClick = { AppHost.cycleDashView() })
            GpxButton(
                course = gpxCourse,
                onLoad = { AppHost.requestGpxPick() },
                onClear = { AppHost.clearGpxCourse() },
            )
            // Course-following HUD on the in-app map too, so progress + off-course are
            // visible without projecting. Off-course banner spans the top; the readout
            // sits mid-right, clear of both the banner and the bottom buttons.
            OffCourseBanner(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
            CourseHud(modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp))
        }
    }
}

/**
 * Loads a GPX course (opens the file picker) or, when one is loaded, shows its name in
 * rally orange and clears it on tap. In-app map only.
 */
@Composable
private fun BoxScope.GpxButton(course: GpxCourse?, onLoad: () -> Unit, onClear: () -> Unit) {
    val loaded = course != null
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (loaded) Color(0xE6FF6D00) else KoveColors.Void.copy(alpha = 0.85f))
            .border(1.dp, if (loaded) Color(0xFFFF6D00) else KoveColors.Mint.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .clickable { if (loaded) onClear() else onLoad() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (loaded) "✕ ${course?.name ?: "GPX"}" else "LOAD GPX",
            color = if (loaded) Color.Black else KoveColors.Mint,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 11.sp,
            letterSpacing = 0.1.sp,
            maxLines = 1,
        )
    }
}

/**
 * Cycles the map view (style + pitch). Lives on the in-app map so the rider can preview
 * and select a view before/around a ride; a bike button can call the same
 * [AppHost.cycleDashView] once we confirm the wire event.
 */
@Composable
private fun BoxScope.ViewCycleButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(16.dp)
            .clip(CircleShape)
            .background(KoveColors.Void.copy(alpha = 0.85f))
            .border(1.dp, KoveColors.Mint.copy(alpha = 0.7f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = KoveColors.Mint,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 12.sp,
            letterSpacing = 0.1.sp,
        )
    }
}

@Composable
private fun BoxScope.RecenterButton(enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0xFF1E88E5) else Color(0x66606060)
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .size(56.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2f, 0f)
                lineTo(w, h)
                lineTo(w / 2f, h * 0.65f)
                lineTo(0f, h)
                close()
            }
            drawPath(path, color = Color.White)
        }
    }
}

@Composable
private fun FrameKeepAlivePip(tick: Long) {
    val on = (tick and 1L) == 0L
    Box(
        modifier = Modifier
            .padding(10.dp)
            .size(8.dp)
            .background(if (on) KoveColors.Mint else Color.Transparent),
    )
}

// Speed threshold below which GPS bearing readings are too noisy to trust. ~3.4 mph.
private const val MIN_BEARING_SPEED_MPS = 1.5

// Auto-tilt speed band: below TILT_MIN the camera is flat (top-down); at/above TILT_FULL
// it's at the view's full pitch; linear between. ~4.5 mph → ~25 mph.
private const val TILT_MIN_MPS = 2.0
private const val TILT_FULL_MPS = 11.0

/**
 * Camera pitch for the current speed. Zero for a flat view (maxPitch 0) or when stopped;
 * ramps to [maxPitch] by cruising speed. Keeps the map readable as an overview at a stop
 * and gives the 3D perspective once moving.
 */
private fun targetPitch(maxPitch: Double, speedMps: Double?): Double {
    if (maxPitch <= 0.0) return 0.0
    val s = speedMps ?: 0.0
    val f = ((s - TILT_MIN_MPS) / (TILT_FULL_MPS - TILT_MIN_MPS)).coerceIn(0.0, 1.0)
    return maxPitch * f
}

// Safe-area insets for dash overlays (dp; the dash panel is 1280×640 @ density 2.0, so
// 640×320 dp). The dash composites its own top clock bar and bottom fuel/gear bar over
// our frame in full projection; these keep our banner + speed HUD in the visible band.
private val DASH_SAFE_TOP = 44.dp
private val DASH_SAFE_BOTTOM = 60.dp
private val DASH_SAFE_SIDE = 20.dp

/**
 * (Re)applies the standard bearing-arrow location puck. Called on first load and after
 * every style swap, since loading a new style resets style-owned config.
 */
private fun applyPuck(mv: MapView) {
    mv.location.updateSettings {
        enabled = true
        pulsingEnabled = false
        locationPuck = createDefault2DPuck(withBearing = true)
        puckBearing = PuckBearing.COURSE
        puckBearingEnabled = true
    }
}

private const val ROUTE_SOURCE_ID = "kove.route.src"
private const val ROUTE_LAYER_ID = "kove.route.layer"
private const val GPX_SOURCE_ID = "kove.gpx.src"
private const val GPX_LAYER_ID = "kove.gpx.layer"

/**
 * Upsert the loaded GPX course polyline. Drawn in rally orange, distinct from the blue
 * Directions route so a loaded course and a computed route don't blend. Same
 * update-in-place pattern as [renderRouteLine]; a null course removes it.
 */
private fun renderGpxLine(style: com.mapbox.maps.Style, course: GpxCourse?) {
    if (course == null || course.coords.size < 2) {
        style.removeStyleLayer(GPX_LAYER_ID)
        style.removeStyleSource(GPX_SOURCE_ID)
        return
    }
    val feature = Feature.fromGeometry(LineString.fromLngLats(course.coords))
    val existing = style.getSourceAs<GeoJsonSource>(GPX_SOURCE_ID)
    if (existing != null) {
        existing.feature(feature)
        return
    }
    style.addSource(geoJsonSource(GPX_SOURCE_ID) { feature(feature) })
    style.addLayer(
        lineLayer(GPX_LAYER_ID, GPX_SOURCE_ID) {
            lineColor("#FF6D00")
            lineWidth(7.0)
            lineOpacity(0.9)
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
        }
    )
}

/**
 * Upsert the route polyline onto [style]. Updates the existing GeoJsonSource feature in
 * place when present (no flicker, no layer re-add), or creates source + layer on first
 * render. Passing a null route removes both — the polyline disappears cleanly.
 */
private fun renderRouteLine(style: com.mapbox.maps.Style, route: ActiveRoute?) {
    if (route == null || route.coords.size < 2) {
        style.removeStyleLayer(ROUTE_LAYER_ID)
        style.removeStyleSource(ROUTE_SOURCE_ID)
        return
    }
    val feature = Feature.fromGeometry(LineString.fromLngLats(route.coords))
    val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
    if (existing != null) {
        existing.feature(feature)
        return
    }
    style.addSource(
        geoJsonSource(ROUTE_SOURCE_ID) {
            feature(feature)
        }
    )
    style.addLayer(
        lineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID) {
            lineColor("#1E88E5")
            lineWidth(9.0)
            lineOpacity(0.9)
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
        }
    )
}

