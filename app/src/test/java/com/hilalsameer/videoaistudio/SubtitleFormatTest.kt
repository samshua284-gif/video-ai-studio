package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/** Milestone 4: SRT/TXT rendering of recognized segments. */
class SubtitleFormatTest {

    @Test
    fun srtTimestampFormats() {
        assertEquals("00:00:00,000", SubtitleFormat.srtTimestamp(0.0))
        assertEquals("00:00:25,000", SubtitleFormat.srtTimestamp(25.0))
        assertEquals("00:01:01,500", SubtitleFormat.srtTimestamp(61.5))
        assertEquals("01:01:01,234", SubtitleFormat.srtTimestamp(3661.234))
        assertEquals("00:00:00,000", SubtitleFormat.srtTimestamp(-3.0))
    }

    @Test
    fun buildSrtNumbersBlocksInOrder() {
        val srt = SubtitleFormat.buildSrt(
            listOf(
                AsrSegment(0.0, 25.0, "السلام عليكم"),
                AsrSegment(25.0, 50.0, "أهلًا بكم")
            )
        )
        val expected =
            "1\n00:00:00,000 --> 00:00:25,000\nالسلام عليكم\n\n" +
                "2\n00:00:25,000 --> 00:00:50,000\nأهلًا بكم\n\n"
        assertEquals(expected, srt)
    }

    @Test
    fun buildTxtJoinsTrimmedLines() {
        val txt = SubtitleFormat.buildTxt(
            listOf(
                AsrSegment(0.0, 25.0, "  أول  "),
                AsrSegment(25.0, 50.0, "ثانٍ")
            )
        )
        assertEquals("أول\nثانٍ", txt)
    }

    @Test
    fun emptySegmentsRenderEmpty() {
        assertEquals("", SubtitleFormat.buildSrt(emptyList()))
        assertEquals("", SubtitleFormat.buildTxt(emptyList()))
    }
}
