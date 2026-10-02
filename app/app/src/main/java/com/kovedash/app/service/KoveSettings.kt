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

    // Forward phone notifications (texts etc.) to the dash as msg_id=6 banners. Opt-in (off by
    // default) — it needs Notification Access, and a rider should choose to see pings mid-ride.
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ENABLED, value).apply()

    // Package allow-list: only these apps' notifications are forwarded (an empty-set guard would
    // flood the dash with every ping). Seeded with common messaging apps; getStringSet returns a
    // copy so we never mutate the stored set in place.
    var notifyApps: Set<String>
        get() = prefs.getStringSet(KEY_NOTIF_APPS, null)?.toSet() ?: DEFAULT_NOTIFY_APPS
        set(value) = prefs.edit().putStringSet(KEY_NOTIF_APPS, value).apply()

    companion object {
        private const val NAME = "kovedash.settings"
        private const val KEY_DASH_PASSWORD = "dash_password"
        private const val KEY_DASH_SSID_PREFIX = "dash_ssid_prefix"
        private const val KEY_DASH_EXACT_SSID = "dash_exact_ssid"
        private const val KEY_DASH_MAC = "dash_mac"
        private const val KEY_NOTIF_ENABLED = "notif_enabled"
        private const val KEY_NOTIF_APPS = "notif_apps"
        const val DEFAULT_SSID_PREFIX = "CQKY_"

        // Default forward list — common messaging apps. The user can refine later (app-picker UI
        // is a fast-follow); these cover the "see my texts on the dash" case out of the box.
        val DEFAULT_NOTIFY_APPS: Set<String> = setOf(
            "com.google.android.apps.messaging", // Google Messages (SMS/RCS)
            "org.thoughtcrime.securesms",         // Signal
            "com.whatsapp",                       // WhatsApp
            "org.telegram.messenger",             // Telegram
            "com.facebook.orca",                  // Messenger
        )
    }
}
