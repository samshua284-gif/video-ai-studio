package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.TranscriptionDraft
import com.hilalsameer.videoaistudio.media.TranslationDraft
import com.hilalsameer.videoaistudio.media.WorkDrafts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TD-026 twin of the mt101 host harness, section (a): the
 * work-draft round trip. A restored draft must be the job's
 * result exactly — texts byte-exact, timings bit-identical —
 * and a broken or foreign draft file must read as no draft.
 */
class WorkDraftsTest {

    private val asrSegs = listOf(
        AsrSegment(0.0, 2.5, "السلام عليكم ورحمة الله", listOf(0.2 to 2.3)),
        AsrSegment(2.5, 5.125, "قال: \"أهلاً\" \\ وسهلاً", listOf(2.7 to 4.9)),
        AsrSegment(5.125, 8.300000000000001, "سطر\nجديد و 🎬", emptyList()),
        AsrSegment(8.300000000000001, 11.0, "This is a tast of the system")
    )
    private val tgtSegs = listOf(
        AsrSegment(0.0, 2.5, "Peace be upon you", listOf(0.2 to 2.3)),
        AsrSegment(2.5, 5.125, "He said: \"welcome\" \\ and hello", listOf(2.7 to 4.9)),
        AsrSegment(5.125, 8.300000000000001, "A new line", emptyList()),
        AsrSegment(8.300000000000001, 11.0, "This is a test of the system")
    )

    @Test
    fun transcriptionDraftRoundTripsExactly() {
        val draft = TranscriptionDraft(
            asrSegs, "friends.mp4", "أصدقاء \"الموسم\" ١",
            "content://media/external/video/media/42", "en", 1732700000123L
        )
        val back = WorkDrafts.decodeTranscription(WorkDrafts.encodeTranscription(draft))
        assertEquals(draft, back)
        assertEquals(
            WorkDrafts.timingFingerprint(asrSegs),
            WorkDrafts.timingFingerprint(back!!.segments)
        )
        assertEquals(
            SubtitleFormat.buildSrt(asrSegs),
            SubtitleFormat.buildSrt(back.segments)
        )
    }

    @Test
    fun transcriptionDraftNullsAndEmptyRoundTrip() {
        val nulls = TranscriptionDraft(asrSegs, "x.mp3", null, null, "", 0L)
        assertEquals(
            nulls,
            WorkDrafts.decodeTranscription(WorkDrafts.encodeTranscription(nulls))
        )
        val empty = TranscriptionDraft(emptyList(), "", null, null, "ar", 7L)
        assertEquals(
            empty,
            WorkDrafts.decodeTranscription(WorkDrafts.encodeTranscription(empty))
        )
    }

    @Test
    fun translationDraftRoundTripsExactly() {
        val draft = TranslationDraft(
            tgtSegs, asrSegs, listOf(1, 3), "friends.mp4", 2,
            "en", "ar", "content://media/external/video/media/42", 1732700000999L
        )
        val back = WorkDrafts.decodeTranslation(WorkDrafts.encodeTranslation(draft))
        assertEquals(draft, back)
        assertEquals(
            WorkDrafts.timingFingerprint(asrSegs),
            WorkDrafts.timingFingerprint(back!!.source)
        )
        assertEquals(listOf(1, 3), back.numberFlags)
    }

    @Test
    fun crossToolAndBrokenFilesReadAsNoDraft() {
        val t = TranscriptionDraft(asrSegs, "a", null, null, "en", 1L)
        val tr = TranslationDraft(tgtSegs, asrSegs, emptyList(), "a", 0, "en", "ar", null, 1L)
        assertNull(WorkDrafts.decodeTranscription(WorkDrafts.encodeTranslation(tr)))
        assertNull(WorkDrafts.decodeTranslation(WorkDrafts.encodeTranscription(t)))
        assertNull(WorkDrafts.decodeTranscription("not json at all"))
        assertNull(WorkDrafts.decodeTranscription(""))
        assertNull(WorkDrafts.decodeTranslation("{\"tool\":\"translation\""))
        assertNull(
            WorkDrafts.decodeTranscription(
                WorkDrafts.encodeTranscription(t).dropLast(30)
            )
        )
    }

    @Test
    fun fingerprintChangesWithTimings() {
        val a = WorkDrafts.timingFingerprint(listOf(AsrSegment(0.0, 2.5, "x")))
        val b = WorkDrafts.timingFingerprint(listOf(AsrSegment(0.0, 2.6, "x")))
        assertEquals(false, a == b)
    }
}
