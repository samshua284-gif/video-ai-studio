package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.SpeechChunks
import com.hilalsameer.videoaistudio.media.SpeechTextFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.7.0: the speech-only decode plan and the text honesty filter. */
class SpeechChunksTest {

    @Test
    fun closeSpansMergeIntoOneChunk() {
        val chunks = SpeechChunks.build(listOf(0.0 to 3.0, 4.0 to 6.5))
        assertEquals(1, chunks.size)
        assertEquals(0.0, chunks[0].startSec, 1e-9)
        assertEquals(6.5, chunks[0].endSec, 1e-9)
        assertEquals(2, chunks[0].spans.size)
        assertEquals(5.5, chunks[0].speechSec, 1e-9)
    }

    @Test
    fun farSpansStaySeparate() {
        val chunks = SpeechChunks.build(listOf(0.0 to 3.0, 10.0 to 12.0))
        assertEquals(2, chunks.size)
        assertEquals(10.0, chunks[1].startSec, 1e-9)
    }

    @Test
    fun longSpanSplitsAtTheCap() {
        val chunks = SpeechChunks.build(listOf(0.0 to 60.0))
        assertEquals(3, chunks.size)
        assertEquals(25.0, chunks[0].endSec, 1e-9)
        assertEquals(50.0, chunks[1].endSec, 1e-9)
        assertEquals(60.0, chunks[2].endSec, 1e-9)
    }

    @Test
    fun mergeNeverExceedsTheCap() {
        // 24 s of speech + a close 5 s span: merging would end at 30 s.
        val chunks = SpeechChunks.build(listOf(0.0 to 24.0, 25.0 to 30.0))
        assertEquals(2, chunks.size)
        assertEquals(24.0, chunks[0].endSec, 1e-9)
        assertEquals(25.0, chunks[1].startSec, 1e-9)
    }

    @Test
    fun isolatedBlipIsDroppedRealSpeechIsKept() {
        assertTrue(SpeechChunks.build(listOf(2.0 to 2.2)).isEmpty())
        val kept = SpeechChunks.build(listOf(2.0 to 2.6))
        assertEquals(1, kept.size)
    }

    @Test
    fun unsortedAndEmptySpansAreNormalized() {
        val chunks = SpeechChunks.build(listOf(10.0 to 12.0, 5.0 to 5.0, 0.0 to 3.0))
        assertEquals(2, chunks.size)
        assertEquals(0.0, chunks[0].startSec, 1e-9)
        assertEquals(10.0, chunks[1].startSec, 1e-9)
    }

    @Test
    fun fallbackCoversTheWholeFile() {
        val chunks = SpeechChunks.fallback(60.0)
        assertEquals(3, chunks.size)
        assertEquals(0.0, chunks.first().startSec, 1e-9)
        assertEquals(60.0, chunks.last().endSec, 1e-9)
        assertTrue(SpeechChunks.fallback(0.0).isEmpty())
    }

    @Test
    fun textFilterDropsNonSpeechKeepsSpeech() {
        assertFalse(SpeechTextFilter.keep(""))
        assertFalse(SpeechTextFilter.keep("   "))
        assertFalse(SpeechTextFilter.keep("..."))
        assertFalse(SpeechTextFilter.keep("♪ ♪ ♪"))
        assertFalse(SpeechTextFilter.keep("[موسيقى]"))
        assertFalse(SpeechTextFilter.keep("(صراخ)"))
        assertTrue(SpeechTextFilter.keep("نعم"))
        assertTrue(SpeechTextFilter.keep("hello."))
        assertTrue(SpeechTextFilter.keep("قال [شيئًا] ثم مضى"))
        assertTrue(SpeechTextFilter.keep("السلام عليكم ورحمة الله"))
    }
}
