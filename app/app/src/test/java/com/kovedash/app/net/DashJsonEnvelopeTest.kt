package com.kovedash.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashJsonEnvelopeTest {

    @Test
    fun encodes_msg_id_13_matches_python_reference() {
        // Reference vector lifted from proto-poc/dash_server.py:86-91
        // body = '{"msg_id":13}' (13 bytes); envelope = EE FD 00 00 00 0D <body> FF
        val expected = byteArrayOf(
            0xEE.toByte(), 0xFD.toByte(),
            0x00, 0x00, 0x00, 0x0D,
            0x7B, 0x22, 0x6D, 0x73, 0x67, 0x5F, 0x69, 0x64,
            0x22, 0x3A, 0x31, 0x33, 0x7D,
            0xFF.toByte()
        )
        val actual = DashJsonEnvelope.encode("""{"msg_id":13}""")
        assertArrayEquals(expected, actual)
    }

    @Test
    fun encodes_empty_object() {
        val expected = byteArrayOf(
            0xEE.toByte(), 0xFD.toByte(),
            0x00, 0x00, 0x00, 0x02,
            0x7B, 0x7D,
            0xFF.toByte()
        )
        assertArrayEquals(expected, DashJsonEnvelope.encode("{}"))
    }

    @Test
    fun roundtrip_decode() {
        val json = """{"msg_id":27,"func":"NAVI","act":3}"""
        val bytes = DashJsonEnvelope.encode(json)
        val decoded = DashJsonEnvelope.tryDecode(bytes)
        assertEquals(json, decoded?.json)
        assertEquals(bytes.size, decoded?.consumed)
    }

    @Test
    fun decode_returns_null_for_short_buffer() {
        val partial = byteArrayOf(0xEE.toByte(), 0xFD.toByte(), 0x00, 0x00, 0x00)
        assertNull(DashJsonEnvelope.tryDecode(partial))
    }

    @Test
    fun decode_returns_null_for_wrong_magic() {
        val bogus = byteArrayOf(
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x02,
            0x7B, 0x7D,
            0xFF.toByte()
        )
        assertNull(DashJsonEnvelope.tryDecode(bogus))
    }

    @Test
    fun decode_returns_null_when_tail_byte_wrong() {
        val noTail = byteArrayOf(
            0xEE.toByte(), 0xFD.toByte(),
            0x00, 0x00, 0x00, 0x02,
            0x7B, 0x7D,
            0xAA.toByte()
        )
        assertNull(DashJsonEnvelope.tryDecode(noTail))
    }
}
