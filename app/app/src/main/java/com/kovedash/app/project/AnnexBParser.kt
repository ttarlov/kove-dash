package com.kovedash.app.project

/**
 * Splits an H.264 Annex-B byte stream into access units (one per encoded picture).
 *
 * Each access unit is the concatenation of zero or more parameter-set NALs (SPS/PPS/SEI)
 * followed by one VCL NAL (slice or IDR slice). Emitting one AU per frame interval gives
 * the decoder exactly one picture per tick — which is what the dash expects.
 */
object AnnexBParser {

    /**
     * Each element is (startIndex, prefixLen) where prefixLen is 3 or 4 — the length of
     * the Annex-B start code preceding the NAL unit byte.
     */
    private data class NalStart(val pos: Int, val prefixLen: Int)

    fun splitAccessUnits(bytes: ByteArray): List<ByteArray> {
        val starts = findNalStarts(bytes)
        if (starts.isEmpty()) return emptyList()
        // For all-I streams with repeat-headers enabled, every access unit starts with an SPS
        // (NAL type 7). Splitting at SPS boundaries gives one AU per encoded picture even when
        // the picture is split across multiple slice NALs.
        val spsIndices = mutableListOf<Int>()
        for (k in starts.indices) {
            val pos = starts[k].pos
            val nalHeaderIdx = pos + starts[k].prefixLen
            if (nalHeaderIdx >= bytes.size) break
            val nalType = bytes[nalHeaderIdx].toInt() and 0x1F
            if (nalType == 7) spsIndices.add(pos)
        }
        if (spsIndices.size < 2) {
            // Single AU only — fall back to whole-stream
            return listOf(bytes.copyOfRange(starts[0].pos, bytes.size))
        }
        val aus = ArrayList<ByteArray>(spsIndices.size)
        for (i in spsIndices.indices) {
            val s = spsIndices[i]
            val e = if (i + 1 < spsIndices.size) spsIndices[i + 1] else bytes.size
            aus.add(bytes.copyOfRange(s, e))
        }
        return aus
    }

    private fun findNalStarts(bytes: ByteArray): List<NalStart> {
        val out = ArrayList<NalStart>(bytes.size / 1024)
        var i = 0
        val n = bytes.size - 2
        while (i < n) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) {
                if (i + 3 < bytes.size && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                    out.add(NalStart(i, 4))
                    i += 4
                    continue
                }
                if (bytes[i + 2] == 1.toByte()) {
                    out.add(NalStart(i, 3))
                    i += 3
                    continue
                }
            }
            i++
        }
        return out
    }
}
