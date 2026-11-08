package com.ifei7.murmur.ime.core

import com.ifei7.murmur.ime.audio.PcmFrameCodec

/** 语音输入会话状态机。 */
enum class VoiceState {
    /** 待机：等待触发。 */
    IDLE,

    /** 已触发，正在连 WSS。 */
    CONNECTING,

    /** 正在录音 / 正在送音频。 */
    RECORDING,

    /** 已停止送音频，等最后一帧识别结果。 */
    FINISHING,
    ;

    val isBusy: Boolean get() = this != IDLE

    /** 映射到 socket 回执里的状态码。 */
    fun wireCode(): Int = when (this) {
        IDLE -> PcmFrameCodec.STATE_IDLE
        CONNECTING -> PcmFrameCodec.STATE_WAITING
        RECORDING -> PcmFrameCodec.STATE_RECORDING
        FINISHING -> PcmFrameCodec.STATE_FINISHING
    }

    fun label(): String = when (this) {
        IDLE -> "待机"
        CONNECTING -> "连接中"
        RECORDING -> "录音中"
        FINISHING -> "识别中"
    }
}
