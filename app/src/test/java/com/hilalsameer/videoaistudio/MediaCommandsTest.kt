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
        // Taller than the cap: explicit scale, no filter expression
        // (a comma inside min() breaks the graph parser — field bug).
        val args = MediaCommands.compress(
            "in.mp4", "out.mp4", crf = 26, maxHeight = 720, sourceHeight = 1280
        )
        val joined = args.joinToString(" ")
        assertTrue(joined.contains("scale=-2:720"))
        assertTrue(!joined.contains("min("))
        assertTrue(joined.contains("26"))
        // ORDER matters: "-i" must be immediately followed by the
        // input path, and the -vf pair must come after it (an earlier
        // build inserted -vf between them and FFmpeg tried to open a
        // file named "-vf").
        assertTrue(args[1] == "-i" && args[2] == "in.mp4")
        assertTrue(args.indexOf("-vf") == 3 && args[4] == "scale=-2:720")
        // Shorter than the cap: no scale filter at all.
        val passthrough = MediaCommands.compress(
            "in.mp4", "out.mp4", crf = 20, maxHeight = 1080, sourceHeight = 720
        ).joinToString(" ")
        assertTrue(!passthrough.contains("scale="))
    }

    @Test
    fun blendAlignsTheDryPath() {
        val neural = MediaCommands.denoiseNeural(
            "in.mp4", "out.mp4", "/data/files/std.rnnn", wet = 0.7f
        ).joinToString(" ")
        assertTrue(neural.contains("adelay=10"))
        assertTrue(neural.contains("weights='0.30 0.70'"))
        assertTrue(neural.contains("amix"))
        val spectral = MediaCommands.denoise("in.mp4", "out.mp4", 1, wet = 0.5f)
            .joinToString(" ")
        assertTrue(spectral.contains("adelay=25"))
        assertTrue(spectral.contains("weights='0.50 0.50'"))
        // Full-clean keeps the legacy single-chain form.
        val full = MediaCommands.denoise("in.mp4", "out.mp4", 1).joinToString(" ")
        assertTrue(!full.contains("amix"))
    }

    @Test
    fun enhancePreCleanInsertsRnnoiseBeforePolish() {
        val plain = MediaCommands.enhanceAudio("in.mp4", "out.mp4").joinToString(" ")
        assertTrue(!plain.contains("arnndn"))
        assertTrue(plain.contains("loudnorm"))
        val cleaned = MediaCommands.enhanceAudio(
            "in.mp4", "out.mp4", "/data/files/std.rnnn", preClean = true
        ).joinToString(" ")
        assertTrue(cleaned.contains("arnndn=m='/data/files/std.rnnn'"))
        // RNNoise must run BEFORE the equalizer/loudnorm polish.
        assertTrue(cleaned.indexOf("arnndn") < cleaned.indexOf("loudnorm"))
        // The residue gate is always in the enhance chain, before
        // loudnorm (it only engages when the floor is truly low).
        assertTrue(plain.contains("agate=threshold=0.004"))
        assertTrue(plain.indexOf("agate") < plain.indexOf("loudnorm"))
        // Soften-start fade is the LAST element of the enhance chain
        // (after loudnorm), so no later gain can resurrect a thump.
        assertTrue(plain.indexOf("afade=t=in:st=0:d=0.8") > plain.indexOf("loudnorm"))
    }

    @Test
    fun errorExcerptFindsTheCause() {
        val log = "ffmpeg version 6.0\nInput #0, mov, from 'x':\n" +
            "Stream mapping:\n  Stream #0:0 -> #0:0\n" +
            "vf: No such file or directory\nConversion failed!"
        val excerpt = MediaCommands.errorExcerpt(log)
        assertTrue(excerpt.contains("No such file or directory"))
        assertTrue(!excerpt.contains("ffmpeg version"))
    }

    @Test
    fun denoiseModesBuildAfftdnChains() {
        val light = MediaCommands.denoise("in.mp4", "out.mp4", 0).joinToString(" ")
        assertTrue(light.contains("afftdn=nr=8:nf=-30"))
        assertTrue(!light.contains("tn=1"))
        val strong = MediaCommands.denoise("in.mp4", "out.mp4", 2).joinToString(" ")
        assertTrue(strong.contains("afftdn=nr=24:nf=-21:tn=1"))
        assertTrue(strong.contains("highpass=f=70"))
        assertTrue(strong.contains("-codec:v copy"))
    }

    @Test
    fun neuralDenoiseUsesBundledRnnoiseModel() {
        val joined = MediaCommands.denoiseNeural("in.mp4", "out.mp4", "/data/files/std.rnnn")
            .joinToString(" ")
        assertTrue(joined.contains("arnndn=m='/data/files/std.rnnn'"))
        assertTrue(joined.contains("aresample=48000"))
        // Pre-roll: the head runs through the network first, then trims.
        assertTrue(joined.contains("asplit=2"))
        assertTrue(joined.contains("atrim=start=1.5"))
        val short = MediaCommands.denoiseNeural(
            "in.mp4", "out.mp4", "/data/files/std.rnnn", preroll = false
        ).joinToString(" ")
        assertTrue(!short.contains("asplit"))
        // Soften-start fade rides at the very end of the audio chain.
        val softened = MediaCommands.denoiseNeural(
            "in.mp4", "out.mp4", "/data/files/std.rnnn", softenStart = true
        ).joinToString(" ")
        assertTrue(softened.contains("afade=t=in:st=0:d=0.8[aout]"))
        val spectral = MediaCommands.denoise("in.mp4", "out.mp4", 1, softenStart = true)
            .joinToString(" ")
        assertTrue(spectral.contains("afade=t=in:st=0:d=0.8"))
        // Start mute: present only when requested, on both engines,
        // with the 0.15 s release ramp.
        val muted = MediaCommands.denoiseNeural(
            "in.mp4", "out.mp4", "/data/files/std.rnnn", muteStartSec = 1.0f
        ).joinToString(" ")
        assertTrue(muted.contains("volume='if(lt(t,1.00),0,if(lt(t,1.15),(t-1.00)/0.15,1))':eval=frame"))
        assertTrue(!spectral.contains("eval=frame"))
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
