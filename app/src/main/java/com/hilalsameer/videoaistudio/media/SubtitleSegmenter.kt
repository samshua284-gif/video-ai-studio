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
            // 0.6.5: when the VAD measured where the voice is, the
            // sentences live on the speech spans only — a cue
            // appears with the voice, disappears when it pauses,
            // and silence stays empty (owner's exact request).
            // Without spans, fall back to the whole window (0.6.4).
            val spans = seg.spans
                .map { maxOf(it.first, seg.startSec) to minOf(it.second, seg.endSec) }
                .filter { it.second > it.first }
                .sortedBy { it.first }
            distribute(
                sentences,
                if (spans.isEmpty()) listOf(seg.startSec to seg.endSec) else spans,
                out
            )
        }
        return out
    }

    /**
     * Place sentences on timeline spans: each sentence's ideal
     * share of the total span time comes from its length; a
     * sentence lives wholly inside one span, so a cue never
     * stretches across a silence gap. A group may overflow its
     * span by a quarter (the timings are approximations) and is
     * then compressed to fit; a group that underfills keeps its
     * ideal durations — a cue ends early rather than lingering.
     */
    private fun distribute(
        sentences: List<String>,
        spans: List<Pair<Double, Double>>,
        out: ArrayList<AsrSegment>
    ) {
        val totalLen = sentences.sumOf { maxOf(it.length, 1) }.toDouble()
        val totalTime = spans.sumOf { it.second - it.first }
        fun ideal(sentence: String) = totalTime * maxOf(sentence.length, 1) / totalLen
        var si = 0
        spans.forEachIndexed { spanIdx, (spanStart, spanEnd) ->
            val spanLen = spanEnd - spanStart
            val isLastSpan = spanIdx == spans.lastIndex
            val group = mutableListOf<String>()
            var groupIdeal = 0.0
            while (si < sentences.size) {
                val next = ideal(sentences[si])
                if (!isLastSpan && group.isNotEmpty() && groupIdeal + next > spanLen * 1.25) break
                group += sentences[si]
                groupIdeal += next
                si++
            }
            if (group.isEmpty()) return@forEachIndexed
            val scale = if (groupIdeal > spanLen) spanLen / groupIdeal else 1.0
            var cursor = spanStart
            for (sentence in group) {
                val end = minOf(cursor + ideal(sentence) * scale, spanEnd)
                out.add(AsrSegment(cursor, end, sentence))
                cursor = end
            }
        }
        // Snap the final cue to the timeline's exact end (float dust
        // would otherwise leave it a few nanoseconds short).
        if (out.isNotEmpty()) {
            val last = out.last()
            val target = spans.last().second
            if (kotlin.math.abs(target - last.endSec) < 0.01) {
                out[out.lastIndex] = last.copy(endSec = target)
            }
        }
    }
}
