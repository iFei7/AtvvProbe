package com.ifei7.murmur.ime.net

import java.net.URLEncoder

/**
 * 豆包 WSS ASR 的端点与固定参数。
 *
 * 协议细节见 `doubao-docs/doubao-wss-asr-spec.md`。
 * 上行：裸 PCM（16kHz / 单声道 / Int16LE）二进制帧，无握手无结束帧；
 * 下行：JSON 文本帧，`event=result|finish`。
 */
object DoubaoConfig {

    const val WS_URL = "wss://ws-samantha.doubao.com/samantha/audio/asr"

    const val ORIGIN = "https://www.doubao.com"

    const val VERSION_CODE = "20800"
    const val PC_VERSION = "3.12.3"

    /** 默认 UA：豆包 Web 端 Chrome UA，抓不到 WebView UA 时用它兜底。 */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; TX6) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    /** 固定 query 参数，顺序与抓包保持一致。 */
    private val FIXED_PARAMS: List<Pair<String, String>> = listOf(
        "version_code" to VERSION_CODE,
        "language" to "zh",
        "device_platform" to "web",
        "aid" to "497858",
        "real_aid" to "497858",
        "pkg_type" to "release_version",
        "pc_version" to PC_VERSION,
        "region" to "",
        "sys_region" to "",
        "samantha_web" to "1",
        "use-olympus-account" to "1",
        "format" to "pcm",
    )

    /**
     * 拼出完整 WSS URL。
     *
     * @param deviceId `localStorage['samantha_web_web_id'].web_id`
     * @param webId `localStorage['__tea_cache_tokens_497858'].web_id`
     * @param teaUuid 通常与 [webId] 相同
     * @param webTabId 每次连接随机生成的 UUID v4
     */
    fun buildUrl(
        deviceId: String,
        webId: String,
        teaUuid: String,
        webTabId: String,
    ): String {
        val sb = StringBuilder(WS_URL).append('?')
        var first = true
        fun append(key: String, value: String) {
            if (!first) sb.append('&')
            first = false
            sb.append(encode(key)).append('=').append(encode(value))
        }
        // device_id / web_id / tea_uuid 插在 pkg_type 之后、pc_version 之前，与抓包一致。
        for ((key, value) in FIXED_PARAMS) {
            append(key, value)
            when (key) {
                "pkg_type" -> {
                    append("device_id", deviceId)
                    append("web_id", webId)
                    append("tea_uuid", teaUuid)
                }
            }
        }
        append("web_tab_id", webTabId)
        return sb.toString()
    }

    /** RFC3986 编码：空格编成 %20 而不是 +。 */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
