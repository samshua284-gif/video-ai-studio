package com.hilalsameer.videoaistudio.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** One elementary track inside a media container, as reported by MediaExtractor. */
data class TrackInfo(
    val kind: String,          // "video", "audio", "text" (subtitle), or "other"
    val mime: String,
    val language: String?,
    val details: String
)

/** Real metadata of an imported video. Every field comes from the file itself. */
data class VideoInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val fps: Float?,
    val bitrate: Int?,
    val videoMime: String?,
    val audioMime: String?,
    val tracks: List<TrackInfo>,
    /** The container's embedded title tag, when the file carries
     *  one (0.5.6): names the subtitle exports meaningfully instead
     *  of inheriting a bare numeric file name. */
    val title: String? = null
) {
    val audioTrackCount: Int get() = tracks.count { it.kind == "audio" }
    val subtitleTrackCount: Int get() = tracks.count { it.kind == "text" }
    /** Display width/height after applying the container rotation flag. */
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height
}

fun formatDuration(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.2f %s".format(v, units[i])
}

fun aspectRatio(w: Int, h: Int): String {
    if (w <= 0 || h <= 0) return "—"
    fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    val g = gcd(w, h)
    return "${w / g}:${h / g}"
}

/**
 * Inspect a video chosen through the Storage Access Framework.
 * Reads only metadata; the original file is never modified.
 * Throws on unsupported/corrupt input so the UI can report it honestly.
 */
fun inspectVideo(context: Context, uri: Uri): VideoInfo {
    val resolver = context.contentResolver

    var name = "video"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }

    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
    } catch (e: Exception) {
        retriever.release()
        throw e
    }
    fun meta(key: Int): String? = retriever.extractMetadata(key)
    val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    val bitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
    var fps = meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
    retriever.release()

    val tracks = mutableListOf<TrackInfo>()
    var videoMime: String? = null
    var audioMime: String? = null
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: "unknown"
            val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
            val kind = when {
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                mime.startsWith("text/") || mime.contains("cea") || mime.contains("subtitle") -> "text"
                else -> "other"
            }
            val details = buildString {
                if (kind == "audio") {
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) append("${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz")
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) append(" · ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch")
                }
                if (kind == "video" && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    append("${f.getInteger(MediaFormat.KEY_FRAME_RATE)} fps")
                    if (fps == null) fps = f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                }
            }
            if (kind == "video" && videoMime == null) videoMime = mime
            if (kind == "audio" && audioMime == null) audioMime = mime
            tracks.add(TrackInfo(kind, mime, lang, details.trim()))
        }
    } finally {
        extractor.release()
    }

    return VideoInfo(
        uri = uri, name = name, sizeBytes = size, durationMs = durationMs,
        width = width, height = height, rotation = rotation, fps = fps,
        bitrate = bitrate, videoMime = videoMime, audioMime = audioMime,
        tracks = tracks,
        title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.trim()?.takeIf { it.isNotBlank() }
    )
}
