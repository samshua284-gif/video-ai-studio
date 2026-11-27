package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.SubtitleSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.4: window segments become sentence cues (owner's field report). */
class SubtitleSegmenterTest {

    @Test
    fun dialogueBlockSplitsIntoSentences() {
        val block = "What are you doing later? Do you want to go watch a movie? " +
            "I actually have plans with somebody tonight. You never have plans. " +
            "Okay, I have plans, alright? Oh, okay, big shot. I have many plans. " +
            "I actually have a date. A date? Yep. Is this the first date? " +
            "It's the third date. Third? Mm-hmm."
        val cues = SubtitleSegmenter.resegment(listOf(AsrSegment(0.0, 29.0, block)))
        assertEquals(14, cues.size)
        assertEquals(0.0, cues.first().startSec, 1e-9)
        assertEquals(29.0, cues.last().endSec, 1e-9)
        assertTrue(cues.zipWithNext().all { (a, b) -> b.startSec == a.endSec })
        assertTrue(cues.all { it.endSec > it.startSec })
        assertEquals("What are you doing later?", cues.first().text)
        assertEquals("Mm-hmm.", cues.last().text)
    }

    @Test
    fun arabicAndDecimalAndEllipsis() {
        assertEquals(3, SubtitleSegmenter.splitSentences("مرحبا بك. كيف حالك؟ أنا بخير!").size)
        val dec = SubtitleSegmenter.splitSentences("The price is 3.5 dollars. Cheap.")
        assertEquals(2, dec.size)
        assertTrue(dec[0].contains("3.5"))
        val ell = SubtitleSegmenter.splitSentences("Wait... what happened?")
        assertEquals(listOf("Wait...", "what happened?"), ell)
    }

    @Test
    fun cuesSitOnVoiceSpansOnly() {
        // Voice in 0..4 and 6..10 of a 29 s window; 4..6 is silence.
        val seg = AsrSegment(
            0.0, 29.0, "One here. Two here. Three go. Four go.",
            listOf(0.0 to 4.0, 6.0 to 10.0)
        )
        val cues = SubtitleSegmenter.resegment(listOf(seg))
        assertEquals(4, cues.size)
        assertTrue(cues.none { it.startSec < 6.0 && it.endSec > 4.0 })
        assertEquals(4.0, cues[1].endSec, 1e-9)
        assertEquals(6.0, cues[2].startSec, 1e-9)
        assertTrue(cues.all { it.endSec > it.startSec })
    }

    @Test
    fun unpunctuatedStaysWhole() {
        val out = SubtitleSegmenter.resegment(listOf(AsrSegment(5.0, 9.0, "no punctuation here")))
        assertEquals(1, out.size)
        assertEquals(5.0, out[0].startSec, 1e-9)
        assertEquals(9.0, out[0].endSec, 1e-9)
    }
}
