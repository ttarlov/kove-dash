package com.kovedash.app.project

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.Composable
import com.kovedash.app.ui.dash.DashPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * V2 live projection: phone listens on 15456, dash dials in after long-press UP, phone sends
 * the 69-byte handshake, then a Compose UI is rendered into an [android.app.Presentation]
 * hosted on a private [VirtualDisplay] whose input Surface is a [MediaCodec] H.264 encoder.
 * The encoder's output NALUs (Annex-B) are streamed to the dash. Zero bitmap copies — GPU
 * end-to-end.
 *
 * The listener survives per-connection failure: when the dash drops the socket (Wi-Fi blip,
 * dash-side exit, decoder timeout) the encoder pipeline is torn down but the ServerSocket
 * stays open — the OEM keeps 15456 open indefinitely — so a re-long-press UP resumes
 * projection without touching the phone. [onStreaming] reports dial-in/drop transitions and
 * [onEnded] fires exactly once when the whole session is over (fatal error or [stop]).
 */
class LiveProjectionSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val content: @Composable () -> Unit,
) {

    /** True when a dash is connected and frames are flowing; false when re-armed waiting for UP. */
    var onStreaming: ((Boolean) -> Unit)? = null

    /** Fired exactly once when the session is finished for good (listener dead or stop()). */
    var onEnded: (() -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val endedNotified = AtomicBoolean(false)
    private var serverJob: Job? = null
    private val serverSocket = AtomicReference<ServerSocket?>(null)

    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var presentation: DashPresentation? = null
    private var socket: Socket? = null

    /**
     * Start the projection listener. If [mediaProjection] is non-null, the underlying
     * VirtualDisplay is created via that MediaProjection — survives device screen-off
     * and app-background (SurfaceFlinger keeps the MP-anchored display's vsync alive
     * even when the primary display blanks). Null falls back to the legacy
     * DisplayManager path, which dies on screen-off.
     */
    fun start(
        width: Int = 1280,
        height: Int = 640,
        mediaProjection: MediaProjection? = null,
    ) {
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "start: already running, ignoring")
            return
        }
        endedNotified.set(false)

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                serverSocket.set(server)
                server.use {
                    // Accept loop: one connection at a time, re-arm after each drop.
                    // Mirrors the OEM SocketListenThread, which never closes 15456.
                    while (running.get()) {
                        Log.i(TAG, "15456 listener up, waiting for dash dial-in")
                        val s = runCatching { server.accept() }.getOrNull() ?: break
                        socket = s
                        s.tcpNoDelay = true
                        // Small send buffer (~200 ms of video at 2.4 Mbps). The default
                        // multi-hundred-KB buffer absorbs seconds of stale frames before
                        // write() ever blocks, which the rider experiences as the map
                        // falling further and further behind. A small buffer pushes
                        // backpressure to the encoder quickly, where
                        // KEY_REPEAT_PREVIOUS_FRAME_AFTER pacing self-limits latency.
                        runCatching { s.sendBufferSize = SEND_BUFFER_BYTES }
                        Log.i(TAG, "15456 dash dialed in: ${s.remoteSocketAddress}")
                        val ok = runCatching {
                            sendHandshake(s.getOutputStream(), width, height)
                            Log.i(TAG, "15456 handshake sent (69B); starting live encoder")
                            onStreaming?.invoke(true)
                            runEncoderPipeline(s, width, height, mediaProjection)
                        }
                        releaseConnection()
                        if (ok.isFailure) {
                            val cause = ok.exceptionOrNull()
                            if (cause is FatalPipelineException) {
                                Log.e(TAG, "fatal pipeline error — ending session", cause)
                                break
                            }
                            Log.w(TAG, "connection ended with error — re-arming listener", cause)
                        }
                        if (running.get()) {
                            Log.i(TAG, "dash connection ended — listener re-armed for next long-press UP")
                            onStreaming?.invoke(false)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "LiveProjectionSession failed", t)
            } finally {
                releaseConnection()
                running.set(false)
                serverSocket.set(null)
                if (endedNotified.compareAndSet(false, true)) onEnded?.invoke()
            }
        }
    }

    fun stop() {
        running.set(false)
        // Closing the ServerSocket unblocks a pending accept(); cancel alone can't.
        runCatching { serverSocket.getAndSet(null)?.close() }
        serverJob?.cancel()
        releaseConnection()
        if (endedNotified.compareAndSet(false, true)) onEnded?.invoke()
    }

    /** Tears down the per-connection pipeline; the ServerSocket is left alone. */
    private fun releaseConnection() {
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching {
            encoder?.let {
                it.signalEndOfInputStream()
                it.stop()
                it.release()
            }
        }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { socket?.close() }
        socket = null
    }

    /** Errors that shouldn't be retried by re-arming the listener. */
    private class FatalPipelineException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private suspend fun runEncoderPipeline(s: Socket, width: Int, height: Int, mediaProjection: MediaProjection?) {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        // Settings match the OEM ThinkerRide live encoder per
        // phase2/_re_report_projection_encoder.md §2 and §14. Going faster than this
        // doesn't help — the dash decoder is the bottleneck. Going wider on bitrate
        // can actually look worse because the dash falls behind.
        val fmt = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // 3 * W * H ≈ 2.4 Mbps at 1280x640 — ThinkerRide's r=3 tier for ≥8 GB phones.
            setInteger(MediaFormat.KEY_BIT_RATE, 3 * width * height)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // If no new pixels arrive in 100ms, the encoder repeats the previous frame.
            // Critical for surface-input mode when Mapbox renders are idle; without it
            // the encoder can stall and the dash decoder times out.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30f)
            }
            // High/4.1 gives noticeably cleaner edges and text at the same bitrate, but
            // some encoders may refuse to configure with these set. Try/catch wraps the
            // codec configure so we fall back to encoder defaults (Baseline) on failure.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        var enc = MediaCodec.createEncoderByType(mime)
        encoder = enc
        val configured = runCatching { enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        if (configured.isFailure) {
            Log.w(TAG, "encoder.configure failed with High/4.1 — retrying without profile/level", configured.exceptionOrNull())
            // Actually remove the keys — setting them to 0 leaves invalid values in the
            // format and the "fallback" configure can fail the same way.
            fmt.removeKey(MediaFormat.KEY_PROFILE)
            fmt.removeKey(MediaFormat.KEY_LEVEL)
            // Once .configure throws, the instance can't be reused — fresh encoder.
            enc.release()
            enc = MediaCodec.createEncoderByType(mime)
            encoder = enc
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val surf = enc.createInputSurface()
        inputSurface = surf
        enc.start()
        Log.i(TAG, "encoder configured + started ${width}x${height} @ 30fps (${3 * width * height} bps)")

        val vdOk = withContext(Dispatchers.Main) {
            val vd = if (mediaProjection != null) {
                // MediaProjection-backed VD: SurfaceFlinger keeps vsync flowing even
                // when the primary display blanks. PRESENTATION + OWN_CONTENT_ONLY
                // still apply (they govern mirroring policy, not wakefulness).
                runCatching {
                    mediaProjection.createVirtualDisplay(
                        "KoveDash",
                        width, height, 320,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        surf,
                        null,
                        null,
                    )
                }.onFailure { Log.e(TAG, "MediaProjection.createVirtualDisplay threw", it) }.getOrNull()
            } else {
                // Legacy fallback: DisplayManager VD. Dies on screen-off because it
                // follows primary-display vsync. Kept for paths that don't have an
                // MP token (e.g., the static-clip ProjectionSession's demo).
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                runCatching {
                    dm.createVirtualDisplay(
                        "KoveDash",
                        width, height, 320,
                        surf,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    )
                }.onFailure { Log.e(TAG, "DisplayManager.createVirtualDisplay threw", it) }.getOrNull()
            }
            if (vd == null) {
                Log.e(TAG, "createVirtualDisplay returned null")
                return@withContext false
            }
            virtualDisplay = vd
            val pres = DashPresentation(context, vd.display)
            presentation = pres
            pres.setComposeContent(content)
            pres.show()
            Log.i(TAG, "Presentation shown on virtualDisplay ${vd.display.displayId} " +
                "(mediaProjection=${mediaProjection != null})")
            true
        }
        // Without a display there is nothing to encode — bail instead of running a
        // drain loop that streams REPEAT_PREVIOUS frames of an empty surface forever.
        if (!vdOk) throw FatalPipelineException("createVirtualDisplay failed")

        drainEncoderToSocket(enc, s.getOutputStream(), s)
    }

    private fun drainEncoderToSocket(enc: MediaCodec, out: OutputStream, s: Socket) {
        val bufferInfo = MediaCodec.BufferInfo()
        var csd: ByteArray? = null
        var sentCsd = false
        var frameCount = 0L
        // Per-second wire metrics, logged so bench and bike runs are measurable:
        // actual fps out of the encoder, actual Mbps onto the wire, IDR cadence, and
        // cumulative ms spent blocked in socket write (the mechanical signature of
        // the dash decoder / Wi-Fi falling behind).
        var statWindowStartMs = System.currentTimeMillis()
        var statFrames = 0
        var statBytes = 0L
        var statIdrs = 0
        var statStallMs = 0L
        while (running.get() && !s.isClosed) {
            val id = try {
                enc.dequeueOutputBuffer(bufferInfo, 10_000L)
            } catch (t: Throwable) {
                Log.e(TAG, "dequeueOutputBuffer threw, exiting drain", t)
                return
            }
            when {
                id == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val of = enc.outputFormat
                    val sps = of.getByteBuffer("csd-0")
                    val pps = of.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val a = ByteArray(sps.remaining()).also { sps.get(it) }
                        val b = ByteArray(pps.remaining()).also { pps.get(it) }
                        csd = a + b
                        Log.i(TAG, "encoder format changed: SPS(${a.size}) + PPS(${b.size}) captured")
                    }
                }
                id >= 0 -> {
                    val buf = enc.getOutputBuffer(id)
                    if (buf == null) {
                        enc.releaseOutputBuffer(id, false)
                        continue
                    }
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    val data = ByteArray(bufferInfo.size).also { buf.get(it) }
                    enc.releaseOutputBuffer(id, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        csd = data
                        Log.i(TAG, "BUFFER_FLAG_CODEC_CONFIG: csd captured (${data.size}B)")
                        continue
                    }
                    val writeStartMs = System.currentTimeMillis()
                    val writeOk = runCatching {
                        if (!sentCsd) {
                            csd?.let {
                                out.write(it)
                                Log.i(TAG, "wrote csd prefix (${it.size}B) before first frame")
                            }
                            sentCsd = true
                        }
                        out.write(data)
                        out.flush()
                    }.isSuccess
                    if (!writeOk) {
                        Log.e(TAG, "socket write failed at frame $frameCount, stopping drain")
                        return
                    }
                    if (frameCount == 0L) Log.i(TAG, "first frame written (${data.size}B)")
                    frameCount++
                    statFrames++
                    statBytes += data.size
                    statStallMs += System.currentTimeMillis() - writeStartMs
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) statIdrs++
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - statWindowStartMs >= 1_000L) {
                        val mbps = statBytes * 8.0 / (nowMs - statWindowStartMs) / 1_000.0
                        Log.i(TAG, "proj stats: ${statFrames} fps, ${"%.2f".format(mbps)} Mbps, " +
                            "$statIdrs IDR, write-stall ${statStallMs}ms (frame #$frameCount)")
                        statWindowStartMs = nowMs
                        statFrames = 0
                        statBytes = 0L
                        statIdrs = 0
                        statStallMs = 0L
                    }
                }
            }
        }
        Log.i(TAG, "drain loop exited at frame $frameCount")
    }

    private fun sendHandshake(out: OutputStream, width: Int, height: Int) {
        val buf = ByteArray(HANDSHAKE_LEN)
        val platform = "android".toByteArray(Charsets.UTF_8)
        System.arraycopy(platform, 0, buf, 1, platform.size)
        ByteBuffer.wrap(buf, 65, 2).order(ByteOrder.BIG_ENDIAN).putShort(width.toShort())
        ByteBuffer.wrap(buf, 67, 2).order(ByteOrder.BIG_ENDIAN).putShort(height.toShort())
        out.write(buf)
        out.flush()
    }

    companion object {
        const val PORT = 15456
        const val HANDSHAKE_LEN = 69
        // ~200 ms of video at 2.4 Mbps CBR.
        private const val SEND_BUFFER_BYTES = 64 * 1024
        private const val TAG = "KoveDash"
    }
}
