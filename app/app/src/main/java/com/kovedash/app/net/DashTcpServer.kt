package com.kovedash.app.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Phone is server on 17818 (device channel) and 15457 (heartbeat). The dash dials in
 * once BLE handshake says "phone is at <gateway-ip>".
 *
 * 15456 (projection) lives in ProjectionSession to keep video framing out of the
 * device/heartbeat path.
 */
class DashTcpServer(private val scope: CoroutineScope) {

    private val _inbound = MutableSharedFlow<InboundDeviceMessage>(extraBufferCapacity = 64)
    val inbound: SharedFlow<InboundDeviceMessage> = _inbound

    private val deviceSocket = AtomicReference<Socket?>(null)
    private val heartbeatSocket = AtomicReference<Socket?>(null)
    private val dvrSocket = AtomicReference<Socket?>(null)
    private val otaSocket = AtomicReference<Socket?>(null)
    private val deviceServer = AtomicReference<ServerSocket?>(null)
    private val heartbeatServer = AtomicReference<ServerSocket?>(null)
    private val dvrServer = AtomicReference<ServerSocket?>(null)
    private val otaServer = AtomicReference<ServerSocket?>(null)
    private var deviceJob: Job? = null
    private var heartbeatJob: Job? = null
    private var dvrJob: Job? = null
    private var otaJob: Job? = null

    fun startDeviceListener() {
        deviceJob?.cancel()
        closeQuietly(deviceServer, deviceSocket)
        deviceJob = scope.launch(Dispatchers.IO) { acceptLoop(PORT_DEVICE, deviceServer, deviceSocket, ::readDevice) }
    }

    fun startHeartbeatListener() {
        heartbeatJob?.cancel()
        closeQuietly(heartbeatServer, heartbeatSocket)
        // 15457 needs the outbound projection heartbeat — see runProjectionHeartbeat.
        heartbeatJob = scope.launch(Dispatchers.IO) { acceptLoop(PORT_HEARTBEAT, heartbeatServer, heartbeatSocket, ::runProjectionHeartbeat) }
    }

    fun startAuxListeners() {
        dvrJob?.cancel()
        closeQuietly(dvrServer, dvrSocket)
        dvrJob = scope.launch(Dispatchers.IO) { acceptLoop(PORT_DVR, dvrServer, dvrSocket, ::readHeartbeat) }
        otaJob?.cancel()
        closeQuietly(otaServer, otaSocket)
        otaJob = scope.launch(Dispatchers.IO) { acceptLoop(PORT_OTA, otaServer, otaSocket, ::readHeartbeat) }
    }

    suspend fun writeDevice(payload: ByteArray) {
        val s = deviceSocket.get() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                s.getOutputStream().apply { write(payload); flush() }
            }
        }
    }

    fun hasDeviceClient(): Boolean = deviceSocket.get() != null

    /**
     * Closes everything: client sockets, then the ServerSockets themselves. Closing a
     * ServerSocket is what actually unblocks a pending accept() — plain job
     * cancellation can't, so without this the ports stay bound after service death
     * and the next start crashes with BindException.
     */
    fun stop() {
        deviceJob?.cancel()
        heartbeatJob?.cancel()
        dvrJob?.cancel()
        otaJob?.cancel()
        closeQuietly(deviceServer, deviceSocket)
        closeQuietly(heartbeatServer, heartbeatSocket)
        closeQuietly(dvrServer, dvrSocket)
        closeQuietly(otaServer, otaSocket)
    }

    private fun closeQuietly(serverRef: AtomicReference<ServerSocket?>, socketRef: AtomicReference<Socket?>) {
        runCatching { socketRef.getAndSet(null)?.close() }
        runCatching { serverRef.getAndSet(null)?.close() }
    }

    private suspend fun acceptLoop(
        port: Int,
        serverRef: AtomicReference<ServerSocket?>,
        socketRef: AtomicReference<Socket?>,
        reader: suspend (InputStream, OutputStream) -> Unit,
    ) {
        val server = runCatching {
            ServerSocket().apply {
                // The OEM sets SO_REUSEADDR; without it a stop/start inside TIME_WAIT
                // can fail to rebind.
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
        }.onFailure {
            Log.e(TAG, "port $port bind failed — listener not started", it)
        }.getOrNull() ?: return
        serverRef.set(server)
        server.use {
            while (scope.isActive()) {
                val s = runCatching { server.accept() }.getOrNull() ?: return
                socketRef.set(s)
                runCatching {
                    s.tcpNoDelay = true
                    reader(s.getInputStream(), s.getOutputStream())
                }
                runCatching { s.close() }
                socketRef.set(null)
            }
        }
    }

    /**
     * V0: read just enough to recognize the 6-byte device-channel header
     *   [type:u8][sub:u8][len:u32_be]
     * plus an optional JSON envelope (handled inline because the dash also sends
     * those over 17818). Forwards parsed messages on the inbound flow.
     */
    private suspend fun readDevice(input: InputStream, output: OutputStream) {
        val header = ByteArray(6)
        while (true) {
            if (!readFully(input, header, 0, 6)) return
            val t = header[0].toInt() and 0xFF
            val s = header[1].toInt() and 0xFF
            val len = ((header[2].toInt() and 0xFF) shl 24) or
                ((header[3].toInt() and 0xFF) shl 16) or
                ((header[4].toInt() and 0xFF) shl 8) or
                (header[5].toInt() and 0xFF)
            if (t == 0xEE && s == 0xFD) {
                // It's actually a JSON envelope; len is the JSON body length.
                if (len < 0 || len > MAX_JSON_BODY) return
                val body = ByteArray(len)
                if (!readFully(input, body, 0, len)) return
                val tail = input.read()
                if (tail != 0xFF) return
                _inbound.emit(InboundDeviceMessage.Json(String(body, Charsets.UTF_8)))
            } else {
                if (len < 0 || len > MAX_BINARY_BODY) return
                val body = ByteArray(len)
                if (!readFully(input, body, 0, len)) return
                _inbound.emit(InboundDeviceMessage.Binary(t, s, body))
            }
        }
    }

    private suspend fun readHeartbeat(input: InputStream, output: OutputStream) {
        // V0: drain bytes, ignore content. Just keeping the socket open prevents the
        // dash from tearing the link down. Echo would also be valid.
        val scratch = ByteArray(64)
        while (input.read(scratch) >= 0) Unit
    }

    /**
     * 15457 outbound projection-heartbeat: 6-byte `02 01 00 00 00 00` every 450 ms,
     * phone → dash. The OEM ThinkerRide app does this with a `Timer` ticking at 450 ms
     * (phase2/_re_report_projection_encoder.md §8). Without it the dash treats the
     * peer as half-dead and may apply quality penalties or drop projection frames.
     * Drains the inbound side too — the dash sends nothing meaningful here, but we
     * have to keep reading so the socket doesn't backfill.
     */
    private suspend fun runProjectionHeartbeat(input: InputStream, output: OutputStream) = coroutineScope {
        val ping = byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x00)
        val pingJob = launch {
            while (isActive) {
                val ok = runCatching {
                    output.write(ping)
                    output.flush()
                }.isSuccess
                if (!ok) return@launch
                delay(450L)
            }
        }
        try {
            val scratch = ByteArray(64)
            while (input.read(scratch) >= 0) yield()
        } finally {
            pingJob.cancel()
        }
    }

    private fun readFully(input: InputStream, dst: ByteArray, offset: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(dst, offset + read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    sealed class InboundDeviceMessage {
        data class Json(val json: String) : InboundDeviceMessage()
        data class Binary(val type: Int, val sub: Int, val payload: ByteArray) : InboundDeviceMessage() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Binary) return false
                return type == other.type && sub == other.sub && payload.contentEquals(other.payload)
            }
            override fun hashCode(): Int = (type * 31 + sub) * 31 + payload.contentHashCode()
        }
    }

    companion object {
        private const val TAG = "KoveDash"
        const val PORT_DEVICE = 17818
        const val PORT_HEARTBEAT = 15457
        const val PORT_PROJECTION = 15456
        const val PORT_DVR = 18888
        const val PORT_OTA = 19000
        private const val MAX_JSON_BODY = 1 shl 20
        private const val MAX_BINARY_BODY = 1 shl 22
    }
}

// kotlinx.coroutines does not expose CoroutineScope.isActive directly on Job-rooted scopes.
private fun CoroutineScope.isActive(): Boolean = coroutineContext[Job]?.isActive ?: false
