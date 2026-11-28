package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.TranslateFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TD-020: the end-of-job honesty rule. A job whose every
 *  segment came back as its own source text is a FAILURE with
 *  the engine's first real error attached — never a success;
 *  a partially passed job finishes but must carry its count. */
class TranslateOutcomeTest {

    @Test
    fun allPassedWithErrorFailsAndCarriesTheError() {
        val o = TranslateFix.outcome(118, 118, 0, "TypeError: boom")
        assertTrue(o is TranslateFix.TranslateOutcome.FailedAllPassed)
        assertEquals(
            "TypeError: boom",
            (o as TranslateFix.TranslateOutcome.FailedAllPassed).firstError
        )
    }

    @Test
    fun allPassedWithoutErrorFailsWithNullError() {
        val o = TranslateFix.outcome(5, 5, 0, null)
        assertTrue(o is TranslateFix.TranslateOutcome.FailedAllPassed)
        assertNull((o as TranslateFix.TranslateOutcome.FailedAllPassed).firstError)
    }

    @Test
    fun someFullyPassedShortOfAllIsDoneWithWarning() {
        val o = TranslateFix.outcome(10, 3, 0, "Error: x")
        assertTrue(o is TranslateFix.TranslateOutcome.DoneWithWarning)
        o as TranslateFix.TranslateOutcome.DoneWithWarning
        assertEquals(3, o.passedSegments)
        assertEquals(10, o.totalSegments)
    }

    @Test
    fun onePartiallyPassedIsDoneWithWarning() {
        val o = TranslateFix.outcome(10, 0, 1, null)
        assertTrue(o is TranslateFix.TranslateOutcome.DoneWithWarning)
        assertEquals(
            1,
            (o as TranslateFix.TranslateOutcome.DoneWithWarning).passedSegments
        )
    }

    @Test
    fun fullyAndPartiallyPassedCountsAddUp() {
        val o = TranslateFix.outcome(10, 2, 3, null)
        assertTrue(o is TranslateFix.TranslateOutcome.DoneWithWarning)
        assertEquals(
            5,
            (o as TranslateFix.TranslateOutcome.DoneWithWarning).passedSegments
        )
    }

    @Test
    fun nonePassedIsDoneClean() {
        val o = TranslateFix.outcome(10, 0, 0, null)
        assertEquals(TranslateFix.TranslateOutcome.DoneClean, o)
    }

    @Test
    fun zeroTotalIsNeverAFailure() {
        val o = TranslateFix.outcome(0, 0, 0, "Error: x")
        assertEquals(TranslateFix.TranslateOutcome.DoneClean, o)
    }

    @Test
    fun memoryOnlyJobIsDoneClean() {
        // Every segment served from the owner's correction
        // memory: the buckets stay empty while the total counts
        // them — his own translations must never fail the job.
        val o = TranslateFix.outcome(4, 0, 0, null)
        assertEquals(TranslateFix.TranslateOutcome.DoneClean, o)
    }
}
