package com.hilalsameer.videoaistudio.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * GTCRN speech enhancement (TD-004): a 2024 streaming neural model
 * (0.5 MB) run fully on-device through ONNX Runtime. It replaces the
 * 2017 RNNoise model whose articulation smearing was its ceiling.
 *
 * Verified before integration (host, real speech at 8 dB SNR):
 * log-spectral distance to clean speech 11.50 (reference runtime)
 * vs 13.11 for RNNoise; noise tail -61 dB vs -28 dB. This engine's
 * own STFT port measured LSD 11.46 against the same reference.
 *
 * The model is streaming: one STFT frame per inference with three
 * cache tensors carried between frames, so memory stays constant
 * regardless of file length. Files are processed in 30 s cores with
 * 1 s of context on each side (the caches warm up inside the
 * context, which is then discarded).
 */
class GtcrnEngine(modelFile: File) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    )

    private val convCache = FloatArray(2 * 1 * 16 * 16 * 33)
    private val traCache = FloatArray(2 * 3 * 1 * 1 * 16)
    private val interCache = FloatArray(2 * 1 * 33 * 16)

    private val convShape = longArrayOf(2, 1, 16, 16, 33)
    private val traShape = longArrayOf(2, 3, 1, 1, 16)
    private val interShape = longArrayOf(2, 1, 33, 16)
    private val mixShape = longArrayOf(1, Dsp.BINS.toLong(), 1, 2)

    private fun directBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun tensorFrom(data: FloatArray, shape: LongArray): OnnxTensor {
        val buf = directBuffer(data.size)
        buf.put(data)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf, shape)
    }

    /** Run one STFT frame through the model; returns the enhanced packed frame. */
    private fun runFrame(frame: FloatArray): FloatArray {
        val mix = tensorFrom(frame, mixShape)
        val convT = tensorFrom(convCache, convShape)
        val traT = tensorFrom(traCache, traShape)
        val interT = tensorFrom(interCache, interShape)
        try {
            session.run(
                mapOf(
                    "mix" to mix,
                    "conv_cache" to convT,
                    "tra_cache" to traT,
                    "inter_cache" to interT
                )
            ).use { res ->
                val enhBuf = (res.get("enh").get() as OnnxTensor).floatBuffer
                val out = FloatArray(Dsp.BINS * 2)
                enhBuf.get(out)
                (res.get("conv_cache_out").get() as OnnxTensor).floatBuffer.get(convCache)
                (res.get("tra_cache_out").get() as OnnxTensor).floatBuffer.get(traCache)
                (res.get("inter_cache_out").get() as OnnxTensor).floatBuffer.get(interCache)
                return out
            }
        } finally {
            mix.close(); convT.close(); traT.close(); interT.close()
        }
    }

    /** Enhance one in-memory segment; the model caches start cold (zeroed). */
    fun enhanceSegment(x: FloatArray): FloatArray {
        convCache.fill(0f); traCache.fill(0f); interCache.fill(0f)
        val frames = Dsp.stftAll(x)
        val enhanced = Array(frames.size) { t -> runFrame(frames[t]) }
        return Dsp.istftAll(enhanced, x.size)
    }

    /**
     * Enhance a whole 16 kHz mono WAV file segment by segment.
     * Returns false if [isCancelled] fires between segments.
     */
    fun enhanceFile(
        inWav: File,
        outWav: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Boolean {
        val info = WaveIo.readHeader(inWav)
        require(info.sampleRate == SAMPLE_RATE) { "GTCRN input must be 16 kHz WAV" }
        val total = info.totalSamples
        RandomAccessFile(outWav, "rw").use { out ->
            out.setLength(0)
            WaveIo.writeHeader(out, SAMPLE_RATE, total)
            var pos = 0L
            while (pos < total) {
                if (isCancelled()) return false
                val segStart = maxOf(0L, pos - CONTEXT)
                val segEnd = min(total, pos + CORE + CONTEXT)
                val seg = WaveIo.readSamples(inWav, info, segStart, (segEnd - segStart).toInt())
                val enh = enhanceSegment(seg)
                val coreFrom = (pos - segStart).toInt()
                val coreLen = (min(pos + CORE, total) - pos).toInt()
                WaveIo.appendSamples(out, enh.copyOfRange(coreFrom, coreFrom + coreLen))
                pos += CORE
                onProgress((pos.toFloat() / total.toFloat()).coerceIn(0f, 1f))
            }
        }
        return true
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CORE = 480000L // 30 s
        private const val CONTEXT = 16000L // 1 s
    }
}
