package com.hilalsameer.videoaistudio.media

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.min

/**
 * Speech spans for the speech gate (0.7.2, TD-013): the same
 * silero VAD, with the same configuration, that the transcription
 * engine trusts (AsrEngine, 0.7.0) — deliberately a separate small
 * class rather than a change to AsrEngine, whose transcription
 * behaviour the owner froze with 0.7.0. If silero ever fails, the
 * caller catches it and falls back to the ungated denoised audio:
 * the gate is an addition on top of a successful denoise, never a
 * new way to fail the job.
 */
object VadSpans {

    private const val VAD_SIZE = 643_854L
    private const val VAD_WINDOW = 512
    private const val BLOCK_SAMPLES = 30 * 16000

    private fun materialize(context: Context): File {
        val dir = File(context.filesDir, "asr/whisper-small")
        dir.mkdirs()
        val f = File(dir, "silero_vad.onnx")
        if (!f.exists() || f.length() != VAD_SIZE) {
            context.assets.open("models/whisper-small/silero_vad.onnx").use { inp ->
                f.outputStream().use { out -> inp.copyTo(out, 1 shl 16) }
            }
            require(f.length() == VAD_SIZE) { "vad copy failed" }
        }
        return f
    }

    /** Speech spans (absolute seconds) of a 16 kHz mono WAV. */
    fun speechSpans(
        context: Context,
        wav: File,
        isCancelled: () -> Boolean
    ): List<Pair<Double, Double>> {
        val info = WaveIo.readHeader(wav)
        require(info.sampleRate == 16000) { "VAD input must be 16 kHz WAV" }
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = materialize(context).absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 25f
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
        val vad = Vad(null, cfg)
        try {
            val spans = mutableListOf<Pair<Double, Double>>()
            var off = 0L
            while (off < info.totalSamples) {
                if (isCancelled()) break
                val n = min(BLOCK_SAMPLES.toLong(), info.totalSamples - off).toInt()
                feed(vad, WaveIo.readSamples(wav, info, off, n))
                drain(vad, spans)
                off += n
            }
            vad.flush()
            drain(vad, spans)
            return spans
        } finally {
            try { vad.release() } catch (e: Exception) { }
        }
    }

    /** Feed one block of samples to the VAD in its own window size. */
    private fun feed(v: Vad, samples: FloatArray) {
        var off = 0
        while (off < samples.size) {
            val n = min(VAD_WINDOW, samples.size - off)
            if (n == VAD_WINDOW) {
                v.acceptWaveform(samples.copyOfRange(off, off + n))
            } else {
                val w = FloatArray(VAD_WINDOW)
                samples.copyInto(w, 0, off, off + n)
                v.acceptWaveform(w)
            }
            off += n
        }
    }

    /** Drain the segments the VAD has completed into [out], as
     *  absolute seconds (the VAD is never reset during a whole-file
     *  pass, so segment starts are stream-absolute). */
    private fun drain(v: Vad, out: MutableList<Pair<Double, Double>>) {
        while (!v.empty()) {
            val seg = v.front()
            val s = seg.start / 16000.0
            val e = s + seg.samples.size / 16000.0
            if (e > s) out += s to e
            v.pop()
        }
    }
}
