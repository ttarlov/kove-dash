package com.kovedash.app.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ByteCat {

    const val FRAME_LEN = 104
    const val CHUNK_PAYLOAD_MAX = 100
    const val FRAME_HEAD: Byte = 0xFE.toByte()
    const val FRAME_TAIL: Byte = 0xFF.toByte()

    fun crc(buf: ByteArray): ByteArray {
        var total = 0
        for (b in buf) total = (total + (b.toInt() and 0xFF)) and 0xFF
        val hi = ((total and 0xF0) ushr 4) or 0x80
        val lo = (total and 0x0F) or 0x80
        return byteArrayOf(hi.toByte(), lo.toByte())
    }

    fun cat(body: ByteArray): ByteArray {
        require(body.isNotEmpty()) { "cat input must be non-empty" }
        val crc = crc(body)
        val out = ByteArray(body.size + 2)
        System.arraycopy(body, 0, out, 0, body.size)
        out[body.size - 1] = crc[0]
        out[body.size] = crc[1]
        return out
    }

    /**
     * Frames a JSON message into 104-byte byteCat chunks, stamping each chunk with a
     * seq number that increments PER CHUNK starting from [startSeq]. The dash's loss
     * detector expects a globally-contiguous-from-0 per-frame sequence; stamping every
     * chunk of a multi-frame message with the same seq (the old behavior) makes the dash
     * see duplicates + gaps and fire item=9 packet-loss requests, so a multi-frame
     * message never reassembles. Caller must advance its counter by the returned list's
     * size so the next message continues the sequence.
     */
    fun framesFor(jsonObject: String, startSeq: Int): List<ByteArray> {
        val body = (jsonObject.toByteArray(Charsets.UTF_8) + 0x00.toByte())
        val catted = cat(body)
        val frames = mutableListOf<ByteArray>()
        var i = 0
        var seq = startSeq
        while (i < catted.size) {
            val chunkLen = minOf(catted.size - i, CHUNK_PAYLOAD_MAX)
            val frame = ByteArray(FRAME_LEN)
            frame[0] = FRAME_HEAD
            ByteBuffer.wrap(frame, 1, 2).order(ByteOrder.BIG_ENDIAN).putShort((seq and 0xFFFF).toShort())
            System.arraycopy(catted, i, frame, 3, chunkLen)
            frame[3 + chunkLen] = FRAME_TAIL
            frames.add(frame)
            i += chunkLen
            seq++
        }
        return frames
    }
}
