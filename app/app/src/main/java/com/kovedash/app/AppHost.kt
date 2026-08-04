package com.kovedash.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.kovedash.app.navshare.NavNotificationListener
import com.kovedash.app.nav.GpxCourse
import com.kovedash.app.nav.GpxParser
import com.kovedash.app.nav.Navigator
import com.kovedash.app.nav.haversineMeters
import com.kovedash.app.net.GpsFix
import com.kovedash.app.net.GpsSource
import com.kovedash.app.service.ConnectionPhase
import com.kovedash.app.service.DashService
import com.kovedash.app.service.DashState
import com.kovedash.app.service.KoveSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * V0 app-level singleton. Holds in-memory UI state and forwards user actions to the
 * foreground service. V1 will replace this with a ViewModel + bound service interface.
 */
object AppHost {

    private val _state = MutableStateFlow(DashState())
    val state: StateFlow<DashState> = _state

    // GPS fixes flow separately from DashState so high-frequency updates don't churn
    // every UI observer that reads connection state.
    private val _gps = MutableStateFlow<GpsFix?>(null)
    val gps: StateFlow<GpsFix?> = _gps

    fun updateGps(fix: GpsFix) {
        _gps.value = fix
    }

    private var appContext: Context? = null
    private var settings: KoveSettings? = null
    private var gpsSource: GpsSource? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        settings = KoveSettings(context.applicationContext).also { s ->
            _state.update { it.copy(savedDashPassword = s.dashPassword, savedSsidPrefix = s.dashSsidPrefix) }
        }
        gpsSource = GpsSource(context.applicationContext)
        startGpsIfPermitted()
        refreshNotificationAccess()
    }

    /** Re-check whether Notification Access is granted and publish it to state so the UI can
     *  prompt for it. Call on launch and on every resume (e.g. returning from the settings
     *  screen the user was deep-linked to). */
    fun refreshNotificationAccess() {
        _state.update { it.copy(notificationAccessGranted = isNotificationAccessGranted()) }
    }

    /**
     * Starts the GPS source if ACCESS_FINE_LOCATION is granted. Re-callable on permission
     * grant — internally a no-op if already running.
     */
    fun startGpsIfPermitted() {
        gpsSource?.start { fix -> _gps.value = fix }
    }

    fun openSettings() {
        _state.update { it.copy(showSettings = true) }
    }

    fun closeSettings() {
        _state.update { it.copy(showSettings = false) }
    }

    fun saveSettings(password: String, ssidPrefix: String) {
        settings?.dashPassword = password
        settings?.dashSsidPrefix = ssidPrefix
        _state.update {
            it.copy(
                showSettings = false,
                needsPassword = false,
                savedDashPassword = password,
                savedSsidPrefix = ssidPrefix,
            )
        }
    }

    fun savePasswordAndConnect(password: String) {
        settings?.dashPassword = password
        _state.update { it.copy(needsPassword = false, errorMessage = null) }
        connect()
    }

    fun dismissPasswordPrompt() {
        _state.update { it.copy(needsPassword = false) }
    }

    fun updateState(transform: (DashState) -> DashState) {
        _state.update(transform)
    }

    /**
     * PRIMARY bring-up, armed automatically on launch: Wi-Fi + the 17818 control channel +
     * BLE + handshake. The dash needs its control channel up to render native widgets, so
     * this is what makes weather + turn-by-turn appear. It does NOT start video — the
     * encoder stays off, so steady state is low power (Wi-Fi idle, no H.264).
     */
    fun connect() {
        _state.update { it.copy(phase = ConnectionPhase.JOINING_WIFI, errorMessage = null) }
        appContext?.let(DashService::startConnect)
    }

    /** On-demand video: arm the projection listener over the already-connected link; the
     *  rider then long-presses UP on the dash to start the map stream. */
    fun project() {
        _state.update { it.copy(projectionWaitingForUp = true, liveMode = true, errorMessage = null) }
        appContext?.let(DashService::startArmProjection)
    }

    /** BLE-primary: drop Wi-Fi to a BLE-only steady state (rendering keeps flowing over BLE). */
    fun parkWifi() {
        _state.update { it.copy(wifiParked = true, errorMessage = null) }
        appContext?.let(DashService::parkWifi)
    }

    /** Bring Wi-Fi back up over the live BLE link (for projection or re-activation). */
    fun unparkWifi() {
        _state.update { it.copy(wifiParked = false, errorMessage = null) }
        appContext?.let(DashService::unparkWifi)
    }

    /** Stop the video stream but KEEP the dash link (BLE + Wi-Fi control) up and quiet, so the
     *  dash falls back to its native pages and native turn-by-turn can render. Re-arm with
     *  [project]. Optimistically reflect READY; the service confirms via state push. */
    fun stopProjection() {
        _state.update { it.copy(liveMode = false, projectionWaitingForUp = false, errorMessage = null) }
        appContext?.let(DashService::stopProjection)
    }

    /** Easter egg: project the canned "this is fine" dog clip. Rider long-presses UP so the
     *  dash dials in, same as normal projection. */
    fun projectEasterEgg() {
        _state.update { it.copy(projectionWaitingForUp = true, liveMode = true, errorMessage = null) }
        appContext?.let(DashService::projectEasterEgg)
    }

    /** True if the user has granted this app Notification Access (needed for the Google
     *  Maps turn-by-turn forwarder — navshare). */
    fun isNotificationAccessGranted(): Boolean {
        val ctx = appContext ?: return false
        return NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }

    /** Deep-link the user to the system Notification Access screen to enable the listener. */
    fun openNotificationAccessSettings() {
        val ctx = appContext ?: return
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val comp = ComponentName(ctx, NavNotificationListener::class.java)
            intent.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp.flattenToString())
        }
        runCatching { ctx.startActivity(intent) }
    }

    /**
     * Auto-connect on app launch: fire the connect flow if we're idle and have a saved
     * password, so the rider doesn't tap Engage Link every time. No-op if already
     * connecting/connected or if there's no saved password (first run). Caller must
     * confirm runtime permissions are granted first.
     */
    fun autoConnectIfReady(): Boolean {
        if (_state.value.phase != ConnectionPhase.IDLE) return false
        // The dash needs its Wi-Fi control channel to render widgets, so auto-connect brings
        // up the full control link (no video). Needs the saved Wi-Fi password.
        if (settings?.dashPassword.isNullOrBlank()) return false
        connect()
        return true
    }

    /**
     * Called by the Activity to register a MediaProjection-consent requester. The lambda
     * accepts a callback that the Activity invokes with (resultCode, data) once the
     * system consent dialog returns. Stored as a nullable function so the Activity owns
     * the ActivityResultLauncher lifecycle.
     */
    private var projectionConsentRequester: ((onResult: (Int, Intent?) -> Unit) -> Unit)? = null

    fun setProjectionConsentRequester(req: ((onResult: (Int, Intent?) -> Unit) -> Unit)?) {
        projectionConsentRequester = req
    }

    fun disconnect() {
        _state.update { DashState() }
        appContext?.let(DashService::stop)
    }

    // --- GPX course loading ---

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The Activity registers an OpenDocument launcher here (it owns the ActivityResult
    // lifecycle); the UI's "Load GPX" button invokes requestGpxPick() → this → picker.
    private var gpxPickRequester: (() -> Unit)? = null

    fun setGpxPickRequester(req: (() -> Unit)?) { gpxPickRequester = req }

    fun requestGpxPick() { gpxPickRequester?.invoke() }

    /**
     * Reads + parses a picked .gpx file (off the main thread), then hands the course to
     * [Navigator] which NavMap draws and frames. Surfaces the loaded name (or an error)
     * in [DashState] for the UI.
     */
    fun loadGpxFromUri(uri: Uri) {
        val ctx = appContext ?: return
        ioScope.launch {
            val course = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { GpxParser.parse(it) }
            }.getOrNull()
            val coords = course?.coords.orEmpty()
            if (coords.size < 2) {
                _state.update { it.copy(errorMessage = "Couldn't read a course from that GPX file.") }
                return@launch
            }
            var dist = 0.0
            for (i in 1 until coords.size) {
                dist += haversineMeters(
                    coords[i - 1].latitude(), coords[i - 1].longitude(),
                    coords[i].latitude(), coords[i].longitude(),
                )
            }
            Navigator.setGpxCourse(GpxCourse(course?.name, coords, dist))
            _state.update { it.copy(gpxCourseName = course?.name ?: "Course", errorMessage = null) }
        }
    }

    fun clearGpxCourse() {
        Navigator.setGpxCourse(null)
        _state.update { it.copy(gpxCourseName = null) }
    }

    // Selected map view (style + camera pitch) for the dash and in-app map. Cycled by a
    // phone-side button and, once we confirm the wire event, by a bike button.
    private val _dashView = MutableStateFlow(DashView.NAV_3D)
    val dashView: StateFlow<DashView> = _dashView

    fun cycleDashView() {
        _dashView.update { DashView.entries[(it.ordinal + 1) % DashView.entries.size] }
    }
}

/**
 * A map "view" = a Mapbox style + a camera pitch. NAV_3D/TRAIL_3D give the tilted
 * turn-by-turn perspective; NAV_2D is the flat top-down overview.
 */
enum class DashView(val styleUri: String, val pitch: Double, val label: String) {
    NAV_3D("mapbox://styles/mapbox/navigation-day-v1", 55.0, "NAV 3D"),
    TRAIL_3D("mapbox://styles/mapbox/outdoors-v12", 55.0, "TRAIL 3D"),
    NAV_2D("mapbox://styles/mapbox/navigation-day-v1", 0.0, "NAV 2D"),
}
