package com.kovedash.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WiFi side. Two responsibilities:
 *   1. Discover the dash gateway IP after we've joined the AP (works on any API).
 *   2. Auto-join the dash AP via NetworkRequest + WifiNetworkSpecifier on API 29+.
 *
 * Below API 29 we tell the user to join manually. The Pixel 9 Pro runs API 35+ so
 * this is a paper safety; the real-world code path on this user's device is always
 * the NetworkRequest one.
 */
class DashWifi(private val context: Context) {

    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound

    // True after the system gave up on the dash NetworkRequest (onUnavailable). At that
    // point the callback is dead and the only way back is a fresh requestNetwork(), which
    // re-pops the Wi-Fi consent dialog. The supervisor watches this and stops the service
    // instead of poking the OS for another dialog. Reset on the next fresh registration.
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable

    @Suppress("DEPRECATION")
    fun dashGatewayIp(): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcp = wifi.dhcpInfo ?: return null
        val raw = dhcp.serverAddress
        if (raw == 0) return null
        return int2ip(raw)
    }

    /**
     * The current SSID, stripped of surrounding quotes. Returns null if not connected
     * or if the system returns the placeholder "<unknown ssid>" (happens when the
     * runtime location permission isn't granted).
     */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ssid = wifi.connectionInfo?.ssid ?: return null
        if (ssid == WifiManager.UNKNOWN_SSID || ssid.contains("unknown ssid", ignoreCase = true)) return null
        return ssid.trim('"').takeIf { it.isNotBlank() }
    }

    /**
     * Ensures a WifiNetworkSpecifier request is registered for the dash AP and waits for
     * the system to associate (onAvailable). Returns the resolved [Network] on success,
     * null on timeout / user-cancel / wrong password.
     *
     * Re-entrant: subsequent calls do NOT unregister + re-register the prior callback.
     * If a callback is already registered and live, this just waits on [bound]. The
     * existing callback auto-re-fires onAvailable when the dash AP returns after a
     * transient loss (bike key cycle). Re-registering would invalidate Android's
     * consent cache and pop the system Wi-Fi dialog again.
     *
     * The callback is torn down only on [release] (explicit disconnect) or after
     * onUnavailable (system definitively gave up — at which point the next call will
     * register fresh, accepting the cost of a consent re-prompt).
     */
    /** True if the phone is on the dash subnet (gateway 192.168.10.1) — a reliable
     * "we're on the dash AP" signal even when the SSID read comes back null. */
    fun isOnDashSubnet(): Boolean = dashGatewayIp() == DASH_GATEWAY

    suspend fun requestDashNetwork(
        ssidPrefix: String,
        password: String,
        exactSsid: String? = null,
        timeoutMs: Long = WAIT_FOR_AVAILABLE_MS,
    ): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "WifiNetworkSpecifier requires API 29+; please join AP manually")
            return null
        }

        // Previous request was retired by the system — refuse to re-register here. The
        // service supervisor watches [unavailable] and tears the whole thing down; we
        // belt-and-braces against the race where a stale supervisor tick gets here first.
        if (_unavailable.value) {
            Log.w(TAG, "requestDashNetwork: prior request unavailable, not re-registering")
            return null
        }

        // Fast path: already bound.
        if (_bound.value) {
            currentNetwork?.let {
                Log.i(TAG, "requestDashNetwork: already bound to $it")
                return it
            }
        }

        // Existing callback still registered — wait on it instead of re-requesting.
        // After onLost (bike off), the system MAY re-fire onAvailable when the AP
        // returns without us calling requestNetwork() again. This is the fast path
        // for the bike key-off → key-on cycle.
        if (activeCallback != null) {
            Log.i(TAG, "requestDashNetwork: existing callback in flight, waiting for AP")
            return waitForBound(timeoutMs)
        }

        // First registration this session, or re-registration after onUnavailable.
        registerFresh(ssidPrefix, password, exactSsid)
        return waitForBound(timeoutMs)
    }

    private fun registerFresh(ssidPrefix: String, password: String, exactSsid: String?) {
        _unavailable.value = false
        // Prefer an EXACT SSID: Android caches the user's approval for a specifier keyed
        // on an exact SSID, so after the first approval it auto-connects with NO dialog.
        // A prefix pattern defeats that cache and re-prompts every time — which is the
        // repeated-popup complaint. Fall back to the prefix only on the very first connect
        // (before we've learned the exact SSID).
        val builder = WifiNetworkSpecifier.Builder().setWpa2Passphrase(password)
        if (exactSsid != null) {
            builder.setSsid(exactSsid)
            Log.i(TAG, "specifier: exact SSID '$exactSsid' (approval will be cached)")
        } else {
            builder.setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
            Log.i(TAG, "specifier: prefix '$ssidPrefix' (first connect; will re-prompt)")
        }
        val specifier = builder.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "dash network available: $network")
                // Intentionally NOT calling bindProcessToNetwork — that would route
                // ALL app HTTP traffic through the dash AP (no internet) and break
                // Mapbox tile fetches. The dash subnet (192.168.10.0/24) has its
                // own Linux routing entry on wlan1, so wildcard ServerSockets pick
                // up dash dial-ins automatically; everything else uses the default
                // network (home Wi-Fi or cellular) for internet.
                currentNetwork = network
                _bound.value = true
            }
            override fun onUnavailable() {
                Log.w(TAG, "dash network unavailable (system gave up, request is dead)")
                currentNetwork = null
                _bound.value = false
                // Mark callback inactive so the next requestDashNetwork registers fresh.
                if (activeCallback === this) activeCallback = null
                // Signal the supervisor: don't auto-retry, that would re-pop the consent
                // dialog. The user has to tap Engage Link to start a fresh request.
                _unavailable.value = true
            }
            override fun onLost(network: Network) {
                Log.w(TAG, "dash network lost: $network (callback stays warm for reconnect)")
                if (currentNetwork == network) currentNetwork = null
                _bound.value = false
                // Don't null activeCallback — the request lives, so onAvailable can re-fire
                // when the bike powers back on without another consent prompt.
            }
        }
        activeCallback = cb
        cm.requestNetwork(request, cb)
        Log.i(TAG, "registered fresh dash NetworkCallback")
    }

    private suspend fun waitForBound(timeoutMs: Long): Network? {
        return withTimeoutOrNull(timeoutMs) {
            _bound.filter { it }.first()
            currentNetwork
        }
    }

    fun release() {
        activeCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
            activeCallback = null
        }
        currentNetwork = null
        // Defensive: in case some prior install bound the process, clear it.
        runCatching { cm.bindProcessToNetwork(null) }
        _bound.value = false
        _unavailable.value = false
    }

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    private fun int2ip(addr: Int): String =
        "${addr and 0xFF}.${(addr ushr 8) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 24) and 0xFF}"

    companion object {
        private const val TAG = "KoveDash"
        private const val DASH_GATEWAY = "192.168.10.1"
        // Generous — the first-time flow needs the rider to tap through the system Wi-Fi
        // "connect to device" dialog, which 15s didn't reliably allow. Once the
        // WifiNetworkSuggestion auto-join is approved, this path is the fallback anyway.
        private const val WAIT_FOR_AVAILABLE_MS = 45_000L
    }
}
