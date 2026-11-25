package com.hilalsameer.videoaistudio.media

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Milestone 2 media engine: FFmpegKit (TD-001) command builders plus an
 * async runner. The builders are pure functions so the exact command
 * lines are unit-testable without a device.
 */
object MediaCommands {

    private fun sec(v: Double): String = String.format(Locale.US, "%.3f", v)

    /** Extract the audio track. MP3 via libmp3lame; WAV as 16-bit PCM. */
    fun extractAudio(input: String, output: String, mp3: Boolean, bitrateKbps: Int): Array<String> =
        if (mp3) {
            arrayOf(
                "-y", "-i", input,
                "-vn", "-codec:a", "libmp3lame", "-b:a", "${bitrateKbps}k",
                output
            )
        } else {
            arrayOf(
                "-y", "-i", input,
                "-vn", "-codec:a", "pcm_s16le",
                output
            )
        }

    /**
     * Frame-accurate trim: input seeking (-ss before -i) with a full
     * re-encode, so the cut lands on the requested second rather than
     * the nearest keyframe.
     */
    fun trim(input: String, output: String, startSec: Double, endSec: Double): Array<String> =
        arrayOf(
            "-y", "-ss", sec(startSec), "-i", input,
            "-t", sec(endSec - startSec),
            "-codec:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-codec:a", "aac", "-b:a", "160k",
            output
        )

    /**
     * Compress: H.264 at the given CRF, downscaling to maxHeight if
     * taller. The scale decision is made HERE, not in a filter
     * expression: the first build used `scale=-2:min(720,ih)` and the
     * filtergraph parser split on the comma inside min(), failing
     * with "No such filter: 'ih)'" on every input (owner's device log,
     * 2026-11-25; reproduced on the host the same day). An explicit
     * target height has no comma to split on.
     */
    fun compress(
        input: String, output: String, crf: Int, maxHeight: Int, sourceHeight: Int
    ): Array<String> {
        val base = arrayOf(
            "-y", "-i", input,
            "-codec:v", "libx264", "-preset", "veryfast", "-crf", crf.toString(),
            "-codec:a", "aac", "-b:a", "128k",
            output
        )
        if (sourceHeight <= maxHeight) return base
        val withScale = base.toMutableList()
        // Index 3 = immediately AFTER the input path. The first fix
        // inserted at index 2, between "-i" and the input, so FFmpeg
        // tried to open a file named "-vf" (owner's log, 2026-11-25:
        // "vf: No such file or directory"). The unit test now asserts
        // argument ORDER, not just presence.
        withScale.addAll(3, listOf("-vf", "scale=-2:$maxHeight"))
        return withScale.toTypedArray()
    }

    /**
     * Denoise modes: (noise reduction dB, noise floor dB, tracking).
     * afftdn's nf range in FFmpeg 6.0 is [-80, -20] — the first field
     * build used -14 for Strong and the filter refused it (owner's
     * log, 2026-11-25). Keep every nf inside the range.
     */
    val denoiseModes = listOf(
        Triple(8, -30, false),
        Triple(14, -24, true),
        Triple(24, -21, true)
    )

    /**
     * Noise removal (TD-003): adaptive FFT spectral denoising of the
     * audio track; the video stream is copied untouched.
     */
    fun denoise(input: String, output: String, mode: Int, softenStart: Boolean = false): Array<String> {
        val (nr, nf, tn) = denoiseModes[mode.coerceIn(0, 2)]
        val afftdn = buildString {
            append("afftdn=nr=$nr:nf=$nf")
            if (tn) append(":tn=1")
        }
        val fade = if (softenStart) ",afade=t=in:st=0:d=0.8" else ""
        return arrayOf(
            "-y", "-i", input,
            "-af", "highpass=f=70,$afftdn$fade",
            "-codec:v", "copy", "-codec:a", "aac", "-b:a", "160k",
            output
        )
    }

    /**
     * Neural noise removal (TD-003 amendment): RNNoise via arnndn with
     * the original Xiph model (assets/std.rnnn, BSD-3). The network is
     * trained to separate speech from noise, so it suppresses far more
     * noise than spectral gating at the same speech cost. RNNoise runs
     * at 48 kHz, hence the resample into it.
     *
     * Pre-roll (field fix, 2026-11-25): the network needs ~0.25 s of
     * the actual signal to lock onto its noise, so the plain chain
     * left a brief under-suppressed burst at the very start (owner:
     * "a light sound at the beginning, like something fell"; measured
     * on the host testbed: first 0.25 s at -35.8 dB vs -53 dB settled).
     * The fix feeds the file's own first 1.5 s through the network
     * first and trims it off afterwards: with pre-roll the first
     * window measured -59.8 dB — locked from frame one. A silence pad
     * was tried and measured useless (the lock follows the signal,
     * not the stream). Short clips (< 3 s) skip the pre-roll.
     */
    fun denoiseNeural(
        input: String, output: String, modelPath: String,
        preroll: Boolean = true, softenStart: Boolean = false
    ): Array<String> {
        // Soften-start: a gentle 0.8 s fade-in. It exists because a
        // one-off event in the source (a recording-start thump) is
        // CONTENT to the denoiser, not background noise — it survives
        // cleaning and then stands out naked in the new silence. The
        // fade dissolves it instead of letting it pop (host-measured:
        // a test thump's peak fell from -16.1 dB after cleaning to
        // -32.5 dB with the fade).
        val fade = if (softenStart) ",afade=t=in:st=0:d=0.8" else ""
        return if (preroll) {
            arrayOf(
                "-y", "-i", input,
                "-filter_complex",
                "[0:a]highpass=f=70,aresample=48000,asplit=2[hd][bd];" +
                    "[hd]atrim=end=1.5,asetpts=PTS-STARTPTS[pre];" +
                    "[pre][bd]concat=n=2:v=0:a=1,arnndn=m='$modelPath'," +
                    "atrim=start=1.5,asetpts=PTS-STARTPTS$fade[aout]",
                "-map", "0:v", "-map", "[aout]",
                "-codec:v", "copy", "-codec:a", "aac", "-b:a", "160k",
                output
            )
        } else {
            arrayOf(
                "-y", "-i", input,
                "-af", "highpass=f=70,aresample=48000,arnndn=m='$modelPath'$fade",
                "-codec:v", "copy", "-codec:a", "aac", "-b:a", "160k",
                output
            )
        }
    }

    /**
     * Audio enhancement: rumble cut, presence lift for speech clarity,
     * and loudness normalization to -16 LUFS. Video copied untouched.
     */
    fun enhanceAudio(
        input: String, output: String,
        modelPath: String? = null, preClean: Boolean = false,
        softenStart: Boolean = true
    ): Array<String> {
        // loudnorm stays LAST: an aresample appended after it in
        // 0.3.1 could not negotiate a channel layout with the
        // encoder on FFmpeg 6.0 and failed the whole conversion
        // (owner's device log, 2026-11-25).
        // preClean runs RNNoise BEFORE the polish: enhancement lifts
        // everything, noise included, so a noisy source comes out
        // with polished noise ("تشويش" — owner's field report).
        // Host measurement (quiet-gap noise RMS): source -25.2 dB,
        // plain chain -25.1 dB, pre-cleaned chain -85.2 dB. A noise
        // gate in the chain measured NO improvement and was dropped.
        val clean = if (preClean && modelPath != null)
            "aresample=48000,arnndn=m='$modelPath'," else ""
        // The gate sits before the polish: on an already-cleaned
        // (quiet) file, loudnorm's gain otherwise lifts the neural
        // residue warbling in the silences into audibility — the
        // owner's "تشويش" report came from enhancing a denoised
        // file. Host measurement on a quiet cleaned source: speech
        // lift identical (-10.9 dB), gap residue -62.6 dB plain vs
        // -86.3 dB gated. On raw noisy sources the floor sits above
        // the gate threshold, so the gate is a harmless no-op there.
        val gate = "agate=threshold=0.004:ratio=2:attack=5:release=150,"
        // Soften-start rides AFTER loudnorm, deliberately: loudnorm's
        // dynamic gain surges at the very start of a quiet file and
        // resurrects a start thump that denoise+soften had buried
        // (host measurement: a faded thump at -44.6 dB peak came out
        // of plain enhance at -1.5 dB; with the end fade, -15.7 dB).
        // Nothing after the fade can lift it again.
        val fade = if (softenStart) ",afade=t=in:st=0:d=0.8" else ""
        return arrayOf(
            "-y", "-i", input,
            "-af", "highpass=f=60,${clean}${gate}equalizer=f=3000:width_type=o:width=2:g=3,loudnorm=I=-16:TP=-1.5:LRA=11$fade",
            "-codec:v", "copy", "-codec:a", "aac", "-b:a", "160k",
            output
        )
    }

    private val errorNeedles = listOf(
        "error", "no such", "invalid", "failed", "cannot", "out of range",
        "not found", "denied", "unsupported"
    )

    /**
     * The diagnostic heart of an FFmpeg log: the last few lines that
     * actually carry an error, so a failure card (and a screenshot of
     * it) shows the cause first instead of burying it under the
     * banner. Falls back to the plain tail when nothing matches.
     */
    fun errorExcerpt(output: String, maxLines: Int = 3): String {
        val hits = output.lines().filter { line ->
            val low = line.lowercase()
            errorNeedles.any { it in low }
        }
        return if (hits.isEmpty()) output.takeLast(300)
        else hits.takeLast(maxLines).joinToString("\n")
    }

    /** Null when the range is usable, else a short machine-readable reason. */
    fun trimRangeError(startSec: Double, endSec: Double, durationSec: Double): String? = when {
        startSec < 0.0 -> "start_negative"
        endSec <= startSec -> "end_not_after_start"
        durationSec > 0.0 && endSec > durationSec -> "end_beyond_duration"
        else -> null
    }
}

/** Outcome of one FFmpeg run. */
data class FFmpegResult(val success: Boolean, val cancelled: Boolean, val output: String)

class MediaEngine {

    @Volatile
    private var activeSessionId: Long = -1L

    fun cancel() {
        val id = activeSessionId
        if (id >= 0) FFmpegKit.cancel(id)
    }

    /**
     * Runs FFmpeg with the given arguments. [onProgress] receives the
     * processed media time in milliseconds (the caller divides by the
     * source duration for a fraction).
     */
    suspend fun run(args: Array<String>, onProgress: (Long) -> Unit): FFmpegResult =
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                { completed ->
                    activeSessionId = -1L
                    val rc = completed.returnCode
                    val ok = rc != null && ReturnCode.isSuccess(rc)
                    val cancelled = rc != null && ReturnCode.isCancel(rc)
                    if (cont.isActive) {
                        cont.resume(FFmpegResult(ok, cancelled, completed.output ?: ""))
                    }
                },
                { /* per-line logs are folded into session.output */ },
                { stats -> if (stats != null) onProgress(stats.time.toLong()) }
            )
            activeSessionId = session.sessionId
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
}

/** Copies a picked content URI into the app cache so FFmpeg can seek in it. */
fun prepareInputFile(context: Context, uri: Uri, displayName: String): File {
    val dir = File(context.cacheDir, "inputs").apply { mkdirs() }
    val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val out = File(dir, safeName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
    } ?: throw IllegalStateException("cannot open input stream")
    return out
}

/** Directory where exports are written (app-specific, no permission needed). */
fun exportsDir(context: Context): File {
    val base = context.getExternalFilesDir("exports") ?: File(context.filesDir, "exports")
    return base.apply { mkdirs() }
}
