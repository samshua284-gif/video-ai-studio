package com.hilalsameer.videoaistudio.media

/**
 * The decode plan of the speech-only transcription pipeline (0.7.0).
 *
 * The owner asked for two things together: never transcribe
 * non-speech (music, noise, effects — Whisper invents words for
 * them), and keep every spoken line on its true timing. The plan
 * that serves both: a silero VAD pass first finds WHERE the speech
 * is, and Whisper then decodes ONLY the audio of speech chunks —
 * non-speech never reaches the decoder at all, so there is nothing
 * for it to hallucinate over.
 *
 * A chunk is one or more speech spans lying close together (gaps up
 * to [MERGE_GAP_SEC] ride inside the chunk; the decoder handles a
 * breath of silence between sentences better than a hard cut), capped
 * at [MAX_CHUNK_SEC] end to end — sherpa's Whisper ceiling is ~29.5 s
 * per stream and 25 s keeps a margin under it. A chunk holding less
 * than [MIN_CHUNK_SPEECH_SEC] of actual speech is dropped: an
 * isolated cough-length blip the VAD let through is not a sentence,
 * and decoding it alone invites an invented word.
 *
 * Pure and host-tested (SpeechChunksTest): no Android, no I/O.
 */
data class SpeechChunk(
    val startSec: Double,
    val endSec: Double,
    /** The speech spans inside this chunk, absolute seconds. */
    val spans: List<Pair<Double, Double>>
) {
    val speechSec: Double get() = spans.sumOf { it.second - it.first }
}

object SpeechChunks {

    /** Longest single decode, seconds end to end. */
    const val MAX_CHUNK_SEC = 25.0

    /** Silence gap up to this long merges two spans into one chunk. */
    const val MERGE_GAP_SEC = 1.5

    /** A chunk with less real speech than this is not decoded. */
    const val MIN_CHUNK_SPEECH_SEC = 0.35

    fun build(spans: List<Pair<Double, Double>>): List<SpeechChunk> {
        // Normalize: drop empties, sort, split over-long spans at the
        // chunk cap (a span is a VAD segment; the cap is ours).
        val pieces = mutableListOf<Pair<Double, Double>>()
        for ((s, e) in spans.filter { it.second > it.first }.sortedBy { it.first }) {
            var cur = s
            while (e - cur > MAX_CHUNK_SEC) {
                pieces += cur to (cur + MAX_CHUNK_SEC)
                cur += MAX_CHUNK_SEC
            }
            if (e > cur) pieces += cur to e
        }
        // Merge pieces that lie close together, under the cap.
        val chunks = mutableListOf<SpeechChunk>()
        var curStart = 0.0
        var curEnd = 0.0
        var curSpans = mutableListOf<Pair<Double, Double>>()
        fun flush() {
            if (curSpans.isNotEmpty()) {
                chunks += SpeechChunk(curStart, curEnd, curSpans.toList())
                curSpans = mutableListOf()
            }
        }
        for (p in pieces) {
            if (curSpans.isEmpty()) {
                curStart = p.first
                curEnd = p.second
                curSpans += p
                continue
            }
            val gap = p.first - curEnd
            val wouldEnd = maxOf(curEnd, p.second)
            if (gap <= MERGE_GAP_SEC && wouldEnd - curStart <= MAX_CHUNK_SEC) {
                curSpans += p
                curEnd = wouldEnd
            } else {
                flush()
                curStart = p.first
                curEnd = p.second
                curSpans += p
            }
        }
        flush()
        return chunks.filter { it.speechSec >= MIN_CHUNK_SPEECH_SEC }
    }

    /**
     * Fixed windows covering the whole file — the fallback when the
     * VAD pass itself failed. The standing rule survives the rewrite:
     * a VAD error must never cost the owner his speech, so on failure
     * the decoder goes back to plain windows over everything.
     */
    fun fallback(totalSec: Double): List<SpeechChunk> {
        val out = mutableListOf<SpeechChunk>()
        var s = 0.0
        while (s < totalSec) {
            val e = minOf(s + MAX_CHUNK_SEC, totalSec)
            if (e > s) out += SpeechChunk(s, e, listOf(s to e))
            s = e
        }
        return out
    }
}

/**
 * The last honesty filter on decoded text (0.7.0): a segment that
 * cannot be spoken words is dropped. Two shapes only — a segment
 * holding no letter or digit in any script (pure symbols, music
 * notes, punctuation), and a segment wrapped whole in brackets,
 * which is a sound annotation ([music], (screaming)), not a
 * sentence somebody said. Real speech is never touched: any letter
 * anywhere keeps the segment.
 */
object SpeechTextFilter {
    fun keep(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if ((t.startsWith("[") && t.endsWith("]")) ||
            (t.startsWith("(") && t.endsWith(")"))
        ) return false
        return t.any { it.isLetterOrDigit() }
    }
}
