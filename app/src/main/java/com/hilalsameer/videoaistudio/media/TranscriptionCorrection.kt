package com.hilalsameer.videoaistudio.media

/**
 * TD-025 — the pure core of the transcription correction
 * station (0.10.0). Everything here moves TEXT ONLY: every
 * function takes segments plus text-keyed edits and returns
 * segments whose start, end and speech spans are the input's
 * own values, carried through [AsrSegment.copy] untouched.
 * No function accepts a timing as input, so no caller of
 * this layer can retime a cue even by accident — the lock
 * is structural, not a display setting.
 */
object TranscriptionCorrection {

    /** The result of a text sweep: the new segments and how
     *  many cues actually changed text. */
    data class SweepResult(
        val segments: List<AsrSegment>,
        val changedCues: Int
    )

    /**
     * The shipped Layer-1 rule list — INTENTIONALLY EMPTY
     * (TD-025). A rule may enter this list only with its own
     * measured evidence on whisper-medium output; no such
     * evidence exists in this project today, and the voice
     * translator's rules belong to a different model and are
     * never imported. The live source of fixes is the
     * owner's correction memory (see [harvest] and
     * [suggestions]), applied only on his tap.
     */
    val BUILT_IN_RULES: List<Pair<String, String>> = emptyList()

    /**
     * Replaces cue texts by index. Indices outside the list
     * are ignored; a cue with no edit keeps its segment
     * object. Timings and spans are never parameters here —
     * they ride through [AsrSegment.copy] unchanged.
     */
    fun applyTextEdits(
        segments: List<AsrSegment>,
        edits: Map<Int, String>
    ): List<AsrSegment> {
        if (edits.isEmpty()) return segments
        return segments.mapIndexed { i, seg ->
            val t = edits[i]
            if (t == null || t == seg.text) seg else seg.copy(text = t)
        }
    }

    /** How many cue texts contain [find] as a literal
     *  substring. An empty needle matches nothing — searching
     *  for nothing must never "fix" everything. */
    fun countMatches(segments: List<AsrSegment>, find: String): Int {
        if (find.isEmpty()) return 0
        return segments.count { it.text.contains(find) }
    }

    /**
     * Literal replace-all of [find] by [replacement] across
     * every cue text (plain [String.replace]: no regex, no
     * word boundaries, single pass — a replacement that
     * itself contains [find] is not rescanned). The count
     * is the number of cues whose text actually changed.
     */
    fun replaceAll(
        segments: List<AsrSegment>,
        find: String,
        replacement: String
    ): SweepResult {
        if (find.isEmpty() || find == replacement) {
            return SweepResult(segments, 0)
        }
        var changed = 0
        val out = segments.map { seg ->
            if (seg.text.contains(find)) {
                val t = seg.text.replace(find, replacement)
                if (t != seg.text) {
                    changed++
                    seg.copy(text = t)
                } else {
                    seg
                }
            } else {
                seg
            }
        }
        return SweepResult(out, changed)
    }

    /**
     * The Layer-1 mechanism: applies ordered literal rules
     * to the cue texts, each rule seeing the previous rules'
     * output. [SweepResult.changedCues] counts the cues
     * whose final text differs from their original text.
     * With [BUILT_IN_RULES] empty this is the identity — by
     * design, not by omission.
     */
    fun applyRules(
        segments: List<AsrSegment>,
        rules: List<Pair<String, String>>
    ): SweepResult {
        var current = segments
        for ((find, replacement) in rules) {
            current = replaceAll(current, find, replacement).segments
        }
        var changed = 0
        segments.forEachIndexed { i, seg ->
            if (current[i].text != seg.text) changed++
        }
        return SweepResult(current, changed)
    }

    /**
     * Teaches the correction memory from one correction
     * session: for every cue whose corrected text differs
     * from the recognized text, the pair (recognized text
     * to his text) is recorded under the same normalised
     * key the translation memory uses ([CorrectionMemory.key]).
     * Blank corrections and unchanged cues teach nothing;
     * a size mismatch teaches nothing at all — ambiguous
     * alignment is never guessed.
     */
    fun harvest(
        original: List<AsrSegment>,
        corrected: List<AsrSegment>
    ): Map<String, String> {
        if (original.size != corrected.size) return emptyMap()
        val out = LinkedHashMap<String, String>()
        original.forEachIndexed { i, seg ->
            val fixed = corrected[i].text.trim()
            if (fixed.isNotEmpty() && fixed != seg.text.trim()) {
                out[CorrectionMemory.key(seg.text)] = fixed
            }
        }
        return out
    }

    /**
     * Offers from the memory: cue index to the owner's
     * stored correction, for every cue whose CURRENT text
     * he has corrected before. A suggestion is data only —
     * nothing here applies it; the screen applies on his
     * tap, via [applyTextEdits]. A cue already carrying
     * his text is not re-offered its own correction.
     */
    fun suggestions(
        segments: List<AsrSegment>,
        memory: Map<String, String>
    ): Map<Int, String> {
        if (memory.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        segments.forEachIndexed { i, seg ->
            val remembered = CorrectionMemory.lookup(seg.text, memory)
            if (remembered != null && remembered != seg.text.trim()) {
                out[i] = remembered
            }
        }
        return out
    }
}
