package com.ifei7.murmur.ime.audio

/**
 * 极简 WAV 解析：只保留 16bit PCM 数据块，校验采样率与声道数。
 *
 * 开发期联调用：把任意 16k / mono / 16bit 的 wav 丢进 App，
 * 直接取出裸 PCM 走 [FilePcmSource] / 自测流程。
 */
object WavReader {

    class WavFormatException(message: String) : Exception(message)

    data class Result(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val pcm: ByteArray,
    )

    private const val FORMAT_PCM = 1

    fun parse(bytes: ByteArray): Result {
        if (bytes.size < 44) throw WavFormatException("文件太小，不是合法 wav")
        if (readAscii(bytes, 0, 4) != "RIFF") throw WavFormatException("缺少 RIFF 头")
        if (readAscii(bytes, 8, 4) != "WAVE") throw WavFormatException("缺少 WAVE 头")

        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var audioFormat = 0
        var pcm: ByteArray? = null

        while (offset + 8 <= bytes.size) {
            val id = readAscii(bytes, offset, 4)
            val size = readIntLe(bytes, offset + 4)
            val body = offset + 8
            if (size < 0 || body + size > bytes.size) {
                // 有些录音笔的 size 字段不准，这里容错：只处理能读到的部分
                if (id == "data") {
                    pcm = bytes.copyOfRange(body, bytes.size)
                    break
                }
                break
            }
            when (id) {
                "fmt " -> {
                    audioFormat = readShortLe(bytes, body)
                    channels = readShortLe(bytes, body + 2)
                    sampleRate = readIntLe(bytes, body + 4)
                    bits = readShortLe(bytes, body + 14)
                }
                "data" -> {
                    pcm = bytes.copyOfRange(body, body + size)
                }
            }
            offset = body + size + (size and 1) // chunk 按偶数字节对齐
        }

        val data = pcm ?: throw WavFormatException("没有找到 data 块")
        if (audioFormat != FORMAT_PCM) {
            throw WavFormatException("只支持未压缩 PCM（当前 format=$audioFormat）")
        }
        if (bits != 16) throw WavFormatException("只支持 16bit（当前 $bits bit）")
        if (channels != 1) throw WavFormatException("只支持单声道（当前 $channels 声道）")
        if (sampleRate != 16_000) {
            throw WavFormatException("只支持 16kHz（当前 $sampleRate Hz），请先转码")
        }
        return Result(sampleRate, channels, bits, data)
    }

    private fun readAscii(bytes: ByteArray, offset: Int, len: Int): String =
        String(bytes, offset, len, Charsets.US_ASCII)

    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
