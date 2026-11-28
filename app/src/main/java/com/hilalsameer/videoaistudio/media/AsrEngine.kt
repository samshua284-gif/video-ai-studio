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
 *
 * Threading (0.9.5, TD-023): the whisper recognizer's whole
 * life — construction, every stream decode, its release — runs
 * on the one decode thread owned by [DecodeCoordinator]; the
 * job thread submits and polls, never touches sherpa whisper
 * objects. A chunk decode is one long native call that cannot
 * be interrupted, so a cancel ABANDONS the await instead: the
 * screen frees within a poll slice, the orphan finishes on the
 * decode thread and is discarded, and the recognizer is
 * retired by the drain — never reused, and (via the
 * coordinator's process gate) never one of two alive at once.
 * The silero VAD stays on the job thread, as before: a separate
 * object with its own cancel-aware pass.
 */
class AsrEngine(private val context: Context) {

    /** Touched only on the decode thread; the references are
     *  volatile so the job thread may test them for the
     *  already-built fast path without touching the objects. */
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var builtLang: String? = null
    private var modelFiles: Triple<File, File, File>? = null
    private var vad: Vad? = null

    /** The detached-decode orchestration (0.9.5, TD-023). */
    private val decode = DecodeCoordinator()

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

    /** Build once; a language change reconfigures in place (no
     *  rebuild). DECODE THREAD ONLY (0.9.5, TD-023): invoked
     *  solely inside coordinator tasks, never by the job thread. */
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
     * Load the recognizer for [language] ahead of the job (0.9.4,
     * TD-022; detached in 0.9.5, TD-023). The model load —
     * materializing ~946 MB of medium model on first use and
     * building the sherpa recognizer — is the longest single
     * wait in the pipeline, and until 0.9.4 it happened
     * invisibly inside [transcribe], behind the generic
     * "preparing" label. The service runs it as its own named
     * phase (LoadingEngine) and checks the cancel flag the
     * moment it returns, before any VAD or decode work.
     *
     * The construction itself runs on the decode thread and is
     * awaited in cancel-poll slices: the native build cannot be
     * cut, but the AWAIT can — a cancel during the load returns
     * false here at once, and the construction completes on the
     * decode thread into a ready-but-unused recognizer
     * (harmless; released at engine close). Returns true when
     * the recognizer stands ready. Idempotent: exactly
     * [recognizerFor]'s caching — a no-op when the recognizer
     * for this language already exists.
     */
    fun prepare(language: String, isCancelled: () -> Boolean): Boolean {
        return ensureRecognizer(language, isCancelled)
    }

    /**
     * Bring the recognizer for [language] up on the decode
     * thread, honouring the coordinator's invariants: wait out
     * a drain on this engine, take the process gate (at most
     * one recognizer alive in the process), then construct.
     * False means the owner's cancel fired during one of the
     * waits — nothing was decoded and the caller ends the job
     * as Cancelled.
     */
    private fun ensureRecognizer(language: String, isCancelled: () -> Boolean): Boolean {
        if (recognizer != null && builtLang == language) return true
        if (!decode.awaitUnpoisoned(isCancelled)) return false
        if (recognizer != null && builtLang == language) return true
        if (!decode.acquireGate(isCancelled)) return false
        return try {
            decode.runTask(isCancelled, poisonOnAbandon = false) {
                recognizerFor(language)
            }
            true
        } catch (e: MtAbortException) {
            false
        }
    }

    /**
     * Transcribe a 16 kHz mono WAV (MediaCommands.extractWav16k).
     * [language] is a Whisper code ("ar", "en", "hi", "ur") or ""
     * for automatic detection. Progress is reported once with the
     * plan's total before decoding starts (0.9.4) and then per
     * decoded speech chunk (0.7.0: chunks, not fixed windows — a
     * file that is mostly silence has few chunks and finishes
     * fast). Decodes run detached on the decode thread (0.9.5,
     * TD-023): a cancel abandons the current chunk's await and
     * returns the partial list promptly, as a boundary cancel
     * did before, while the orphan finishes in the background.
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
        // 0.9.5 (TD-023): the recognizer is built on the decode
        // thread — after prepare() this is the already-built
        // fast path. False means the cancel fired during a gate
        // or construction wait: nothing was decoded.
        if (!ensureRecognizer(language, isCancelled)) return emptyList()
        // 0.9.4 (TD-022): a cancel pressed during the load is
        // honoured here, before the VAD pass — after prepare()
        // this is a cached no-op, so the check is free.
        if (isCancelled()) return emptyList()
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
        // 0.9.4 (TD-022): the counter is born here — the plan's
        // total reaches the UI BEFORE the first decode, not after
        // it. Until 0.9.4 the first progress call fired only once
        // chunk 1 had decoded, so the whole load + VAD + plan
        // stretch showed a 0% bar with no numbers at all.
        onProgress(0, chunks.size)
        // Pass 2: decode speech chunks only. Each chunk's
        // stream work runs as ONE task on the decode thread
        // (0.9.5, TD-023); this thread reads the samples and
        // awaits the text in cancel-poll slices.
        val segments = mutableListOf<AsrSegment>()
        var done = 0
        for (chunk in chunks) {
            if (isCancelled()) break
            val startSample = (chunk.startSec * 16000).toLong()
            val endSample = min(info.totalSamples, (chunk.endSec * 16000).toLong())
            val n = (endSample - startSample).toInt()
            if (n > 0) {
                val samples = WaveIo.readSamples(wav, info, startSample, n)
                val text = try {
                    decode.runTask(
                        isCancelled,
                        poisonOnAbandon = true,
                        onAbandoned = {
                            // The drain, on the decode thread,
                            // after the orphaned decode returned:
                            // the recognizer is retired — never
                            // reused after an abandoned decode —
                            // and the process gate frees with it.
                            try { recognizer?.release() } catch (e: Exception) { }
                            recognizer = null
                            builtLang = null
                            decode.releaseGate()
                        }
                    ) {
                        val rec = recognizer
                            ?: throw IllegalStateException("recognizer released before decode")
                        val stream = rec.createStream()
                        try {
                            stream.acceptWaveform(samples, 16000)
                            rec.decode(stream)
                            rec.getResult(stream).text.trim()
                        } finally {
                            try { stream.release() } catch (e: Exception) { }
                        }
                    }
                } catch (e: MtAbortException) {
                    // The owner stopped mid-chunk: the await is
                    // abandoned, the decode finishes on the
                    // decode thread and its result is discarded.
                    // Break with the partial list, exactly as a
                    // boundary cancel did before 0.9.5 — the
                    // service's flag check ends the job as
                    // Cancelled and the partials are dropped.
                    break
                }
                if (SpeechTextFilter.keep(text)) {
                    segments += AsrSegment(chunk.startSec, chunk.endSec, text, chunk.spans)
                }
            }
            done++
            onProgress(done, chunks.size)
        }
        return segments
    }

    /**
     * Release everything. The VAD is a job-thread object and is
     * released directly, as before; the whisper recognizer is
     * released on the decode thread via the coordinator's
     * close, which also frees the process gate — deferred to
     * the drain when a poison is still in flight, so no
     * recognizer is ever leaked without a scheduled release
     * (0.9.5, TD-023).
     */
    fun release() {
        try { vad?.release() } catch (e: Exception) { }
        vad = null
        decode.close {
            try { recognizer?.release() } catch (e: Exception) { }
            recognizer = null
            builtLang = null
            decode.releaseGate()
        }
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
