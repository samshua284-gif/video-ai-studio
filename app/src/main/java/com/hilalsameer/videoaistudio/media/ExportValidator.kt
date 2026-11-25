package com.hilalsameer.videoaistudio.media

import android.media.MediaExtractor
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
        // A readable first sample on ANY media track proves the file
        // decodes. Probing track 0 only (the first version) failed
        // files whose track 0 is a metadata track — common in phone
        // recordings — while they played perfectly (owner's
        // 217946.mp4, 2026-11-25: size/duration/tracks all correct,
        // playback fine, card still said "not validated").
        val buf = java.nio.ByteBuffer.allocate(64 * 1024)
        for (t in 0 until tracks) {
            try {
                extractor.selectTrack(t)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                if (extractor.readSampleData(buf, 0) >= 0) sampleOk = true
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
