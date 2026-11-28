package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.data.aspectRatio
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.data.formatSize
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun duration_formats() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun duration_formats_for_the_chunk_clock() {
        // 0.9.5 (TD-023): formatDuration also renders the
        // transcription chunk-elapsed clock (whole seconds in).
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("10:00", formatDuration(600_000))
        assertEquals("59:59", formatDuration(3_599_000))
    }

    @Test
    fun aspect_ratios() {
        assertEquals("16:9", aspectRatio(1920, 1080))
        assertEquals("9:16", aspectRatio(1080, 1920))
        assertEquals("4:3", aspectRatio(640, 480))
    }

    @Test
    fun sizes() {
        assertEquals("0.00 B", formatSize(0))
        assertEquals("1.00 KB", formatSize(1024))
        assertEquals("1.50 MB", formatSize(1_572_864))
    }
}
