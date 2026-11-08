package com.ifei7.murmur.ime.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * PCM 电平计算，用于「麦克风自检」：判断麦克风到底有没有收到声音。
 *
 * 输入固定是 16bit 小端、单声道、16kHz 的裸 PCM。
 */
object AudioLevel {

    /** 判定阈值（经验值）。 */
    const val RMS_SILENT = 200.0
    const val RMS_SPEECH = 2000.0

    /** 均方根。全静音返回 0，满幅方波接近 32767。 */
    fun rms(pcm16le: ByteArray): Double {
        if (pcm16le.size < 2) return 0.0
        val samples = pcm16le.size / 2
        var sum = 0.0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val v = readSample(pcm16le, i)
            sum += v.toDouble() * v
            i += 2
        }
        return sqrt(sum / samples)
    }

    /** 峰值绝对值。 */
    fun peak(pcm16le: ByteArray): Int {
        var max = 0
        var i = 0
        while (i + 1 < pcm16le.size) {
            val a = abs(readSample(pcm16le, i))
            if (a > max) max = a
            i += 2
        }
        return max
    }

    /** 读取一个小端 int16 并做符号扩展。 */
    private fun readSample(pcm: ByteArray, offset: Int): Int {
        val low = pcm[offset].toInt() and 0xFF
        val high = pcm[offset + 1].toInt() and 0xFF
        return ((low or (high shl 8)).toShort()).toInt()
    }

    /** 采样点数量。 */
    fun sampleCount(pcm16le: ByteArray): Int = pcm16le.size / 2

    /**
     * 给用户看的判定：静音 / 有环境声 / 像在说话。
     */
    fun classify(rms: Double): String = when {
        rms < RMS_SILENT -> "几乎静音（麦克风可能没工作，或者系统静音）"
        rms < RMS_SPEECH -> "有环境声，但不像在说话（可以再大声说一句）"
        else -> "音量正常，像是在说话"
    }

    /** 粗略估算分贝值（相对满幅），只用于显示。 */
    fun dbfs(rms: Double): Double =
        if (rms <= 0.0) -120.0 else 20 * kotlin.math.log10(rms / 32768.0)
}
