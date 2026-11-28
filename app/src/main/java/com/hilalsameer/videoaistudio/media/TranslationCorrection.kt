package com.hilalsameer.videoaistudio.media

/**
 * TD-026 — the pure core of the translation correction
 * station (0.10.1), the sibling of [TranscriptionCorrection]
 * under the same structural discipline: every function moves
 * the TRANSLATED text only, keyed by cue index, and rebuilds
 * segments through [AsrSegment.copy] — timings and speech
 * spans ride through untouched and are never parameters.
 * The source segments are read-only inputs here: they key
 * the correction memory and feed re-translation, and no
 * function returns a modified source list.
 *
 * TD-028 (0.10.3) adds the same single bounded exception
 * as the transcription core: [nudge], the translation
 * station's − / + buttons, delegating to
 * [TranscriptionCorrection.nudge] and its clamp contract.
 * It moves the TRANSLATED cue's timings; the source list
 * stays exactly as read-only as it was.
 */
object TranslationCorrection {

    /** The result of a text sweep over the translated cues. */
    data class SweepResult(
        val segments: List<AsrSegment>,
        val changedCues: Int
    )

    /** Replaces translated cue texts by index — the same
     *  text-only rebuild as the transcription station. */
    fun applyTextEdits(
        translated: List<AsrSegment>,
        edits: Map<Int, String>
    ): List<AsrSegment> = TranscriptionCorrection.applyTextEdits(translated, edits)

    /** How many translated cue texts contain [find]. */
    fun countMatches(translated: List<AsrSegment>, find: String): Int =
        TranscriptionCorrection.countMatches(translated, find)

    /** Literal replace-all across the translated texts only;
     *  the source list is not involved and cannot change. */
    fun replaceAll(
        translated: List<AsrSegment>,
        find: String,
        replacement: String
    ): SweepResult {
        val r = TranscriptionCorrection.replaceAll(translated, find, replacement)
        return SweepResult(r.segments, r.changedCues)
    }

    /**
     * Teaches the translation correction memory from one
     * station session, with exactly the semantics of the
     * existing translation editing ([CorrectionMemory.harvest]):
     * for every cue whose final text differs from the machine
     * text the station was opened with, the pair
     * (source text → his text) is recorded under
     * [CorrectionMemory.key]. Blank corrections and unchanged
     * cues teach nothing; any size mismatch teaches nothing
     * at all — ambiguous alignment is never guessed.
     */
    fun harvest(
        source: List<AsrSegment>,
        machine: List<AsrSegment>,
        corrected: List<AsrSegment>
    ): Map<String, String> {
        if (source.size != machine.size || corrected.size != machine.size) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        source.forEachIndexed { i, seg ->
            val fixed = corrected[i].text.trim()
            if (fixed.isNotEmpty() && fixed != machine[i].text.trim()) {
                out[CorrectionMemory.key(seg.text)] = fixed
            }
        }
        return out
    }

    /**
     * Offers from the translation memory: cue index to his
     * stored rendering, for every cue whose SOURCE segment he
     * has corrected before (the same lookup the translation
     * job itself performs). An offer is data only — the
     * screen applies it on his tap, via [applyTextEdits]. A
     * cue already carrying his rendering is not re-offered it.
     */
    fun suggestions(
        source: List<AsrSegment>,
        translated: List<AsrSegment>,
        memory: Map<String, String>
    ): Map<Int, String> {
        if (memory.isEmpty() || source.size != translated.size) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        source.forEachIndexed { i, seg ->
            val remembered = CorrectionMemory.lookup(seg.text, memory)
            if (remembered != null && remembered != translated[i].text.trim()) {
                out[i] = remembered
            }
        }
        return out
    }

    /** The nudge step the stations' − / + buttons use —
     *  the transcription core's step (TD-028). */
    const val NUDGE_STEP_MS: Long = TranscriptionCorrection.NUDGE_STEP_MS

    /**
     * TD-028: shifts translated cue [index]'s start and end
     * together by [deltaMs], under exactly the transcription
     * core's clamp contract (see
     * [TranscriptionCorrection.nudge]): never across a
     * neighbour, never before zero, duration preserved, a
     * partial shift at a wall, a no-op at a closed one. The
     * exported translation carries the translated cues'
     * timings, so this is the list that had to move; the
     * source segments are not involved.
     */
    fun nudge(
        translated: List<AsrSegment>,
        index: Int,
        deltaMs: Long
    ): List<AsrSegment> = TranscriptionCorrection.nudge(translated, index, deltaMs)

    /**
     * One cue's re-translation outcome, as reported by the
     * engine bridge (MtResult's diagnostics, TD-020): the
     * rendered [text], how many of the cue's units the worker
     * saw, how many really translated, how many passed
     * through untranslated, and the first real engine error.
     */
    data class UnitOutcome(
        val text: String,
        val unitsTotal: Int,
        val unitsOk: Int,
        val unitsPassed: Int,
        val firstError: String?
    )

    /**
     * What a re-translation may become in the cue's card.
     * [Offer] carries a rendering the owner may accept; it is
     * NEVER written into a cue by this layer — [applyOffer],
     * called only from the screen's accept action, is the
     * single path from an offer to a cue text. [Failed]
     * carries the engine's own account verbatim (its first
     * error, or null when the failure was an empty result):
     * a cue whose units all passed through has no new
     * rendering to offer — offering the passed-through source
     * text as a "translation" would repeat the exact
     * masquerade TD-020 abolished.
     */
    sealed interface RetranslateEvaluation {
        data class Offer(
            val text: String,
            val passedUnits: Int,
            val totalUnits: Int
        ) : RetranslateEvaluation

        data class Failed(val error: String?) : RetranslateEvaluation
    }

    /** Judges one re-translation outcome (TD-020 honesty,
     *  applied per cue): blank text or a complete
     *  pass-through is a failure with the engine's error
     *  attached; anything else is an offer, carrying its
     *  partial pass-through count so the card can state it. */
    fun evaluate(outcome: UnitOutcome): RetranslateEvaluation {
        if (outcome.text.isBlank()) {
            return RetranslateEvaluation.Failed(outcome.firstError)
        }
        if (outcome.unitsTotal > 0 && outcome.unitsPassed >= outcome.unitsTotal) {
            return RetranslateEvaluation.Failed(outcome.firstError)
        }
        return RetranslateEvaluation.Offer(
            text = outcome.text,
            passedUnits = outcome.unitsPassed,
            totalUnits = outcome.unitsTotal
        )
    }

    /**
     * The one path from an offer to a cue: replaces cue
     * [index]'s translated text with the accepted rendering.
     * Nothing else in this layer — and nothing in the
     * evaluation above — mutates a cue on an offer's behalf.
     */
    fun applyOffer(
        translated: List<AsrSegment>,
        index: Int,
        offerText: String
    ): List<AsrSegment> = applyTextEdits(translated, mapOf(index to offerText))
}
