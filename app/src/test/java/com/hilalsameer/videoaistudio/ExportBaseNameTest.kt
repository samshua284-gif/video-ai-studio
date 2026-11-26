package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Export naming (0.5.6): the film's title names its subtitles. */
class ExportBaseNameTest {

    @Test
    fun embeddedTitleWinsOverNumericFileName() {
        assertEquals(
            "The Great Journey",
            SubtitleFormat.exportBaseName("217793.mp4", "The Great Journey")
        )
    }

    @Test
    fun missingOrBlankTitleFallsBackToFileBase() {
        assertEquals("217793", SubtitleFormat.exportBaseName("217793.mp4", null))
        assertEquals("217793", SubtitleFormat.exportBaseName("217793.mp4", "   "))
        assertEquals("my clip", SubtitleFormat.exportBaseName("my clip.mkv", null))
    }

    @Test
    fun illegalCharactersAreRemoved() {
        val out = SubtitleFormat.exportBaseName("x.mp4", "a/b:c*d?e\"f<g>h|i")
        assertFalse(out.contains('/'))
        assertFalse(out.contains(':'))
        assertEquals("a b c d e f g h i", out)
    }

    @Test
    fun longTitlesAreCapped() {
        val out = SubtitleFormat.exportBaseName("x.mp4", "t".repeat(200))
        assertEquals(60, out.length)
    }
}
