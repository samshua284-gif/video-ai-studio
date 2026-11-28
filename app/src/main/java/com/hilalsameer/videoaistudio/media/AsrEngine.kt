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

/**
 * On-device speech recognition (Milestone 4, TD-002): Whisper via
 * sherpa-onnx — the exact engine and call pattern the owner already
 * field-proved in his voice translator (its 1.13 ear). Fully
 * offline; the manifest declares no INTERNET permission.
 * The model is MEDIUM int8 since 0.9.0 (TD-015), by the owner's
 * explicit decision reversing his 0.7.0 choice of small: he found
 * small's word accuracy wanting and accepted medium's costs
 * (~946 MB of model, slower decode) with the numbers in front of
 * him. Only the model files changed; the pipeline below is the
 * 0.7.0 pipeline untouched.
 *
 * The build fetches the AAR and the model into the APK assets (see
 * app/build.gradle.kts, fetchAsrDeps, SHA-256 pinned); here they are
 * materialized once into internal storage with exact-size checks,
 * because sherpa opens real file paths.
 *
 * Pipeline (0.7.0, the owner's final transcription revision):
 *  1. A silero VAD pass over the whole file finds WHERE the speech
 *     is — the absolute spans of voice, nothing else.
 *  2. [SpeechChunks] plans decode units out of those spans alone.
 *  3. Whisper decodes ONLY the speech chunks. Non-speech — music,
 *     noise, effects, silence — never reaches the decoder, so it
 *     cannot invent words for it (the owner's explicit ask: no
 *     transcribing screams and outside sounds, only spoken words on
 *     their true timing). Each chunk's segment carries its measured
 *     spans, and the segmenter downstream places the cues on them.
 *  4. [SpeechTextFilter] drops any decoded segment that cannot be
 *     spoken words (pure symbols, a bracketed sound annotation).
 *
 * The standing honesty rule survives: a VAD failure never costs
 * speech — if the VAD pass itself throws, the plan falls back to
 * plain windows over the whole file ([SpeechChunks.fallback]).
 * Honest limit, stated to the owner: a scream that sounds like a
 * human voice can pass the VAD gate — the gate separates voice
 * from non-voice, not shouting from talking.
 */
class AsrEngine(private val context: Context) {

    private var recognizer: OfflineRecognizer? = null
    private var builtLang: String? = null
    private var modelFiles: Triple<File, File, File>? = null
    private var vad: Vad? = null

    /** Copy the model out of the assets once; verify exact sizes. */
    private fun materializeModels(): Triple<File, File, File> {
        modelFiles?.let { return it }
        val dir = File(context.filesDir, "asr/whisper-medium")
        dir.mkdirs()
        // 0.9.0 (TD-015): the retired small model of 0.5.0-0.8.0 is
        // deleted here so its ~375 MB does not stay orphaned on
        // the phone; after the first run this is a no-op.
        try {
            File(context.filesDir, "asr/whisper-small").deleteRecursively()
        } catch (e: Exception) {
            // Cleanup is best-effort; it never blocks transcription.
        }
        val specs = listOf(
            "medium-encoder.int8.onnx" to 374_196_283L,
            "medium-decoder.int8.onnx" to 571_059_257L,
            "medium-tokens.txt" to 816_730L
        )
        for ((name, size) in specs) {
            val f = File(dir, name)
            if (!f.exists() || f.length() != size) {
                context.assets.open("models/whisper-medium/$name").use { inp ->
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
        val dir = File(context.filesDir, "asr/whisper-medium")
        dir.mkdirs()
        val f = File(dir, "silero_vad.onnx")
        if (!f.exists() || f.length() != VAD_SIZE) {
            context.assets.open("models/whisper-medium/silero_vad.onnx").use { inp ->
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
                // 0.7.0: a VAD segment never exceeds one decode
                // chunk, so spans arrive pre-capped for the planner.
                maxSpeechDuration = SpeechChunks.MAX_CHUNK_SEC.toFloat()
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

    /** Feed one block of samples to the VAD in its own window size. */
    private fun feedVad(v: Vad, samples: FloatArray) {
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
     *  absolute seconds. The VAD is never reset during a whole-file
     *  pass, so segment starts are stream-absolute. */
    private fun drainVad(v: Vad, out: MutableList<Pair<Double, Double>>) {
        while (!v.empty()) {
            val seg = v.front()
            val s = seg.start / 16000.0
            val e = s + seg.samples.size / 16000.0
            if (e > s) out += s to e
            v.pop()
        }
    }

    /**
     * Pass 1 (0.7.0): the speech spans of the whole file. The audio
     * streams through the VAD in 30 s blocks — a long film's WAV is
     * never held in memory at once.
     */
    private fun allSpeechSpans(
        wav: File,
        info: WaveIo.Info,
        isCancelled: () -> Boolean
    ): List<Pair<Double, Double>> {
        val v = vadFor()
        v.reset()
        val spans = mutableListOf<Pair<Double, Double>>()
        var off = 0L
        while (off < info.totalSamples) {
            if (isCancelled()) break
            val n = min(BLOCK_SAMPLES.toLong(), info.totalSamples - off).toInt()
            val samples = WaveIo.readSamples(wav, info, off, n)
            feedVad(v, samples)
            drainVad(v, spans)
            off += n
        }
        v.flush()
        drainVad(v, spans)
        return spans
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
     * for automatic detection. Progress is reported per decoded
     * speech chunk (0.7.0: chunks, not fixed windows — a file that
     * is mostly silence has few chunks and finishes fast).
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
        val totalSec = info.totalSamples / 16000.0
        // Pass 1: where is the speech? A VAD failure falls back to
        // plain windows over everything — speech is never lost to it.
        val spans = try {
            allSpeechSpans(wav, info, isCancelled)
        } catch (e: Exception) {
            null
        }
        if (isCancelled()) return emptyList()
        val chunks = if (spans != null) {
            SpeechChunks.build(spans)
        } else {
            SpeechChunks.fallback(totalSec)
        }
        // Pass 2: decode speech chunks only.
        val segments = mutableListOf<AsrSegment>()
        var done = 0
        for (chunk in chunks) {
            if (isCancelled()) break
            val startSample = (chunk.startSec * 16000).toLong()
            val endSample = min(info.totalSamples, (chunk.endSec * 16000).toLong())
            val n = (endSample - startSample).toInt()
            if (n > 0) {
                val samples = WaveIo.readSamples(wav, info, startSample, n)
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(samples, 16000)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (SpeechTextFilter.keep(text)) {
                        segments += AsrSegment(chunk.startSec, chunk.endSec, text, chunk.spans)
                    }
                } finally {
                    try { stream.release() } catch (e: Exception) { }
                }
            }
            done++
            onProgress(done, chunks.size)
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

    companion object {
        /** VAD feeding block: 30 s of samples per file read. */
        const val BLOCK_SAMPLES = 30 * 16000

        /** silero_vad.onnx, exact size of the pinned release asset. */
        const val VAD_SIZE = 643_854L

        /** Silero's analysis window: 512 samples at 16 kHz. */
        const val VAD_WINDOW = 512
    }
}
