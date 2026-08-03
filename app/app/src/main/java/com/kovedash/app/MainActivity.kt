package com.kovedash.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapbox.common.MapboxOptions
import com.kovedash.app.service.ConnectionPhase
import com.kovedash.app.ui.ConnectScreen
import com.kovedash.app.ui.FullscreenSearch
import com.kovedash.app.ui.PasswordDialog
import com.kovedash.app.ui.SettingsScreen
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            AppHost.startGpsIfPermitted()
        }
        maybeAutoConnect()
    }

    // Held while a MediaProjection consent dialog is in flight; invoked from the
    // ActivityResultLauncher callback with the (resultCode, data) tuple so AppHost
    // can hand them to the service. Cleared after each result to avoid stale fires.
    private var pendingProjectionConsentCallback: ((Int, Intent?) -> Unit)? = null

    private val projectionConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = pendingProjectionConsentCallback
        pendingProjectionConsentCallback = null
        cb?.invoke(result.resultCode, result.data)
    }

    // GPX file picker. "*/*" because .gpx files often carry no proper MIME type
    // (octet-stream / none); the parser validates the contents. Null result = user
    // cancelled.
    private val gpxPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { AppHost.loadGpxFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapboxOptions.accessToken = BuildConfig.MAPBOX_PUBLIC_TOKEN
        AppHost.attach(this)
        AppHost.setProjectionConsentRequester { onResult ->
            pendingProjectionConsentCallback = onResult
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionConsentLauncher.launch(mpm.createScreenCaptureIntent())
        }
        AppHost.setGpxPickRequester { gpxPickerLauncher.launch(arrayOf("*/*")) }
        // A prior full "Disconnect" quit released the notification listener (so the process could
        // truly die). Re-bind it on launch so Google-Maps turn-by-turn forwarding works again.
        com.kovedash.app.navshare.NavNotificationListener.requestRebindNow(this)
        requestRuntimePermissions()
        setContent { App() }
        // Returning users (permissions already granted, password saved): connect on
        // launch so all that's left is holding UP on the dash. First-run users hit the
        // permission dialog, and maybeAutoConnect() runs again from its result callback.
        maybeAutoConnect()
    }

    private fun maybeAutoConnect() {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val allGranted = need.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) AppHost.autoConnectIfReady()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppHost.setProjectionConsentRequester(null)
        AppHost.setGpxPickRequester(null)
    }

    private fun requestRuntimePermissions() {
        val need = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) permissionLauncher.launch(need.toTypedArray())
    }
}

@Composable
private fun App() {
    KoveTheme {
        Surface(modifier = Modifier.fillMaxSize().background(KoveColors.Void).safeDrawingPadding()) {
            val state by AppHost.state.collectAsStateWithLifecycle()

            // Keep the pipeline alive while projecting. A VirtualDisplay borrows the
            // internal display's vsync; if the panel dozes, SurfaceFlinger stops
            // compositing our Presentation, the encoder starves, and the dash decoder
            // drops us. FLAG_KEEP_SCREEN_ON holds the display in STATE_ON so vsync keeps
            // flowing; forcing brightness to OFF makes the panel look dark and saves the
            // backlight without ever leaving STATE_ON. Net effect: the phone appears off
            // in your pocket but the dash keeps rendering. Cleared when projection ends.
            val context = LocalContext.current
            val projecting = state.phase == ConnectionPhase.PROJECTING
            DisposableEffect(projecting) {
                val window = (context as? android.app.Activity)?.window
                if (projecting && window != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window.attributes = window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    }
                }
                onDispose {
                    if (window != null) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        window.attributes = window.attributes.apply {
                            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        }
                    }
                }
            }
            // Search overlay state lives here at the app level so FullscreenSearch can
            // render above ConnectScreen — including the header, action rail, and all
            // the rest. Otherwise the search ends up inside the body Row of
            // ConnectScreen and gets compressed to nothing when the IME opens.
            // rememberSaveable so the search mode survives orientation changes.
            var searching by rememberSaveable { mutableStateOf(false) }
            if (state.showSettings) {
                SettingsScreen(
                    currentPassword = state.savedDashPassword,
                    currentSsidPrefix = state.savedSsidPrefix,
                    onSave = AppHost::saveSettings,
                    onBack = AppHost::closeSettings,
                )
            } else {
                ConnectScreen(
                    state = state,
                    onConnect = AppHost::connect,  // wi-fi + control + ble (activates widgets)
                    onProject = AppHost::project,  // arm video on demand
                    onStopProjection = AppHost::stopProjection,  // drop video, keep BLE link
                    onDisconnect = AppHost::disconnect,
                    onOpenSettings = AppHost::openSettings,
                    onActivateSearch = { searching = true },
                    onEasterEgg = AppHost::projectEasterEgg,
                )
            }
            if (state.needsPassword) {
                PasswordDialog(
                    onSubmit = AppHost::savePasswordAndConnect,
                    onDismiss = AppHost::dismissPasswordPrompt,
                )
            }
            // Order matters: drawn last so it covers everything else. Surface stacks
            // its children Box-style.
            if (searching) {
                FullscreenSearch(
                    modifier = Modifier.fillMaxSize().background(KoveColors.Void),
                    onDone = { searching = false },
                )
            }
        }
    }
}
