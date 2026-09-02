package com.kovedash.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.app.PendingIntent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kovedash.app.AppHost
import com.kovedash.app.net.DashBleClient
import com.kovedash.app.net.DashTcpServer
import java.util.Locale
import com.kovedash.app.net.DashWifi
import com.kovedash.app.net.WeatherSource
import com.kovedash.app.project.LiveProjectionSession
import com.kovedash.app.project.ProjectionSession
import com.kovedash.app.proto.DashMessages
import com.kovedash.app.proto.MiniJson
import com.kovedash.app.proto.TelemetryProbe
import com.kovedash.app.ui.dash.NavMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single foreground service that owns the connection lifecycle:
 *   - BLE GATT
 *   - TCP server sockets (17818, 15457, 15456)
 *   - MediaCodec H.264 encoder + 15456 streamer
 *
 * Owns the full connect lifecycle and every write to the dash: handshake, telemetry pushes
 * (weather / altitude), native turn-by-turn forwarding, and on-demand video projection. All
 * BLE writes funnel through the single DashBleClient owner + its send mutex.
 */
class DashService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var wifi: DashWifi
    private lateinit var ble: DashBleClient
    private lateinit var tcp: DashTcpServer
    private lateinit var projection: ProjectionSession
    private lateinit var liveProjection: LiveProjectionSession
    private lateinit var probe: TelemetryProbe
    private lateinit var settings: KoveSettings

    // Periodic weather + elevation refresh loop. Re-armed on each connect; cancelled with
    // the service scope. Kept as a single job so reconnects don't stack loops.
    private var telemetryJob: Job? = null


    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startInForeground()
        wifi = DashWifi(applicationContext)
        ble = DashBleClient(applicationContext, scope)
        tcp = DashTcpServer(scope)
        projection = ProjectionSession(applicationContext, scope)
        liveProjection = LiveProjectionSession(applicationContext, scope) {
            NavMap(keepAlive = true, autoFollow = true)
        }
        // The session outlives individual dash connections. A dropped socket re-arms
        // the listener (rider just long-presses UP again); only when the whole session
        // dies do we release the wake lock and MediaProjection and fall back to READY.
        // Without these callbacks the phase sits at PROJECTING forever after a drop,
        // the notification lies, and the 8h wake lock burns the battery.
        // Projection is auto-armed at READY (the 15456 listener is open, waiting), but
        // the phase only flips to PROJECTING — darkening the screen + taking the wake
        // lock — when the dash actually dials in after the rider's UP long-press. On a
        // drop the listener re-arms and we fall back to READY, screen normal.
        liveProjection.onStreaming = { streaming ->
            if (streaming) {
                acquireProjectionWakeLock()
                AppHost.updateState {
                    it.copy(phase = ConnectionPhase.PROJECTING, liveMode = true, projectionWaitingForUp = false)
                }
            } else {
                releaseProjectionWakeLock()
                AppHost.updateState {
                    if (it.phase == ConnectionPhase.PROJECTING) {
                        it.copy(phase = ConnectionPhase.READY, projectionWaitingForUp = true)
                    } else it
                }
            }
        }
        liveProjection.onEnded = {
            Log.i(TAG, "live projection session ended")
            releaseProjectionWakeLock()
            runCatching {
                mediaProjection?.unregisterCallback(mediaProjectionCallback)
                mediaProjection?.stop()
            }
            mediaProjection = null
            if (AppHost.state.value.phase == ConnectionPhase.PROJECTING) {
                AppHost.updateState {
                    it.copy(phase = ConnectionPhase.READY, liveMode = false, projectionWaitingForUp = false)
                }
            }
        }
        probe = TelemetryProbe(ble, tcp, scope)
        settings = KoveSettings(applicationContext)
        // GpsSource is owned by AppHost (app-process scoped) so the Map tab has fixes
        // before the service ever starts. No need to start it here.
        scope.launch { watchPhaseForNotification() }
        scope.launch { watchBattery() }
        scope.launch { watchIdleAutoStop() }
    }

    private suspend fun watchBattery() {
        val bm = getSystemService(BatteryManager::class.java)
        while (true) {
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            AppHost.updateState { it.copy(batteryLevel = level) }
            if (level != null && level < BATTERY_ABORT_THRESHOLD) {
                if (AppHost.state.value.phase == ConnectionPhase.PROJECTING) {
                    Log.w(TAG, "battery $level% < $BATTERY_ABORT_THRESHOLD% — aborting projection")
                    projection.stop()
                    liveProjection.stop()
                    releaseProjectionWakeLock()
                    AppHost.updateState {
                        it.copy(
                            phase = ConnectionPhase.ERROR,
                            errorMessage = "Battery at $level%. Projection aborted to preserve charge.",
                            liveMode = false,
                        )
                    }
                }
            }
            delay(30_000)
        }
    }

    private suspend fun watchPhaseForNotification() {
        AppHost.state.collect { state ->
            updateNotification(state)
        }
    }

    private var idleStopJob: Job? = null

    /**
     * Don't linger as a foreground service (with its permanent notification) while idle. A brief
     * IDLE at startup is normal — connect flips us to JOINING_WIFI within milliseconds. But if we
     * SIT at IDLE (a failed/finished connect that didn't stop, a stray start), there's nothing to
     * keep alive, so after a short grace we drop out of foreground and stop — clearing the
     * notification. Soft stop (not [coldQuit]): the process may stay warm and a launch/connect
     * re-foregrounds it; the "Disconnect" button is what fully kills the process. Skips while a
     * password prompt is pending (the user is mid-input).
     */
    private suspend fun watchIdleAutoStop() {
        AppHost.state.collect { state ->
            val idle = state.phase == ConnectionPhase.IDLE && !state.needsPassword
            if (idle) {
                if (idleStopJob?.isActive != true) {
                    idleStopJob = scope.launch {
                        delay(IDLE_AUTOSTOP_GRACE_MS)
                        val s = AppHost.state.value
                        if (s.phase == ConnectionPhase.IDLE && !s.needsPassword) {
                            Log.i(TAG, "idle ${IDLE_AUTOSTOP_GRACE_MS / 1000}s — auto-stopping foreground service")
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                }
            } else {
                idleStopJob?.cancel()
                idleStopJob = null
            }
        }
    }

    // Set during coldQuit so the phase watcher below doesn't RE-POST the notification we're
    // tearing down: coldQuit sets state to DashState() (phase=IDLE), which would otherwise make
    // watchPhaseForNotification immediately notify() the "Idle" notification back into the shade.
    @Volatile private var quitting = false

    // The dash solicits the clock (msg_id=10 item=4) ~once per SECOND. Echoing setTime to every
    // solicit was ~59% of all BLE traffic on a ride and starved multi-frame nav on a lossy link.
    // The clock doesn't change — one setTime sets it — so we only answer the first few solicits
    // after each (re)connect (drop-resilient), then go silent. Reset in runHandshake.
    @Volatile private var timeSyncEchoesLeft = 0

    // Wedge detection: the dash asks us to resend dropped frames (msg_id=10 item=7/9). We don't
    // answer (replaying stormed the link — see closed #16/#17), so a genuinely stuck dash keeps
    // asking — but SLOWLY (observed ~once every 12s, not a burst). So we detect by PERSISTENCE:
    // if resend requests keep arriving across a streak longer than WEDGE_PERSIST_MS with no quiet
    // gap, the multi-frame stream is wedged and won't self-heal — force a clean relink (fresh GATT
    // resets seq→0 so the dash re-syncs). A quiet gap resets the streak (a transient loss the dash
    // recovers from stays under the bar); a cooldown prevents relink loops.
    private var wedgeStreakStartMs = 0L
    private var wedgeLastReqMs = 0L
    @Volatile private var lastWedgeRelinkMs = 0L

    // A dropped multi-frame fragment during the FRESH handshake leaves the dash blank and unable
    // to self-heal: it asks ONCE to resend the lost fragment (msg_id=10 item=7/9), we can't
    // cheaply re-feed it, then it idles — soliciting the clock forever while never activating the
    // widgets. That's the "app says connected but the dash shows nothing" hang the user otherwise
    // clears by quitting + restarting the app. The wedge detector doesn't catch it (that needs a
    // 12s PERSISTENT stream of resend requests; a lost handshake fragment is a single one). So
    // inside the activation window right after connect, a single resend request forces an immediate
    // clean relink (fresh GATT, seq→0, re-handshake) — automating the restart. connectedAtMs is
    // (re)set at the top of runHandshake; a dedicated short cooldown lets it retry a lossy link.
    @Volatile private var connectedAtMs = 0L
    @Volatile private var lastActivationRelinkMs = 0L

    private fun withinActivationWindow() =
        connectedAtMs != 0L && SystemClock.elapsedRealtime() - connectedAtMs < ACTIVATION_WINDOW_MS

    private fun updateNotification(state: DashState) {
        if (quitting) return
        val (title, text) = when (state.phase) {
            ConnectionPhase.IDLE -> "KoveDash" to "Idle"
            ConnectionPhase.JOINING_WIFI -> "KoveDash · joining dash AP" to "Looking for CQKY_*"
            ConnectionPhase.WIFI_READY -> "KoveDash · linked Wi-Fi" to "Gateway ${state.dashGatewayIp ?: ""}"
            ConnectionPhase.BLE_HANDSHAKE -> "KoveDash · BLE handshake" to "Sending OEM bootstrap…"
            ConnectionPhase.BLE_READY -> "KoveDash · BLE ready" to "Waiting for dash dial-in"
            ConnectionPhase.TCP_LISTENING -> "KoveDash · TCP listening" to "17818 / 15457 / 15456 up"
            ConnectionPhase.DEVICE_DIALED -> "KoveDash · dash linked" to "${state.firmware ?: "device dialed"}"
            ConnectionPhase.READY -> "KoveDash · ready" to "Long-press UP to project"
            ConnectionPhase.PROJECTING -> "KoveDash · streaming" to "30 fps to dash · 15456 TCP"
            ConnectionPhase.RECONNECTING -> "KoveDash · reconnecting" to "attempt ${state.reconnectAttempt}"
            ConnectionPhase.ERROR -> "KoveDash · error" to (state.errorMessage ?: "see app")
        }
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    // Full cold quit: tear everything down AND kill the process, so nothing lingers
                    // in memory until the app is launched again (ACTION_QUIT, not the soft STOP).
                    PendingIntent.getService(
                        this,
                        0,
                        Intent(this, DashService::class.java).setAction(ACTION_QUIT),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null/actionless intent means Android restarted us on its own (START_STICKY
        // redelivery after a process death — e.g. the cold-quit kill or a rare
        // low-memory kill), not a user action. Don't resurrect as a zombie foreground
        // service that shows "connected" but isn't — stop cleanly. Real starts always
        // carry an action, and the app auto-connects again next time it's opened.
        if (intent?.action == null) {
            Log.i(TAG, "onStartCommand: sticky restart (no action) — stopping cleanly")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_CONNECT -> {
                cancelReconnect()
                scope.launch { runConnect() }
            }
            ACTION_FORWARD_TBT -> {
                // Google-Maps-notification → dash turn-by-turn (navshare silo). Drop if the
                // dash link isn't up; NavForwarder already gates on phase, this is the
                // belt-and-suspenders check at the BLE owner.
                if (ble.connectionState.value == DashBleClient.State.CONNECTED) {
                    val icon = intent.getIntExtra(EXTRA_TBT_ICON, 9)
                    val road = intent.getStringExtra(EXTRA_TBT_ROAD) ?: ""
                    val curMeters = intent.getIntExtra(EXTRA_TBT_CUR_M, 0)
                    val pathMeters = intent.getIntExtra(EXTRA_TBT_PATH_M, -1)
                    val remainSec = intent.getIntExtra(EXTRA_TBT_REMAIN_S, -1)
                    val retainRate = intent.getIntExtra(EXTRA_TBT_RETAIN_RATE, -1)
                    scope.launch { forwardTbtInternal(icon, road, curMeters, pathMeters, remainSec, retainRate) }
                } else {
                    Log.w(TAG, "FORWARD_TBT: BLE not connected — dropping")
                }
            }
            ACTION_END_TBT -> {
                if (ble.connectionState.value == DashBleClient.State.CONNECTED) {
                    scope.launch { runCatching { ble.sendJson(DashMessages.endNavi()) } }
                }
            }
            ACTION_TEST_NAV -> {
                // Debug: fire a hardcoded REAL turn countdown, bypassing Google Maps entirely.
                // Reproduces the fake-nav POC that rendered (icon=3 right, real road, non-zero
                // distance ticking down) — vs every Maps test which was icon=9 / 0 m.
                val icon = intent.getIntExtra(EXTRA_TBT_ICON, 3)
                val road = intent.getStringExtra(EXTRA_TBT_ROAD) ?: "Pearl Street"
                val startM = intent.getIntExtra(EXTRA_TBT_CUR_M, 850)
                scope.launch { runTestNav(icon, road, startM) }
            }
            ACTION_SIM_RIDE -> {
                // Debug: play a scripted neighborhood ride — a sequence of maneuvers, each
                // with a distance countdown, at a tunable cadence. Tests dynamic turn-by-turn
                // (vs the single-shot that proved rendering). tickMs spacing keeps each
                // multi-frame update quiet enough to reassemble before the next.
                val tickMs = intent.getLongExtra(EXTRA_SIM_TICK_MS, 1500L)
                val stepM = intent.getIntExtra(EXTRA_SIM_STEP_M, 60)
                scope.launch { runSimulatedRide(tickMs, stepM) }
            }
            ACTION_TEST_ALT -> {
                // Debug: push a distinctive altitude value once (single-shot, quiet channel)
                // to see if the phone-pushed msg_type=9 drives the dash's altitude display
                // (the dash also self-reports altitude via msg_type=17).
                val altM = intent.getIntExtra(EXTRA_ALT_M, 3000)
                scope.launch { runTestAltitude(altM) }
            }
            ACTION_ARM_PROJECTION -> scope.launch {
                if (wifiParked) unparkWifi() // projection needs Wi-Fi/15456 — bring it back first
                armProjection() // on-demand video: open 15456, wait for UP
            }
            ACTION_PARK_WIFI -> parkWifi()          // BLE-primary: drop Wi-Fi, keep BLE rendering
            ACTION_UNPARK_WIFI -> scope.launch { unparkWifi() }
            ACTION_PROJECT -> scope.launch { runProject() }
            ACTION_PROJECT_LIVE -> runProjectLive()
            ACTION_PROJECT_LIVE_WITH_CONSENT -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    Log.w(TAG, "ACTION_PROJECT_LIVE_WITH_CONSENT: no data extra, aborting")
                    return START_STICKY
                }
                runProjectLiveWithConsent(rc, data)
            }
            ACTION_STOP_PROJECTION -> stopProjectionKeepBle()
            ACTION_PROJECT_EASTER_EGG -> scope.launch { runProjectAsset(EASTER_EGG_ASSET) }
            ACTION_STOP -> stopSelf()
            ACTION_QUIT -> coldQuit() // notification "Disconnect": full teardown + kill the process
        }
        return START_STICKY
    }

    private var reconnectJob: Job? = null
    private var bleWatchJob: Job? = null
    private var wifiWatchJob: Job? = null
    private var wifiUnavailableJob: Job? = null

    // BLE-primary: Wi-Fi intentionally dropped after it activated rendering. While true, the
    // supervisor must NOT treat Wi-Fi loss as a fault, and reconnects must skip the Wi-Fi
    // re-request and go straight to BLE. @Volatile — read from the supervisor coroutine.
    @Volatile private var wifiParked = false

    // When the BLE link last dropped (supervisor sets it). A long outage before we re-link
    // signals a dash power-cycle (key off→on) — the dash rebooted and lost its native-render
    // activation, so we must re-run the Wi-Fi/17818 activation, not just re-link BLE.
    @Volatile private var bleDropAtMs = 0L

    // PARTIAL_WAKE_LOCK held while projection is active. Without it, Doze suspends
    // the encoder drain coroutine and the dash decoder times out after ~1 s of no
    // NALUs. setReferenceCounted(false) so repeat acquires are a no-op rather than
    // stacking. 8 h ceiling is a safety net — projection should release on stop /
    // onDestroy, this just stops a leaked lock from killing the battery overnight.
    private var projectionWakeLock: PowerManager.WakeLock? = null

    // MediaProjection session held while a live projection is active. Acquired from
    // the consent (resultCode, data) tuple in [runProjectLiveWithConsent]; the
    // mandatory Callback#onStop fires when the system terminates the session
    // (notification swipe, user denies, etc.) and we tear down the encoder + VD.
    private var mediaProjection: MediaProjection? = null
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection.onStop fired — tearing down live projection")
            runCatching { liveProjection.stop() }
            releaseProjectionWakeLock()
            mediaProjection = null
            AppHost.updateState {
                it.copy(phase = ConnectionPhase.READY, liveMode = false, projectionWaitingForUp = false)
            }
        }
    }

    private fun acquireProjectionWakeLock() {
        if (projectionWakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kovedash:projection")
        wl.setReferenceCounted(false)
        wl.acquire(8L * 60L * 60L * 1000L)
        projectionWakeLock = wl
        Log.i(TAG, "projection wake lock acquired")
    }

    private fun releaseProjectionWakeLock() {
        val wl = projectionWakeLock ?: return
        if (wl.isHeld) {
            wl.release()
            Log.i(TAG, "projection wake lock released")
        }
        projectionWakeLock = null
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun armLinkSupervisor() {
        bleWatchJob?.cancel()
        wifiWatchJob?.cancel()
        wifiUnavailableJob?.cancel()

        // If the system retires our dash NetworkRequest (out of range too long, dialog
        // declined, wrong password) the callback is dead. Re-issuing requestNetwork()
        // would pop the consent dialog at the user — observed as the "connect to device"
        // prompt appearing after rides. Hard stop instead; user re-engages explicitly.
        wifiUnavailableJob = scope.launch {
            wifi.unavailable.collect { isUnavailable ->
                if (!isUnavailable) return@collect
                Log.w(TAG, "Wi-Fi NetworkRequest unavailable — stopping service to avoid consent re-prompt")
                cancelReconnect()
                AppHost.updateState {
                    it.copy(
                        phase = ConnectionPhase.IDLE,
                        reconnectAttempt = 0,
                        errorMessage = "Dash Wi-Fi unreachable. Tap Engage Link to retry.",
                    )
                }
                stopSelf()
            }
        }

        // BLE half: react to post-connect disconnect.
        bleWatchJob = scope.launch {
            var sawConnected = false
            ble.connectionState.collect { state ->
                if (state == DashBleClient.State.CONNECTED) sawConnected = true
                if (sawConnected && state == DashBleClient.State.DISCONNECTED) {
                    bleDropAtMs = System.currentTimeMillis()
                    Log.w(TAG, "BLE dropped — supervisor triggering reconnect")
                    triggerReconnect()
                    sawConnected = false
                }
            }
        }

        // Wi-Fi half: react to post-bind network loss (bike key off, signal gone). But when
        // Wi-Fi is PARKED (BLE-primary mode) the drop is intentional — ignore it, keep BLE.
        wifiWatchJob = scope.launch {
            var sawBound = false
            wifi.bound.collect { isBound ->
                if (isBound) sawBound = true
                if (sawBound && !isBound) {
                    if (wifiParked) {
                        Log.i(TAG, "Wi-Fi dropped but PARKED (BLE-primary) — not reconnecting")
                    } else {
                        Log.w(TAG, "Wi-Fi dropped — supervisor triggering reconnect")
                        triggerReconnect()
                    }
                    sawBound = false
                }
            }
        }
    }

    private fun triggerReconnect() {
        if (reconnectJob?.isActive == true) return
        val startMs = System.currentTimeMillis()
        reconnectJob = scope.launch {
            var attempt = 1
            var backoffMs = 1_000L
            while (true) {
                val elapsedMs = System.currentTimeMillis() - startMs
                if (elapsedMs >= MAX_RECONNECT_WALL_CLOCK_MS) {
                    val minutes = MAX_RECONNECT_WALL_CLOCK_MS / 60_000L
                    Log.w(TAG, "reconnect: ${minutes}min budget exhausted after $attempt attempts; auto-stopping service")
                    AppHost.updateState {
                        it.copy(
                            phase = ConnectionPhase.IDLE,
                            reconnectAttempt = 0,
                            errorMessage = "Auto-stopped after ${minutes}min of failed reconnect. Tap Engage Link to retry.",
                        )
                    }
                    stopSelf()
                    return@launch
                }
                AppHost.updateState {
                    it.copy(phase = ConnectionPhase.RECONNECTING, reconnectAttempt = attempt)
                }
                Log.i(TAG, "reconnect attempt $attempt (backoff ${backoffMs}ms, elapsed ${elapsedMs / 1000}s)")
                val ok = attemptFullReconnect()
                if (ok) {
                    Log.i(TAG, "reconnect attempt $attempt succeeded")
                    AppHost.updateState {
                        it.copy(phase = ConnectionPhase.READY, reconnectAttempt = 0, errorMessage = null)
                    }
                    return@launch
                }
                delay(backoffMs)
                attempt++
                // Cap tightened from 30s → 8s: reconnectAuto (autoConnect=true) does the real
                // waiting inside each attempt, re-linking the instant the dash re-advertises, so
                // this loop is a safety net for total failures — a long idle cap just delayed us.
                backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_CAP_MS)
            }
        }
    }

    private suspend fun attemptFullReconnect(): Boolean {
        val password = settings.dashPassword ?: run {
            Log.w(TAG, "reconnect: no saved password, giving up")
            return false
        }

        if (wifiParked) {
            // BLE-PRIMARY steady state. Re-link BLE FIRST — reconnectAuto (autoConnect=true) waits
            // through a key-off→key-on, so the outage we measure once it lands tells us whether the
            // dash merely blipped or fully rebooted.
            if (!relinkBle()) { Log.w(TAG, "reconnect: BLE re-link failed"); return false }
            val outageMs = if (bleDropAtMs > 0L) System.currentTimeMillis() - bleDropAtMs else 0L
            val powerCycle = outageMs >= POWER_CYCLE_OUTAGE_MS
            Log.i(TAG, "reconnect: BLE re-linked after ${outageMs}ms (powerCycle=$powerCycle)")
            if (powerCycle) {
                // The dash rebooted → it lost this power-cycle's native-render activation, and only
                // the Wi-Fi/17818 control channel restores it. Rejoin the AP, run the handshake, give
                // the dash a moment to re-dial 17818, then drop back to the BLE-only steady state.
                Log.i(TAG, "reconnect: power-cycle — re-activating rendering over Wi-Fi/17818")
                if (!unparkWifi()) {
                    Log.w(TAG, "reconnect: re-activation Wi-Fi rejoin failed — widgets may stay dark until Wi-Fi returns")
                }
                runHandshake()
                if (ble.connectionState.value == DashBleClient.State.CONNECTED) {
                    delay(4000)      // let the dash re-dial 17818 before we release Wi-Fi
                    parkWifi()
                    Log.i(TAG, "reconnect: re-activation done — re-parked to BLE-only")
                }
            } else {
                runHandshake()       // transient blip — BLE-only, no Wi-Fi churn
            }
        } else {
            // Not parked (e.g. mid-projection). Original order: rejoin Wi-Fi if actually down, then
            // BLE. Checking both the live SSID and the `bound` StateFlow avoids a redundant
            // requestDashNetwork() (which risks re-popping the consent dialog + wastes battery).
            val currentSsid = wifi.currentSsid()
            val onDashApBySsid = currentSsid?.startsWith(settings.dashSsidPrefix) == true
            val onDashApByBound = wifi.bound.value || wifi.isOnDashSubnet()
            if (!onDashApBySsid && !onDashApByBound) {
                Log.i(TAG, "reconnect: Wi-Fi gone (ssid=$currentSsid bound=$onDashApByBound), re-requesting")
                val network = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                    wifi.requestDashNetwork(settings.dashSsidPrefix, password, settings.dashExactSsid)
                }
                if (network == null) {
                    Log.w(TAG, "reconnect: Wi-Fi request failed/timed out")
                    return false
                }
            } else {
                Log.i(TAG, "reconnect: Wi-Fi still on dash (ssid=$currentSsid bound=$onDashApByBound), skip request")
            }
            if (!relinkBle()) { Log.w(TAG, "reconnect: BLE re-link failed"); return false }
            runHandshake()
        }
        bleDropAtMs = 0L
        return true
    }

    /** Re-establish the BLE link: OS-driven autoConnect fast path first (instant re-link the
     *  moment the dash re-advertises), an active scan as fallback. No-op success if already up. */
    private suspend fun relinkBle(): Boolean {
        if (ble.connectionState.value == DashBleClient.State.CONNECTED) {
            Log.i(TAG, "reconnect: BLE already connected, skipping re-link")
            return true
        }
        val mac = settings.dashMac
        if (!mac.isNullOrBlank() && ble.reconnectAuto(mac, RECONNECT_AUTO_TIMEOUT_MS)) return true
        Log.i(TAG, "reconnect: autoConnect miss — falling back to scan")
        return kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            runCatching { ble.scanAndConnect(settings.dashSsidPrefix, settings.dashMac) }.getOrDefault(false)
        } ?: false
    }

    /** True if the phone's region uses imperial units (US, Liberia, Myanmar) — drives the dash
     *  unit push so nav distance / altitude render in miles-feet vs km-meters. */
    private fun usesImperialUnits(): Boolean =
        Locale.getDefault().country.uppercase(Locale.US) in setOf("US", "LR", "MM")

    /**
     * Matches the OEM ThinkerRide post-firmware-version burst order documented in
     * phase2/_re_report_thinkerride.md §4 step 15. setTime is the SECOND message after
     * the dash's version reply (msg_id=10 item=6), not the last — the dash appears to
     * have a state machine that only honors msg_id 11 in this window.
     *
     * Our 2s delay after msg_id 13 mirrors waiting for the version-reply (we don't
     * parse it yet; future work to gate on the actual inbound msg_id=10 item=6).
     */
    private suspend fun runHandshake() {
        // Start (or restart, on a relink) the activation window — see connectedAtMs.
        connectedAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "handshake: msg_id 13 requestVersionCode")
        ble.sendJson(DashMessages.requestVersionCode())
        delay(2000)
        // Post-version-reply burst — order matters; OEM at BleConnectWrapper.java:934-963.
        ble.sendJson(DashMessages.setTime())
        Log.i(TAG, "handshake: msg_id 11 setTime pushed")
        // Allow a few solicit-echoes right after connect so the dash reliably applies the time
        // even if the unsolicited push above dropped; after that we stop echoing (see item=4).
        timeSyncEchoesLeft = TIME_SYNC_ECHO_MAX
        delay(500)
        // Tell the dash which unit system to render app-pushed distances in (nav finish-flag,
        // altitude). We send meters/km and the dash converts; without this it defaults to metric
        // even when the dash's own odo menu is imperial. Derived from the phone's locale.
        val imperial = usesImperialUnits()
        ble.sendJson(DashMessages.setUnit(imperial))
        Log.i(TAG, "handshake: msg_id 25 setUnit ${if (imperial) "imperial" else "metric"} pushed")
        delay(500)
        ble.sendJson(DashMessages.sendLinkInfo())
        delay(500)
        ble.sendJson(DashMessages.requestProductType())
        delay(500)
        ble.sendJson(DashMessages.requestCarInfo())
        Log.i(TAG, "handshake: msg_id 27 get_car_info requested")
        delay(500)
        ble.sendJson(DashMessages.checkVehicleCurStatus())
        delay(500)
        ble.sendJson(DashMessages.queryDevicePlayerVoiceStatus())
        delay(500)
        ble.sendJson(DashMessages.queryInsideNaviStatus())
        // startRide is INTENTIONALLY NOT sent. It flips the dash into ride/telemetry mode and
        // off the nav page, and its handshake traffic added to the link noise that blocked
        // native turn-by-turn from reassembling. Leaving it out is part of the proven
        // "keep the BLE link quiet" recipe (see forwardTbtInternal): with it gone, weather,
        // altitude, and native turn-by-turn all render. Builder kept in DashMessages for
        // future experiments; do not re-enable here without re-testing nav.
        delay(500)
        // ble.sendJson(DashMessages.startRide())  // deliberately disabled — see note above
        Log.i(TAG, "handshake: startRide intentionally not sent (quiet-link recipe)")
        // Push weather + real GPS elevation immediately on connect, then keep them fresh with
        // a periodic refresh (weather drifts slowly; elevation changes as you ride). Fetched
        // over the phone's own internet (available even on the dash's no-internet AP via
        // cellular; a failed fetch just skips that tick).
        startAmbientTelemetry()
    }

    /**
     * Immediately pushes weather + elevation, then refreshes both every
     * [AMBIENT_REFRESH_MS]. Weather is a single frame, elevation two — both far below the
     * nav pacing budget, and the refresh interval is minutes, so this never meaningfully
     * congests the BLE link even during active turn-by-turn. Re-armed on each connect.
     */
    private fun startAmbientTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (true) {
                if (ble.connectionState.value == DashBleClient.State.CONNECTED) {
                    sendWeatherOnConnect()
                    delay(1500) // small stagger so weather + altitude don't burst together
                    sendAltitudeOnConnect()
                }
                delay(AMBIENT_REFRESH_MS)
            }
        }
    }

    /** Fetch current weather for the phone's location and push it to the dash (msg_id 25
     *  type 11). Best-effort: skips if no location. Resilient to no/thin connectivity —
     *  [WeatherSource.fetchCurrent] retries and falls back to the last good reading, so the
     *  widget keeps showing weather (re-pushed each refresh) instead of dropping out. */
    private suspend fun sendWeatherOnConnect() {
        val loc = lastKnownLocation() ?: run { Log.i(TAG, "weather: no location — skipping"); return }
        val w = WeatherSource.fetchCurrent(loc.first, loc.second) ?: return
        runCatching {
            ble.sendJson(DashMessages.setWeather(w.dashCode, "${w.tempF}F", "${w.windMph}mph"))
        }.onFailure { Log.w(TAG, "weather send failed", it); return }
        Log.i(TAG, "weather sent: ${w.tempF}F wind=${w.windMph}mph code=${w.dashCode}" +
            if (w.stale) " (cached/stale)" else "")
    }

    /** Push the phone's real GPS elevation to the dash's native altitude field (msg_type=9).
     *  Proven 2026-07-30: the dash's elevation readout (trip/odo section) takes this value. */
    private suspend fun sendAltitudeOnConnect() {
        val alt = lastKnownAltitudeMeters() ?: run { Log.i(TAG, "altitude: no GPS altitude — skipping"); return }
        runCatching {
            ble.sendJson(DashMessages.setAltitude(alt, alt, alt))
        }.onFailure { Log.w(TAG, "altitude send failed", it); return }
        Log.i(TAG, "altitude sent: ${alt}m")
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Pair<Double, Double>? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        for (p in listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
        )) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (l != null) return l.latitude to l.longitude
        }
        return null
    }

    /** Phone GPS altitude in whole metres (WGS84), or null if no fix carries altitude. */
    private fun lastKnownAltitudeMeters(): Int? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
        for (p in listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        )) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (l != null && l.hasAltitude()) return l.altitude.toInt()
        }
        return null
    }

    /**
     * DEBUG ONLY (reached only via the BuildConfig.DEBUG-gated NavTestReceiver). Fire one real
     * turn to the dash independent of Google Maps: `icon` at `road`, single-shot both wire
     * shapes, then silence. Lets a bench test confirm the dash draws a REAL maneuver (icon=3,
     * non-zero distance) using the exact same send path as the live feature.
     */
    private suspend fun runTestNav(icon: Int, road: String, startM: Int) {
        if (ble.connectionState.value != DashBleClient.State.CONNECTED) {
            Log.w(TAG, "testNav: BLE not connected — skipping")
            return
        }
        // Same recipe as the live path: fire ONCE (both shapes) then leave the link quiet so
        // the multi-frame nav message can reassemble. Rapid re-sends re-congest the transparent
        // BLE link and reassembly breaks, so the debug harness deliberately sends a single shot.
        Log.i(TAG, "testNav: single-shot (both shapes, quiet-link recipe) icon=$icon road='$road' cur=${startM}m")
        runCatching { ble.sendJson(DashMessages.naviModern(icon, road, startM, 0, 0, 0L, 0)) }
        runCatching { ble.sendJson(DashMessages.naviLegacy(icon, road, startM, 0, 0)) }
        Log.i(TAG, "testNav: sent once — channel now quiet, watch the dash for ~60 s")
    }

    /**
     * DEBUG ONLY (via BuildConfig.DEBUG-gated NavTestReceiver). Push a distinctive altitude
     * (msg_id=25/msg_type=9) once into the quiet channel. Sends altitude = ave = max = [altM]
     * so a single obvious number (e.g. 3000 m) shows up on the dash altitude field.
     */
    private suspend fun runTestAltitude(altM: Int) {
        if (ble.connectionState.value != DashBleClient.State.CONNECTED) {
            Log.w(TAG, "testAlt: BLE not connected — skipping")
            return
        }
        Log.i(TAG, "testAlt: pushing altitude=${altM}m (single-shot)")
        runCatching { ble.sendJson(DashMessages.setAltitude(altM, altM, altM)) }
            .onFailure { Log.w(TAG, "testAlt send failed", it) }
        Log.i(TAG, "testAlt: sent — watch the dash altitude field")
    }

    /** One leg of the simulated ride: the maneuver glyph, the road you turn ONTO, and the
     *  distance (m) at which you first "see" that instruction. */
    private data class RideLeg(val icon: Int, val road: String, val startM: Int, val label: String)

    /**
     * DEBUG ONLY (via BuildConfig.DEBUG-gated NavTestReceiver). Play a scripted neighborhood
     * ride through the live nav path ([forwardTbtInternal], both BLE shapes) — a sequence of
     * maneuvers, each counting distance down to the turn, then advancing. Uses the same
     * one-update-then-settle discipline as the real feature: [tickMs] between updates gives
     * each multi-frame message a quiet window to reassemble. Tune tickMs/stepM to probe how
     * fast a cadence the dash tolerates before re-congesting the transparent BLE link.
     */
    private suspend fun runSimulatedRide(tickMs: Long, stepM: Int) {
        if (ble.connectionState.value != DashBleClient.State.CONNECTED) {
            Log.w(TAG, "simRide: BLE not connected — skipping")
            return
        }
        // Dash glyph enum: 2=left 3=right 4=slight-left 5=slight-right 6=sharp-left
        // 7=sharp-right 8=uturn 9=straight/continue 21=arrive 31=roundabout.
        val ride = listOf(
            RideLeg(9,  "Elm Street",     500, "depart, continue"),
            RideLeg(3,  "Oak Avenue",     650, "turn right"),
            RideLeg(2,  "Maple Street",   480, "turn left"),
            RideLeg(5,  "Birch Lane",     300, "slight right"),
            RideLeg(31, "Park Circle",    420, "roundabout"),
            RideLeg(2,  "Cedar Court",    350, "turn left"),
            RideLeg(7,  "Willow Way",     260, "sharp right"),
            RideLeg(21, "Destination",    200, "arrive"),
        )
        Log.i(TAG, "simRide: START ${ride.size} legs, tick=${tickMs}ms step=${stepM}m")
        for ((i, leg) in ride.withIndex()) {
            var cur = leg.startM
            while (true) {
                forwardTbtInternal(leg.icon, leg.road, cur)
                Log.i(TAG, "simRide: [${i + 1}/${ride.size}] ${leg.label} icon=${leg.icon} road='${leg.road}' cur=${cur}m")
                if (cur <= 0) break
                cur = (cur - stepM).coerceAtLeast(0)
                delay(tickMs)
            }
            delay(tickMs) // brief hold at the maneuver before the next leg appears
        }
        runCatching { ble.sendJson(DashMessages.endNavi()) }
        Log.i(TAG, "simRide: DONE (endNavi sent)")
    }

    private suspend fun forwardTbtInternal(
        icon: Int,
        road: String,
        curMeters: Int,
        pathMeters: Int = -1,
        remainSec: Int = -1,
        retainRate: Int = -1,
    ) {
        // THE WORKING RECIPE (proven 2026-07-30): send BOTH BLE shapes — modern (msg_id=27,
        // 3 frames) + the 6-field legacy (msg_id=1, 2 frames) — and NOTHING else. The native
        // arrow renders as long as the BLE link is QUIET so the multi-frame message can
        // reassemble. That quiet is achieved by NOT sending startRide, NOT running the
        // telemetry probe sweep, and NOT running the resend responder (all disabled) — those
        // were our own traffic storming the dash's transparent link (AT+Trans_Start flapping)
        // and blocking reassembly. Nav also lands at a low BLE seq (~10-13) because the
        // handshake is now lean. Dedup upstream (NavForwarder) keeps it one-shot-per-turn.
        //
        // Distances are SI METERS; the dash renders the legacy frame's raw meters as km. Unlike
        // altitude (which the dash DOES convert to feet from the imperial setting), the nav
        // distance field has no unit control in firmware — it's metric-only (confirmed: the dash
        // uses the legacy msg_id=1 frame, which carries no unittype; the OEM shows km here too).
        // So for imperial locales we scale the DESTINATION distance to miles ourselves: the dash
        // then shows "N.N km" where the number is really MILES (the label lies; the number is
        // right). Turn distance (cur) stays metric for now — small values don't scale cleanly.
        // -1 sentinels (Maps hadn't populated yet) clamp to 0 so we never send garbage.
        val cur = curMeters.coerceAtLeast(0)
        val pathReal = pathMeters.coerceAtLeast(0)          // true meters to destination
        val path = if (usesImperialUnits()) (pathReal * METERS_TO_MILES + 0.5).toInt() else pathReal
        val remain = remainSec.coerceAtLeast(0)             // trip seconds remaining
        val rate = retainRate.coerceIn(0, 100)              // % of route travelled (0 if unknown)
        // Modern remain_time is a wall-clock arrival epoch (dash shows ETA); legacy remain_time
        // is the raw seconds-remaining. retain_rate drives the route-progress bar (computed by
        // NavForwarder from peak-remaining). cur_retain_time (per-maneuver seconds) isn't in the
        // Maps notification, so it stays 0. retain_rate is modern-only (legacy has no such field).
        val etaEpoch = if (remain > 0) System.currentTimeMillis() / 1000 + remain else 0L
        runCatching {
            ble.sendJson(DashMessages.naviModern(icon, road, cur, path, 0, etaEpoch, rate))
            ble.sendJson(DashMessages.naviLegacy(icon, road, cur, path, remain))
        }.onFailure { Log.w(TAG, "forwardTbt send failed", it) }
    }


    private suspend fun runConnect() {
        Log.i(TAG, "runConnect: begin")
        AppHost.updateState { it.copy(phase = ConnectionPhase.JOINING_WIFI, errorMessage = null) }

        // 1. Make sure we have a password to try.
        val password = settings.dashPassword
        if (password.isNullOrBlank()) {
            Log.i(TAG, "runConnect: no saved password, prompting UI")
            AppHost.updateState {
                it.copy(phase = ConnectionPhase.IDLE, needsPassword = true)
            }
            return
        }

        // 2. Already on the dash AP? Check by SSID, and fall back to the dash gateway
        //    (192.168.10.1) since the SSID read can transiently return null right after
        //    launch. Either signal means we're on the dash and can skip the Wi-Fi request
        //    (and its dialog) entirely — this is the no-popup path when the phone already
        //    auto-joined.
        val currentSsid = wifi.currentSsid()
        val onDashAp = currentSsid?.startsWith(settings.dashSsidPrefix) == true || wifi.isOnDashSubnet()
        Log.i(TAG, "runConnect: current ssid=$currentSsid onDashSubnet=${wifi.isOnDashSubnet()} (onDashAp=$onDashAp)")

        // 3. Not on the dash AP — request auto-join via NetworkRequest. Pass the exact
        //    SSID (once learned) so Android caches the approval and stops re-prompting.
        if (!onDashAp) {
            Log.i(TAG, "runConnect: requesting dash network via NetworkRequest")
            val network = wifi.requestDashNetwork(settings.dashSsidPrefix, password, settings.dashExactSsid)
            if (network == null) {
                // The request timed out — but the join can complete just after our window
                // (Android slow, or a system dialog stole focus). Re-check the live SSID
                // before giving up.
                val nowSsid = wifi.currentSsid()
                val nowOnDash = nowSsid?.startsWith(settings.dashSsidPrefix) == true
                if (!nowOnDash) {
                    Log.w(TAG, "runConnect: auto-join failed (ssid=$nowSsid)")
                    // Only re-prompt for the password if we don't have one saved — a
                    // transient join timeout with a saved password is almost never a bad
                    // password, and forcing the dialog every time is the annoyance.
                    AppHost.updateState {
                        it.copy(
                            phase = ConnectionPhase.ERROR,
                            errorMessage = "Couldn't join the dash Wi-Fi. Bike on and in range? Tap Engage Link to retry.",
                            needsPassword = password.isBlank(),
                        )
                    }
                    return
                }
                Log.i(TAG, "runConnect: join completed after timeout (ssid=$nowSsid) — proceeding")
            }
        }

        val gateway = wifi.dashGatewayIp()
        if (gateway == null) {
            Log.w(TAG, "runConnect: joined AP but no DHCP gateway reported")
            AppHost.updateState { it.copy(phase = ConnectionPhase.ERROR, errorMessage = "Joined AP but no DHCP gateway reported. Power-cycle dash and retry.") }
            return
        }
        Log.i(TAG, "runConnect: dash gateway=$gateway")
        AppHost.updateState { it.copy(phase = ConnectionPhase.WIFI_READY, dashGatewayIp = gateway, needsPassword = false) }

        tcp.startDeviceListener()
        tcp.startHeartbeatListener()
        tcp.startAuxListeners()
        scope.launch { collectInboundDeviceMessages() }
        scope.launch { sendDevice17818Heartbeat() }
        Log.i(TAG, "runConnect: TCP listeners on 17818/15457/18888/19000 up, heartbeat sender armed")

        // Skip the BLE bring-up + handshake if we're already connected (e.g. a reconnect
        // where the GATT link survived). Otherwise do the full scan + connect + handshake.
        if (ble.connectionState.value != DashBleClient.State.CONNECTED) {
            AppHost.updateState { it.copy(phase = ConnectionPhase.BLE_HANDSHAKE) }
            val connected = runCatching { ble.scanAndConnect(settings.dashSsidPrefix, settings.dashMac) }.onFailure {
                Log.e(TAG, "BLE scanAndConnect threw", it)
            }.getOrDefault(false)
            if (!connected) {
                AppHost.updateState { it.copy(phase = ConnectionPhase.ERROR, errorMessage = "BLE scan/connect failed.") }
                return
            }
            // Remember the exact MAC so next connect skips scanning (throttle-proof).
            ble.connectedDeviceAddress?.let { if (it != settings.dashMac) { settings.dashMac = it; Log.i(TAG, "learned dash MAC $it") } }
            Log.i(TAG, "runConnect: BLE connected")
            AppHost.updateState { it.copy(phase = ConnectionPhase.BLE_READY) }
            scope.launch { collectBleNotifies() }
            armLinkSupervisor()
            runHandshake()
        } else {
            Log.i(TAG, "runConnect: BLE already up (armed on launch) — adding Wi-Fi + projection only")
        }

        AppHost.updateState { it.copy(phase = ConnectionPhase.TCP_LISTENING) }

        delay(3000)
        AppHost.updateState { it.copy(phase = ConnectionPhase.READY) }
        Log.i(TAG, "runConnect: READY — starting telemetry probe")

        // Remember the exact SSID so the next connect requests it by exact match — that's
        // what makes Android cache the Wi-Fi approval and stop re-prompting. (We used to
        // also register a WifiNetworkSuggestion for background auto-join, but it was
        // unreliable for a no-internet AP and would fight the swipe-to-disconnect intent,
        // so the app-driven specifier request on launch is the only path now.)
        wifi.currentSsid()?.let { ssid ->
            if (ssid != settings.dashExactSsid) {
                settings.dashExactSsid = ssid
                Log.i(TAG, "learned dash SSID '$ssid'")
            }
        }

        // NOTE: video is NOT armed here. The full control channel (Wi-Fi + 17818 + BLE) is
        // what the dash needs to activate native widget rendering (weather / turn-by-turn) —
        // that's this whole connect. Video (the 15456 listener + H.264 encoder) is the
        // expensive part and is armed separately, on demand, by the "Project" action
        // (ACTION_ARM_PROJECTION → armProjection()). So the steady state is: dash rendering
        // our widgets over BLE, Wi-Fi idle, encoder OFF — the real low-power mode.

        // The telemetry probe sweep is INTENTIONALLY NOT run. It fires NAVI act=3 (empty),
        // ROAD_NAVI, INSIDENAVI act=0, etc. right after connect — traffic that both re-congests
        // the transparent BLE link (blocking native turn-by-turn reassembly) and can reset the
        // dash's nav widget. Keeping the link quiet after connect is part of the proven recipe
        // (see forwardTbtInternal). We still collect any findings the probe WOULD emit so the UI
        // wiring stays live, but we never actually kick off probe.runOnce().
        scope.launch {
            probe.findings.collect { f ->
                Log.d(TAG, "probe: ${f.label} = ${f.value}")
                AppHost.updateState { st -> st.copy(telemetry = st.telemetry + f) }
            }
        }
        // probe.runOnce()  // deliberately not called — see note above
        Log.i(TAG, "runConnect: telemetry probe sweep intentionally not run (quiet-link recipe)")

        // BLE-PRIMARY DEFAULT: the whole point of connect is to bring Wi-Fi/17818 up ONCE so the
        // dash activates native rendering this power-cycle. That's done by now — so drop to the
        // BLE-only steady state automatically: release Wi-Fi (radio idles, battery drops), keep
        // BLE driving the widgets. Proven: turn-by-turn/weather render over BLE alone once
        // activated. "Project" (and the "Wi-Fi on" toggle) re-join the AP on demand for video.
        // Short settle first so the dash finishes its 17818 dial-in before we drop Wi-Fi; skip if
        // BLE fell over in the meantime or Wi-Fi was already parked (e.g. a reconnect).
        if (!wifiParked) {
            delay(4000)
            if (ble.connectionState.value == DashBleClient.State.CONNECTED && !wifiParked) {
                Log.i(TAG, "runConnect: auto-parking Wi-Fi (BLE-primary default)")
                parkWifi()
            }
        }
    }

    private var firedPostFirmware = false

    private suspend fun sendDevice17818Heartbeat() {
        while (true) {
            delay(2000)
            if (tcp.hasDeviceClient()) {
                tcp.writeDevice(DashMessages.HEARTBEAT_17818)
            }
        }
    }

    private suspend fun collectInboundDeviceMessages() {
        tcp.inbound.collect { msg ->
            when (msg) {
                is DashTcpServer.InboundDeviceMessage.Json -> {
                    Log.i(TAG, "17818 <- JSON: ${msg.json.take(200)}")
                    AppHost.updateState { it.copy(phase = ConnectionPhase.DEVICE_DIALED) }
                }
                is DashTcpServer.InboundDeviceMessage.Binary -> {
                    Log.i(TAG, "17818 <- bin type=${msg.type} sub=${msg.sub} len=${msg.payload.size}")
                    if (msg.type == 0x01 && !firedPostFirmware && msg.payload.size >= 8) {
                        firedPostFirmware = true
                        Log.i(TAG, "17818 *** firmware reply seen, firing post-firmware bootstrap ***")
                        scope.launch {
                            delay(100)
                            tcp.writeDevice(DashMessages.REQ_PRODUCT_TYPE_BIN)
                            tcp.writeDevice(DashMessages.REQ_MAC_BIN)
                            delay(100)
                            tcp.writeDevice(com.kovedash.app.net.DashJsonEnvelope.encode(DashMessages.queryDevicePlayerVoiceStatus()))
                            tcp.writeDevice(com.kovedash.app.net.DashJsonEnvelope.encode(DashMessages.queryInsideNaviStatus()))
                        }
                    }
                    AppHost.updateState {
                    // Type 1 sub 5 = firmware version; type 1 sub 11 = MAC; type 1 sub 13 = device type.
                    // Treating payload bytes as UTF-8 is fine for these — the OEM does the same.
                    val s = String(msg.payload, Charsets.UTF_8).trimEnd(' ').ifBlank { null }
                    when (msg.sub) {
                        5 -> it.copy(phase = ConnectionPhase.DEVICE_DIALED, firmware = s)
                        11 -> it.copy(phase = ConnectionPhase.DEVICE_DIALED, mac = s)
                        13 -> it.copy(phase = ConnectionPhase.DEVICE_DIALED, deviceType = s)
                        else -> it.copy(phase = ConnectionPhase.DEVICE_DIALED)
                    }
                    }
                }
            }
        }
    }


    /**
     * The dash actively pushes us msg_id=10 frames with various `item` values; we have
     * to react. item=4 is a time-sync solicitation (dash asks "what time is it, here's my
     * tag") that the dash repeats ~once per SECOND; the OEM echoes msg_id=11 with that tag
     * (phase2/_re_report_greentrip.md §5.4). We echo only the FIRST few solicits per connect
     * (enough for the dash to apply the time), then go silent — answering every 1 Hz solicit
     * was ~59% of our BLE traffic and starved multi-frame nav on a lossy link (#19).
     *
     * Other inbound items we observe (item=6 firmware-version, item=7/9 resend requests) are
     * just logged — version is already covered by the 17818 binary path, and we DELIBERATELY
     * do not answer the dash's item=7/9 retransmit requests. Replaying frames re-congests the
     * transparent BLE link and breaks the very multi-frame reassembly it's asking for; leaving
     * the link quiet lets the dash reassemble on its own (the proven native-nav recipe). When the
     * requests instead PERSIST, the stream is wedged and won't self-heal — [maybeRelinkOnWedge].
     */

    /**
     * Track how long dash resend requests (item=7/9) have been arriving continuously. A quiet gap
     * longer than [WEDGE_STREAK_GAP_MS] starts a fresh streak (so a transient loss the dash
     * recovers from resets). Once a streak lasts past [WEDGE_PERSIST_MS] the dash is genuinely
     * wedged on a lost frame we can't cheaply re-feed, so force a clean relink — a fresh GATT
     * resets seq→0 and the dash reassembles from scratch. A [WEDGE_RELINK_COOLDOWN_MS] cooldown
     * stops relink thrash. Tuned to tonight's ~1-request-per-12s wedge; adjust against ride logs.
     */
    private fun maybeRelinkOnWedge() {
        val now = SystemClock.elapsedRealtime()
        if (now - wedgeLastReqMs > WEDGE_STREAK_GAP_MS) wedgeStreakStartMs = now // quiet gap → new streak
        wedgeLastReqMs = now
        if (now - wedgeStreakStartMs < WEDGE_PERSIST_MS) return                  // not persistent yet
        if (now - lastWedgeRelinkMs < WEDGE_RELINK_COOLDOWN_MS) return           // just relinked
        lastWedgeRelinkMs = now
        wedgeStreakStartMs = now
        Log.w(TAG, "dash wedged (resend reqs persisting >${WEDGE_PERSIST_MS}ms) — forcing clean relink")
        ble.forceRelink()
    }

    private suspend fun collectBleNotifies() {
        ble.notify.collect { event ->
            val text = String(event.bytes, Charsets.UTF_8)
            // Self-synchronizing parse — find the JSON object boundaries; framing bytes
            // (0xFE, seq, 0xFF, CRC nibbles) decode to non-ASCII and live outside the
            // braces, so simple substring extraction is enough.
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return@collect
            val json = text.substring(start, end + 1)
            val msgId = MiniJson.number(json, "msg_id")?.toIntOrNull() ?: return@collect
            if (msgId != 10) {
                // Inbound button-press discovery: the dash forwards physical button
                // presses as msg_id=25 msg_type=1 msg_source=1 (control_info 1=start,
                // 2=pause, 3=end, 4=lap per split_screen_thinkerride.md). Log every
                // non-telemetry inbound so a bench test can map which buttons reach us;
                // flag likely button events loudly.
                val msgType = MiniJson.number(json, "msg_type")?.toIntOrNull()
                val msgSource = MiniJson.number(json, "msg_source")?.toIntOrNull()
                // msg_id=25 type=17 is the dash's 1 Hz status broadcast — decoded as just
                // {altitude:17} (a hardcoded sentinel, no real data). Bench-confirmed
                // 2026-07-16; drop it silently so it doesn't flood the log.
                if (msgId == 25 && msgType == 17) return@collect
                if (msgId == 25 && msgType == 1 && msgSource == 1) {
                    // Physical dash buttons don't forward to the phone on this firmware
                    // (bench-confirmed 2026-07-16 — no presses ever produced this frame).
                    // Kept in case a firmware update starts forwarding: control_info per
                    // RE = 1 start / 2 pause / 3 end / 4 lap; VIEW_CYCLE_BUTTON_CTRL binds
                    // one to the map-view cycle.
                    val ctrl = MiniJson.number(json, "control_info")?.toIntOrNull()
                    Log.w(TAG, "*** DASH BUTTON: msg_id=25 control_info=$ctrl → $json")
                    if (ctrl == VIEW_CYCLE_BUTTON_CTRL) {
                        Log.i(TAG, "button $ctrl → cycling dash view")
                        AppHost.cycleDashView()
                    }
                } else {
                    Log.i(TAG, "dash → inbound msg_id=$msgId type=$msgType src=$msgSource: $json")
                }
                return@collect
            }
            val item = MiniJson.number(json, "item")?.toIntOrNull() ?: return@collect
            when (item) {
                4 -> {
                    // Answer only the first few solicits per connect (the clock is now set),
                    // then stay silent — echoing every ~1 Hz solicit was ~59% of our BLE
                    // traffic and starved multi-frame nav. The dash keeps its own RTC.
                    if (timeSyncEchoesLeft > 0) {
                        timeSyncEchoesLeft--
                        val tag = MiniJson.number(json, "tag")?.toIntOrNull() ?: -1
                        Log.i(TAG, "dash → time-sync solicit (item=4, tag=$tag); echoing setTime ($timeSyncEchoesLeft left)")
                        runCatching { ble.sendJson(DashMessages.setTime(tag = tag)) }
                            .onFailure { Log.w(TAG, "setTime echo failed", it) }
                    }
                }
                6 -> Log.i(TAG, "dash → firmware version frame (item=6): $json") // dump for `cv` branch selector
                7, 9 -> {
                    if (withinActivationWindow()) {
                        // Lost fragment during the fresh handshake → the dash won't come up and
                        // won't self-heal. Relink now (fresh GATT re-handshake) instead of waiting
                        // out the slow wedge-persistence path — this is the auto-restart.
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastActivationRelinkMs >= ACTIVATION_RELINK_COOLDOWN_MS) {
                            lastActivationRelinkMs = now
                            Log.w(TAG, "dash → resend request (item=$item) in activation window — lost handshake fragment; forcing clean relink")
                            ble.forceRelink()
                        } else {
                            Log.w(TAG, "dash → resend request (item=$item) in activation window but relink on cooldown ($json)")
                        }
                    } else {
                        Log.w(TAG, "dash → resend request (item=$item): $json — not answered (quiet link); checking for wedge")
                        maybeRelinkOnWedge()
                    }
                }
                else -> Log.d(TAG, "dash → msg_id=10 item=$item: $json")
            }
        }
    }

    private fun runProject() {
        acquireProjectionWakeLock()
        AppHost.updateState { it.copy(phase = ConnectionPhase.PROJECTING, projectionWaitingForUp = true) }
        projection.start()
    }

    /**
     * Easter egg — stream a canned H.264 clip (the "this is fine" dog) to the dash instead of
     * the live map. Reuses the same [ProjectionSession] asset path as [runProject]; the dash
     * still dials 15456 on the rider's UP long-press. Stops any live projection first so the
     * 15456 listener is free (both paths bind the same port).
     */
    private suspend fun runProjectAsset(asset: String) {
        Log.i(TAG, "easter egg: triggered (asset='$asset')")
        // GATE #1 — the dash dials US on 15456, so there must be a live link. No dash → don't open
        // a listener that can only time out and black the screen for nothing.
        if (ble.connectionState.value != DashBleClient.State.CONNECTED) {
            Log.w(TAG, "easter egg: dash not connected — ignoring (connect first). no state change.")
            return
        }
        runCatching { liveProjection.stop() } // free 15456 if live projection was armed
        // GATE #2 — the dash reaches 15456 over Wi-Fi, but the BLE-primary steady state PARKS Wi-Fi.
        // Without unparking, the listener opens on a phone the dash can't route to and it NEVER
        // dials in — the main cause of "nothing happens, try again". Bring Wi-Fi up like arming does.
        val weUnparked = wifiParked
        if (weUnparked) {
            Log.i(TAG, "easter egg: Wi-Fi parked — unparking so the dash can reach 15456")
            if (!unparkWifi()) {
                Log.w(TAG, "easter egg: Wi-Fi unpark FAILED — aborting cleanly (no black screen)")
                return
            }
        }
        acquireProjectionWakeLock()
        // Do NOT flip to PROJECTING here — that's what darkens the panel. Only show a waiting
        // state; we go PROJECTING (and let MainActivity dim) from onStarted, once the dash has
        // actually dialed in and the clip is streaming. This kills the "tap → black screen" bug.
        AppHost.updateState { it.copy(liveMode = true, projectionWaitingForUp = true) }
        projection.onStarted = {
            Log.i(TAG, "easter egg: dash dialed in on 15456 — streaming now; screen dims")
            AppHost.updateState { it.copy(phase = ConnectionPhase.PROJECTING, liveMode = true, projectionWaitingForUp = false) }
        }
        projection.onEnded = {
            Log.i(TAG, "easter egg: session ended (clip done / dash closed / dial-in timeout) — back to READY")
            releaseProjectionWakeLock()
            AppHost.updateState {
                if (it.phase == ConnectionPhase.PROJECTING || it.liveMode || it.projectionWaitingForUp) {
                    it.copy(phase = ConnectionPhase.READY, liveMode = false, projectionWaitingForUp = false)
                } else it
            }
            if (weUnparked) scope.launch { parkWifi() } // return to the BLE-primary steady state we found
        }
        Log.i(TAG, "easter egg: 15456 listener open, waiting for the dash to dial in " +
            "(needs the UP long-press on the dash; timeout ${EASTER_EGG_DIAL_TIMEOUT_MS}ms)")
        projection.start(assetName = asset, connectTimeoutMs = EASTER_EGG_DIAL_TIMEOUT_MS)
    }

    /**
     * Stop the video stream but hold the dash link. [LiveProjectionSession.stop] tears down the
     * encoder + active socket + the 15456 listener and fires onEnded → phase READY with video
     * disarmed, so the dash can't re-dial 15456 and re-grab projection. That leaves the link
     * QUIET (no 30 fps flood), which is what native turn-by-turn reassembly requires. BLE and the
     * Wi-Fi control channel stay up; re-arm video later with [armProjection] (the Project button).
     */
    private fun stopProjectionKeepBle() {
        Log.i(TAG, "stop projection (keep BLE) — tearing down video, holding dash link quiet")
        runCatching { liveProjection.stop() }   // fires onEnded → READY, liveMode=false, disarmed
        runCatching { projection.stop() }        // on-demand path, if it was the one running
        runCatching {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null
        releaseProjectionWakeLock()
        // onEnded normally sets this, but call defensively in case nothing was actively streaming.
        AppHost.updateState {
            if (it.phase == ConnectionPhase.PROJECTING || it.liveMode) {
                it.copy(phase = ConnectionPhase.READY, liveMode = false, projectionWaitingForUp = false)
            } else it
        }
        // Stopping projection returns to the BLE-only default: drop the Wi-Fi we brought up for
        // video. (Rendering stays active over BLE.) This is the "Project → off" half of the toggle.
        parkWifi()
    }

    /**
     * BLE-primary: drop to a BLE-only steady state. Wi-Fi already activated the dash's native
     * rendering this power-cycle (17818 came up during connect), and widgets flow over BLE — so
     * we release the Wi-Fi network (radio idles, battery drops) and set [wifiParked] so the
     * supervisor treats the resulting Wi-Fi loss as expected and reconnects BLE-only. The TCP
     * control listeners stay bound (WiFi-independent local sockets) and resume when Wi-Fi
     * returns. Re-arm Wi-Fi with [unparkWifi] (also done automatically before projecting).
     */
    private fun parkWifi() {
        if (wifiParked) {
            Log.i(TAG, "parkWifi: already parked")
            return
        }
        Log.i(TAG, "park Wi-Fi — BLE-only steady state (rendering stays active over BLE)")
        wifiParked = true
        runCatching { wifi.release() } // drop the dash AP association; Wi-Fi radio idles
        AppHost.updateState { it.copy(wifiParked = true) }
    }

    /** Bring Wi-Fi back up over the live BLE link — for projection, or to re-activate rendering
     *  after a dash power-cycle. Re-requests the dash AP; the still-bound TCP listeners resume. */
    private suspend fun unparkWifi(): Boolean {
        if (!wifiParked) return true
        val password = settings.dashPassword
        if (password.isNullOrBlank()) {
            Log.w(TAG, "unparkWifi: no saved password"); return false
        }
        Log.i(TAG, "un-park Wi-Fi — re-joining dash AP")
        wifiParked = false
        AppHost.updateState { it.copy(wifiParked = false) }
        val onDashAp = wifi.currentSsid()?.startsWith(settings.dashSsidPrefix) == true || wifi.isOnDashSubnet()
        if (!onDashAp) {
            val network = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                wifi.requestDashNetwork(settings.dashSsidPrefix, password, settings.dashExactSsid)
            }
            if (network == null) {
                Log.w(TAG, "unparkWifi: Wi-Fi request failed/timed out")
                return false
            }
        }
        Log.i(TAG, "un-park Wi-Fi — back on dash AP")
        return true
    }

    /**
     * Arms live projection: opens the 15456 listener (DisplayManager path — no
     * MediaProjection, no consent) and waits for the dash to dial in after the rider's
     * UP long-press. Idempotent — `liveProjection.start()` no-ops if already armed. Does
     * NOT change phase or take the wake lock; that happens on dial-in (onStreaming), so
     * the screen stays normal and usable while waiting at READY. Called automatically
     * once at READY; the Project button re-arms if a session was torn down.
     */
    private fun runProjectLive() {
        // Tear down any canned session (easter egg) first — it holds 15456, so arming the live
        // map without this fails with BindException/EADDRINUSE. ProjectionSession.stop() closes
        // the listen socket synchronously, so liveProjection.start() can re-bind immediately.
        runCatching { projection.stop() }
        AppHost.updateState { it.copy(liveMode = true, projectionWaitingForUp = true) }
        liveProjection.start()
    }

    private fun armProjection() = runProjectLive()

    /**
     * Promote the foreground service to `mediaProjection` FGS type with the freshly
     * granted consent, instantiate MediaProjection, register the mandatory onStop
     * callback (Android 14+ throws from createVirtualDisplay if absent), then hand
     * the MP to LiveProjectionSession which creates a sleep-immune VirtualDisplay.
     *
     * Order matters: per Android 14+ rules, [startForeground] with the mediaProjection
     * FGS type must happen BEFORE [MediaProjectionManager.getMediaProjection]; the
     * system rejects the projection token otherwise.
     */
    private fun runProjectLiveWithConsent(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notif = buildNotification("KoveDash · projecting", "streaming to dash · 15456 TCP")
            runCatching {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            }.onFailure {
                Log.e(TAG, "startForeground(mediaProjection) failed", it)
                AppHost.updateState {
                    it.copy(
                        phase = ConnectionPhase.ERROR,
                        errorMessage = "Could not promote service to mediaProjection. Re-tap Project Live.",
                        liveMode = false,
                        projectionWaitingForUp = false,
                    )
                }
                return
            }
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpm == null) {
            Log.e(TAG, "MediaProjectionManager null — bailing")
            return
        }
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }
            .onFailure { Log.e(TAG, "getMediaProjection threw", it) }
            .getOrNull()
        if (mp == null) {
            AppHost.updateState {
                it.copy(
                    phase = ConnectionPhase.ERROR,
                    errorMessage = "Could not obtain MediaProjection. Re-tap Project Live.",
                    liveMode = false,
                    projectionWaitingForUp = false,
                )
            }
            return
        }
        // Register before any createVirtualDisplay call. Handler on main looper so
        // the callback runs on a thread that's safe to call Surface / View teardown
        // from. Old `mediaProjection` instances (if any) are released first.
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        runCatching { mediaProjection?.stop() }
        mediaProjection = mp
        mp.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

        acquireProjectionWakeLock()
        AppHost.updateState {
            it.copy(phase = ConnectionPhase.PROJECTING, projectionWaitingForUp = true, liveMode = true)
        }
        liveProjection.start(mediaProjection = mp)
    }

    /**
     * The rider swiped the app away from Recents — quit cold. A foreground service
     * normally survives task removal (that's the point of an FGS), but here the intent is
     * "dismiss = done": tear down projection, disconnect Wi-Fi + BLE, drop the
     * notification, and kill the process so nothing lingers holding the dash link.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed from Recents — full teardown + cold quit")
        super.onTaskRemoved(rootIntent)
        coldQuit()
    }

    /**
     * Full teardown + hard process exit — the dash link, all sockets, GPS, wake lock, and
     * foreground notification go away, and the process is killed so NOTHING lingers (the AppHost
     * singleton + GPS would otherwise keep it warm) until the app is launched again. Used by both
     * task-removal (swipe from Recents) and the notification's "Disconnect" action. Teardown runs
     * synchronously before killProcess so nothing leaks.
     */
    private fun coldQuit() {
        Log.i(TAG, "cold quit — full teardown + process kill")
        quitting = true // stop the phase watcher from re-posting the notification we're removing
        runCatching { liveProjection.stop() }
        runCatching { projection.stop() }
        runCatching { tcp.stop() }
        runCatching { ble.close() }
        runCatching { wifi.release() }   // unregisters the network request → drops the dash AP
        releaseProjectionWakeLock()
        AppHost.updateState { DashState() }
        // Release the notification listener FIRST so the OS won't RESPAWN us to rebind it (a
        // NotificationListenerService is held persistently → kill just churns: die → rebind →
        // bare process is back). App launch re-binds it (MainActivity.requestRebindNow).
        runCatching { com.kovedash.app.navshare.NavNotificationListener.releaseForQuit() }
        // Remove the FGS notification: detach it from the service AND cancel it explicitly.
        stopForegroundCompat()
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        stopSelf()
        // Kill AFTER a short delay so the teardown/notification-removal Binder calls settle in
        // system_server first — an immediate kill can leave the FGS notification stuck in the
        // shade with the process already dead (a ghost notification with a dead button).
        android.os.Handler(mainLooper).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 600)
    }

    override fun onDestroy() {
        runCatching { liveProjection.stop() }
        runCatching { projection.stop() }
        // Close TCP server sockets and the BLE GATT explicitly. Cancelling the scope
        // is not enough: a blocked accept() ignores cancellation, so the ports stay
        // bound and the next Engage Link crashes with BindException; a leaked GATT
        // can block the next connect since the dash is a single-client device.
        runCatching { tcp.stop() }
        runCatching { ble.close() }
        runCatching { wifi.release() }
        runCatching {
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null
        releaseProjectionWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dash link", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun startInForeground() {
        val notif = buildNotification("KoveDash", "Idle")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The `location` FGS type is what keeps fused-location delivery alive once
            // the app leaves the foreground (screen off, phone pocketed) — without it
            // GPS fixes stop shortly after backgrounding and the dash map freezes.
            // Requires ACCESS_FINE_LOCATION at call time; fall back without it so a
            // missing runtime grant degrades to the old behavior instead of crashing.
            val withLocation = runCatching {
                startForeground(
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            }
            if (withLocation.isFailure) {
                Log.w(TAG, "startForeground with location type failed — falling back to dataSync only",
                    withLocation.exceptionOrNull())
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val ACTION_CONNECT = "kovedash.CONNECT"
        const val ACTION_ARM_PROJECTION = "kovedash.ARM_PROJECTION"
        const val ACTION_PARK_WIFI = "kovedash.PARK_WIFI"     // BLE-primary: drop Wi-Fi
        const val ACTION_UNPARK_WIFI = "kovedash.UNPARK_WIFI" // bring Wi-Fi back up
        const val ACTION_FORWARD_TBT = "kovedash.FORWARD_TBT"
        const val ACTION_END_TBT = "kovedash.END_TBT"
        const val ACTION_TEST_NAV = "kovedash.TEST_NAV"
        const val ACTION_SIM_RIDE = "kovedash.SIM_RIDE"
        const val EXTRA_SIM_TICK_MS = "kovedash.sim.tickMs"
        const val EXTRA_SIM_STEP_M = "kovedash.sim.stepM"
        const val ACTION_TEST_ALT = "kovedash.TEST_ALT"
        const val EXTRA_ALT_M = "kovedash.alt.m"
        const val EXTRA_TBT_ICON = "kovedash.tbt.icon"
        const val EXTRA_TBT_ROAD = "kovedash.tbt.road"
        const val EXTRA_TBT_CUR_M = "kovedash.tbt.curM"
        const val EXTRA_TBT_PATH_M = "kovedash.tbt.pathM"    // meters to destination
        const val EXTRA_TBT_REMAIN_S = "kovedash.tbt.remainS" // trip seconds remaining
        const val EXTRA_TBT_RETAIN_RATE = "kovedash.tbt.retainRate" // % of route travelled 0..100
        const val ACTION_PROJECT = "kovedash.PROJECT"
        const val ACTION_PROJECT_LIVE = "kovedash.PROJECT_LIVE"
        const val ACTION_PROJECT_LIVE_WITH_CONSENT = "kovedash.PROJECT_LIVE_CONSENT"
        // Stop the video stream but KEEP the dash link (BLE + Wi-Fi control) up and quiet, so
        // native turn-by-turn can render. Distinct from ACTION_STOP, which tears down everything.
        const val ACTION_STOP_PROJECTION = "kovedash.STOP_PROJECTION"
        // Easter egg: stream the canned "this is fine" dog clip to the dash.
        const val ACTION_PROJECT_EASTER_EGG = "kovedash.PROJECT_EASTER_EGG"
        const val EASTER_EGG_ASSET = "thisisfine.h264"
        // How long to hold the 15456 listener waiting for the dash to dial in before aborting
        // cleanly (releases the wake lock, back to READY, no black screen). Generous so the
        // rider has time to do the UP long-press on the dash that makes it dial.
        const val EASTER_EGG_DIAL_TIMEOUT_MS = 15_000L
        const val ACTION_STOP = "kovedash.STOP"
        const val ACTION_QUIT = "kovedash.QUIT" // notification Disconnect: full teardown + kill process
        const val EXTRA_RESULT_CODE = "kovedash.resultCode"
        const val EXTRA_DATA = "kovedash.data"

        private const val TAG = "KoveDash"
        private const val CHANNEL_ID = "kovedash.dash"
        private const val NOTIF_ID = 1
        // How long to sit at IDLE before auto-stopping the foreground service (clears the
        // notification). Long enough that a normal startup IDLE→connecting never trips it.
        private const val IDLE_AUTOSTOP_GRACE_MS = 10_000L
        // Number of dash clock solicits to echo per connect before going silent. >1 for
        // drop-resilience (an early setTime frame may be lost); the clock is set after one.
        private const val TIME_SYNC_ECHO_MAX = 3
        // Scale real meters so the dash's km readout shows the MILES number instead: the dash
        // displays sentValue/1000 as "km", and miles = meters/1609.344, so sentValue =
        // meters * (1000/1609.344) = meters * 0.621371 makes "N.N km" read as N.N miles.
        private const val METERS_TO_MILES = 0.621371
        // Wedge detection (reconnect-on-wedge). Relink when dash resend requests have been
        // arriving continuously for this long (a quiet gap resets the streak). Tuned so a
        // transient loss the dash self-recovers stays under the bar; adjust against ride logs.
        private const val WEDGE_PERSIST_MS = 12_000L     // continuous resend requests this long = wedged
        private const val WEDGE_STREAK_GAP_MS = 20_000L  // no request for this long → fresh streak
        private const val WEDGE_RELINK_COOLDOWN_MS = 30_000L
        // Activation window: right after (re)connect, a single dash resend request means a lost
        // handshake fragment → the dash never activates and won't self-heal, so relink at once
        // rather than waiting out WEDGE_PERSIST_MS. Short cooldown so a lossy link can retry.
        private const val ACTIVATION_WINDOW_MS = 20_000L
        private const val ACTIVATION_RELINK_COOLDOWN_MS = 10_000L
        const val BATTERY_WARN_THRESHOLD = 20
        const val BATTERY_ABORT_THRESHOLD = 10
        // Which dash-button control_info cycles the map view. 4 = "lap" per the RE docs —
        // a guess pending the bench test that confirms which buttons actually reach us.
        const val VIEW_CYCLE_BUTTON_CTRL = 4
        // Hard ceiling on the supervisor's retry loop. After this many ms of failed
        // reconnect, the service auto-stops to clear the foreground notification, the
        // Wi-Fi consent re-prompt loop, and the BLE scanning drain.
        const val MAX_RECONNECT_WALL_CLOCK_MS = 5L * 60L * 1000L

        // Backoff cap between reconnect attempts. Small on purpose — reconnectAuto waits inside
        // each attempt for the dash to re-advertise, so the loop is a safety net, not the timer.
        const val RECONNECT_BACKOFF_CAP_MS = 8_000L
        // Per-attempt window for the autoConnect fast reconnect before falling back to a scan.
        const val RECONNECT_AUTO_TIMEOUT_MS = 45_000L
        // A BLE outage at least this long is treated as a dash power-cycle (reboot), which needs
        // Wi-Fi/17818 re-activation — not just a BLE re-link. Below this = a transient blip.
        const val POWER_CYCLE_OUTAGE_MS = 5_000L

        // How often to re-push weather + elevation after the initial connect push. 5 min
        // keeps elevation reasonably live on a ride while weather (slow-moving) stays fresh;
        // tune anywhere in the ~5–15 min range.
        const val AMBIENT_REFRESH_MS = 5L * 60L * 1000L

        fun startConnect(ctx: Context) {
            val i = Intent(ctx, DashService::class.java).setAction(ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** On-demand: arm video projection (open 15456, wait for the rider's UP) over the
         *  already-connected link. The control channel is up from [startConnect]. */
        fun startArmProjection(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_ARM_PROJECTION))
        }

        /** BLE-primary: drop Wi-Fi to a BLE-only steady state (rendering already activated). */
        fun parkWifi(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_PARK_WIFI))
        }

        /** Bring Wi-Fi back up over the live BLE link (for projection or re-activation). */
        fun unparkWifi(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_UNPARK_WIFI))
        }

        /** Stop the video stream but keep the dash link up (BLE + Wi-Fi control), returning to
         *  READY with video disarmed so the dash can't re-grab projection and the link stays
         *  quiet for native turn-by-turn. Re-arm later with [startArmProjection]. */
        fun stopProjection(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_STOP_PROJECTION))
        }

        /** Easter egg: stream the canned "this is fine" dog clip to the dash (rider still
         *  long-presses UP to let the dash dial in). */
        fun projectEasterEgg(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_PROJECT_EASTER_EGG))
        }

        /** Debug: fire a hardcoded real-turn countdown (bypasses Google Maps). Triggered by
         *  [NavTestReceiver] from adb. */
        fun testNav(ctx: Context, icon: Int, road: String, startM: Int) {
            ctx.startService(
                Intent(ctx, DashService::class.java)
                    .setAction(ACTION_TEST_NAV)
                    .putExtra(EXTRA_TBT_ICON, icon)
                    .putExtra(EXTRA_TBT_ROAD, road)
                    .putExtra(EXTRA_TBT_CUR_M, startM)
            )
        }

        /** Debug: push a distinctive altitude once. Triggered by [NavTestReceiver] from adb. */
        fun testAlt(ctx: Context, altM: Int) {
            ctx.startService(
                Intent(ctx, DashService::class.java)
                    .setAction(ACTION_TEST_ALT)
                    .putExtra(EXTRA_ALT_M, altM)
            )
        }

        /** Debug: play a scripted neighborhood ride. Triggered by [NavTestReceiver] from adb. */
        fun simRide(ctx: Context, tickMs: Long, stepM: Int) {
            ctx.startService(
                Intent(ctx, DashService::class.java)
                    .setAction(ACTION_SIM_RIDE)
                    .putExtra(EXTRA_SIM_TICK_MS, tickMs)
                    .putExtra(EXTRA_SIM_STEP_M, stepM)
            )
        }

        /** Forward one Google Maps turn to the dash over BLE (navshare silo). */
        fun forwardTbt(
            ctx: Context,
            icon: Int,
            road: String,
            curMeters: Int,
            pathMeters: Int = -1,
            remainSec: Int = -1,
            retainRate: Int = -1,
        ) {
            ctx.startService(
                Intent(ctx, DashService::class.java)
                    .setAction(ACTION_FORWARD_TBT)
                    .putExtra(EXTRA_TBT_ICON, icon)
                    .putExtra(EXTRA_TBT_ROAD, road)
                    .putExtra(EXTRA_TBT_CUR_M, curMeters)
                    .putExtra(EXTRA_TBT_PATH_M, pathMeters)
                    .putExtra(EXTRA_TBT_REMAIN_S, remainSec)
                    .putExtra(EXTRA_TBT_RETAIN_RATE, retainRate)
            )
        }

        /** Tear down the dash's native nav widget when Google Maps navigation ends. */
        fun endTbt(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_END_TBT))
        }

        fun startProject(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_PROJECT))
        }

        fun startProjectLive(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_PROJECT_LIVE))
        }

        fun startProjectLiveWithConsent(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, DashService::class.java)
                .setAction(ACTION_PROJECT_LIVE_WITH_CONSENT)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DashService::class.java))
        }
    }
}

