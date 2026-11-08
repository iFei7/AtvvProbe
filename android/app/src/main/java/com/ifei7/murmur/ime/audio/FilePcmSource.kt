package com.ifei7.murmur.ime.audio

import com.ifei7.murmur.ime.core.PcmChunker
import com.ifei7.murmur.ime.util.Mlog
import java.io.File
import java.io.RandomAccessFile

/**
 * 从一个裸 PCM / WAV 文件按实时速率喂数据，用于开发期联调（不需要 daemon）。
 *
 * 按 100ms 一帧的节奏吐数据，模拟真实录音的时序，避免一次灌满把服务端打爆。
 *
 * [tail] = true 时不会在读到 EOF 时结束，而是持续等待文件增长，
 * 用于「daemon 往文件里追加写 PCM」的场景（daemon 需要 `chmod 644` 该文件，
 * App 才能读 /data/local/tmp 下的内容）。
 */
class FilePcmSource(
    private val file: File,
    private val chunkSize: Int = PcmChunker.DEFAULT_CHUNK_SIZE,
    private val realtime: Boolean = true,
    private val tail: Boolean = false,
) : PcmSource {

    override val name: String = "file:${file.name}"

    @Volatile
    private var running = false

    private var thread: Thread? = null

    override fun start(listener: PcmSource.Listener) {
        if (running) return
        if (!file.exists() || file.length() == 0L) {
            listener.onSourceError("测试音频不存在或为空: ${file.absolutePath}", null)
            return
        }
        running = true
        thread = Thread({ loop(listener) }, "murmur-file-pcm").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(listener: PcmSource.Listener) {
        Mlog.ime("FilePcmSource 开流: ${file.name} (${file.length()} B)")
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(chunkSize)
                val intervalMs = if (realtime) {
                    chunkSize * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                } else {
                    0L
                }
                while (running) {
                    val read = raf.read(buffer)
                    if (read <= 0) {
                        if (!tail) break
                        // tail 模式：等文件继续变长（daemon 还在写）
                        Thread.sleep(30)
                        continue
                    }
                    listener.onPcm(buffer.copyOf(read))
                    if (intervalMs > 0) Thread.sleep(intervalMs)
                }
            }
            if (running) listener.onSourceEnded()
        } catch (e: InterruptedException) {
            Mlog.ime("FilePcmSource 被中断")
        } catch (e: Exception) {
            if (running) listener.onSourceError("读取测试音频失败: ${e.message}", e)
        } finally {
            running = false
        }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    override fun release() = stop()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
    }
}
