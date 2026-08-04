package com.kovedash.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kovedash.app.service.ConnectionPhase
import com.kovedash.app.service.DashState
import com.kovedash.app.ui.components.BeveledButton
import com.kovedash.app.ui.components.BeveledButtonVariant
import com.kovedash.app.ui.components.CriticalBanner
import com.kovedash.app.ui.components.HudCard
import com.kovedash.app.ui.components.HudSplit
import com.kovedash.app.ui.components.PacenoteRow
import com.kovedash.app.ui.components.PacenoteState
import com.kovedash.app.ui.components.PositionPill
import com.kovedash.app.ui.components.scanlineOverlay
import com.kovedash.app.ui.components.SectionHeader
import com.kovedash.app.ui.components.SponsorBand
import com.kovedash.app.ui.components.SplitKind
import com.kovedash.app.ui.components.StageBar
import com.kovedash.app.ui.dash.ManeuverBanner
import com.kovedash.app.ui.dash.NavMap
import com.kovedash.app.ui.theme.KoveColors
import com.kovedash.app.ui.theme.KoveFonts

private enum class DashTab { Map, Telemetry }

@Composable
fun ConnectScreen(
    state: DashState,
    onConnect: () -> Unit,
    onProject: () -> Unit,
    onStopProjection: () -> Unit = {},
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onActivateSearch: () -> Unit = {},
    onEasterEgg: () -> Unit = {},
    onGrantNotifAccess: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(DashTab.Map) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoveColors.Void)
            .scanlineOverlay()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // In landscape the bike is mounted and screen real estate is precious — drop
        // the top SponsorBand + Header (KoveDash brand line, status pill, SET cog)
        // so the MAP / TELEMETRY tab row is the topmost element and the map gets
        // those ~120dp back. Settings are still reachable by rotating to portrait;
        // the right rail's PhaseStrip carries the handshake status info that lived
        // in the header pill.
        if (!isLandscape) {
            SponsorBand(modifier = Modifier.fillMaxWidth())
            Header(state = state, onOpenSettings = onOpenSettings, onEasterEgg = onEasterEgg)
        }
        // Turn-by-turn reads Google Maps' navigation notification, which needs Notification
        // Access. If it isn't granted, surface a prompt that deep-links to the system settings
        // screen (AppHost.openNotificationAccessSettings). Clears automatically once granted —
        // MainActivity.onResume re-checks on return. Shown in both orientations so it can't be
        // missed before a ride.
        if (!state.notificationAccessGranted) {
            BeveledButton(
                label = "ENABLE TURN-BY-TURN",
                meta = "Grant Notification access →",
                onClick = onGrantNotifAccess,
                variant = BeveledButtonVariant.Action,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Landscape: split the body into [tabbed content | controls rail] so the map
        // gets the lion's share of vertical space instead of being squeezed to a
        // sliver by the bottom-stacked action buttons. Portrait keeps the original
        // vertical stack — there's plenty of height there for everything to breathe.
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TabRow(selected = selectedTab, onSelect = { selectedTab = it })
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(KoveColors.Void2)
                            .border(1.dp, KoveColors.Hairline2),
                    ) {
                        when (selectedTab) {
                            DashTab.Map -> MapTab(onActivateSearch = onActivateSearch)
                            DashTab.Telemetry -> TelemetryTab(state)
                        }
                    }
                }
                // Right rail: PhaseStrip + ActionBar. Scrolls in case the PhaseStrip
                // expansion (during BLE_HANDSHAKE the pacenote rows extend) plus three
                // ActionBar buttons (in READY) overflow the rail height.
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PhaseStrip(state)
                    ActionBar(
                        state = state,
                        onConnect = onConnect,
                        onProject = onProject,
                        onStopProjection = onStopProjection,
                        onDisconnect = onDisconnect,
                    )
                }
            }
        } else {
            TabRow(selected = selectedTab, onSelect = { selectedTab = it })
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(KoveColors.Void2)
                    .border(1.dp, KoveColors.Hairline2),
            ) {
                when (selectedTab) {
                    DashTab.Map -> MapTab(onActivateSearch = onActivateSearch)
                    DashTab.Telemetry -> TelemetryTab(state)
                }
            }
            PhaseStrip(state)
            ActionBar(
                state = state,
                onConnect = onConnect,
                onProject = onProject,
                onStopProjection = onStopProjection,
                onDisconnect = onDisconnect,
            )
        }
        SponsorBand(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Header(state: DashState, onOpenSettings: () -> Unit, onEasterEgg: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .border(0.dp, KoveColors.Ink),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "KoveDash",
                    color = KoveColors.Yellow,
                    fontFamily = KoveFonts.BungeeInline,
                    fontSize = 26.sp,
                    lineHeight = 26.sp,
                    letterSpacing = 0.02.sp,
                    // Hidden easter egg: long-press the brand → project the "this is fine" dog.
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onLongPress = { onEasterEgg() })
                    },
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = phaseDotColor(state.phase))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = subtagFor(state),
                        color = phaseDotColor(state.phase),
                        fontFamily = KoveFonts.PressStart2P,
                        fontSize = 7.sp,
                        letterSpacing = 0.1.sp,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pillFor(state)?.let { (label, denom, color) ->
                    PositionPill(
                        label = label,
                        denom = denom,
                        color = color,
                        textColor = if (color == KoveColors.Mint || color == KoveColors.Yellow) KoveColors.Ink else KoveColors.Paper,
                    )
                }
                SettingsCog(onClick = onOpenSettings)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(KoveColors.Ink))
    }
}

@Composable
private fun TabRow(selected: DashTab, onSelect: (DashTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoveColors.Void2)
            .border(1.dp, KoveColors.Hairline2),
    ) {
        DashTab.entries.forEach { key ->
            TabButton(
                label = key.name.uppercase(),
                selected = selected == key,
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) KoveColors.Yellow else Color.Transparent
    val fg = if (selected) KoveColors.Ink else KoveColors.Sky
    Box(
        modifier = modifier
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 10.sp,
            letterSpacing = 0.15.sp,
        )
    }
}

@Composable
private fun MapTab(onActivateSearch: () -> Unit) {
    // The destination tap-target asks the App level to flip on FullscreenSearch as a
    // top-level overlay — that way the search covers the header + action rail + everything,
    // instead of being trapped inside this tab where IME adjustResize would squash it
    // to a few dp tall in landscape.
    Column(modifier = Modifier.fillMaxSize()) {
        ManeuverBanner(modifier = Modifier.fillMaxWidth())
        NavMap(modifier = Modifier.fillMaxWidth().weight(1f), keepAlive = false, autoFollow = false)
        DestinationBar(
            modifier = Modifier.fillMaxWidth(),
            onActivateSearch = onActivateSearch,
        )
    }
}

@Composable
private fun TelemetryTab(state: DashState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(number = "§00", title = "Identity")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KoveColors.Void)
                .border(1.dp, KoveColors.Hairline2)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IdleKv(label = "Phase", value = subtagFor(state), color = phaseDotColor(state.phase))
            IdleKv(label = "Gateway", value = state.dashGatewayIp ?: "—", color = if (state.dashGatewayIp != null) KoveColors.Mint else KoveColors.Paper.copy(alpha = 0.5f))
            IdleKv(label = "Firmware", value = state.firmware ?: "—", color = KoveColors.Paper)
            IdleKv(label = "MAC", value = state.mac ?: "—", color = KoveColors.Paper)
            IdleKv(label = "Device", value = state.deviceType ?: "—", color = KoveColors.Yellow)
            IdleKv(label = "Battery", value = state.batteryLevel?.let { "$it%" } ?: "—", color = batteryColor(state.batteryLevel))
            IdleKv(label = "SSID Prefix", value = state.savedSsidPrefix, color = KoveColors.Sky)
        }

        SectionHeader(number = "§01", title = "Probes")
        TelemetryPanel(state = state, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PhaseStrip(state: DashState) {
    when (state.phase) {
        ConnectionPhase.IDLE,
        ConnectionPhase.ERROR -> {
            if (state.errorMessage != null) ErrorBox(state.errorMessage)
            else PressStart()
        }
        ConnectionPhase.JOINING_WIFI,
        ConnectionPhase.WIFI_READY,
        ConnectionPhase.BLE_HANDSHAKE,
        ConnectionPhase.BLE_READY,
        ConnectionPhase.TCP_LISTENING,
        ConnectionPhase.RECONNECTING -> HandshakeProgress(state)
        ConnectionPhase.DEVICE_DIALED,
        ConnectionPhase.READY -> CriticalBanner(
            headlinePrefix = "PRESS",
            highlightedWord = "UP!",
            body = buildAnnotatedString {
                withStyle(SpanStyle(color = KoveColors.Sky)) { append("Hold ") }
                withStyle(SpanStyle(color = KoveColors.Yellow)) { append("UP") }
                withStyle(SpanStyle(color = KoveColors.Sky)) { append(" on dash ~2s to unlock projection") }
            },
        )
        ConnectionPhase.PROJECTING -> HudCard(
            headlineNumber = 30,
            headlineUnit = "FPS",
            framePill = "STREAMING · 15456 TCP",
            splits = buildList {
                add(HudSplit("0 DROPS", SplitKind.Good))
                state.batteryLevel?.let { add(HudSplit("$it% BAT", if (it < 20) SplitKind.Bad else SplitKind.Info)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HandshakeProgress(state: DashState) {
    val stage = if (state.phase == ConnectionPhase.RECONNECTING) {
        ((state.reconnectAttempt - 1) % 6) + 1
    } else stageFor(state.phase)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StageBar(
            title = if (state.phase == ConnectionPhase.RECONNECTING) "Reconnecting" else "Establishing Link",
            currentStage = stage,
        )
        val rows = listOf(
            "msg 13 · requestVersionCode" to (stage >= 1),
            "msg 24 · sendLinkInfo" to (stage >= 2),
            "msg 26 · requestProductType" to (stage >= 3),
            "msg 54 · checkVehicleCurStatus" to (stage >= 4),
            "msg 27 · INSIDENAVI q=2" to (stage >= 5),
            "msg 27 · INSIDENAVI q=1" to (stage >= 6),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { i, (body, done) ->
                val rowState = when {
                    done -> PacenoteState.Done
                    i + 1 == stage + 1 -> PacenoteState.Live
                    else -> PacenoteState.Pending
                }
                PacenoteRow(body = body, state = rowState, modifier = Modifier.fillMaxWidth())
            }
        }
        if (state.errorMessage != null) ErrorBox(state.errorMessage)
    }
}

@Composable
private fun ActionBar(
    state: DashState,
    onConnect: () -> Unit,
    onProject: () -> Unit,
    onStopProjection: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (state.phase) {
            ConnectionPhase.IDLE,
            ConnectionPhase.ERROR -> {
                // Connect the control link (wi-fi + 17818 + ble) that activates the dash's
                // native widgets — weather + turn-by-turn. No video (encoder off = low
                // power). Video is the separate "Project" action once connected.
                BeveledButton(
                    label = "Connect",
                    meta = "wi-fi · ble · widgets · no video",
                    variant = BeveledButtonVariant.Action,
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ConnectionPhase.JOINING_WIFI,
            ConnectionPhase.WIFI_READY,
            ConnectionPhase.BLE_HANDSHAKE,
            ConnectionPhase.BLE_READY,
            ConnectionPhase.TCP_LISTENING,
            ConnectionPhase.RECONNECTING -> {
                BeveledButton(
                    label = "Abort",
                    meta = "cancel stage",
                    variant = BeveledButtonVariant.Stop,
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ConnectionPhase.DEVICE_DIALED,
            ConnectionPhase.READY,
            ConnectionPhase.PROJECTING -> {
                // Single toggle. Default steady state is BLE-only (widgets over BLE, Wi-Fi idle).
                // "Project" brings Wi-Fi up + arms video (turns red, "PROJECTION LIVE"); tapping it
                // again stops projection and drops back to BLE-only.
                if (state.liveMode) {
                    BeveledButton(
                        label = "PROJECTION LIVE",
                        meta = "tap to stop · back to ble-only",
                        variant = BeveledButtonVariant.Stop,
                        onClick = onStopProjection,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BeveledButton(
                        label = "Project",
                        meta = "add wi-fi + map video · hold UP on dash",
                        variant = BeveledButtonVariant.Go,
                        onClick = onProject,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                BeveledButton(
                    label = "Disconnect",
                    meta = "drop ble + tcp",
                    variant = BeveledButtonVariant.Ghost,
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoveColors.MagentaShadow)
            .border(1.dp, KoveColors.Magenta)
            .padding(10.dp),
    ) {
        Text(message, color = KoveColors.Paper, fontFamily = KoveFonts.VT323, fontSize = 16.sp)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.height(7.dp).padding(0.dp).background(color)) {
        Spacer(Modifier.width(7.dp).height(7.dp))
    }
}

private fun phaseDotColor(phase: ConnectionPhase) = when (phase) {
    ConnectionPhase.IDLE -> KoveColors.Paper.copy(alpha = 0.5f)
    ConnectionPhase.ERROR -> KoveColors.Magenta
    ConnectionPhase.PROJECTING -> KoveColors.Magenta
    ConnectionPhase.RECONNECTING -> KoveColors.Yellow
    ConnectionPhase.READY, ConnectionPhase.DEVICE_DIALED -> KoveColors.Mint
    else -> KoveColors.Sky
}

private fun subtagFor(state: DashState): String = when (state.phase) {
    ConnectionPhase.IDLE -> "K450 RALLY · STANDBY"
    ConnectionPhase.JOINING_WIFI -> "JOINING DASH AP"
    ConnectionPhase.WIFI_READY -> "WI-FI LINKED"
    ConnectionPhase.BLE_HANDSHAKE -> "BLE HANDSHAKE"
    ConnectionPhase.BLE_READY -> "BLE READY"
    ConnectionPhase.TCP_LISTENING -> "TCP LISTENING"
    ConnectionPhase.DEVICE_DIALED -> "LINK ESTABLISHED"
    ConnectionPhase.READY -> "READY · AWAITING UP"
    ConnectionPhase.PROJECTING -> "STREAMING · 15456 TCP"
    ConnectionPhase.RECONNECTING -> "RECONNECTING · ATTEMPT ${state.reconnectAttempt}"
    ConnectionPhase.ERROR -> "ERROR"
}

private fun pillFor(state: DashState): Triple<String, String?, Color>? = when (state.phase) {
    ConnectionPhase.IDLE -> Triple("00", "/06", KoveColors.PurpleDim)
    ConnectionPhase.READY, ConnectionPhase.DEVICE_DIALED -> Triple("RDY", "/06", KoveColors.Mint)
    ConnectionPhase.PROJECTING -> Triple("LIVE", null, KoveColors.Magenta)
    ConnectionPhase.RECONNECTING -> Triple("${state.reconnectAttempt}", null, KoveColors.Yellow)
    ConnectionPhase.ERROR -> Triple("ERR", null, KoveColors.Magenta)
    else -> {
        val stage = stageFor(state.phase)
        Triple("%02d".format(stage), "/06", KoveColors.Magenta)
    }
}

private fun stageFor(phase: ConnectionPhase): Int = when (phase) {
    ConnectionPhase.JOINING_WIFI -> 1
    ConnectionPhase.WIFI_READY -> 2
    ConnectionPhase.BLE_HANDSHAKE -> 3
    ConnectionPhase.BLE_READY -> 4
    ConnectionPhase.TCP_LISTENING -> 5
    ConnectionPhase.DEVICE_DIALED, ConnectionPhase.READY -> 6
    else -> 0
}

@Composable
private fun SettingsCog(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(KoveColors.Void2)
            .border(2.dp, KoveColors.Yellow)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = "SET",
            color = KoveColors.Yellow,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 8.sp,
            letterSpacing = 0.1.sp,
        )
    }
}

private fun batteryColor(level: Int?) = when {
    level == null -> KoveColors.Paper.copy(alpha = 0.5f)
    level < 10 -> KoveColors.Magenta
    level < 20 -> KoveColors.Yellow
    else -> KoveColors.Mint
}

@Composable
private fun IdleKv(label: String, value: String, color: Color) {
    Row {
        Text(
            text = label.uppercase(),
            color = KoveColors.Sky,
            fontFamily = KoveFonts.PressStart2P,
            fontSize = 8.sp,
            modifier = Modifier.weight(1f),
            letterSpacing = 0.06.sp,
        )
        Text(
            text = value,
            color = color,
            fontFamily = KoveFonts.VT323,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun PressStart() {
    val infinite = rememberInfiniteTransition(label = "press-start")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "press-blink",
    )
    Text(
        text = "PRESS ENGAGE TO START",
        color = KoveColors.Yellow.copy(alpha = alpha),
        fontFamily = KoveFonts.PressStart2P,
        fontSize = 10.sp,
        letterSpacing = 0.15.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
