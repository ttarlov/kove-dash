package com.kovedash.app.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

object DashJsonEnvelope {

    const val HEAD_0: Byte = 0xEE.toByte()
    const val HEAD_1: Byte = 0xFD.toByte()
    const val TAIL: Byte = 0xFF.toByte()
    private const val HEADER_LEN = 6
    private const val TAIL_LEN = 1

    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(HEADER_LEN + body.size + TAIL_LEN)
        out[0] = HEAD_0
        out[1] = HEAD_1
        ByteBuffer.wrap(out, 2, 4).order(ByteOrder.BIG_ENDIAN).putInt(body.size)
        System.arraycopy(body, 0, out, HEADER_LEN, body.size)
        out[out.size - 1] = TAIL
        return out
    }

    data class Decoded(val json: String, val consumed: Int)

    fun tryDecode(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Decoded? {
        if (length < HEADER_LEN + TAIL_LEN) return null
        if (buf[offset] != HEAD_0 || buf[offset + 1] != HEAD_1) return null
        val bodyLen = ByteBuffer.wrap(buf, offset + 2, 4).order(ByteOrder.BIG_ENDIAN).int
        if (bodyLen < 0 || bodyLen > 1 shl 20) return null
        val total = HEADER_LEN + bodyLen + TAIL_LEN
        if (length < total) return null
        if (buf[offset + HEADER_LEN + bodyLen] != TAIL) return null
        val json = String(buf, offset + HEADER_LEN, bodyLen, Charsets.UTF_8)
        return Decoded(json, total)
    }
}
