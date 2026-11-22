package com.ifei7.murmur.ime.core

/**
 * 把任意长度的 PCM 字节流切成固定大小的块，方便按帧发送。
 *
 * 豆包前端 ScriptProcessor 方案每帧 16384 字节（约 512ms），
 * AudioWorklet 方案每帧 4096 字节（约 128ms）。这里默认 3200 字节（约 100ms），
 * 延迟更低且不会超过 socket 帧的 65535 字节上限。
 */
class PcmChunker(
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
) {
    init {
        require(chunkSize in 2..MAX_CHUNK_SIZE) { "chunkSize 必须在 2..$MAX_CHUNK_SIZE 之间" }
        require(chunkSize % 2 == 0) { "chunkSize 必须是偶数（16bit PCM 一个采样 2 字节）" }
    }

    private var pending = ByteArray(0)

    /** 追加数据并回调所有完整的块。 */
    fun accept(data: ByteArray, sink: (ByteArray) -> Unit) {
        if (data.isEmpty()) return
        val merged = ByteArray(pending.size + data.size)
        System.arraycopy(pending, 0, merged, 0, pending.size)
        System.arraycopy(data, 0, merged, pending.size, data.size)

        var offset = 0
        while (merged.size - offset >= chunkSize) {
            val chunk = ByteArray(chunkSize)
            System.arraycopy(merged, offset, chunk, 0, chunkSize)
            sink(chunk)
            offset += chunkSize
        }
        pending = if (offset == merged.size) {
            ByteArray(0)
        } else {
            merged.copyOfRange(offset, merged.size)
        }
    }

    /** 取走残留数据（停止录音时调用，通常是半个采样）。 */
    fun flush(): ByteArray {
        val rest = pending
        pending = ByteArray(0)
        return rest
    }

    fun reset() {
        pending = ByteArray(0)
    }

    companion object {
        /** 16kHz * 16bit * 单声道 = 32000 B/s，3200 B ≈ 100ms。 */
        const val DEFAULT_CHUNK_SIZE = 3200

        /** 帧格式里的长度字段是 uint16。 */
        const val MAX_CHUNK_SIZE = 65535
    }
}
