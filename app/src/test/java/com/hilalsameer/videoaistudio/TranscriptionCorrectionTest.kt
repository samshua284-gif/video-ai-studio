package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.CorrectionMemory
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.TranscriptionCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** TD-025: the correction station's pure core. The invariant
 *  under every test is the timing lock — text may move,
 *  timings and speech spans never do — followed by the
 *  exactness of replace-all and the offer-never-force
 *  semantics of the correction memory. Host twin:
 *  vas-host-test/mt100 (39/39). */
class TranscriptionCorrectionTest {

    private val segs = listOf(
        AsrSegment(0.0, 2.5, "السلام عليكم ورحمة الله", listOf(0.2 to 2.3)),
        AsrSegment(2.5, 5.0, "كيف الحال اليوم", listOf(2.7 to 4.8)),
        AsrSegment(5.0, 8.25, "السلام عليكم من جديد", listOf(5.1 to 8.0)),
        AsrSegment(8.25, 11.0, "This is a tast of the system"),
        AsrSegment(11.0, 13.5, "Another tast here"),
        AsrSegment(13.5, 16.0, "Nothing to fix")
    )

    private fun timings(l: List<AsrSegment>) =
        l.map { Triple(it.startSec, it.endSec, it.spans) }

    @Test
    fun textEditsNeverTouchTimings() {
        val edited = TranscriptionCorrection.applyTextEdits(
            segs,
            mapOf(0 to "السلام عليكم ورحمة الله وبركاته", 3 to "This is a test of the system")
        )
        assertEquals(timings(segs), timings(edited))
        assertEquals("السلام عليكم ورحمة الله وبركاته", edited[0].text)
        assertEquals("This is a test of the system", edited[3].text)
        assertSame(segs[1], edited[1])
        assertEquals(segs.size, edited.size)
    }

    @Test
    fun outOfRangeEditsAreIgnored() {
        assertEquals(
            segs,
            TranscriptionCorrection.applyTextEdits(segs, mapOf(99 to "x", -1 to "y"))
        )
    }

    @Test
    fun replaceAllCountsAndContentsExactEnglish() {
        val r = TranscriptionCorrection.replaceAll(segs, "tast", "test")
        assertEquals(2, r.changedCues)
        assertEquals("This is a test of the system", r.segments[3].text)
        assertEquals("Another test here", r.segments[4].text)
        assertEquals(timings(segs), timings(r.segments))
    }

    @Test
    fun replaceAllCountsAndContentsExactArabic() {
        val r = TranscriptionCorrection.replaceAll(segs, "عليكم", "عليكم ورحمة الله وبركاته")
        assertEquals(2, r.changedCues)
        assertEquals("السلام عليكم ورحمة الله وبركاته ورحمة الله", r.segments[0].text)
        assertEquals("السلام عليكم ورحمة الله وبركاته من جديد", r.segments[2].text)
    }

    @Test
    fun countMatchesMirrorsTheSweep() {
        assertEquals(2, TranscriptionCorrection.countMatches(segs, "السلام"))
        assertEquals(0, TranscriptionCorrection.countMatches(segs, ""))
        assertEquals(0, TranscriptionCorrection.countMatches(segs, "غير موجود"))
        assertEquals(
            TranscriptionCorrection.replaceAll(segs, "tast", "test").changedCues,
            TranscriptionCorrection.countMatches(segs, "tast")
        )
    }

    @Test
    fun replaceAllGuardsAreNoOps() {
        val empty = TranscriptionCorrection.replaceAll(segs, "", "x")
        assertEquals(0, empty.changedCues)
        assertEquals(segs, empty.segments)
        val same = TranscriptionCorrection.replaceAll(segs, "tast", "tast")
        assertEquals(0, same.changedCues)
        assertEquals(segs, same.segments)
    }

    @Test
    fun replaceAllIsSinglePassAndLiteral() {
        val grow = TranscriptionCorrection.replaceAll(
            listOf(AsrSegment(0.0, 1.0, "aaa")), "a", "aa"
        )
        assertEquals(1, grow.changedCues)
        assertEquals("aaaaaa", grow.segments[0].text)
        val inside = TranscriptionCorrection.replaceAll(
            listOf(AsrSegment(0.0, 1.0, "test")), "est", "EST"
        )
        assertEquals("tEST", inside.segments[0].text)
    }

    @Test
    fun harvestRecordsExactlyTheChangedPairs() {
        val taught = TranscriptionCorrection.harvest(
            segs,
            TranscriptionCorrection.applyTextEdits(
                segs, mapOf(3 to "This is a test of the system")
            )
        )
        assertEquals(
            mapOf(
                CorrectionMemory.key("This is a tast of the system") to
                    "This is a test of the system"
            ),
            taught
        )
    }

    @Test
    fun harvestSkipsBlankUnchangedAndMismatched() {
        val blank = TranscriptionCorrection.harvest(
            segs,
            TranscriptionCorrection.applyTextEdits(
                segs, mapOf(0 to "   ", 1 to "كيف الحال اليوم")
            )
        )
        assertTrue(blank.isEmpty())
        assertTrue(TranscriptionCorrection.harvest(segs, segs.drop(1)).isEmpty())
    }

    @Test
    fun suggestionsOfferButNeverApply() {
        val memory = mapOf(
            CorrectionMemory.key("This is a tast of the system") to
                "This is a test of the system"
        )
        val later = listOf(
            AsrSegment(0.0, 3.0, "Something else entirely"),
            AsrSegment(3.0, 6.5, "This is a tast of the system"),
            AsrSegment(6.5, 9.0, "This is a test of the system")
        )
        val offers = TranscriptionCorrection.suggestions(later, memory)
        assertEquals(mapOf(1 to "This is a test of the system"), offers)
        assertEquals("This is a tast of the system", later[1].text)
        val applied = TranscriptionCorrection.applyTextEdits(later, offers)
        assertEquals("This is a test of the system", applied[1].text)
        assertEquals(timings(later), timings(applied))
    }

    @Test
    fun suggestionsEmptyMemoryAndAlreadyCorrected() {
        assertTrue(TranscriptionCorrection.suggestions(segs, emptyMap()).isEmpty())
        val memory = mapOf(
            CorrectionMemory.key("This is a tast of the system") to
                "This is a test of the system"
        )
        assertTrue(
            TranscriptionCorrection.suggestions(
                listOf(AsrSegment(0.0, 1.0, "This is a test of the system")), memory
            ).isEmpty()
        )
    }

    @Test
    fun correctedSrtDiffersOnlyInTextLines() {
        var chain = TranscriptionCorrection.applyTextEdits(
            segs, mapOf(1 to "كيف الحال اليوم؟")
        )
        chain = TranscriptionCorrection.replaceAll(chain, "tast", "test").segments
        chain = TranscriptionCorrection.applyTextEdits(
            chain, mapOf(4 to "Another test here.")
        )
        val timingLines = { s: String ->
            s.split("\n").filter { it.contains(" --> ") }
        }
        assertEquals(
            timingLines(SubtitleFormat.buildSrt(segs)),
            timingLines(SubtitleFormat.buildSrt(chain))
        )
        assertEquals(timings(segs), timings(chain))
        assertEquals(
            "This is a test of the system",
            SubtitleFormat.buildTxt(chain).split("\n")[3]
        )
    }

    @Test
    fun builtInRulesShipEmptyAndIdentity() {
        assertTrue(TranscriptionCorrection.BUILT_IN_RULES.isEmpty())
        val r = TranscriptionCorrection.applyRules(
            segs, TranscriptionCorrection.BUILT_IN_RULES
        )
        assertEquals(0, r.changedCues)
        assertEquals(segs, r.segments)
    }

    @Test
    fun rulesApplyInOrder() {
        val r = TranscriptionCorrection.applyRules(
            listOf(AsrSegment(0.0, 1.0, "a a")),
            listOf("a" to "b", "b" to "c")
        )
        assertEquals("c c", r.segments[0].text)
        assertEquals(1, r.changedCues)
    }
}
