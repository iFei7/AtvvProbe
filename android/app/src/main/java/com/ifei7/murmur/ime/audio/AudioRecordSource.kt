package com.ifei7.murmur.ime.audio

import com.ifei7.murmur.ime.core.PcmChunker
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ifei7.murmur.ime.util.Mlog

/**
 * 系统麦克风（含 USB 麦克风）音频源，作为 socket 方案的备选。
 *
 * 采不到 16kHz 时 Android 会自动重采样到最近的可用采样率，
 * 因此必须显式检查 `AudioRecord.getSampleRate()`。
 */
class AudioRecordSource(
    private val chunkSize: Int = PcmChunker.DEFAULT_CHUNK_SIZE,
) : PcmSource {

    override val name: String = "mic:AudioRecord"

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    override fun start(listener: PcmSource.Listener) {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            listener.onSourceError("设备不支持 16kHz 单声道录音（minBuffer=$minBuffer）", null)
            return
        }
        // TV 盒子上偶发丢帧，缓冲区给到 2 倍最小缓冲
        val bufferSize = maxOf(minBuffer * 2, chunkSize * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (e: Exception) {
            listener.onSourceError("创建 AudioRecord 失败: ${e.message}", e)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            listener.onSourceError(
                "AudioRecord 初始化失败（多半是没有录音权限）：\n" +
                    "adb shell pm grant com.ifei7.murmur.ime android.permission.RECORD_AUDIO",
                null,
            )
            return
        }
        if (rec.sampleRate != SAMPLE_RATE) {
            Mlog.imeE("AudioRecord 实际采样率 ${rec.sampleRate}，与 16kHz 不一致，识别质量可能受影响")
        }
        record = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            running = false
            rec.release()
            record = null
            listener.onSourceError("启动录音失败: ${e.message}", e)
            return
        }
        thread = Thread({ loop(listener) }, "murmur-mic").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(listener: PcmSource.Listener) {
        val rec = record ?: return
        val buffer = ByteArray(chunkSize)
        try {
            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) {
                    listener.onPcm(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
                } else if (read < 0) {
                    listener.onSourceError("AudioRecord.read 返回错误码 $read", null)
                    break
                }
            }
        } catch (e: Exception) {
            if (running) listener.onSourceError("录音读取异常: ${e.message}", e)
        }
    }

    override fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.interrupt()
        thread = null
    }

    override fun release() {
        stop()
        runCatching { record?.release() }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
