package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.CorrectionMemory
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.TranslationCorrection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TD-026 twin of the mt101 host harness, sections (b)–(d):
 * the translation correction station's pure core — timings
 * locked, source untouched, a re-translation offer is never
 * applied until accepted, and the memory semantics match the
 * existing translation editing exactly.
 */
class TranslationCorrectionTest {

    private val source = listOf(
        AsrSegment(0.0, 2.5, "السلام عليكم ورحمة الله", listOf(0.2 to 2.3)),
        AsrSegment(2.5, 5.125, "كيف الحال اليوم", listOf(2.7 to 4.9)),
        AsrSegment(5.125, 8.25, "السلام عليكم من جديد", listOf(5.1 to 8.0)),
        AsrSegment(8.25, 11.0, "This is a tast of the system")
    )
    private val translated = listOf(
        AsrSegment(0.0, 2.5, "Peace be upon you", listOf(0.2 to 2.3)),
        AsrSegment(2.5, 5.125, "How are you today", listOf(2.7 to 4.9)),
        AsrSegment(5.125, 8.25, "Peace be upon you again", listOf(5.1 to 8.0)),
        AsrSegment(8.25, 11.0, "This is a test of the system")
    )

    private fun timings(l: List<AsrSegment>) =
        l.map { Triple(it.startSec, it.endSec, it.spans) }

    @Test
    fun editsMoveTranslatedTextOnly() {
        val edited = TranslationCorrection.applyTextEdits(
            translated, mapOf(0 to "السلام عليكم", 3 to "This is THE test")
        )
        assertEquals(timings(translated), timings(edited))
        assertEquals("السلام عليكم", edited[0].text)
        assertSame(translated[1], edited[1])
        assertEquals("This is a tast of the system", source[3].text)
    }

    @Test
    fun replaceAllCountsAndContents() {
        val swept = TranslationCorrection.replaceAll(translated, "Peace", "سلام")
        assertEquals(2, swept.changedCues)
        assertEquals("سلام be upon you", swept.segments[0].text)
        assertEquals(timings(translated), timings(swept.segments))
        assertEquals(2, TranslationCorrection.countMatches(translated, "Peace"))
        assertEquals(
            0, TranslationCorrection.replaceAll(translated, "", "x").changedCues
        )
    }

    @Test
    fun offerIsNotAppliedUntilAccepted() {
        val offer = TranslationCorrection.evaluate(
            TranslationCorrection.UnitOutcome("ترجمة نظيفة", 2, 2, 0, null)
        )
        assertTrue(offer is TranslationCorrection.RetranslateEvaluation.Offer)
        // Holding the offer changes nothing.
        assertEquals("Peace be upon you", translated[0].text)
        val applied = TranslationCorrection.applyOffer(
            translated, 0,
            (offer as TranslationCorrection.RetranslateEvaluation.Offer).text
        )
        assertEquals("ترجمة نظيفة", applied[0].text)
        assertSame(translated[1], applied[1])
        assertEquals(timings(translated), timings(applied))
    }

    @Test
    fun partialPassThroughIsAnOfferWithItsCount() {
        val ev = TranslationCorrection.evaluate(
            TranslationCorrection.UnitOutcome("نصف مترجم half", 3, 2, 1, null)
        )
        assertTrue(ev is TranslationCorrection.RetranslateEvaluation.Offer)
        ev as TranslationCorrection.RetranslateEvaluation.Offer
        assertEquals(1, ev.passedUnits)
        assertEquals(3, ev.totalUnits)
    }

    @Test
    fun completePassThroughAndBlankAreFailures() {
        val passed = TranslationCorrection.evaluate(
            TranslationCorrection.UnitOutcome(
                "This is a tast of the system", 1, 0, 1, "NLLB: empty result"
            )
        )
        assertTrue(passed is TranslationCorrection.RetranslateEvaluation.Failed)
        assertEquals(
            "NLLB: empty result",
            (passed as TranslationCorrection.RetranslateEvaluation.Failed).error
        )
        val blank = TranslationCorrection.evaluate(
            TranslationCorrection.UnitOutcome("", 1, 0, 0, null)
        )
        assertTrue(blank is TranslationCorrection.RetranslateEvaluation.Failed)
        assertEquals(
            null, (blank as TranslationCorrection.RetranslateEvaluation.Failed).error
        )
    }

    @Test
    fun harvestMatchesLegacySemantics() {
        val handEdited = TranslationCorrection.applyTextEdits(
            translated, mapOf(3 to "هذا اختبار للنظام")
        )
        val station = TranslationCorrection.harvest(source, translated, handEdited)
        assertEquals(
            mapOf(
                CorrectionMemory.key("This is a tast of the system") to
                    "هذا اختبار للنظام"
            ),
            station
        )
        val legacy = CorrectionMemory.harvest(
            source, translated, SubtitleFormat.buildTxt(handEdited)
        )
        assertEquals(legacy, station)
    }

    @Test
    fun suggestionsOfferAndApplyOnTap() {
        val memory = mapOf(
            CorrectionMemory.key("This is a tast of the system") to "هذا اختبار للنظام"
        )
        val offers = TranslationCorrection.suggestions(source, translated, memory)
        assertEquals(mapOf(3 to "هذا اختبار للنظام"), offers)
        assertEquals("This is a test of the system", translated[3].text)
        val applied = TranslationCorrection.applyTextEdits(translated, offers)
        assertEquals("هذا اختبار للنظام", applied[3].text)
        assertEquals(timings(translated), timings(applied))
        assertTrue(
            TranslationCorrection.suggestions(source, applied, memory).isEmpty()
        )
    }

    @Test
    fun harvestGuards() {
        assertTrue(
            TranslationCorrection.harvest(
                source, translated,
                TranslationCorrection.applyTextEdits(translated, mapOf(0 to "   "))
            ).isEmpty()
        )
        assertTrue(
            TranslationCorrection.harvest(
                source, translated, translated.drop(1)
            ).isEmpty()
        )
    }
}
