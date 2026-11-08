package io.github.androidconnect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpsRewriterTest {
    private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val samples = mapOf(
        // vah264enc, High profile, VUI with timing info but no bitstream restriction
        "vaapi" to "27640032ac3c4201f4097bff0001000110000003001000000303c9da08042e",
        // x264enc, High profile, VUI with bitstream restriction
        "x264-high" to "67640032acb200fa04bd80b501010140000003004000000f23c60c92",
        // x264enc, Baseline profile
        "x264-baseline" to "6742c01fd9005005bb016a02020280000003008000001e478c1924",
    )

    @Test
    fun rewritesAndIsStable() {
        for ((name, hex) in samples) {
            val once = SpsRewriter.rewrite(unhex(hex))
            assertNotNull(name, once)
            // Parsing the result again must give the same bytes: proves it is well formed.
            assertArrayEquals(name, once, SpsRewriter.rewrite(once!!))
            // Header, profile, constraints and level are untouched.
            assertArrayEquals(name, unhex(hex).copyOf(4), once.copyOf(4))
        }
    }

    @Test
    fun rejectsTruncatedInput() {
        assertNull(SpsRewriter.rewrite(unhex("6742")))
    }
}
