package com.hilalsameer.videoaistudio.media

/**
 * Pure decision for the voice-activity gate (0.5.1, TD-008): a
 * window is decoded only when the VAD found at least
 * [MIN_SPEECH_SEC] of speech in it. Music-only, action and
 * near-silent stretches are skipped instead of being decoded —
 * Whisper invents text on non-speech, so skipping is both the
 * speed lever and an honesty lever.
 */
object VadGate {
    const val MIN_SPEECH_SEC = 1.0

    fun shouldDecode(speechSec: Double): Boolean = speechSec >= MIN_SPEECH_SEC
}
