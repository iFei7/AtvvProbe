package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.core.AudioLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {

    private fun pcm16(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `全静音 RMS 为 0`() {
        val pcm = ByteArray(3200)
        assertEquals(0.0, AudioLevel.rms(pcm), 0.001)
        assertEquals(0, AudioLevel.peak(pcm))
    }

    @Test
    fun `满幅方波 RMS 约等于 32767`() {
        val pcm = pcm16(32767, -32768, 32767, -32768)
        assertEquals(32767.5, AudioLevel.rms(pcm), 1.0)
        assertEquals(32768, AudioLevel.peak(pcm))
    }

    @Test
    fun `幅值 1000 的正弦 RMS 约 707`() {
        val n = 1000
        val values = IntArray(n) { i ->
            (1000 * kotlin.math.sin(2 * Math.PI * i / 100)).toInt()
        }
        val pcm = pcm16(*values)
        assertEquals(707.0, AudioLevel.rms(pcm), 30.0)
        assertTrue(AudioLevel.peak(pcm) in 990..1000)
    }

    @Test
    fun `采样点数量按两字节一个算`() {
        assertEquals(3, AudioLevel.sampleCount(ByteArray(7)))
    }

    @Test
    fun `判定阈值`() {
        assertTrue(AudioLevel.classify(10.0).contains("静音"))
        assertTrue(AudioLevel.classify(500.0).contains("环境声"))
        assertTrue(AudioLevel.classify(5000.0).contains("说话"))
    }

    @Test
    fun `负数的低字节解析正确`() {
        // -1 的小端是 FF FF
        val pcm = pcm16(-1)
        assertEquals(1.0, AudioLevel.rms(pcm), 0.001)
        assertEquals(1, AudioLevel.peak(pcm))
    }

    @Test
    fun `空数据不崩溃`() {
        assertEquals(0.0, AudioLevel.rms(ByteArray(0)), 0.001)
        assertEquals(0.0, AudioLevel.rms(ByteArray(1)), 0.001)
        assertEquals(0, AudioLevel.peak(ByteArray(1)))
    }
}
