package com.kovedash.app.net

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * BLE GATT client for the dash. Holds long-lived state so connection drops AFTER the
 * initial handshake are observable via [connectionState].
 *
 *   service: 0000e0ff-3c17-d293-8e48-14fe2e4da212
 *     write:  0000ffe1-…  (no-response 104-byte frames)
 *     notify: 0000ffe2-…  (dash -> phone JSON responses)
 *     ctrl:   0000ffe3-…  (large-message notify path)
 */
class DashBleClient(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    // Seq counter MUST start at 0 — the dash tracks per-packet seq numbers and asks
    // for "missing packet 0" if our first packet is seq=1. AtomicInteger(0) means first
    // getAndIncrement() returns 0 (then 1, then 2, ...). Per _re_report_thinkerride.md
    // observation that the dash dispatches resend requests (msg_id=10 item=7 / item=9)
    // based on a contiguous-from-0 expectation.
    private val seqCounter = AtomicInteger(0)
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    // Ring buffer of recently-sent frames, keyed by their per-frame seq. It backs a BOUNDED
    // single-frame resend responder (see handleResendRequest): when the dash asks to retransmit
    // a dropped frame (msg_id=10 item=7 "resume from N" / item=9 "single packet loss N") we hand
    // back EXACTLY that one frame. Because a fire-and-forget NO_RESPONSE write can silently drop
    // and our global seq only moves forward, an unanswered drop leaves the dash stuck on that seq
    // forever (renders nothing until power-cycle). The OEM answered item=7 by replaying the whole
    // tail [N..cursor] — that ~40-frame burst re-congested the transparent link and broke
    // reassembly (why it was disabled). Single-frame-per-request is self-paced by the dash's own
    // request cadence, NOT a burst — the whole bet of #16. Evicts oldest (lowest) seq past the cap.
    // Guarded by [sentFramesLock]; cleared on a fresh GATT link (seq resets to 0 there).
    private val sentFrames = object : LinkedHashMap<Int, ByteArray>(SENT_BUFFER_MAX, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean =
            size > SENT_BUFFER_MAX
    }
    private val sentFramesLock = Any()
    // Storm guards (all guarded by [sentFramesLock]): per-seq dedup so a rapidly re-requested
    // index isn't spammed, plus a hard cap of resends per rolling window as a runaway backstop.
    private val lastResendAtBySeq = HashMap<Int, Long>()
    private var resendWindowStartMs = 0L
    private var resendsInWindow = 0

    // Serializes whole messages onto the wire. sendJson is called from several
    // concurrent coroutines (time-sync echo, handshake, probes); without this,
    // callers race the seq counter (out-of-order seq on the wire breaks the dash's
    // contiguous-from-0 loss detection), interleave frames of multi-frame messages,
    // and steal each other's write ACKs off the shared conflated channel.
    private val sendMutex = Mutex()

    // Single-slot channel that the GATT onCharacteristicWrite callback drops the result
    // status into. sendJson() awaits this between chunks so we never queue-overflow the
    // BLE stack. CONFLATED: if a callback arrives faster than the writer consumes, we
    // keep only the latest — which is fine since each write is a discrete event.
    private val writeAckChannel = Channel<Int>(Channel.CONFLATED)

    private val _notify = MutableSharedFlow<NotifyEvent>(extraBufferCapacity = 64)
    val notify: SharedFlow<NotifyEvent> = _notify

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val connectionState: StateFlow<State> = _state

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            // STATE_TURNING_OFF = 13, STATE_OFF = 10
            if (newState == BluetoothAdapter.STATE_OFF || newState == BluetoothAdapter.STATE_TURNING_OFF) {
                if (_state.value != State.DISCONNECTED) {
                    Log.w("KoveDash", "BT adapter off — forcing connectionState DISCONNECTED")
                    _state.value = State.DISCONNECTED
                    writeChar = null
                }
            }
        }
    }

    init {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.registerReceiver(
                adapterReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            context.applicationContext.registerReceiver(
                adapterReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            )
        }
    }

    /** The MAC of the dash we last connected to — persist it (KoveSettings.dashMac) and pass
     *  it back as [knownMac] so future connects skip scanning entirely. */
    @Volatile
    var connectedDeviceAddress: String? = null
        private set

    @Suppress("MissingPermission")
    suspend fun scanAndConnect(namePrefix: String = "CQKY_", knownMac: String? = null): Boolean {
        _state.value = State.CONNECTING
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            _state.value = State.DISCONNECTED; return false
        }

        // Fast path: connect DIRECTLY to the known dash MAC, no scan. Android throttles BLE
        // scans after repeated start/stops (returns zero results), and the dash stops
        // advertising once a stale GATT still holds it — both make scanning fail even though
        // the dash is right there. connectGatt to a bonded device works regardless. Bounded
        // by a timeout so an absent device falls back to scanning instead of hanging ~30s.
        if (!knownMac.isNullOrBlank()) {
            val dev = runCatching { adapter.getRemoteDevice(knownMac) }.getOrNull()
            if (dev != null) {
                Log.i(TAG, "connect: direct to known MAC $knownMac (no scan)")
                val ok = withTimeoutOrNull(DIRECT_CONNECT_TIMEOUT_MS) { connectTo(dev) } ?: false
                if (ok) {
                    connectedDeviceAddress = knownMac
                    _state.value = State.CONNECTED
                    return true
                }
                Log.w(TAG, "connect: direct to $knownMac failed/timed out — falling back to scan")
                runCatching { gatt?.disconnect(); gatt?.close() }
                gatt = null; writeChar = null
            }
        }

        val scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = State.DISCONNECTED; return false
        }

        // Collect ALL CQKY_ dashes over a short settle window, then connect to the STRONGEST
        // signal — i.e. the closest one, the bike you're on. Grabbing whichever advertised
        // first meant a stale/ghost bond or a second nearby dash could win the race and we'd
        // silently talk to the wrong device (healthy link, but nothing renders on YOUR dash).
        val device: BluetoothDevice? = suspendCancellableCoroutine { cont ->
            val seen = LinkedHashMap<String, ScanResult>()
            var resumed = false
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device?.name ?: return
                    if (!name.startsWith(namePrefix)) return
                    val addr = result.device.address
                    val prev = seen[addr]
                    if (prev == null || result.rssi > prev.rssi) seen[addr] = result
                    Log.i(TAG, "scan match: $name $addr rssi=${result.rssi} (candidates=${seen.size})")
                }
            }
            scanner.startScan(
                listOf(ScanFilter.Builder().build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            scope.launch {
                kotlinx.coroutines.delay(SCAN_SETTLE_MS)
                runCatching { scanner.stopScan(cb) }
                val best = seen.values.maxByOrNull { it.rssi }
                if (best != null) {
                    Log.i(TAG, "scan → strongest: ${best.device.name} ${best.device.address} rssi=${best.rssi} (of ${seen.size} CQKY_)")
                } else {
                    Log.w(TAG, "scan → no CQKY_ dash found")
                }
                if (!resumed) { resumed = true; if (cont.isActive) cont.resume(best?.device) }
            }
            cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
        }

        val dev = device ?: run { _state.value = State.DISCONNECTED; return false }
        val connected = connectTo(dev)
        if (connected) connectedDeviceAddress = dev.address
        _state.value = if (connected) State.CONNECTED else State.DISCONNECTED
        return connected
    }

    @Suppress("MissingPermission")
    private suspend fun connectTo(device: BluetoothDevice): Boolean {
        // The dash drops an UNBONDED LE link every ~30s (issue #5): each drop resets the GATT,
        // the seq counter, and the handshake — wiping any native widget state, so weather /
        // elevation / turn-by-turn never persist long enough to render. Establish the bond FIRST
        // so the link stays up. No-op if already bonded (the normal, persistent case).
        ensureBonded(device)
        return suspendCancellableCoroutine { cont ->
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        // Two paths:
                        //  - first-connect: continuation hasn't fired yet, resume(false)
                        //  - post-connect: surface as state change so the service can reconnect
                        _state.value = State.DISCONNECTED
                        writeChar = null
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(SERVICE_UUID) ?: run {
                    if (cont.isActive) cont.resume(false); return
                }
                writeChar = svc.getCharacteristic(WRITE_UUID)
                // Fresh GATT link → the dash resets its receive cursor to 0, so we restart
                // the package stream at 0. Keeps our package-index aligned with the dash's
                // cur_package_index. Drop the resend buffer + rate-limit state too: the old
                // frames belong to the previous seq epoch and must never answer a new request.
                seqCounter.set(0)
                synchronized(sentFramesLock) {
                    sentFrames.clear()
                    lastResendAtBySeq.clear()
                    resendWindowStartMs = 0L
                    resendsInWindow = 0
                }
                writeChar?.let {
                    val p = it.properties
                    Log.i(WIRE, "ffe1 properties=0x%02X (WRITE=%b, WRITE_NO_RESP=%b)".format(
                        p, p and 0x08 != 0, p and 0x04 != 0))
                }
                listOf(NOTIFY_UUID, CONTROL_UUID).forEach { uuid ->
                    val ch = svc.getCharacteristic(uuid) ?: return@forEach
                    g.setCharacteristicNotification(ch, true)
                    val desc = ch.getDescriptor(CCCD)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        desc?.let { g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                    } else {
                        @Suppress("DEPRECATION")
                        desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        desc?.let { g.writeDescriptor(it) }
                    }
                }
                // Negotiate a larger MTU. Default is 23 bytes (20 byte payload), but we
                // ship 104-byte frames. The OEM apps request 247 (185 byte payload).
                // Without this, some Android BLE stacks silently truncate or drop writes
                // that exceed the default. We hold the connect continuation open until
                // onMtuChanged fires.
                val mtuRequested = runCatching { g.requestMtu(247) }.getOrDefault(false)
                if (!mtuRequested) {
                    Log.w(TAG, "requestMtu(247) returned false — resuming connect without MTU bump")
                    if (cont.isActive) cont.resume(true)
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "MTU negotiated: $mtu bytes (status=$status)")
                if (cont.isActive) cont.resume(true)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                // Signal the writer that the stack has drained. Used to gate the next
                // chunk in [sendJson] so we don't queue-overflow.
                if (status != 0) Log.w(WIRE, "TX ack ${short(ch.uuid)} status=$status (NON-ZERO = write failed)")
                writeAckChannel.trySend(status)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val data = ch.value ?: return
                Log.i(WIRE, "RX ← ${short(ch.uuid)}  ${hexTrim(data)}  | ${ascii(data)}")
                onInbound(ch.uuid, data)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                Log.i(WIRE, "RX ← ${short(ch.uuid)}  ${hexTrim(value)}  | ${ascii(value)}")
                onInbound(ch.uuid, value)
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        }
    }

    /**
     * Ensure the phone is bonded to the dash before we rely on the link (issue #5). The dash
     * requires an encrypted/bonded LE connection and drops an unbonded one every ~30s, which
     * churns reconnects and prevents native rendering from ever settling. If the device isn't
     * bonded, kick off [BluetoothDevice.createBond] and wait (this triggers the one-time system
     * pairing dialog on a first connect; the bond then persists). Best-effort: returns false on
     * timeout/failure and lets the caller connect anyway, but proactive bonding avoids the churn.
     */
    @Suppress("MissingPermission")
    private suspend fun ensureBonded(device: BluetoothDevice, timeoutMs: Long = BOND_TIMEOUT_MS): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        Log.i(TAG, "ensureBonded: ${device.address} not bonded (state=${device.bondState}) — createBond()")
        val result = Channel<Boolean>(Channel.CONFLATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> { Log.i(TAG, "ensureBonded: BONDED"); result.trySend(true) }
                    BluetoothDevice.BOND_NONE -> { Log.w(TAG, "ensureBonded: bond failed (BOND_NONE)"); result.trySend(false) }
                    else -> { /* BOND_BONDING — keep waiting */ }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return try {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            Log.i(TAG, "ensureBonded: createBond() started=$started")
            withTimeoutOrNull(timeoutMs) { result.receive() } ?: run {
                Log.w(TAG, "ensureBonded: timed out after ${timeoutMs}ms waiting for bond"); false
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /**
     * Sends a JSON message to the dash, chunked into 104-byte byteCat frames.
     *
     * Two big differences from V0:
     *   1. WRITE_TYPE_DEFAULT (with ACK) instead of NO_RESPONSE. Fire-and-forget writes
     *      silently dropped frames when the BLE stack's internal queue overflowed; the
     *      OEM apps use ACKed writes and we now match.
     *   2. Inter-chunk gating on onCharacteristicWrite. The ack channel signals when
     *      the previous frame has actually been transmitted; we wait before queueing
     *      the next. Falls back to a short delay if the ack never arrives (timeout).
     *   3. Retry with backoff if writeCharacteristic itself fails (queue full at call
     *      time). Matches the OEM pattern at gl.java:901-923.
     */
    @Suppress("MissingPermission")
    suspend fun sendJson(json: String) = sendMutex.withLock {
        val ch = writeChar ?: return@withLock
        val g = gatt ?: return@withLock
        // Seq increments per-message starting from 0. The dash dispatches resend
        // requests (msg_id=10 item=7 cur_package_index=N / item=9 packet_loss_index=N)
        // assuming a contiguous-from-0 sequence — proved empirically by trying
        // seq-from-1 (dash complained "missing packet 0") and seq-always-0 (dash got
        // packet 0 then complained "missing packet 1"). seqCounter starts at 0.
        // Assigned inside the mutex so seq order matches wire order.
        // Per-CHUNK seq: framesFor stamps startSeq, startSeq+1, … across the message's
        // frames; advance the counter by the frame count so the next message continues
        // the globally-contiguous sequence the dash's loss detector expects. (Old code
        // stamped every chunk of a multi-frame message with one seq → dash saw gaps and
        // NAKed with item=9, so multi-frame messages never reassembled.)
        val startSeq = seqCounter.get()
        val frames = ByteCat.framesFor(json, startSeq)
        seqCounter.addAndGet(frames.size)
        // Buffer each frame by its seq so we can honor a later single-frame resend request.
        synchronized(sentFramesLock) {
            frames.forEachIndexed { idx, f -> sentFrames[startSeq + idx] = f }
        }
        Log.i(WIRE, "TX → ffe1  seq=$startSeq (${frames.size}f)  $json")
        frames.forEachIndexed { idx, f ->
            Log.i(WIRE, "   TX frame seq=${startSeq + idx}: ${hexTrim(f)}")
        }
        for (frame in frames) {
            if (!writeFrame(g, ch, frame)) {
                Log.e(TAG, "BLE write failed after $MAX_WRITE_RETRIES retries — dropping rest of message")
                return@withLock
            }
        }
    }

    /**
     * Writes a single already-framed chunk to ffe1 with retry + inter-chunk ACK gating.
     * Extracted from [sendJson] to keep the per-frame write discipline in one place.
     * Returns false if the stack refused after all retries.
     */
    @Suppress("MissingPermission")
    private suspend fun writeFrame(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        frame: ByteArray,
    ): Boolean {
        // Drain any stale ack from a previous frame so we don't accidentally satisfy
        // this one's wait on a leftover signal.
        while (writeAckChannel.tryReceive().isSuccess) { /* drain */ }

        var queued = false
        var attempt = 0
        while (!queued && attempt < MAX_WRITE_RETRIES) {
            // WRITE_TYPE_NO_RESPONSE — the OEM writes ffe1 this way (BleConnectWrapper
            // setWriteType(1)). ffe1's property is Write-Without-Response; sending a
            // Write REQUEST (WRITE_TYPE_DEFAULT) got rejected by the dash's GATT server
            // with ATT status 14 on EVERY write — the raw wire monitor proved no message
            // ever landed. Match the OEM: write commands, no ATT response.
            queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val rc = g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                rc == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = frame
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!queued) {
                attempt++
                Log.w(TAG, "BLE writeCharacteristic refused (attempt $attempt), backing off")
                kotlinx.coroutines.delay(WRITE_RETRY_BACKOFF_MS)
            }
        }
        if (!queued) return false
        // Gate the next chunk on the actual write completion. Queueing the next chunk
        // before the previous drains causes the stack to silently drop. If the callback
        // truly never arrives (rare), fall through after WRITE_ACK_TIMEOUT_MS.
        withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { writeAckChannel.receive() }
        return true
    }

    /** Fan-out for an inbound notification: publish to the flow, and — for ffe2 — log any
     *  dash retransmit request (which we intentionally do NOT answer; see below). */
    private fun onInbound(uuid: UUID, data: ByteArray) {
        val copy = data.copyOf()
        scope.launch { _notify.emit(NotifyEvent(uuid, copy)) }
        if (uuid == NOTIFY_UUID) scope.launch { handleResendRequest(copy) }
    }

    /**
     * The dash asks us to retransmit a dropped frame: msg_id=10 item=7 "resume from
     * cur_package_index N", item=9 "single packet loss packet_loss_index N". We answer with
     * EXACTLY the one buffered frame at seq N ([resendFrame]) — never the OEM's whole-tail
     * replay, which stormed the transparent link and broke reassembly (#16). For item=7 we
     * still send just frame N; the dash re-requests N+1, N+2… and walks forward, self-paced.
     * Storm guards (per-seq dedup + a per-window cap) live in [resendFrame].
     */
    private suspend fun handleResendRequest(data: ByteArray) {
        val s = runCatching { String(data, Charsets.UTF_8) }.getOrNull() ?: return
        if (!s.contains("\"msg_id\"") || jsonInt(s, "msg_id") != 10) return
        val item = jsonInt(s, "item") ?: return
        val seq = when (item) {
            7 -> jsonInt(s, "cur_package_index")
            9 -> jsonInt(s, "packet_loss_index")
            else -> null
        } ?: return
        resendFrame(seq, item)
    }

    /**
     * Retransmit the single buffered frame at [seq], if we still have it and the storm guards
     * allow. Serialized through [sendMutex] so it can't race a live [sendJson] on the write-ack
     * channel; does NOT touch [seqCounter] (it's a re-send of an existing seq, not a new frame).
     */
    private suspend fun resendFrame(seq: Int, item: Int) {
        val now = SystemClock.elapsedRealtime()
        val frame = synchronized(sentFramesLock) {
            if (now - resendWindowStartMs > RESEND_WINDOW_MS) { resendWindowStartMs = now; resendsInWindow = 0 }
            // Timestamps only matter inside the dedup window — drop the rest so this map can't
            // grow across a long session.
            lastResendAtBySeq.entries.removeAll { now - it.value > RESEND_DEDUP_MS }
            val last = lastResendAtBySeq[seq]
            when {
                last != null && now - last < RESEND_DEDUP_MS -> null // just resent this seq; let it land
                resendsInWindow >= MAX_RESENDS_PER_WINDOW -> {
                    Log.w(TAG, "resend item=$item seq=$seq SUPPRESSED — storm guard ($resendsInWindow/${RESEND_WINDOW_MS}ms)")
                    null
                }
                else -> sentFrames[seq]?.also {
                    lastResendAtBySeq[seq] = now
                    resendsInWindow++
                }
            }
        }
        if (frame == null) {
            // Either deduped (quiet), storm-guarded (logged above), or evicted from the buffer.
            if (synchronized(sentFramesLock) { !sentFrames.containsKey(seq) }) {
                Log.i(TAG, "resend item=$item seq=$seq — not in buffer (evicted); can't answer")
            }
            return
        }
        val g = gatt ?: return
        val ch = writeChar ?: return
        sendMutex.withLock { writeFrame(g, ch, frame) }
        Log.i(TAG, "resend item=$item seq=$seq — sent 1 frame")
    }

    /** Pull an integer field out of the dash's tab-indented flat JSON without a full parser. */
    private fun jsonInt(s: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()

    // ── Raw wire monitor helpers. Every TX frame and RX notification is logged under the
    // "KoveWire" tag so we can watch the actual BT conversation both directions:
    //   adb logcat -s KoveWire
    private fun short(u: UUID): String = u.toString().substring(4, 8) // e.g. "ffe1"
    private fun hexTrim(b: ByteArray): String {
        // Trim the 104-byte frame's zero padding: stop just after the 0xFF tail if present.
        val tail = b.indexOf(0xFF.toByte())
        val end = if (tail >= 0) tail + 1 else b.size
        return b.copyOfRange(0, minOf(end, b.size)).joinToString(" ") { "%02X".format(it) }
    }
    private fun ascii(b: ByteArray): String =
        String(b, Charsets.US_ASCII).map { if (it in ' '..'~') it else '·' }.joinToString("")

    @Suppress("MissingPermission")
    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        _state.value = State.DISCONNECTED
        runCatching { context.applicationContext.unregisterReceiver(adapterReceiver) }
    }

    data class NotifyEvent(val char: UUID, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NotifyEvent) return false
            return char == other.char && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = char.hashCode() * 31 + bytes.contentHashCode()
    }

    companion object {
        private const val TAG = "KoveDash"
        private const val WIRE = "KoveWire" // raw TX/RX byte monitor — `adb logcat -s KoveWire`
        private const val SCAN_SETTLE_MS = 2500L // collect all CQKY_ dashes, then pick strongest
        private const val DIRECT_CONNECT_TIMEOUT_MS = 12_000L // known-MAC connect before scan fallback
        private const val BOND_TIMEOUT_MS = 30_000L // wait for createBond() (incl. user accepting the pair dialog)
        private const val MAX_WRITE_RETRIES = 5
        private const val WRITE_RETRY_BACKOFF_MS = 500L
        private const val WRITE_ACK_TIMEOUT_MS = 500L
        // Single-frame resend responder (#16).
        private const val SENT_BUFFER_MAX = 256      // frames kept for resend (~27 KB of 104-byte frames)
        private const val RESEND_DEDUP_MS = 250L     // don't resend the same seq more often than this
        private const val RESEND_WINDOW_MS = 3000L   // storm-guard rolling window
        private const val MAX_RESENDS_PER_WINDOW = 24 // hard cap on resends per window (runaway backstop)
        val SERVICE_UUID: UUID = UUID.fromString("0000e0ff-3c17-d293-8e48-14fe2e4da212")
        val WRITE_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
        val CONTROL_UUID: UUID = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
