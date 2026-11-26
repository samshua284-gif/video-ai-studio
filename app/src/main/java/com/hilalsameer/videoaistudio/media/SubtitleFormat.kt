package com.hilalsameer.videoaistudio.media

import java.util.Locale

/**
 * One recognized speech part with its REAL position in the media
 * (Milestone 4): the start/end are the bounds of the audio window
 * that produced the text — measured, never invented (master prompt,
 * Feature E: "Never fabricate timestamps").
 */
data class AsrSegment(
    val startSec: Double,
    val endSec: Double,
    val text: String
)

/** Pure SRT/TXT rendering for recognized segments (unit-tested). */
object SubtitleFormat {

    /** SRT timestamp: HH:MM:SS,mmm — always Latin digits. */
    fun srtTimestamp(sec: Double): String {
        val totalMs = (sec.coerceAtLeast(0.0) * 1000).toLong()
        val h = totalMs / 3_600_000
        val m = (totalMs % 3_600_000) / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    /** Standard SRT: numbered blocks, UTF-8 content, blank-line separated. */
    fun buildSrt(segments: List<AsrSegment>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { i, seg ->
            sb.append(i + 1).append('\n')
            sb.append(srtTimestamp(seg.startSec)).append(" --> ")
                .append(srtTimestamp(seg.endSec)).append('\n')
            sb.append(seg.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    /** Plain transcript: one part per line, in order. */
    fun buildTxt(segments: List<AsrSegment>): String =
        segments.joinToString("\n") { it.text.trim() }
}
