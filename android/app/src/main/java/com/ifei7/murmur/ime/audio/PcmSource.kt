package com.ifei7.murmur.ime.audio

/**
 * 可插拔的 PCM 音频源。
 *
 * 所有实现都产出 **16kHz / 单声道 / Int16 小端** 的裸 PCM，
 * 由 [com.ifei7.murmur.ime.core.VoiceController] 直接灌进豆包 WSS。
 */
interface PcmSource {

    interface Listener {
        fun onPcm(data: ByteArray)
        fun onSourceEnded()
        fun onSourceError(message: String, error: Throwable?)
    }

    val name: String

    /** 开始产出音频；[listener] 的回调在后台线程上触发。 */
    fun start(listener: Listener)

    /** 停止产出（不释放资源）。 */
    fun stop()

    /** 释放资源，服务销毁时调用。 */
    fun release()
}

/** 音频源类型，保存在 SharedPreferences 里。 */
enum class PcmSourceType(val key: String, val label: String) {
    /** 系统麦克风 / USB 麦克风 —— **默认**，唯一不依赖遥控器解码的可用音频源。 */
    MIC("mic", "麦克风（USB / AudioRecord）"),

    /** 本地 Unix socket，等 daemon 能产出 PCM 后启用（触发仍走这条）。 */
    SOCKET("socket", "Socket（daemon 送流）"),

    /** 从文件读 PCM，联调时用。 */
    FILE("file", "文件（测试音频）"),
    ;

    companion object {
        /** 默认走麦克风：遥控器音频解码不通时，这是唯一能端到端跑通的源。 */
        fun fromKey(key: String?): PcmSourceType =
            entries.firstOrNull { it.key == key } ?: MIC
    }
}
