package com.hilalsameer.videoaistudio.media

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device speech recognition (Milestone 4, TD-002): Whisper-small
 * via sherpa-onnx — the exact engine, model files and call pattern
 * the owner already field-proved in his voice translator (its 1.13
 * ear). Fully offline; the manifest declares no INTERNET permission.
 *
 * The build fetches the AAR and the model into the APK assets (see
 * app/build.gradle.kts, fetchAsrDeps, SHA-256 pinned); here they are
 * materialized once into internal storage with exact-size checks,
 * because sherpa opens real file paths.
 *
 * sherpa's Whisper accepts at most ~29.5 s per stream, so the audio
 * is decoded in consecutive 25 s windows (the translator's proven
 * chunk size). Each window becomes one [AsrSegment] stamped with its
 * real position. Silent windows (RMS below the floor) are skipped:
 * Whisper invents text on silence, and an honest gap beats a
 * fabricated sentence (TD-006 upstream lever: silence detection).
 */
class AsrEngine(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var builtLang: String? = null
    private var modelFiles: Triple<File, File, File>? = null

    /** Copy the model out of the assets once; verify exact sizes. */
    private fun materializeModels(): Triple<File, File, File> {
        modelFiles?.let { return it }
        val dir = File(context.filesDir, "asr/whisper-small")
        dir.mkdirs()
        val specs = listOf(
            "small-encoder.int8.onnx" to 112_442_483L,
            "small-decoder.int8.onnx" to 262_226_114L,
            "small-tokens.txt" to 816_730L
        )
        for ((name, size) in specs) {
            val f = File(dir, name)
            if (!f.exists() || f.length() != size) {
                context.assets.open("models/whisper-small/$name").use { inp ->
                    f.outputStream().use { out -> inp.copyTo(out, 1 shl 16) }
                }
                require(f.length() == size) { "model copy failed: $name" }
            }
        }
        val triple = Triple(
            File(dir, specs[0].first), File(dir, specs[1].first), File(dir, specs[2].first)
        )
        modelFiles = triple
        return triple
    }

    /** The translator's proven configuration, verbatim. */
    private fun buildConfig(enc: File, dec: File, tokens: File, lang: String): OfflineRecognizerConfig {
        val wcfg = OfflineWhisperModelConfig()
        wcfg.encoder = enc.absolutePath
        wcfg.decoder = dec.absolutePath
        wcfg.language = lang
        wcfg.task = "transcribe"
        val mcfg = OfflineModelConfig()
        mcfg.whisper = wcfg
        mcfg.tokens = tokens.absolutePath
        mcfg.numThreads = 4
        mcfg.debug = false
        mcfg.provider = "cpu"
        val fcfg = FeatureConfig()
        fcfg.sampleRate = 16000
        fcfg.featureDim = 80
        val rcfg = OfflineRecognizerConfig()
        rcfg.featConfig = fcfg
        rcfg.modelConfig = mcfg
        rcfg.decodingMethod = "greedy_search"
        return rcfg
    }

    /** Build once; a language change reconfigures in place (no rebuild). */
    private fun recognizerFor(lang: String): OfflineRecognizer {
        val (enc, dec, tok) = materializeModels()
        val existing = recognizer
        if (existing == null) {
            // sherpa 1.12.40's only constructor takes an AssetManager
            // (null here: the model lives at real file paths — the
            // translator's proven call, its 1.13 ear).
            val rec = OfflineRecognizer(null, buildConfig(enc, dec, tok, lang))
            recognizer = rec
            builtLang = lang
            return rec
        }
        if (builtLang != lang) {
            existing.setConfig(buildConfig(enc, dec, tok, lang))
            builtLang = lang
        }
        return existing
    }

    /**
     * Transcribe a 16 kHz mono WAV (MediaCommands.extractWav16k).
     * [language] is a Whisper code ("ar", "en", "hi", "ur") or ""
     * for automatic detection. Progress is reported per window.
     */
    fun transcribe(
        wav: File,
        language: String,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): List<AsrSegment> {
        val info = WaveIo.readHeader(wav)
        require(info.sampleRate == 16000 && info.channels == 1) {
            "expected a 16 kHz mono WAV, got ${info.sampleRate} Hz / ${info.channels} ch"
        }
        val rec = recognizerFor(language)
        val chunk = CHUNK_SAMPLES
        val totalChunks = ((info.totalSamples + chunk - 1) / chunk).toInt()
        val segments = mutableListOf<AsrSegment>()
        var off = 0L
        var done = 0
        while (off < info.totalSamples) {
            if (isCancelled()) break
            val n = min(chunk.toLong(), info.totalSamples - off).toInt()
            val samples = WaveIo.readSamples(wav, info, off, n)
            val startSec = off / 16000.0
            val endSec = (off + n) / 16000.0
            if (rms(samples) >= SILENCE_RMS) {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotEmpty()) segments += AsrSegment(startSec, endSec, text)
                } finally {
                    try { stream.release() } catch (e: Exception) { }
                }
            }
            off += n
            done++
            onProgress(done, totalChunks)
        }
        return segments
    }

    fun release() {
        try { recognizer?.release() } catch (e: Exception) { }
        recognizer = null
        builtLang = null
    }

    private fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        return sqrt(sum / samples.size)
    }

    companion object {
        /** sherpa Whisper ceiling is ~29.5 s/stream; 25 s is proven. */
        const val CHUNK_SAMPLES = 25 * 16000

        /** Below this RMS a window counts as silence and is skipped. */
        const val SILENCE_RMS = 0.004
    }
}
