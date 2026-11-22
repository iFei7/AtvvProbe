package com.ifei7.murmur.ime.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ifei7.murmur.ime.core.AudioLevel
import com.ifei7.murmur.ime.util.Mlog
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 麦克风自检：录 [durationMs] 毫秒，算出 RMS / 峰值 / 判定。
 *
 * 不需要豆包登录，用来先确认「盒子 + USB 麦克风」这条链路是通的。
 * 阻塞调用，必须在后台线程跑。
 */
object MicProbe {

    data class Result(
        val durationMs: Long,
        val sampleCount: Int,
        val rms: Double,
        val peak: Int,
        val dbfs: Double,
        val verdict: String,
        /** 原始 16kHz/mono/16bit PCM，用于导出 WAV 试听。 */
        val pcm: ByteArray,
    )

    @SuppressLint("MissingPermission")
    fun record(durationMs: Int = 3000): Result {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("设备不支持 16kHz 单声道录音（minBuffer=$minBuffer）")
        }
        // TV 盒子上偶发丢帧，缓冲区给到 2 倍最小缓冲
        val bufferSize = max(minBuffer * 2, SAMPLE_RATE / 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            throw IllegalStateException("创建 AudioRecord 失败：${e.message}")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord 初始化失败（可能没有录音权限）")
        }

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        val startAt = System.currentTimeMillis()
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("录音启动失败")
            }
            while (System.currentTimeMillis() - startAt < durationMs) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    out.write(buffer, 0, read)
                } else if (read < 0) {
                    throw IllegalStateException("AudioRecord.read 返回错误码 $read")
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }

        val pcm = out.toByteArray()
        val rms = AudioLevel.rms(pcm)
        val peak = AudioLevel.peak(pcm)
        val result = Result(
            durationMs = System.currentTimeMillis() - startAt,
            sampleCount = AudioLevel.sampleCount(pcm),
            rms = rms,
            peak = peak,
            dbfs = AudioLevel.dbfs(rms),
            verdict = AudioLevel.classify(rms),
            pcm = pcm,
        )
        Mlog.ime(
            "麦克风自检：${result.durationMs}ms, ${result.sampleCount} 采样, " +
                "rms=${"%.1f".format(rms)}, peak=$peak, ${result.verdict}",
        )
        return result
    }

    private const val SAMPLE_RATE = 16_000
}
