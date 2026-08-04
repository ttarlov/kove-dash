package com.kovedash.app.service

enum class ConnectionPhase {
    IDLE,
    JOINING_WIFI,
    WIFI_READY,
    BLE_HANDSHAKE,
    BLE_READY,
    TCP_LISTENING,
    DEVICE_DIALED,
    READY,
    PROJECTING,
    RECONNECTING,
    ERROR,
}

data class TelemetryFinding(
    val label: String,
    val value: String?,
    val raw: String? = null,
)

data class DashState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val dashGatewayIp: String? = null,
    val firmware: String? = null,
    val mac: String? = null,
    val deviceType: String? = null,
    val telemetry: List<TelemetryFinding> = emptyList(),
    val projectionWaitingForUp: Boolean = false,
    val liveMode: Boolean = false,
    // BLE-primary steady state: Wi-Fi has been intentionally dropped after it activated the
    // dash's native rendering. Widgets keep flowing over BLE; the supervisor won't fight the
    // (expected) Wi-Fi loss. Re-arm Wi-Fi via [AppHost.unparkWifi] (or projecting).
    val wifiParked: Boolean = false,
    // Whether the app has been granted Notification Access — required for the Google Maps
    // turn-by-turn forwarder (navshare). Default true so we don't flash the prompt before the
    // first real check (AppHost refreshes it on attach + on each resume).
    val notificationAccessGranted: Boolean = true,
    val errorMessage: String? = null,
    val needsPassword: Boolean = false,
    val reconnectAttempt: Int = 0,
    val batteryLevel: Int? = null,
    val showSettings: Boolean = false,
    val savedDashPassword: String? = null,
    val savedSsidPrefix: String = "CQKY_",
    val gpxCourseName: String? = null,
)
