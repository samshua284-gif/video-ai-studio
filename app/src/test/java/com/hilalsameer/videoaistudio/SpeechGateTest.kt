package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.SpeechGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host mirror of the gate harness run before the 0.7.2 push. */
class SpeechGateTest {

    private val p = SpeechGate.DEFAULT

    @Test
    fun noSpansMeansNoRegions() {
        assertTrue(SpeechGate.keepRegions(emptyList(), 10.0, p).isEmpty())
    }

    @Test
    fun spanGrowsBySafetyMargins() {
        val r = SpeechGate.keepRegions(listOf(1.0 to 2.0), 5.0, p)
        assertEquals(1, r.size)
        assertEquals(0.85, r[0].first, 1e-9)
        assertEquals(2.25, r[0].second, 1e-9)
    }

    @Test
    fun marginsClampToFileBounds() {
        val r = SpeechGate.keepRegions(listOf(0.05 to 4.95), 5.0, p)
        assertEquals(0.0, r[0].first, 1e-9)
        assertEquals(5.0, r[0].second, 1e-9)
    }

    @Test
    fun shortPauseMergesIntoOneRegion() {
        // 0.2 s pause between spans: prosody, never muted.
        val r = SpeechGate.keepRegions(listOf(1.0 to 2.0, 2.2 to 3.0), 5.0, p)
        assertEquals(1, r.size)
        val env = SpeechGate.Envelope(r, p)
        assertEquals(1f, env.gainAt(2.1), 1e-6f)
    }

    @Test
    fun longPauseIsSilencedWithFades() {
        val r = SpeechGate.keepRegions(listOf(1.0 to 2.0, 4.0 to 5.0), 6.0, p)
        assertEquals(2, r.size)
        val env = SpeechGate.Envelope(r, p)
        // The envelope is monotonic-query: ask in time order.
        assertEquals(1f, env.gainAt(1.5), 1e-6f)   // inside speech
        val fadeMid = env.gainAt(2.25 + 0.015)     // halfway down the fade-out
        assertTrue(fadeMid in 0.4f..0.6f)
        assertEquals(0f, env.gainAt(3.0), 1e-6f)   // middle of the pause
        assertEquals(1f, env.gainAt(4.5), 1e-6f)   // second span intact
    }

    @Test
    fun leadingNonSpeechIsSilent() {
        val r = SpeechGate.keepRegions(listOf(1.0 to 2.0), 5.0, p)
        val env = SpeechGate.Envelope(r, p)
        assertEquals(0f, env.gainAt(0.0), 1e-6f)
        assertEquals(0f, env.gainAt(0.5), 1e-6f)
        assertEquals(1f, env.gainAt(1.0), 1e-6f)
    }

    @Test
    fun unsortedSpansAreSortedAndMerged() {
        val r = SpeechGate.keepRegions(listOf(4.0 to 5.0, 1.0 to 2.0), 6.0, p)
        assertEquals(2, r.size)
        assertTrue(r[0].first < r[1].first)
    }
}
