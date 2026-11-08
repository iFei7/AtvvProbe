package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.audio.WavReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavReaderTest {

    private fun buildWav(
        sampleRate: Int = 16_000,
        channels: Int = 1,
        bits: Int = 16,
        format: Int = 1,
        samples: Int = 160,
    ): ByteArray {
        val dataSize = samples * channels * (bits / 8)
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)
        le16(format)
        le16(channels)
        le32(sampleRate)
        le32(sampleRate * channels * bits / 8)
        le16(channels * bits / 8)
        le16(bits)
        ascii("data")
        le32(dataSize)
        repeat(samples * channels) { i -> le16(i and 0x7FFF) }
        return out.toByteArray()
    }

    @Test
    fun `解析标准 16k 单声道 wav`() {
        val result = WavReader.parse(buildWav())
        assertEquals(16_000, result.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(16, result.bitsPerSample)
        assertEquals(320, result.pcm.size)
    }

    @Test
    fun `采样率不是 16k 时报错`() {
        val e = runCatching { WavReader.parse(buildWav(sampleRate = 44_100)) }.exceptionOrNull()
        assertTrue(e is WavReader.WavFormatException)
        assertTrue(e!!.message!!.contains("16kHz"))
    }

    @Test
    fun `双声道报错`() {
        val e = runCatching { WavReader.parse(buildWav(channels = 2)) }.exceptionOrNull()
        assertTrue(e is WavReader.WavFormatException)
    }

    @Test
    fun `浮点格式报错`() {
        val e = runCatching { WavReader.parse(buildWav(format = 3)) }.exceptionOrNull()
        assertTrue(e is WavReader.WavFormatException)
    }

    @Test
    fun `非 wav 内容报错`() {
        val e = runCatching { WavReader.parse(ByteArray(100)) }.exceptionOrNull()
        assertTrue(e is WavReader.WavFormatException)
    }
}
