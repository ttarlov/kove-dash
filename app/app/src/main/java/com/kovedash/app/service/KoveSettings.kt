package com.kovedash.app.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny settings wrapper. V1 uses plain SharedPreferences; V1.1 will swap in
 * EncryptedSharedPreferences for the password field.
 */
class KoveSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var dashPassword: String?
        get() = prefs.getString(KEY_DASH_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_DASH_PASSWORD, value).apply()

    var dashSsidPrefix: String
        get() = prefs.getString(KEY_DASH_SSID_PREFIX, DEFAULT_SSID_PREFIX) ?: DEFAULT_SSID_PREFIX
        set(value) = prefs.edit().putString(KEY_DASH_SSID_PREFIX, value).apply()

    // The exact dash SSID (e.g. "CQKY_XXXXXXXXX"), learned on first successful connect.
    // Needed for the WifiNetworkSuggestion auto-join (suggestions require an exact SSID,
    // not a prefix).
    var dashExactSsid: String?
        get() = prefs.getString(KEY_DASH_EXACT_SSID, null)
        set(value) = prefs.edit().putString(KEY_DASH_EXACT_SSID, value).apply()

    // The dash's BLE MAC (e.g. "D8:02:F7:D6:80:0D"), learned on first successful connect.
    // Lets us connect DIRECTLY (getRemoteDevice + connectGatt) without scanning — immune to
    // Android BLE scan throttling and to the dash not advertising when it's mid-reconnect.
    var dashMac: String?
        get() = prefs.getString(KEY_DASH_MAC, null)
        set(value) = prefs.edit().putString(KEY_DASH_MAC, value).apply()

    companion object {
        private const val NAME = "kovedash.settings"
        private const val KEY_DASH_PASSWORD = "dash_password"
        private const val KEY_DASH_SSID_PREFIX = "dash_ssid_prefix"
        private const val KEY_DASH_EXACT_SSID = "dash_exact_ssid"
        private const val KEY_DASH_MAC = "dash_mac"
        const val DEFAULT_SSID_PREFIX = "CQKY_"
    }
}
