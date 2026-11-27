package com.hilalsameer.videoaistudio.media

/**
 * SRT reader (Milestone 5): parses standard SubRip text into
 * segments carrying their REAL timestamps. The inverse of
 * [SubtitleFormat.buildSrt]; tolerant of CRLF line endings, a
 * leading BOM, missing numeric index lines, and both ',' and '.'
 * as the millisecond separator — the shapes real-world SRT files
 * actually arrive in. Blocks without a valid timing line are
 * skipped, never guessed at (master prompt: never fabricate
 * timestamps).
 */
object SrtParser {

    private val TIMING = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(srt: String): List<AsrSegment> {
        val clean = srt.removePrefix("\uFEFF").replace("\r\n", "\n")
        val out = ArrayList<AsrSegment>()
        for (block in clean.split(Regex("\n\\s*\n"))) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timingIdx = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (timingIdx < 0) continue
            val m = TIMING.find(lines[timingIdx]) ?: continue
            val g = m.groupValues
            val start = toSec(g[1], g[2], g[3], g[4])
            val end = toSec(g[5], g[6], g[7], g[8])
            val text = lines.drop(timingIdx + 1).joinToString("\n").trim()
            if (text.isEmpty() || end < start) continue
            out.add(AsrSegment(start, end, text))
        }
        return out
    }

    private fun toSec(h: String, m: String, s: String, ms: String): Double {
        val millis = ms.padEnd(3, '0').take(3).toInt()
        return h.toInt() * 3600.0 + m.toInt() * 60.0 + s.toInt() + millis / 1000.0
    }
}
