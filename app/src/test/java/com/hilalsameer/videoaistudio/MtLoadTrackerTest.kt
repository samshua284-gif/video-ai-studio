package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.MtLoadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TD-019: per-file load progress must count only what the
 *  worker itself reported — no invented numbers. */
class MtLoadTrackerTest {

    @Test
    fun initiateSetsCurrentButNotDone() {
        val t = MtLoadTracker()
        val s = t.onEvent("config.json", "initiate", null)
        assertEquals(0, s.done)
        assertEquals(7, s.total)
        assertEquals("config.json", s.currentFile)
    }

    @Test
    fun doneIncrementsOncePerFile() {
        val t = MtLoadTracker()
        t.onEvent("config.json", "done", null)
        val s = t.onEvent("config.json", "done", null)
        assertEquals(1, s.done)
        assertNull(s.currentFile)
    }

    @Test
    fun progressBelow100IsNotDone() {
        val t = MtLoadTracker()
        val s = t.onEvent("onnx/encoder_model_quantized.onnx", "progress", 50.0)
        assertEquals(0, s.done)
        assertEquals("encoder_model_quantized.onnx", s.currentFile)
    }

    @Test
    fun progressAt100CountsAsDone() {
        val t = MtLoadTracker()
        val s = t.onEvent("onnx/encoder_model_quantized.onnx", "progress", 100.0)
        assertEquals(1, s.done)
        assertNull(s.currentFile)
    }

    @Test
    fun doneCountCapsAtTotal() {
        val t = MtLoadTracker(totalFiles = 2)
        t.onEvent("a.json", "done", null)
        t.onEvent("b.json", "done", null)
        val s = t.onEvent("c.json", "done", null)
        assertEquals(2, s.done)
        assertEquals(2, s.total)
    }

    @Test
    fun emptyFileEventsAreIgnored() {
        val t = MtLoadTracker()
        val s = t.onEvent("", "ready", null)
        assertEquals(0, s.done)
        assertNull(s.currentFile)
    }

    @Test
    fun fullSevenFileSequenceReachesTotal() {
        val t = MtLoadTracker()
        val files = listOf(
            "config.json", "generation_config.json", "tokenizer.json",
            "tokenizer_config.json", "special_tokens_map.json",
            "onnx/encoder_model_quantized.onnx",
            "onnx/decoder_model_merged_quantized.onnx"
        )
        var s = t.snapshot()
        for (f in files) {
            t.onEvent(f, "initiate", null)
            s = t.onEvent(f, "done", null)
        }
        assertEquals(7, s.done)
        assertNull(s.currentFile)
    }
}
