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
 *
 * 0.9.6 (TD-024) tightens placement inside overlapped-speech
 * spans, where voices merge into ONE long unbroken VAD span and
 * whisper returns under-punctuated run-on text: (1) a sentence
 * longer than [MAX_PIECE_CHARS] is split at clause boundaries
 * before distribution, so no single cue is pinned to a wide
 * proportional window on a guess; (2) a minimum cue duration of
 * [MIN_CUE_SEC] is funded by borrowing inside the span, so short
 * interjections stop flickering. Two voices truly simultaneous
 * in the recording still share one span and one mixed text
 * stream — there is no diarization or source separation in this
 * offline stack; that residual is a physical limit, not a bug
 * this pass claims to fix.
 */
object SubtitleSegmenter {

    private val ENDERS = setOf('.', '?', '!', '؟', '…', '।')
    private const val CLOSERS = "\"'”’»)]}»"

    /** Longest text one cue carries before clause splitting. */
    private const val MAX_PIECE_CHARS = 84

    /** A clause split never creates a piece shorter than this. */
    private const val MIN_PIECE_CHARS = 20

    /** Minimum on-screen time for a cue inside a span (0.9.6). */
    private const val MIN_CUE_SEC = 0.8

    private const val EPS = 1e-9

    /** Clause punctuation in split-priority order. */
    private val CLAUSE_PUNCT = listOf('،', ',', '؛', ';', '—', '–')

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

    /**
     * Split one sentence at clause boundaries until every piece
     * is at most [MAX_PIECE_CHARS] (0.9.6). Boundaries, in
     * priority order: '،', ',', '؛', ';', '—', '–', and finally
     * the Arabic coordinating conjunction ' و '. Punctuation
     * stays attached to the PRECEDING piece; the conjunction
     * stays attached to the FOLLOWING piece, as Arabic reads.
     *
     * HARD INVARIANT: splitting loses and adds nothing —
     * joining the pieces in order reproduces the sentence
     * exactly, character for character, separators included.
     * A split is only usable when both sides keep at least
     * [MIN_PIECE_CHARS] (no shredding); a sentence with no
     * usable boundary stays whole — it is never cut mid-word.
     */
    fun splitClauses(sentence: String): List<String> {
        if (sentence.length <= MAX_PIECE_CHARS) return listOf(sentence)
        val cut = findClauseCut(sentence) ?: return listOf(sentence)
        return splitClauses(sentence.substring(0, cut)) +
            splitClauses(sentence.substring(cut))
    }

    /** The cut index for one clause split, or null if none is usable. */
    private fun findClauseCut(s: String): Int? {
        for (ch in CLAUSE_PUNCT) {
            val cuts = mutableListOf<Int>()
            var i = s.indexOf(ch)
            while (i >= 0) {
                cuts += i + 1 // punctuation stays with the preceding piece
                i = s.indexOf(ch, i + 1)
            }
            pickClauseCut(s, cuts)?.let { return it }
        }
        val cuts = mutableListOf<Int>()
        var i = s.indexOf(" و ")
        while (i >= 0) {
            cuts += i + 1 // cut before 'و': it opens the following piece
            i = s.indexOf(" و ", i + 1)
        }
        return pickClauseCut(s, cuts)
    }

    /** Of the usable cuts of one boundary kind, the one nearest the middle. */
    private fun pickClauseCut(s: String, cuts: List<Int>): Int? {
        val mid = s.length / 2.0
        return cuts
            .filter { it >= MIN_PIECE_CHARS && s.length - it >= MIN_PIECE_CHARS }
            .minByOrNull { kotlin.math.abs(it - mid) }
    }

    /** Re-segment window segments into sentence cues. */
    fun resegment(segments: List<AsrSegment>): List<AsrSegment> {
        val out = ArrayList<AsrSegment>()
        for (seg in segments) {
            // 0.9.6: over-long sentences are clause-split before
            // distribution, so a run-on spanning a whole merged
            // overlap span becomes several placeable pieces.
            val sentences = splitSentences(seg.text).flatMap { splitClauses(it) }
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
            val groupCues = ArrayList<AsrSegment>(group.size)
            var cursor = spanStart
            for (sentence in group) {
                val end = minOf(cursor + ideal(sentence) * scale, spanEnd)
                groupCues.add(AsrSegment(cursor, end, sentence))
                cursor = end
            }
            applyFloor(groupCues, spanLen)
            out.addAll(groupCues)
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

    /**
     * Fund the [MIN_CUE_SEC] floor for one span's placed cues
     * (0.9.6). A cue shorter than the floor is extended by
     * borrowing from the other cues of the same span: the
     * FOLLOWING cues first (nearest first), then the preceding
     * ones — the preceding fallback exists for a short cue whose
     * following neighbours are themselves sub-floor, where the
     * time can only come from earlier in the same span. A donor
     * never drops below the floor itself. If every donor is
     * already at the floor, the short cue grows into the span's
     * unused room instead. Borrowing never crosses the span's
     * bounds, never creates overlap or a negative duration, and
     * never reaches into a silence gap: the group's total stays
     * at most the span's length, and boundaries are rebuilt
     * contiguously from the group's start.
     *
     * The floor YIELDS when the span cannot fund it: if the
     * span is shorter than cues × [MIN_CUE_SEC], the placed
     * proportional durations stand unchanged. When no cue is
     * below the floor, the placement is bit-identical to the
     * pre-0.9.6 segmenter.
     */
    private fun applyFloor(cues: ArrayList<AsrSegment>, spanLen: Double) {
        val n = cues.size
        if (n == 0) return
        if (spanLen < n * MIN_CUE_SEC - EPS) return
        val d = DoubleArray(n) { cues[it].endSec - cues[it].startSec }
        if (d.all { it >= MIN_CUE_SEC - EPS }) return
        for (i in 0 until n) {
            if (d[i] >= MIN_CUE_SEC - EPS) continue
            var deficit = MIN_CUE_SEC - d[i]
            val donors = (i + 1 until n).asIterable() + (i - 1 downTo 0)
            for (j in donors) {
                if (deficit <= EPS) break
                val give = minOf(d[j] - MIN_CUE_SEC, deficit)
                if (give > EPS) {
                    d[j] -= give
                    d[i] += give
                    deficit -= give
                }
            }
            if (deficit > EPS) d[i] += deficit // grow into the span's room
        }
        var cursor = cues.first().startSec
        for (k in 0 until n) {
            val end = cursor + d[k]
            cues[k] = cues[k].copy(startSec = cursor, endSec = end)
            cursor = end
        }
    }
}
