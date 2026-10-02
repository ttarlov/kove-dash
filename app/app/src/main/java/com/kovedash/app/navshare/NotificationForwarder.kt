package com.kovedash.app.navshare

import android.content.Context
import android.util.Log
import com.kovedash.app.AppHost
import com.kovedash.app.service.ConnectionPhase
import com.kovedash.app.service.DashService

/**
 * Forwards an allow-listed phone notification to the dash as a msg_id=6 banner. Sibling to
 * [NavForwarder]: it holds no BLE reference, gates on connection state, dedups/throttles, and
 * fires a [DashService] intent so the send inherits the single BLE owner + mutex.
 *
 * Render facts (probed on-dash 2026-10-01, see docs/re/dash_render_map.md): the dash shows
 * msg_id=6 `{title, content}` as a yellow banner — `title` in the header, `content` scrolling,
 * open/dismiss with the dash SELECT key. So `title` = sender, `content` = message body. The
 * firmware wraps ~13 chars and breaks words mid-word, so we cap lengths rather than fight it.
 */
object NotificationForwarder {

    private const val TAG = "KoveDash"

    // Phases where the dash link is up and can render a banner (same set NavForwarder uses).
    private val USABLE = setOf(
        ConnectionPhase.READY,
        ConnectionPhase.PROJECTING,
        ConnectionPhase.DEVICE_DIALED,
    )

    // Messaging apps re-post / update the same notification repeatedly; drop an identical
    // (pkg|title|body) within this window. A short min-gap stops a burst from flooding the link.
    private const val DEDUP_WINDOW_MS = 10_000L
    private const val MIN_GAP_MS = 1_500L
    private const val MAX_TITLE = 32
    private const val MAX_CONTENT = 110

    private var lastKey: String? = null
    private var lastKeyMs = 0L
    private var lastSendMs = 0L

    /** [title] = sender (notification title), [appLabel] = fallback when the notif has no title. */
    fun onNotification(ctx: Context, pkg: String, appLabel: String, title: String?, text: String?) {
        val phase = AppHost.state.value.phase
        if (phase !in USABLE) {
            Log.i(TAG, "notif-fwd: drop — dash not usable (phase=$phase)")
            return
        }
        val header = (title?.takeIf { it.isNotBlank() } ?: appLabel).trim().take(MAX_TITLE)
        val body = (text ?: "").trim().take(MAX_CONTENT)
        if (body.isBlank()) {
            Log.i(TAG, "notif-fwd: drop — empty body ($pkg)")
            return
        }
        val key = "$pkg|$header|$body"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastKeyMs < DEDUP_WINDOW_MS) {
            Log.i(TAG, "notif-fwd: dedup (same notif within ${DEDUP_WINDOW_MS}ms)")
            return
        }
        if (now - lastSendMs < MIN_GAP_MS) {
            Log.i(TAG, "notif-fwd: throttled (${now - lastSendMs}ms < ${MIN_GAP_MS}ms)")
            return
        }
        lastKey = key
        lastKeyMs = now
        lastSendMs = now
        Log.i(TAG, "notif-fwd: forwarding $pkg header='$header' body='${body.take(40)}'")
        DashService.forwardNotify(ctx, header, body)
    }
}
