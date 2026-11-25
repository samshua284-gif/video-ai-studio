package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.MediaCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCommandsTest {

    @Test
    fun mp3ExtractionUsesLameAndBitrate() {
        val args = MediaCommands.extractAudio("in.mp4", "out.mp3", mp3 = true, bitrateKbps = 192)
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("libmp3lame"))
        assertTrue(joined.contains("192k"))
        assertTrue(joined.contains("-vn"))
        assertEquals("out.mp3", args.last())
    }

    @Test
    fun wavExtractionUsesPcm() {
        val args = MediaCommands.extractAudio("in.mp4", "out.wav", mp3 = false, bitrateKbps = 192)
        assertTrue(args.joinToString(" ").contains("pcm_s16le"))
    }

    @Test
    fun trimSeeksBeforeInputAndUsesDuration() {
        val args = MediaCommands.trim("in.mp4", "out.mp4", 10.0, 25.5)
        val ss = args.indexOf("-ss")
        val input = args.indexOf("-i")
        val t = args.indexOf("-t")
        assertTrue(ss in 0 until input)
        assertEquals("10.000", args[ss + 1])
        assertEquals("15.500", args[t + 1])
        assertTrue(args.joinToString(" ").contains("libx264"))
    }

    @Test
    fun compressScalesAndSetsCrf() {
        val args = MediaCommands.compress("in.mp4", "out.mp4", crf = 26, maxHeight = 720)
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("scale=-2:min(720,ih)"))
        assertTrue(joined.contains("26"))
    }

    @Test
    fun denoiseModesBuildAfftdnChains() {
        val light = MediaCommands.denoise("in.mp4", "out.mp4", 0).joinToString(" ")
        assertTrue(light.contains("afftdn=nr=8:nf=-30"))
        assertTrue(!light.contains("tn=1"))
        val strong = MediaCommands.denoise("in.mp4", "out.mp4", 2).joinToString(" ")
        assertTrue(strong.contains("afftdn=nr=24:nf=-14:tn=1"))
        assertTrue(strong.contains("highpass=f=70"))
        assertTrue(strong.contains("-codec:v copy"))
    }

    @Test
    fun enhanceChainNormalizesLoudness() {
        val joined = MediaCommands.enhanceAudio("in.mp4", "out.mp4").joinToString(" ")
        assertTrue(joined.contains("loudnorm=I=-16:TP=-1.5:LRA=11"))
        assertTrue(joined.contains("equalizer=f=3000"))
    }

    @Test
    fun trimRangeValidation() {
        assertNull(MediaCommands.trimRangeError(0.0, 10.0, 60.0))
        assertEquals("start_negative", MediaCommands.trimRangeError(-1.0, 10.0, 60.0))
        assertEquals("end_not_after_start", MediaCommands.trimRangeError(10.0, 10.0, 60.0))
        assertEquals("end_beyond_duration", MediaCommands.trimRangeError(0.0, 61.0, 60.0))
        assertNull(MediaCommands.trimRangeError(0.0, 10.0, 0.0)) // unknown duration
    }
}
