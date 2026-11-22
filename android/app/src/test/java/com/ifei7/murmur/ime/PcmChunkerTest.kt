package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.core.PcmChunker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmChunkerTest {

    @Test
    fun `按固定大小切块并保留余量`() {
        val chunker = PcmChunker(chunkSize = 4)
        val chunks = mutableListOf<ByteArray>()

        chunker.accept(byteArrayOf(1, 2, 3, 4, 5, 6, 7)) { chunks.add(it) }

        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunks[0])
        // 剩余 3 字节留在内部
        assertArrayEquals(byteArrayOf(5, 6, 7), chunker.flush())
    }

    @Test
    fun `跨多次喂入的数据要拼成完整块`() {
        val chunker = PcmChunker(chunkSize = 4)
        val chunks = mutableListOf<ByteArray>()

        chunker.accept(byteArrayOf(1, 2)) { chunks.add(it) }
        assertTrue(chunks.isEmpty())

        chunker.accept(byteArrayOf(3, 4, 5)) { chunks.add(it) }
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunks[0])
        assertArrayEquals(byteArrayOf(5), chunker.flush())
    }

    @Test
    fun `恰好整除时没有残留`() {
        val chunker = PcmChunker(chunkSize = 4)
        val chunks = mutableListOf<ByteArray>()

        chunker.accept(ByteArray(8) { it.toByte() }) { chunks.add(it) }

        assertEquals(2, chunks.size)
        assertEquals(0, chunker.flush().size)
    }

    @Test
    fun `默认块大小是 100ms 的 16k 单声道数据`() {
        // 16000 采样/秒 * 2 字节 = 32000 B/s → 100ms = 3200 B
        assertEquals(3200, PcmChunker.DEFAULT_CHUNK_SIZE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `奇数块大小直接拒绝`() {
        PcmChunker(chunkSize = 3)
    }
}
