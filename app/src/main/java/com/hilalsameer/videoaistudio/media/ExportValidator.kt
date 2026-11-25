package com.hilalsameer.videoaistudio.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/**
 * Post-export validation (Milestone 2, Command 2): every produced file
 * is checked for existence, non-zero size, a readable container with at
 * least one track, a positive duration, and a decodable first sample.
 * Nothing is reported as a successful export unless all checks pass.
 */
data class ValidationReport(
    val fileName: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val durationMs: Long,
    val trackCount: Int,
    val firstSampleReadable: Boolean
) {
    val valid: Boolean
        get() = exists && sizeBytes > 0 && durationMs > 0 && trackCount > 0 && firstSampleReadable
}

fun validateExport(file: File): ValidationReport {
    if (!file.exists() || file.length() == 0L) {
        return ValidationReport(file.name, file.exists(), file.length(), 0, 0, false)
    }
    var durationMs = 0L
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        // duration stays 0 -> report invalid
    } finally {
        try { retriever.release() } catch (e: Exception) { }
    }

    var tracks = 0
    var sampleOk = false
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        tracks = extractor.trackCount
        // A readable sample on ANY media track proves the file
        // decodes. Two probe defects were fixed on the evidence of
        // the owner's result cards (217946.mp4 and the 0.4.0 GTCRN
        // output, 2026-11-25 — every other check green, files
        // playing perfectly, only this probe red):
        //  (1) Probing track 0 only failed files whose track 0 is a
        //      metadata track — common in phone recordings.
        //  (2) THE buffer was 64 KB. readSampleData returns -1 when
        //      the sample does not fit the buffer, and a single
        //      keyframe of a ~23 Mbps phone recording measures
        //      100 KB+ (host-measured on the exact remux chain:
        //      103,656 bytes for the first video packet) — so the
        //      video probe could NEVER pass on his files. The
        //      buffer is now sized per track from the track's own
        //      KEY_MAX_INPUT_SIZE with an 8 MB floor, and the probe
        //      tries the first few samples at two seek anchors
        //      instead of a single read at a single position.
        for (t in 0 until tracks) {
            try {
                var maxInput = 0
                try {
                    val format = extractor.getTrackFormat(t)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInput = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    }
                } catch (e: Exception) { }
                val buf = java.nio.ByteBuffer.allocate(
                    maxOf(maxInput, 8 * 1024 * 1024).coerceAtMost(64 * 1024 * 1024)
                )
                extractor.selectTrack(t)
                var ok = false
                for (anchor in intArrayOf(
                    MediaExtractor.SEEK_TO_CLOSEST_SYNC,
                    MediaExtractor.SEEK_TO_PREVIOUS_SYNC
                )) {
                    if (ok) break
                    extractor.seekTo(0L, anchor)
                    for (i in 0 until 5) {
                        buf.clear()
                        if (extractor.readSampleData(buf, 0) >= 0) { ok = true; break }
                        if (!extractor.advance()) break
                    }
                }
                if (ok) sampleOk = true
                extractor.unselectTrack(t)
            } catch (e: Exception) {
                // An unprobable track does not decide the verdict.
            }
            if (sampleOk) break
        }
    } catch (e: Exception) {
        // tracks/sampleOk stay at failure values
    } finally {
        extractor.release()
    }
    return ValidationReport(file.name, true, file.length(), durationMs, tracks, sampleOk)
}
