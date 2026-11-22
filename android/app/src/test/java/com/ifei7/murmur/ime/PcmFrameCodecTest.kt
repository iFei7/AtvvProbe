package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.audio.Frame
import com.ifei7.murmur.ime.audio.FrameDecoder
import com.ifei7.murmur.ime.audio.PcmFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFrameCodecTest {

    @Test
    fun `控制帧编码`() {
        assertArrayEquals(
            byteArrayOf(PcmFrameCodec.TYPE_CONTROL.toByte(), 1),
            PcmFrameCodec.control(PcmFrameCodec.CMD_START),
        )
    }

    @Test
    fun `PCM 帧带小端长度前缀`() {
        val payload = ByteArray(300) { 7 }
        val frame = PcmFrameCodec.pcm(payload)

        assertEquals(PcmFrameCodec.TYPE_PCM, frame[0].toInt())
        assertEquals(300 and 0xFF, frame[1].toInt())
        assertEquals(300 shr 8, frame[2].toInt())
        assertEquals(303, frame.size)
        assertArrayEquals(payload, frame.copyOfRange(3, 303))
    }

    @Test
    fun `HELLO 帧可携带 token`() {
        val frame = PcmFrameCodec.hello("abc123")
        assertEquals(PcmFrameCodec.TYPE_HELLO, frame[0].toInt())
        assertEquals(6, frame[1].toInt())
        assertEquals("abc123", String(frame, 3, 6))
    }

    @Test
    fun `解码器能处理粘包`() {
        val decoder = FrameDecoder()
        val frames = mutableListOf<Frame>()

        val data = PcmFrameCodec.control(PcmFrameCodec.CMD_START) +
            PcmFrameCodec.pcm(byteArrayOf(1, 2, 3, 4)) +
            PcmFrameCodec.state(PcmFrameCodec.STATE_RECORDING)

        decoder.feed(data) { frames.add(it) }

        assertEquals(3, frames.size)
        assertEquals(PcmFrameCodec.TYPE_CONTROL, frames[0].type)
        assertEquals(1, frames[0].byteValue())
        assertEquals(PcmFrameCodec.TYPE_PCM, frames[1].type)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frames[1].payload)
        assertEquals(PcmFrameCodec.STATE_RECORDING, frames[2].byteValue())
    }

    @Test
    fun `解码器能处理半包`() {
        val decoder = FrameDecoder()
        val frames = mutableListOf<Frame>()
        val data = PcmFrameCodec.pcm(byteArrayOf(9, 8, 7, 6, 5))

        // 先喂前两个字节（type + len 低字节）
        decoder.feed(data, 2) { frames.add(it) }
        assertTrue(frames.isEmpty())

        decoder.feed(data.copyOfRange(2, data.size)) { frames.add(it) }
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5), frames[0].payload)
    }

    @Test
    fun `未知类型字节会被跳过并重新同步`() {
        val decoder = FrameDecoder()
        val frames = mutableListOf<Frame>()
        val data = byteArrayOf(0x7F, 0x7E) + PcmFrameCodec.control(PcmFrameCodec.CMD_STOP)

        decoder.feed(data) { frames.add(it) }

        assertEquals(1, frames.size)
        assertEquals(PcmFrameCodec.CMD_STOP, frames[0].byteValue())
    }

    @Test
    fun `超过 65535 字节的单帧会被拒绝`() {
        var thrown = false
        try {
            PcmFrameCodec.pcm(ByteArray(70_000))
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
