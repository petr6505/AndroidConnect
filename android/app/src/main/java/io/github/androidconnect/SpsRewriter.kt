package io.github.androidconnect

/**
 * Patches the SPS VUI to say max_num_reorder_frames = 0.
 *
 * Without it decoders assume the level's worst case and buffer frames
 * (Exynos: 9-16 frames of delay). Since the laptop only sends on screen
 * changes, that can mean seconds of lag.
 */
object SpsRewriter {
    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Takes and returns an SPS NAL unit without start code. Returns null if it can't be parsed. */
    fun rewrite(nal: ByteArray): ByteArray? = try {
        val rbsp = unescape(nal, 1)
        val r = BitReader(rbsp)
        val w = BitWriter()
        w.bits(nal[0].toInt() and 0xff, 8)  // NAL header
        rewriteRbsp(r, w)
        escape(w.toByteArray())
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    /** Copies everything up to the VUI's bitstream_restriction, then writes our own. */
    private fun rewriteRbsp(r: BitReader, w: BitWriter) {
        fun u(n: Int) = r.bits(n).also { w.bits(it, n) }
        fun ue() = r.ue().also { w.ue(it) }
        fun se() = r.se().also { w.se(it) }

        val profile = u(8)
        u(8)  // constraint flags
        u(8)  // level
        ue()  // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chroma = ue()
            if (chroma == 3) u(1)
            ue(); ue()  // bit depths
            u(1)        // qpprime_y_zero_transform_bypass
            if (u(1) == 1) {  // seq_scaling_matrix_present
                for (i in 0 until if (chroma == 3) 12 else 8) {
                    if (u(1) == 1) {
                        var last = 8
                        var next = 8
                        for (j in 0 until if (i < 6) 16 else 64) {
                            if (next != 0) next = (last + se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        ue()  // log2_max_frame_num_minus4
        when (ue()) {  // pic_order_cnt_type
            0 -> ue()
            1 -> {
                u(1); se(); se()
                repeat(ue()) { se() }
            }
        }
        val maxRefFrames = ue()
        u(1)        // gaps_in_frame_num_allowed
        ue(); ue()  // size in macroblocks
        if (u(1) == 0) u(1)  // frame_mbs_only / mb_adaptive_frame_field
        u(1)        // direct_8x8_inference
        if (u(1) == 1) { ue(); ue(); ue(); ue() }  // cropping

        val vui = r.bits(1)
        w.bits(1, 1)
        if (vui == 0) {
            w.bits(0, 8)  // all optional VUI parts absent up to pic_struct_present_flag
        } else {
            if (u(1) == 1 && u(8) == 255) { u(16); u(16) }  // aspect ratio
            if (u(1) == 1) u(1)                              // overscan
            if (u(1) == 1) { u(3); u(1); if (u(1) == 1) { u(8); u(8); u(8) } }  // video signal
            if (u(1) == 1) { ue(); ue() }                    // chroma location
            if (u(1) == 1) { u(32); u(32); u(1) }            // timing info
            val nalHrd = u(1)
            if (nalHrd == 1) hrd(::u, ::ue)
            val vclHrd = u(1)
            if (vclHrd == 1) hrd(::u, ::ue)
            if (nalHrd == 1 || vclHrd == 1) u(1)  // low_delay_hrd
            u(1)  // pic_struct_present
            // The original bitstream_restriction (if any) is dropped and replaced below.
        }
        w.bits(1, 1)    // bitstream_restriction_flag
        w.bits(1, 1)    // motion_vectors_over_pic_boundaries
        w.ue(2)         // max_bytes_per_pic_denom (default)
        w.ue(1)         // max_bits_per_mb_denom (default)
        w.ue(16); w.ue(16)  // log2_max_mv_length (default)
        w.ue(0)         // max_num_reorder_frames
        w.ue(maxOf(1, maxRefFrames))  // max_dec_frame_buffering
        w.trailingBits()
    }

    private fun hrd(u: (Int) -> Int, ue: () -> Int) {
        val count = ue() + 1
        u(4); u(4)
        repeat(count) { ue(); ue(); u(1) }
        u(5); u(5); u(5); u(5)
    }

    /** Removes emulation prevention bytes (00 00 03 -> 00 00). */
    private fun unescape(data: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var zeros = 0
        for (i in from until data.size) {
            val b = data[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out.write(b)
        }
        return out.toByteArray()
    }

    /** Inserts emulation prevention bytes (header byte included, it is never 0). */
    private fun escape(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 8)
        var zeros = 0
        for (byte in data) {
            val b = byte.toInt() and 0xff
            if (zeros >= 2 && b <= 3) {
                out.write(3)
                zeros = 0
            }
            zeros = if (b == 0) zeros + 1 else 0
            out.write(b)
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bit(): Int {
            if (pos >= data.size * 8) throw IndexOutOfBoundsException("SPS truncated")
            val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return b
        }

        fun bits(n: Int): Int {
            var v = 0L
            repeat(n) { v = (v shl 1) or bit().toLong() }
            return v.toInt()
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                if (++zeros > 31) throw IllegalStateException("bad Exp-Golomb code")
            }
            return ((1L shl zeros) - 1 + bits(zeros).toLong().and(0xffffffffL)).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    private class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var cur = 0
        private var n = 0

        fun bits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1)
                if (++n == 8) {
                    out.write(cur)
                    cur = 0
                    n = 0
                }
            }
        }

        fun ue(value: Int) {
            val v = value.toLong() + 1
            val len = 64 - java.lang.Long.numberOfLeadingZeros(v)
            bits(0, len - 1)
            for (i in len - 1 downTo 0) bits(((v ushr i) and 1).toInt(), 1)
        }

        fun se(value: Int) = ue(if (value > 0) 2 * value - 1 else -2 * value)

        fun trailingBits() {
            bits(1, 1)
            while (n != 0) bits(0, 1)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
