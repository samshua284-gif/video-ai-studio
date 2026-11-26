package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.VadGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VAD gate's pure decision (0.5.1): decode only real speech. */
class VadGateTest {

    @Test
    fun silenceAndMusicOnlyAreSkipped() {
        assertFalse(VadGate.shouldDecode(0.0))
        assertFalse(VadGate.shouldDecode(0.5))
        assertFalse(VadGate.shouldDecode(0.99))
    }

    @Test
    fun realSpeechIsDecoded() {
        assertTrue(VadGate.shouldDecode(1.0))
        assertTrue(VadGate.shouldDecode(3.5))
        assertTrue(VadGate.shouldDecode(28.0))
    }
}
