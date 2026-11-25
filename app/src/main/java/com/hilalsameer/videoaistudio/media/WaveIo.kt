package com.hilalsameer.videoaistudio.media

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Minimal RIFF/WAVE PCM16 I/O with random access by sample (Milestone
 * 4): the GTCRN engine consumes and produces plain 16 kHz mono WAV,
 * which FFmpeg creates from / remuxes into the video container.
 */
object WaveIo {

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val dataOffset: Long,
        val totalSamples: Long // per channel
    )

    fun readHeader(file: File): Info {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            require(
                head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte()
            ) { "not a WAVE file" }
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1L
            var dataBytes = 0L
            while (raf.filePointer < raf.length() - 8) {
                val id = ByteArray(4)
                raf.readFully(id)
                val size = raf.readIntLE()
                val chunkStart = raf.filePointer
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        val fmt = ByteArray(size)
                        raf.readFully(fmt)
                        val audioFormat = fmt.u16le(0)
                        require(audioFormat == 1) { "WAVE is not PCM" }
                        channels = fmt.u16le(2)
                        sampleRate = fmt.i32le(4)
                        bits = fmt.u16le(14)
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataBytes = size.toLong()
                        raf.seek(raf.length())
                    }
                    else -> raf.seek(chunkStart + size + (size and 1))
                }
            }
            require(dataOffset >= 0 && channels > 0 && bits == 16) { "bad WAVE" }
            return Info(sampleRate, channels, dataOffset, dataBytes / (2 * channels))
        }
    }

    /** Read [count] samples starting at [start] (per-channel index), mixed to mono floats in [-1, 1]. */
    fun readSamples(file: File, info: Info, start: Long, count: Int): FloatArray {
        val out = FloatArray(count)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset + start * 2L * info.channels)
            val bytes = ByteArray(count * 2 * info.channels)
            val read = raf.read(bytes)
            val frames = if (read < 0) 0 else read / (2 * info.channels)
            for (i in 0 until min(count, frames)) {
                var acc = 0f
                for (c in 0 until info.channels) {
                    val off = (i * info.channels + c) * 2
                    val v = (bytes[off].toInt() and 0xFF) or (bytes[off + 1].toInt() shl 8)
                    acc += v.toShort().toFloat() / 32768f
                }
                out[i] = acc / info.channels
            }
        }
        return out
    }

    /** Canonical 44-byte PCM16 mono header for [totalSamples] samples. */
    fun writeHeader(raf: RandomAccessFile, sampleRate: Int, totalSamples: Long) {
        val dataBytes = totalSamples * 2
        val h = ByteArray(44)
        fun putStr(at: Int, s: String) {
            for (i in s.indices) h[at + i] = s[i].code.toByte()
        }
        fun put32(at: Int, v: Long) {
            for (i in 0..3) h[at + i] = (v shr (8 * i)).toByte()
        }
        fun put16(at: Int, v: Int) {
            for (i in 0..1) h[at + i] = (v shr (8 * i)).toByte()
        }
        putStr(0, "RIFF"); put32(4, 36 + dataBytes); putStr(8, "WAVE")
        putStr(12, "fmt "); put32(16, 16); put16(20, 1); put16(22, 1)
        put32(24, sampleRate.toLong()); put32(28, sampleRate * 2L)
        put16(32, 2); put16(34, 16)
        putStr(36, "data"); put32(40, dataBytes)
        raf.write(h)
    }

    fun appendSamples(raf: RandomAccessFile, samples: FloatArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        raf.write(bytes)
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b = ByteArray(4)
        readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.u16le(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.i32le(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24)
}
