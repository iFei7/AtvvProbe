package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.bluetooth.ReportReference
import com.ifei7.murmur.ime.bluetooth.VoiceSequence
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音使能命令序列构造与 Report Reference 解析（纯逻辑，无 Android 依赖）。 */
class VoiceSequenceTest {

    private fun r(id: Int, output: Boolean) = ReportReference(id.toByte(), output)

    /** TX6 实测的 report map（NANOSIC_PROTOCOL_SPEC.md §4）：01/02/03 输入 + FC/F8/F9 vendor 输入 + FB/FA 输出。 */
    private val tx6Reports = listOf(
        r(0x01, output = false),
        r(0x02, output = false),
        r(0x03, output = false),
        r(0xFC, output = false),
        r(0xFB, output = true),
        r(0xF8, output = false),
        r(0xFA, output = true),
        r(0xF9, output = false),
    )

    @Test
    fun `ReportReference 解析 input 与 output`() {
        assertEquals(ReportReference(0xFA.toByte(), true), ReportReference.parse(byteArrayOf(0xFA.toByte(), 0x02)))
        assertEquals(ReportReference(0xF8.toByte(), false), ReportReference.parse(byteArrayOf(0xF8.toByte(), 0x01)))
        assertEquals(ReportReference(0x03.toByte(), false), ReportReference.parse(byteArrayOf(0x03.toByte(), 0x03)))
    }

    @Test
    fun `ReportReference 非法输入返回 null`() {
        assertNull(ReportReference.parse(null))
        assertNull(ReportReference.parse(byteArrayOf(0x01)))
        assertNull(ReportReference.parse(byteArrayOf()))
        assertNull(ReportReference.parse(byteArrayOf(0x01, 0x04)))
        assertNull(ReportReference.parse(byteArrayOf(0x01, 0x00)))
    }

    @Test
    fun `TX6 报告表判定为映射完整`() {
        assertTrue(VoiceSequence.isComplete(tx6Reports))
    }

    @Test
    fun `缺少 FA 时映射不完整`() {
        assertFalse(VoiceSequence.isComplete(tx6Reports.filter { it.reportId != 0xFA.toByte() }))
    }

    @Test
    fun `精确序列按 btsnoop 实测顺序生成 5 条写`() {
        val seq = VoiceSequence.build(tx6Reports)
        assertEquals(5, seq.size)

        assertEquals(0xFA.toByte(), seq[0].reportId)
        assertArrayEquals(byteArrayOf(0x01), seq[0].payload)

        assertEquals(0xFA.toByte(), seq[1].reportId)
        assertArrayEquals(byteArrayOf(0xFA.toByte(), 0x01), seq[1].payload)

        assertEquals(0xFA.toByte(), seq[2].reportId)
        assertArrayEquals(byteArrayOf(0xFA.toByte(), 0x00), seq[2].payload)

        assertEquals(0xF8.toByte(), seq[3].reportId)
        assertArrayEquals(byteArrayOf(0x01), seq[3].payload)

        assertEquals(0xFB.toByte(), seq[4].reportId)
        assertArrayEquals(byteArrayOf(0x01), seq[4].payload)
    }

    @Test
    fun `缺 FA 时精确序列跳过对应条目`() {
        val seq = VoiceSequence.build(tx6Reports.filter { it.reportId != 0xFA.toByte() })
        assertEquals(2, seq.size)
        assertEquals(0xF8.toByte(), seq[0].reportId)
        assertEquals(0xFB.toByte(), seq[1].reportId)
    }

    @Test
    fun `兜底序列只覆盖 output 报告`() {
        val seq = VoiceSequence.buildFallback(tx6Reports)
        // FB 与 FA 两个 output，各 3 条
        assertEquals(6, seq.size)
        assertEquals(0xFB.toByte(), seq[0].reportId)
        assertArrayEquals(byteArrayOf(0x01), seq[0].payload)
        assertEquals(0xFB.toByte(), seq[1].reportId)
        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0x01), seq[1].payload)
        assertEquals(0xFB.toByte(), seq[2].reportId)
        assertArrayEquals(byteArrayOf(0xFB.toByte(), 0x00), seq[2].payload)
        assertEquals(0xFA.toByte(), seq[3].reportId)
        assertArrayEquals(byteArrayOf(0xFA.toByte(), 0x01), seq[4].payload)
        assertArrayEquals(byteArrayOf(0xFA.toByte(), 0x00), seq[5].payload)
    }

    @Test
    fun `没有任何 output 时兜底覆盖全部报告`() {
        val inputs = tx6Reports.map { ReportReference(it.reportId, isOutput = false) }
        val seq = VoiceSequence.buildFallback(inputs)
        assertEquals(inputs.size * 3, seq.size)
    }

    @Test
    fun `空报告表时两个构造器都返回空`() {
        assertTrue(VoiceSequence.build(emptyList()).isEmpty())
        assertTrue(VoiceSequence.buildFallback(emptyList()).isEmpty())
    }
}
