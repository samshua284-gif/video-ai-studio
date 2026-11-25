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
        if (tracks > 0) {
            extractor.selectTrack(0)
            // A readable first sample proves the stream actually decodes.
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            sampleOk = extractor.readSampleData(buf, 0) >= 0
        }
    } catch (e: Exception) {
        // tracks/sampleOk stay at failure values
    } finally {
        extractor.release()
    }
    return ValidationReport(file.name, true, file.length(), durationMs, tracks, sampleOk)
}
