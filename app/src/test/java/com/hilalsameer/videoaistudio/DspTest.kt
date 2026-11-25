package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.Dsp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The GTCRN STFT scaffolding (TD-004): analysis followed by
 * synthesis must reconstruct the signal (the convention was
 * additionally matched frame-for-frame against the reference
 * implementation's golden values on the build host).
 */
class DspTest {

    @Test
    fun stftIstftIdentity() {
        val sr = 16000
        val x = FloatArray(sr) { i ->
            (0.4 * sin(2.0 * Math.PI * 440.0 * i / sr) +
                0.2 * sin(2.0 * Math.PI * 2700.0 * i / sr)).toFloat()
        }
        val frames = Dsp.stftAll(x)
        assertEquals(257, frames[0].size / 2)
        val back = Dsp.istftAll(frames, x.size)
        assertEquals(x.size, back.size)
        var maxErr = 0f
        for (i in x.indices) maxErr = maxOf(maxErr, abs(back[i] - x[i]))
        assertTrue("max reconstruction error $maxErr", maxErr < 1e-4f)
    }

    @Test
    fun silenceStaysSilence() {
        val x = FloatArray(16000)
        val back = Dsp.istftAll(Dsp.stftAll(x), x.size)
        assertTrue(back.all { it == 0f })
    }
}
