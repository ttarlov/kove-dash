package com.kovedash.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteCatTest {

    @Test
    fun crc_for_msg_id_13_with_null_terminator() {
        // body = '{"msg_id":13}\0' (14 bytes)
        // sum = 0x4D, so crc = 0x84 0x8D
        val body = """{"msg_id":13}""".toByteArray(Charsets.UTF_8) + 0x00.toByte()
        val crc = ByteCat.crc(body)
        assertArrayEquals(byteArrayOf(0x84.toByte(), 0x8D.toByte()), crc)
    }

    @Test
    fun cat_overlays_crc_on_final_byte() {
        // For input '{"msg_id":13}\0' (14B), cat returns 16B:
        //   bytes 0..12 = '{"msg_id":13}' (the '\0' is overwritten)
        //   byte 13     = crc high (0x84)
        //   byte 14     = crc low  (0x8D)
        //   byte 15     = 0x00 (default-zero tail)
        val body = """{"msg_id":13}""".toByteArray(Charsets.UTF_8) + 0x00.toByte()
        val catted = ByteCat.cat(body)
        assertEquals(16, catted.size)
        val expected = byteArrayOf(
            0x7B, 0x22, 0x6D, 0x73, 0x67, 0x5F, 0x69, 0x64,
            0x22, 0x3A, 0x31, 0x33, 0x7D,
            0x84.toByte(), 0x8D.toByte(), 0x00
        )
        assertArrayEquals(expected, catted)
    }

    @Test
    fun framesFor_msg_id_13_produces_one_104_byte_frame() {
        val frames = ByteCat.framesFor("""{"msg_id":13}""", startSeq = 1)
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertEquals(104, frame.size)

        // head + seq
        assertEquals(0xFE.toByte(), frame[0])
        assertEquals(0x00.toByte(), frame[1])
        assertEquals(0x01.toByte(), frame[2])

        // catted payload (16 bytes starting at index 3)
        val expectedCatted = byteArrayOf(
            0x7B, 0x22, 0x6D, 0x73, 0x67, 0x5F, 0x69, 0x64,
            0x22, 0x3A, 0x31, 0x33, 0x7D,
            0x84.toByte(), 0x8D.toByte(), 0x00
        )
        assertArrayEquals(expectedCatted, frame.copyOfRange(3, 3 + 16))

        // tail marker
        assertEquals(0xFF.toByte(), frame[3 + 16])

        // remainder is zero
        for (i in (3 + 16 + 1) until 104) assertEquals("byte $i should be zero", 0.toByte(), frame[i])
    }

    @Test
    fun framesFor_chunks_when_body_exceeds_100_bytes() {
        // Build a payload that will produce >100 bytes after cat (+ \0 + 2 crc)
        val big = """{"data":"${"x".repeat(110)}"}"""
        val frames = ByteCat.framesFor(big, startSeq = 42)
        assert(frames.size >= 2)
        for (f in frames) assertEquals(104, f.size)
        // each frame starts with 0xFE; seq increments PER CHUNK from startSeq (42, 43, …)
        // so the dash's contiguous-from-0 loss detector doesn't see gaps/dupes.
        frames.forEachIndexed { idx, f ->
            assertEquals(0xFE.toByte(), f[0])
            val seq = 42 + idx
            assertEquals(((seq ushr 8) and 0xFF).toByte(), f[1])
            assertEquals((seq and 0xFF).toByte(), f[2])
        }
    }

    @Test
    fun seq_wraps_at_16_bits() {
        val frames = ByteCat.framesFor("{}", startSeq = 0x1_0001)
        val f = frames[0]
        assertEquals(0x00.toByte(), f[1])
        assertEquals(0x01.toByte(), f[2])
    }
}
