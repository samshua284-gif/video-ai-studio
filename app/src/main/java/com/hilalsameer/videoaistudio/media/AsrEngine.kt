package com.hilalsameer.videoaistudio.media

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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
 * is decoded in consecutive 29 s windows. Each window becomes one
 * [AsrSegment] stamped with its real position. Two gates keep the
 * decoder off non-speech (0.5.1, TD-008): silent windows (RMS below
 * the floor) are skipped outright, and the rest pass a silero VAD —
 * a window holding under a second of voice (music-only, action,
 * ambience) is skipped too. Whisper invents text on non-speech, so
 * the gates are an honesty lever as much as a speed lever. A VAD
 * failure never costs speech: on any VAD error the window is
 * decoded anyway.
 */
class AsrEngine(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var builtLang: String? = null
    private var modelFiles: Triple<File, File, File>? = null
    private var vad: Vad? = null

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

    /** Copy the silero VAD model out of the assets once (0.5.1). */
    private fun materializeVad(): File {
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

    private fun vadFor(): Vad {
        vad?.let { return it }
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = materializeVad().absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = 30f
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
        // null AssetManager: real file paths, same as the recognizer.
        val v = Vad(null, cfg)
        vad = v
        return v
    }

    /** Seconds of speech the VAD finds in one window. */
    private fun speechSeconds(v: Vad, samples: FloatArray): Double {
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
        v.flush()
        var speech = 0L
        while (!v.empty()) {
            speech += v.front().samples.size
            v.pop()
        }
        v.reset()
        return speech / 16000.0
    }

    /** The translator's proven configuration (threads widened 0.5.1). */
    private fun buildConfig(enc: File, dec: File, tokens: File, lang: String): OfflineRecognizerConfig {
        val wcfg = OfflineWhisperModelConfig()
        wcfg.encoder = enc.absolutePath
        wcfg.decoder = dec.absolutePath
        wcfg.language = lang
        wcfg.task = "transcribe"
        val mcfg = OfflineModelConfig()
        mcfg.whisper = wcfg
        mcfg.tokens = tokens.absolutePath
        mcfg.numThreads = min(8, Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
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
                // VAD gate (0.5.1): skip windows without real speech.
                // A VAD error decodes anyway — never lose speech to it.
                val decode = try {
                    VadGate.shouldDecode(speechSeconds(vadFor(), samples))
                } catch (e: Exception) {
                    true
                }
                if (decode) {
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
        try { vad?.release() } catch (e: Exception) { }
        vad = null
    }

    private fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += (s * s).toDouble()
        return sqrt(sum / samples.size)
    }

    companion object {
        /** sherpa Whisper ceiling is ~29.5 s/stream; 29 s windows
         *  mean ~14% fewer encoder passes than the 0.5.0 25 s cut. */
        const val CHUNK_SAMPLES = 29 * 16000

        /** Below this RMS a window counts as silence and is skipped. */
        const val SILENCE_RMS = 0.004

        /** silero_vad.onnx, exact size of the pinned release asset. */
        const val VAD_SIZE = 643_854L

        /** Silero's analysis window: 512 samples at 16 kHz. */
        const val VAD_WINDOW = 512
    }
}
