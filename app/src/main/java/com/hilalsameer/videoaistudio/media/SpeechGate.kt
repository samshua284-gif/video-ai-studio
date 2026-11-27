package com.hilalsameer.videoaistudio.media

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/**
 * Speech gate (0.7.2, TD-013): the second half of the noise tool's
 * maximum-cleanup mandate. GTCRN removes steady noise but, by
 * design, preserves everything voice-like — including the breaths
 * and gasps that sit in the pauses (the owner's comparison,
 * 2026-11-27: a leading gasp our engine kept that a gated cleanup
 * erased completely). This gate silences what is NOT speech,
 * driven by the silero VAD spans of the denoised audio
 * (see [VadSpans]):
 *
 *  - every speech span is expanded by safety margins (0.15 s
 *    before, 0.25 s after), so word onsets and tails are never
 *    clipped — the classic gate failure this design refuses;
 *  - pauses shorter than 0.35 s between spans are kept whole —
 *    natural prosody is not silence to be eaten;
 *  - everything else fades to zero over 30 ms ramps, so the cuts
 *    are click-free.
 *
 * Safety: with no speech spans at all (music, nature recordings),
 * [keepRegions] returns an empty list and the caller must not run
 * the gate at all — non-speech material is never wiped.
 *
 * The region/gain math is pure and host-tested (SpeechGateTest);
 * the file pass streams in 30 s cores like [GtcrnEngine].
 */
object SpeechGate {

    data class Params(
        val keepBeforeSec: Double = 0.15,
        val keepAfterSec: Double = 0.25,
        val minGapSec: Double = 0.35,
        val fadeSec: Double = 0.03
    )

    val DEFAULT = Params()

    /** Sorted, merged keep-regions (seconds) for [spans] in a file
     *  of [totalSec]: each span grows by the safety margins, and
     *  regions separated by less than minGapSec merge into one. */
    fun keepRegions(
        spans: List<Pair<Double, Double>>,
        totalSec: Double,
        p: Params = DEFAULT
    ): List<Pair<Double, Double>> {
        if (spans.isEmpty() || totalSec <= 0.0) return emptyList()
        val grown = spans
            .map { (s, e) ->
                max(0.0, s - p.keepBeforeSec) to min(totalSec, e + p.keepAfterSec)
            }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        if (grown.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Double, Double>>(grown.size)
        var cur = grown[0]
        for (i in 1 until grown.size) {
            val nxt = grown[i]
            if (nxt.first - cur.second < p.minGapSec) {
                cur = cur.first to max(cur.second, nxt.second)
            } else {
                out += cur
                cur = nxt
            }
        }
        out += cur
        return out
    }

    /** Monotonic gain envelope over [regions]: 1.0 inside a keep
     *  region, 0.0 outside, with linear fades of fadeSec straddling
     *  each edge. Callers must query with non-decreasing t (the
     *  file pass does); the cursor only moves forward. */
    class Envelope(
        private val regions: List<Pair<Double, Double>>,
        private val p: Params = DEFAULT
    ) {
        private var idx = 0

        fun gainAt(tSec: Double): Float {
            while (idx < regions.size && tSec > regions[idx].second + p.fadeSec) idx++
            if (idx >= regions.size) return 0f
            val (s, e) = regions[idx]
            return when {
                tSec < s - p.fadeSec -> 0f
                tSec < s -> ((tSec - (s - p.fadeSec)) / p.fadeSec).toFloat()
                tSec <= e -> 1f
                tSec <= e + p.fadeSec -> (1.0 - (tSec - e) / p.fadeSec).toFloat()
                else -> 0f
            }
        }
    }

    /**
     * Stream [inWav] through the gate into [outWav] (16 kHz mono
     * WAV, the noise tool's working format). [regions] must be
     * non-empty — see the safety note above. Returns false if
     * [isCancelled] fires between cores.
     */
    fun processFile(
        inWav: File,
        outWav: File,
        regions: List<Pair<Double, Double>>,
        p: Params = DEFAULT,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        require(regions.isNotEmpty()) { "gate needs at least one keep region" }
        val info = WaveIo.readHeader(inWav)
        require(info.sampleRate == 16000) { "gate input must be 16 kHz WAV" }
        val total = info.totalSamples
        val env = Envelope(regions, p)
        RandomAccessFile(outWav, "rw").use { out ->
            out.setLength(0)
            WaveIo.writeHeader(out, 16000, total)
            var pos = 0L
            while (pos < total) {
                if (isCancelled()) return false
                val n = min(CORE, total - pos).toInt()
                val samples = WaveIo.readSamples(inWav, info, pos, n)
                for (i in samples.indices) {
                    val g = env.gainAt((pos + i) / 16000.0)
                    if (g != 1f) samples[i] *= g
                }
                WaveIo.appendSamples(out, samples)
                pos += n
                onProgress((pos.toFloat() / total.toFloat()).coerceIn(0f, 1f))
            }
        }
        return true
    }

    private const val CORE = 480000L // 30 s
}
