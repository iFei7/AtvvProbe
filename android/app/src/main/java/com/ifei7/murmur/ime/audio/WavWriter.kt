package com.ifei7.murmur.ime.audio

import java.io.File

/**
 * 极简 WAV 编码：标准 44 字节头 + PCM16 数据，与 [WavReader] 对称。
 *
 * 用于把麦克风自检 / 调试录音落盘成可以 `adb pull` 后直接试听的 wav 文件。
 * 固定输出 16kHz / 单声道 / 16bit PCM（与整条识别链路的采样格式一致）。
 */
object WavWriter {

    /** 生成完整 WAV 字节（44 字节头 + PCM 数据）。 */
    fun encode(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val out = ByteArray(HEADER_SIZE + pcm.size)
        writeHeader(out, pcm.size, sampleRate, channels)
        pcm.copyInto(out, HEADER_SIZE)
        return out
    }

    /** 把 PCM 写成 wav 文件，返回写入的字节数。 */
    fun write(file: File, pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): Int {
        val bytes = encode(pcm, sampleRate, channels)
        file.writeBytes(bytes)
        return bytes.size
    }

    /** 在 [out] 偏移 0 处写 44 字节标准 PCM WAV 头。 */
    private fun writeHeader(out: ByteArray, dataSize: Int, sampleRate: Int, channels: Int) {
        val bits = 16
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign

        fun putAscii(offset: Int, s: String) {
            val raw = s.toByteArray(Charsets.US_ASCII)
            raw.copyInto(out, offset)
        }
        fun putLe32(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
            out[offset + 2] = ((v shr 16) and 0xFF).toByte()
            out[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putLe16(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putLe32(4, 36 + dataSize)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putLe32(16, 16)          // fmt 块长度
        putLe16(20, 1)           // PCM
        putLe16(22, channels)
        putLe32(24, sampleRate)
        putLe32(28, byteRate)
        putLe16(32, blockAlign)
        putLe16(34, bits)
        putAscii(36, "data")
        putLe32(40, dataSize)
    }

    /** 固定输出采样率，与识别链路一致。 */
    const val SAMPLE_RATE = 16_000
    const val HEADER_SIZE = 44
}
