package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.CorrectionMemory
import com.hilalsameer.videoaistudio.media.SrtParser
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.SubtitleMerge
import com.hilalsameer.videoaistudio.media.TranslateFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 5 pure layer: SRT parse, TD-009 merge, TD-006 numbers + memory. */
class SrtMergeFixTest {

    // ---------- SrtParser ----------

    @Test
    fun parsesStandardSrt() {
        val srt = "1\n00:00:01,000 --> 00:00:03,500\nHello world\n\n" +
            "2\n00:00:04,000 --> 00:00:06,000\nSecond line\n"
        val segs = SrtParser.parse(srt)
        assertEquals(2, segs.size)
        assertEquals(1.0, segs[0].startSec, 0.001)
        assertEquals(3.5, segs[0].endSec, 0.001)
        assertEquals("Hello world", segs[0].text)
        assertEquals("Second line", segs[1].text)
    }

    @Test
    fun roundTripsThroughBuilder() {
        val segs = listOf(
            AsrSegment(0.0, 29.0, "First part"),
            AsrSegment(29.0, 58.0, "Second part")
        )
        assertEquals(segs, SrtParser.parse(SubtitleFormat.buildSrt(segs)))
    }

    @Test
    fun toleratesCrlfBomAndDotSeparator() {
        val srt = "\uFEFF1\r\n00:00:01.250 --> 00:00:02.000\r\nText\r\n"
        val segs = SrtParser.parse(srt)
        assertEquals(1, segs.size)
        assertEquals(1.25, segs[0].startSec, 0.001)
    }

    @Test
    fun skipsBlocksWithoutTiming() {
        val srt = "Some header text\n\n1\n00:00:00,000 --> 00:00:01,000\nReal\n"
        assertEquals(1, SrtParser.parse(srt).size)
    }

    // ---------- SubtitleMerge (TD-009) ----------

    @Test
    fun mergeOffsetsByCumulativeVideoDurations() {
        val p1 = listOf(AsrSegment(0.0, 5.0, "a"), AsrSegment(5.0, 9.0, "b"))
        val p2 = listOf(AsrSegment(0.0, 4.0, "c"))
        val p3 = listOf(AsrSegment(1.0, 2.0, "d"))
        val merged = SubtitleMerge.merge(listOf(p1, p2, p3), listOf(600.0, 300.0, 900.0))
        assertEquals(4, merged.size)
        assertEquals("a", merged[0].text)
        assertEquals(600.0, merged[2].startSec, 0.001)   // part 2 shifted by part 1's duration
        assertEquals(901.0, merged[3].startSec, 0.001)   // part 3 shifted by 600 + 300
        assertEquals(902.0, merged[3].endSec, 0.001)
    }

    @Test
    fun mergeRejectsMismatchedDurations() {
        val p = listOf(AsrSegment(0.0, 1.0, "x"))
        try {
            SubtitleMerge.merge(listOf(p, p), listOf(10.0))
            assertTrue("should have thrown", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ---------- TranslateFix (TD-006 Layer 1) ----------

    @Test
    fun numbersNormaliseAcrossDigitScripts() {
        assertEquals(listOf("1250"), TranslateFix.numbersIn("المبلغ ١٬٢٥٠ ريال"))
        assertEquals(listOf("1250"), TranslateFix.numbersIn("total 1,250"))
        assertEquals(listOf("3.5"), TranslateFix.numbersIn("٣.٥"))
    }

    @Test
    fun numbersPreservedCheck() {
        assertTrue(TranslateFix.numbersPreserved("عنده ٣ سيارات و٥ جمال", "He has 3 cars and 5 camels"))
        assertFalse(TranslateFix.numbersPreserved("عنده ٣ سيارات", "He has cars"))
        assertFalse(TranslateFix.numbersPreserved("رقم 5 و5", "number 5"))
        assertTrue(TranslateFix.numbersPreserved("لا أرقام هنا", "no numbers here"))
    }

    @Test
    fun digitConversionRoundTrip() {
        assertEquals("سنة 2026", TranslateFix.toWesternDigits("سنة ٢٠٢٦"))
        assertEquals("سنة ٢٠٢٦", TranslateFix.toArabicIndicDigits("سنة 2026"))
    }

    // ---------- CorrectionMemory (TD-006 Layer 3) ----------

    @Test
    fun memoryLookupIsExactSegmentMatch() {
        val mem = mapOf(CorrectionMemory.key("أين أنتم؟") to "Where are you all?")
        assertEquals("Where are you all?", CorrectionMemory.lookup("  أين   أنتم؟ ", mem))
        assertEquals(null, CorrectionMemory.lookup("أين أنت؟", mem))
    }

    @Test
    fun harvestRecordsOnlyRealEdits() {
        val src = listOf(AsrSegment(0.0, 1.0, "s1"), AsrSegment(1.0, 2.0, "s2"))
        val machine = listOf(AsrSegment(0.0, 1.0, "m1"), AsrSegment(1.0, 2.0, "m2"))
        val pairs = CorrectionMemory.harvest(src, machine, "m1\nowner words")
        assertEquals(mapOf("s2" to "owner words"), pairs)
        // Line-count drift = ambiguous: nothing harvested.
        assertTrue(CorrectionMemory.harvest(src, machine, "only one line").isEmpty())
    }
}
