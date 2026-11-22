package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.audio.WavReader
import com.ifei7.murmur.ime.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WavWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 正弦波 + 一半静音的测试 PCM。 */
    private fun testPcm(samples: Int = 3200): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i < samples / 2) (Math.sin(i * 0.05) * 10000).toInt() else 0
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `文件头字段正确`() {
        val bytes = WavWriter.encode(testPcm(samples = 160))
        assertEquals(44 + 320, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        // RIFF 大小 = 36 + data 大小
        val riffSize = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or ((bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(36 + 320, riffSize)
        val dataSize = (bytes[40].toInt() and 0xFF) or ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or ((bytes[43].toInt() and 0xFF) shl 24)
        assertEquals(320, dataSize)
    }

    @Test
    fun `WavReader 能回读 WavWriter 的输出`() {
        val pcm = testPcm(samples = 1600)
        val parsed = WavReader.parse(WavWriter.encode(pcm))
        assertEquals(16_000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(16, parsed.bitsPerSample)
        assertTrue(parsed.pcm.contentEquals(pcm))
    }

    @Test
    fun `write 落盘后文件可被 WavReader 解析`() {
        val pcm = testPcm(samples = 800)
        val file: File = tmp.newFile("out.wav")
        val written = WavWriter.write(file, pcm)
        assertEquals(44 + pcm.size, written)
        val parsed = WavReader.parse(file.readBytes())
        assertTrue(parsed.pcm.contentEquals(pcm))
    }

    @Test
    fun `空 PCM 也生成合法头`() {
        // WavReader 不支持解析 data size=0 的 wav（报「没有找到 data 块」），这里只验头
        val bytes = WavWriter.encode(ByteArray(0))
        assertEquals(44, bytes.size)
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(0, bytes[40].toInt() and 0xFF)
    }
}
