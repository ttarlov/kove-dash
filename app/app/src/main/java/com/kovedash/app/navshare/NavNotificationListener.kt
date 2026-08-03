package com.kovedash.app.navshare

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kovedash.app.BuildConfig

/**
 * Reads Google Maps' ongoing navigation notification and forwards each turn to the dash
 * over BLE (via [NavForwarder] → DashService). The Garmin/Gadgetbridge model: Google Maps
 * does all routing; we're a relay. Requires the user to grant Notification Access
 * (Settings → Notification access) — see AppHost.openNotificationAccessSettings().
 *
 * Swap point: [classifier] is the only thing that changes to move from text parsing to
 * icon-bitmap matching later.
 */
class NavNotificationListener : NotificationListenerService() {

    private val classifier: ManeuverClassifier = TextManeuverClassifier()

    // The StatusBarNotification key of the active Maps nav notification, so we can match
    // its removal (nav ended) precisely rather than tearing down on any Maps notification.
    @Volatile
    private var activeNavKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PKG) return
        val n = sbn.notification
        val ongoing = n != null && (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        val localOnly = n != null && (n.flags and android.app.Notification.FLAG_LOCAL_ONLY) != 0
        val title = n?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)
        val text = n?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)
        val subText = n?.extras?.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)
        // subText carries the trip-level values (distance to destination + time remaining); log
        // it (and, in debug, the full extras key set) so a real ride confirms Maps' exact format.
        Log.i(TAG, "navshare RX maps notif: ongoing=$ongoing localOnly=$localOnly " +
            "title='$title' text='$text' subText='$subText'")
        if (BuildConfig.DEBUG && n != null) {
            Log.d(TAG, "navshare notif extras: ${n.extras.keySet().joinToString(",")}")
        }
        if (n == null || !ongoing || !localOnly) return
        val update = NavNotificationParser.parse(n.extras, classifier)
        if (update == null) {
            Log.i(TAG, "navshare: parse=null (no usable maneuver/distance) — skipping")
            return
        }
        activeNavKey = sbn.key
        Log.i(TAG, "navshare parsed: ${update.maneuver} road='${update.nextRoad}' " +
            "dist=${update.distanceToTurnMeters}m destM=${update.distanceToDestinationMeters} " +
            "remainS=${update.remainingTimeSec}")
        NavForwarder.onUpdate(applicationContext, update)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PKG) return
        if (activeNavKey != null && sbn.key == activeNavKey) {
            activeNavKey = null
            NavForwarder.onEnded(applicationContext)
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "navshare listener connected")
        instance = this
        // Resume an already-running navigation (e.g. after a listener rebind).
        runCatching {
            activeNotifications
                ?.firstOrNull { isMapsNav(it) }
                ?.let { sbn ->
                    NavNotificationParser.parse(sbn.notification.extras, classifier)?.let { update ->
                        activeNavKey = sbn.key
                        NavForwarder.onUpdate(applicationContext, update)
                    }
                }
        }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "navshare listener disconnected")
        activeNavKey = null
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Google Maps + ongoing + local-only = the navigation notification (Gadgetbridge's filter). */
    private fun isMapsNav(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != MAPS_PKG) return false
        val n = sbn.notification ?: return false
        val ongoing = (n.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0
        return ongoing && n.flags and android.app.Notification.FLAG_LOCAL_ONLY != 0
    }

    companion object {
        private const val TAG = "KoveDash"
        private const val MAPS_PKG = "com.google.android.apps.maps"

        // Live listener instance, so a full app quit can release it. The system binds a
        // NotificationListenerService PERSISTENTLY and RESPAWNS the process whenever it dies —
        // so killing the process on "Disconnect" isn't enough; the OS immediately rebinds and
        // brings us back. requestUnbind() releases that hold so the process can truly die and
        // STAY dead until the app is launched again (which re-binds via [requestRebindNow]).
        @Volatile
        private var instance: NavNotificationListener? = null

        /** Release the system's hold on our listener so a killed process won't be respawned. */
        fun releaseForQuit() {
            runCatching {
                instance?.requestUnbind()
                Log.i(TAG, "navshare listener requestUnbind() for full quit")
            }
        }

        /** Re-bind the listener on app launch (after a prior [releaseForQuit]). Best-effort. */
        fun requestRebindNow(ctx: android.content.Context) {
            runCatching {
                requestRebind(android.content.ComponentName(ctx, NavNotificationListener::class.java))
            }
        }
    }
}
