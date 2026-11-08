package com.ifei7.murmur.ime.audio

/**
 * daemon ↔ IME 的 socket 帧协议（v1）。
 *
 * ```
 * 帧 = type(1 byte) + payload
 * 0x01 CONTROL  payload = cmd(1 byte)          客户端 → App：开始 / 停止 / 取消
 * 0x02 PCM      payload = len(2 byte LE) + 裸 PCM(Int16LE 16kHz mono)
 * 0x03 HELLO    payload = len(2 byte LE) + token(UTF-8)   客户端 → App：鉴权
 * 0x04 STATE    payload = state(1 byte)        App → 客户端：当前状态
 * ```
 *
 * - 连接建立后客户端**必须**先发 HELLO，token 不匹配会被直接断开。
 * - 一个连接既承载控制命令又承载 PCM，因此“按语音键”和“送音频”走同一条 socket。
 * - PCM 帧的 len 是 uint16，单帧最大 65535 字节。
 */
object PcmFrameCodec {

    const val TYPE_CONTROL = 0x01
    const val TYPE_PCM = 0x02
    const val TYPE_HELLO = 0x03
    const val TYPE_STATE = 0x04

    const val CMD_STOP = 0
    const val CMD_START = 1
    const val CMD_CANCEL = 2

    /** App 侧状态回执，和 [com.ifei7.murmur.ime.core.VoiceState] 的序号保持一致。 */
    const val STATE_IDLE = 0
    const val STATE_WAITING = 1
    const val STATE_RECORDING = 2
    const val STATE_FINISHING = 3
    const val STATE_REJECTED = 4

    /** 单帧 PCM 上限。 */
    const val MAX_PCM_FRAME = 0xFFFF

    fun control(cmd: Int): ByteArray = byteArrayOf(TYPE_CONTROL.toByte(), cmd.toByte())

    fun state(state: Int): ByteArray = byteArrayOf(TYPE_STATE.toByte(), state.toByte())

    fun hello(token: String): ByteArray {
        val body = token.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_PCM_FRAME) { "token 过长" }
        val out = ByteArray(3 + body.size)
        out[0] = TYPE_HELLO.toByte()
        out[1] = (body.size and 0xFF).toByte()
        out[2] = ((body.size shr 8) and 0xFF).toByte()
        System.arraycopy(body, 0, out, 3, body.size)
        return out
    }

    fun pcm(data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        require(length <= MAX_PCM_FRAME) { "单帧 PCM 超过 $MAX_PCM_FRAME 字节" }
        val out = ByteArray(3 + length)
        out[0] = TYPE_PCM.toByte()
        out[1] = (length and 0xFF).toByte()
        out[2] = ((length shr 8) and 0xFF).toByte()
        System.arraycopy(data, offset, out, 3, length)
        return out
    }
}

/** 一个完整的协议帧。 */
class Frame(val type: Int, val payload: ByteArray) {

    /** 仅 CONTROL / STATE 帧有效。 */
    fun byteValue(): Int = payload[0].toInt() and 0xFF

    fun text(): String = payload.toString(Charsets.UTF_8)

    override fun toString(): String = "Frame(type=$type, len=${payload.size})"
}

/**
 * 流式帧解码器：内部缓冲，可以容忍任意位置切包的 TCP/Unix socket 数据。
 */
class FrameDecoder {

    private var buffer = ByteArray(0)

    fun feed(data: ByteArray, length: Int = data.size, sink: (Frame) -> Unit) {
        if (length <= 0) return
        val merged = ByteArray(buffer.size + length)
        System.arraycopy(buffer, 0, merged, 0, buffer.size)
        System.arraycopy(data, 0, merged, buffer.size, length)

        var offset = 0
        while (true) {
            if (merged.size - offset < 1) break
            val type = merged[offset].toInt() and 0xFF
            when (type) {
                PcmFrameCodec.TYPE_CONTROL, PcmFrameCodec.TYPE_STATE -> {
                    if (merged.size - offset < 2) break
                    sink(Frame(type, byteArrayOf(merged[offset + 1])))
                    offset += 2
                }
                PcmFrameCodec.TYPE_PCM, PcmFrameCodec.TYPE_HELLO -> {
                    if (merged.size - offset < 3) break
                    val len = (merged[offset + 1].toInt() and 0xFF) or
                        ((merged[offset + 2].toInt() and 0xFF) shl 8)
                    if (merged.size - offset < 3 + len) break
                    sink(Frame(type, merged.copyOfRange(offset + 3, offset + 3 + len)))
                    offset += 3 + len
                }
                else -> {
                    // 未知类型：跳过一个字节重新同步，避免整条连接报废。
                    offset += 1
                }
            }
        }
        buffer = if (offset >= merged.size) ByteArray(0) else merged.copyOfRange(offset, merged.size)
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}
