package com.kovedash.app.project

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the 15456 projection socket and the H.264 frame stream.
 *
 * Flow: phone listens, dash dials, phone SENDS the 69-byte handshake (server-initiated
 * per the working dash_server.py reference), phone streams Annex-B frames at 30fps.
 */
class ProjectionSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val socket = AtomicReference<Socket?>(null)
    private val serverSocket = AtomicReference<ServerSocket?>(null)
    private val running = AtomicBoolean(false)
    private var serverJob: Job? = null

    /**
     * Fired exactly once when the session is finished — the dash closed the video socket, the
     * stream errored, or [stop] was called. Lets the owner release the projection wake lock and
     * reset the phase; without it a canned session (e.g. the easter egg) leaks the wake lock and
     * leaves the UI stuck at PROJECTING after the dash disconnects.
     */
    var onEnded: (() -> Unit)? = null

    fun start(width: Int = 1280, height: Int = 640, assetName: String = DemoFrameSource.DEFAULT_ASSET) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "start: already running, ignoring")
            return
        }
        Log.i(TAG, "start: opening 15456 listener, waiting for dash dial-in")
        serverJob = scope.launch(Dispatchers.IO) {
            // reuseAddress + a hoisted ServerSocket ref so stop() can free 15456 immediately —
            // otherwise a still-open listen socket blocks the live map's bind (EADDRINUSE) when
            // switching from the canned easter-egg stream back to normal projection.
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            serverSocket.set(server)
            try {
                server.use {
                    val s = server.accept()
                    socket.set(s)
                    s.tcpNoDelay = true
                    Log.i(TAG, "15456 dash dialed in: ${s.remoteSocketAddress}")
                    sendHandshake(s.getOutputStream(), width, height)
                    Log.i(TAG, "15456 handshake sent (69B); streaming frames")
                    streamFrameLoop(s, assetName)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ProjectionSession failed", t)
            } finally {
                running.set(false)
                socket.set(null)
                serverSocket.set(null)
                Log.i(TAG, "ProjectionSession ended — notifying owner")
                runCatching { onEnded?.invoke() }
            }
        }
    }

    /** Synchronously frees 15456: closing the ServerSocket unblocks a pending accept() and
     *  releases the listen port before this returns, so the caller can immediately re-bind. */
    fun stop() {
        serverJob?.cancel()
        runCatching { serverSocket.getAndSet(null)?.close() }
        runCatching { socket.get()?.close() }
        socket.set(null)
    }

    private fun sendHandshake(out: java.io.OutputStream, width: Int, height: Int) {
        val buf = ByteArray(HANDSHAKE_LEN)
        val platform = "android".toByteArray(Charsets.UTF_8)
        System.arraycopy(platform, 0, buf, 1, platform.size)
        ByteBuffer.wrap(buf, 65, 2).order(ByteOrder.BIG_ENDIAN).putShort(width.toShort())
        ByteBuffer.wrap(buf, 67, 2).order(ByteOrder.BIG_ENDIAN).putShort(height.toShort())
        out.write(buf)
        out.flush()
    }

    private suspend fun streamFrameLoop(s: Socket, assetName: String) {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val frames = AnnexBParser.splitAccessUnits(bytes)
        val out = s.getOutputStream()
        Log.i(TAG, "asset=$assetName size=${bytes.size}B parsed=${frames.size} access units")

        if (frames.isEmpty()) {
            // Single-frame fallback: emit the whole asset each tick.
            singleFrameLoop(s, out, bytes)
            return
        }

        val frameIntervalNs = 1_000_000_000L / 30L
        var next = System.nanoTime()
        var i = 0L
        while (s.isConnected && !s.isClosed) {
            val au = frames[(i % frames.size).toInt()]
            withContext(Dispatchers.IO) {
                runCatching { out.write(au); out.flush() }.onFailure { return@withContext }
            }
            if (i == 0L) Log.i(TAG, "15456 -> first AU (${au.size}B), ${frames.size} frames in loop")
            else if (i % 300L == 0L) Log.i(TAG, "15456 -> frame #$i")
            i++
            next += frameIntervalNs
            val sleep = (next - System.nanoTime()) / 1_000_000L
            if (sleep > 0) kotlinx.coroutines.delay(sleep)
        }
    }

    private suspend fun singleFrameLoop(s: Socket, out: java.io.OutputStream, frame: ByteArray) {
        val frameIntervalNs = 1_000_000_000L / 30L
        var next = System.nanoTime()
        var i = 0L
        while (s.isConnected && !s.isClosed) {
            withContext(Dispatchers.IO) {
                runCatching { out.write(frame); out.flush() }.onFailure { return@withContext }
            }
            if (i == 0L) Log.i(TAG, "15456 -> first frame (${frame.size}B, single-frame loop)")
            else if (i % 300L == 0L) Log.i(TAG, "15456 -> frame #$i")
            i++
            next += frameIntervalNs
            val sleep = (next - System.nanoTime()) / 1_000_000L
            if (sleep > 0) kotlinx.coroutines.delay(sleep)
        }
    }

    companion object {
        const val PORT = 15456
        const val HANDSHAKE_LEN = 69
        private const val TAG = "KoveDash"
    }
}
