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

    /** Compress: H.264 at the given CRF, downscaling to maxHeight if taller. */
    fun compress(input: String, output: String, crf: Int, maxHeight: Int): Array<String> =
        arrayOf(
            "-y", "-i", input,
            "-vf", "scale=-2:min($maxHeight,ih)",
            "-codec:v", "libx264", "-preset", "veryfast", "-crf", crf.toString(),
            "-codec:a", "aac", "-b:a", "128k",
            output
        )

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
    fun denoise(input: String, output: String, mode: Int): Array<String> {
        val (nr, nf, tn) = denoiseModes[mode.coerceIn(0, 2)]
        val afftdn = buildString {
            append("afftdn=nr=$nr:nf=$nf")
            if (tn) append(":tn=1")
        }
        return arrayOf(
            "-y", "-i", input,
            "-af", "highpass=f=70,$afftdn,aresample=async=1:min_hard_comp=0.100",
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
     */
    fun denoiseNeural(input: String, output: String, modelPath: String): Array<String> =
        arrayOf(
            "-y", "-i", input,
            "-af", "highpass=f=70,aresample=48000,arnndn=m='$modelPath',aresample=async=1:min_hard_comp=0.100",
            "-codec:v", "copy", "-codec:a", "aac", "-b:a", "160k",
            output
        )

    /**
     * Audio enhancement: rumble cut, presence lift for speech clarity,
     * and loudness normalization to -16 LUFS. Video copied untouched.
     */
    fun enhanceAudio(input: String, output: String): Array<String> =
        arrayOf(
            "-y", "-i", input,
            "-af", "highpass=f=60,equalizer=f=3000:width_type=o:width=2:g=3,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=async=1:min_hard_comp=0.100",
            "-codec:v", "copy", "-codec:a", "aac", "-b:a", "160k",
            output
        )

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
