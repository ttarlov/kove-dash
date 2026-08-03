package com.kovedash.app.proto

import com.kovedash.app.net.DashBleClient
import com.kovedash.app.net.DashTcpServer
import com.kovedash.app.service.TelemetryFinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fires every plausible msg_id 27 GET we know about, waits for a reply, surfaces a
 * TelemetryFinding for each. No-response queries get marked (no response).
 *
 * Reply matching is heuristic. The dash emits an unsolicited "altitude:17" heartbeat
 * every few seconds; we filter that out so it doesn't get claimed as the answer to
 * whichever probe happened to be in-flight when it arrived.
 */
class TelemetryProbe(
    private val ble: DashBleClient,
    private val tcp: DashTcpServer,
    private val scope: CoroutineScope,
) {

    private val _findings = MutableSharedFlow<TelemetryFinding>(extraBufferCapacity = 64)
    val findings: SharedFlow<TelemetryFinding> = _findings

    suspend fun runOnce() {
        for (probe in PROBES) {
            val reply = sendAndWait(probe.json, timeoutMs = 600)
            _findings.emit(TelemetryFinding(label = probe.label, value = probe.extractor(reply), raw = reply))
            delay(150)
        }
    }

    private suspend fun sendAndWait(json: String, timeoutMs: Long): String? {
        // Subscribe BEFORE sending so we don't miss a fast reply.
        val collector = scope.async {
            withTimeoutOrNull(timeoutMs) {
                ble.notify.first { event ->
                    val text = String(event.bytes, Charsets.UTF_8)
                    !isUnsolicitedChatter(text)
                }.bytes.toString(Charsets.UTF_8)
            }
        }
        ble.sendJson(json)
        return collector.await()
    }

    /**
     * The dash emits an unsolicited status message every few seconds that looks like
     *   {"msg_id":25,"msg_type":17,"msg_source":1,"altitude":17,...}
     * Without filtering this, every probe gets "answered" by whichever heartbeat
     * lands in its window first.
     */
    private fun isUnsolicitedChatter(text: String): Boolean {
        return text.contains("\"msg_type\"") &&
            text.contains("\"msg_source\"") &&
            text.contains("\"altitude\"")
    }

    private data class Probe(
        val label: String,
        val json: String,
        val extractor: (String?) -> String?,
    )

    companion object {
        private val PROBES = listOf(
            Probe("Version", DashMessages.requestVersionCode()) {
                it?.let { s -> MiniJson.any(s, "version") ?: MiniJson.any(s, "ver") }
            },
            Probe("NAVI status", DashMessages.naviStatus()) { it?.let { s -> MiniJson.any(s, "act") } },
            Probe("GPS", DashMessages.probeFunc("GPS")) { it?.let { s -> MiniJson.any(s, "signal_status") } },
            Probe("THEME", DashMessages.probeFunc("THEME")) { it?.let { s -> MiniJson.any(s, "act") } },
            Probe("MUSIC", DashMessages.probeFunc("MUSIC")) { it?.let { s -> MiniJson.any(s, "ret_status") } },
            Probe("ROAD_NAVI", DashMessages.probeFunc("ROAD_NAVI")) { it?.let { s -> MiniJson.any(s, "get_status") } },
            Probe("KEY", DashMessages.probeFunc("KEY")) { it?.let { s -> MiniJson.any(s, "key") } },
            Probe("USER", DashMessages.probeFunc("USER")) { it?.let { s -> MiniJson.any(s, "racing") } },
            Probe("INSIDENAVI", DashMessages.probeFunc("INSIDENAVI")) { it?.take(120) },
        )
    }
}
