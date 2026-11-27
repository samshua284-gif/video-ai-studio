package com.hilalsameer.videoaistudio.media

/**
 * Sentence-level re-segmentation of ASR window segments (0.6.4).
 *
 * Whisper decodes in ~29 s windows and returns ONE segment per
 * window, so a dialogue-dense window becomes a single SRT cue
 * holding a dozen sentences on screen for half a minute — the
 * owner's field report: "the subtitles appear crammed all at
 * once, not in sync with the speech". This pass splits every
 * window segment into sentence cues and distributes the window's
 * REAL time span across them in proportion to sentence length.
 * The window bounds stay measured; the distribution inside them
 * is a stated approximation — speech is not uniform, but a
 * sentence cue now appears near its words instead of everything
 * arriving at once. Word-level timestamps remain Milestone 6.
 */
object SubtitleSegmenter {

    private val ENDERS = setOf('.', '?', '!', '؟', '…', '।')
    private const val CLOSERS = "\"'”’»)]}»"

    /** Split text into sentences, keeping terminal punctuation. */
    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        val s = text.trim()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            cur.append(c)
            if (c in ENDERS) {
                // A '.' between digits is a decimal point, not an end.
                val decimal = c == '.' && i > 0 && s[i - 1].isDigit() &&
                    i + 1 < s.length && s[i + 1].isDigit()
                // A '.' inside a run ("...") ends only at the last dot.
                val inDotRun = c == '.' && i + 1 < s.length && s[i + 1] == '.'
                if (!decimal && !inDotRun) {
                    // Absorb closing quotes/brackets into the sentence.
                    var j = i + 1
                    while (j < s.length && s[j] in CLOSERS) {
                        cur.append(s[j])
                        j++
                    }
                    val sentence = cur.toString().trim()
                    if (sentence.isNotEmpty()) out.add(sentence)
                    cur.clear()
                    i = j
                    continue
                }
            }
            i++
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    /** Re-segment window segments into sentence cues. */
    fun resegment(segments: List<AsrSegment>): List<AsrSegment> {
        val out = ArrayList<AsrSegment>()
        for (seg in segments) {
            val sentences = splitSentences(seg.text)
            if (sentences.size <= 1 || seg.endSec <= seg.startSec) {
                out.add(seg)
                continue
            }
            val totalWeight = sentences.sumOf { maxOf(it.length, 1) }.toDouble()
            val span = seg.endSec - seg.startSec
            var cursor = seg.startSec
            sentences.forEachIndexed { idx, sentence ->
                val w = maxOf(sentence.length, 1) / totalWeight
                val end = if (idx == sentences.lastIndex) seg.endSec else cursor + span * w
                out.add(AsrSegment(cursor, end, sentence))
                cursor = end
            }
        }
        return out
    }
}
