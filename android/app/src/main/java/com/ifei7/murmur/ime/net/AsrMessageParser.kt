package com.ifei7.murmur.ime.net

import org.json.JSONObject

/**
 * 豆包 ASR 下行 JSON 文本帧的解析器。
 *
 * 正常帧：
 * ```json
 * {"event":"result","result":{"Text":"你好"},"code":0,"message":""}
 * {"event":"finish","result":null,"code":0,"message":""}
 * ```
 */
object AsrMessageParser {

    const val EVENT_RESULT = "result"
    const val EVENT_FINISH = "finish"

    const val CODE_SUCCESS = 0

    // 注意：spec 里同时给了十进制和十六进制两种写法，且两者并不一致
    // （0x2A4B9F4D = 709599053，0x2A4B9F4E = 709599054）。
    // 服务端 JSON 里下发的是十进制，这里以十进制为准。
    const val CODE_TIMEOUT = 709599053
    const val CODE_INVALID = 709599054

    data class Message(
        val event: String,
        val text: String,
        val code: Int,
        val message: String,
    ) {
        val isError: Boolean get() = code != CODE_SUCCESS
        val isFinish: Boolean get() = event == EVENT_FINISH || isError
    }

    /** 解析失败返回 null（服务端偶尔会下发非 JSON 的心跳文本，直接忽略）。 */
    fun parse(raw: String): Message? {
        if (raw.isBlank()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val event = root.optString("event", "")
        val code = root.optInt("code", CODE_SUCCESS)
        val message = root.optString("message", "")
        val text = root.optJSONObject("result")?.optString("Text", "").orEmpty()
        if (event.isEmpty() && code == CODE_SUCCESS) return null
        return Message(event = event, text = text, code = code, message = message)
    }

    /** 把错误码翻译成便于排查的中文说明。 */
    fun describeCode(code: Int): String = when (code) {
        CODE_SUCCESS -> "成功"
        CODE_TIMEOUT -> "服务端超时（可能长时间没有语音输入）"
        CODE_INVALID -> "请求无效（常见原因：cookie 失效 / 参数不匹配）"
        else -> "未知错误码 $code"
    }
}
