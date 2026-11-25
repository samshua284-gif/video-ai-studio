package com.hilalsameer.videoaistudio.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * STFT / iSTFT in the exact convention the GTCRN model was verified
 * against (see TECHNOLOGY_DECISIONS.md TD-004): 512-point Hann
 * (periodic) window, hop 256, signal zero-padded by 256 samples on
 * both sides ("center"), overlap-add with window-square envelope
 * normalization. The convention was matched numerically against the
 * reference implementation on real speech before this port was
 * written (numpy prototype: LSD-to-clean 11.46 vs reference 11.50).
 */
object Dsp {
    const val N_FFT = 512
    const val HOP = 256
    const val BINS = 257

    val WINDOW: FloatArray =
        FloatArray(N_FFT) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / N_FFT)).toFloat() }

    /** In-place iterative radix-2 FFT. Forward uses the e^(-2πi/N) convention; inverse scales by 1/N. */
    fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len / 2
            for (i in 0 until n step len) {
                var cwr = 1f
                var cwi = 0f
                for (k in 0 until half) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val xr = re[i + k + half]; val xi = im[i + k + half]
                    val vr = xr * cwr - xi * cwi
                    val vi = xr * cwi + xi * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + half] = ur - vr; im[i + k + half] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }

    /** STFT of [x] into frames of packed bins: frame t is a FloatArray(514) of (re, im) pairs. */
    fun stftAll(x: FloatArray): Array<FloatArray> {
        val xp = FloatArray(x.size + N_FFT)
        x.copyInto(xp, HOP)
        val nFrames = 1 + (xp.size - N_FFT) / HOP
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        return Array(nFrames) { t ->
            for (i in 0 until N_FFT) {
                re[i] = xp[t * HOP + i] * WINDOW[i]
                im[i] = 0f
            }
            fft(re, im, inverse = false)
            val frame = FloatArray(BINS * 2)
            for (b in 0 until BINS) {
                frame[2 * b] = re[b]
                frame[2 * b + 1] = im[b]
            }
            frame
        }
    }

    /** Inverse of [stftAll]; returns exactly [origLen] samples. */
    fun istftAll(frames: Array<FloatArray>, origLen: Int): FloatArray {
        val total = (frames.size - 1) * HOP + N_FFT
        val y = FloatArray(total)
        val wsum = FloatArray(total)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (t in frames.indices) {
            val f = frames[t]
            for (b in 0 until BINS) {
                re[b] = f[2 * b]
                im[b] = f[2 * b + 1]
            }
            for (b in 1 until BINS - 1) {
                re[N_FFT - b] = f[2 * b]
                im[N_FFT - b] = -f[2 * b + 1]
            }
            re[0] = f[0]; im[0] = 0f
            re[BINS - 1] = f[2 * (BINS - 1)]; im[BINS - 1] = 0f
            fft(re, im, inverse = true)
            val off = t * HOP
            for (i in 0 until N_FFT) {
                y[off + i] += re[i] * WINDOW[i]
                wsum[off + i] += WINDOW[i] * WINDOW[i]
            }
        }
        for (i in y.indices) {
            if (wsum[i] > 1e-8f) y[i] /= wsum[i]
        }
        return y.copyOfRange(HOP, HOP + origLen)
    }
}
