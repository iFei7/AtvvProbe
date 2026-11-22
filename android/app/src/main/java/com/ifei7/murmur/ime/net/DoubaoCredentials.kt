package com.ifei7.murmur.ime.net

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 调用豆包 WSS 所需的全部凭证。
 *
 * ⚠️ 安全约束：这些字段**只允许**存在 SharedPreferences（应用私有目录），
 * 不允许写进源码、提交到 git、或者明文打印到日志。
 */
data class DoubaoCredentials(
    /** `CookieManager.getCookie("https://www.doubao.com")` 的原始串，含 httpOnly cookie。 */
    val cookie: String,
    val deviceId: String,
    val webId: String,
    val teaUuid: String,
    val userAgent: String,
    /** 提取时间（epoch millis），用于过期提示。 */
    val savedAt: Long,
) {
    val isUsable: Boolean
        get() = cookie.isNotBlank() && deviceId.isNotBlank() && webId.isNotBlank()

    /** 不打印任何敏感值，只说明有哪些关键 cookie 存在。 */
    fun describeForLog(): String {
        val names = cookie.split(';')
            .map { it.trim().substringBefore('=') }
            .filter { it.isNotEmpty() }
        val hasSession = names.any { it == "sessionid" || it == "sid_tt" || it == "sessionid_ss" }
        return "cookie=${names.size} 项, sessionid=$hasSession, deviceId=${deviceId.take(4)}***, " +
            "webId=${webId.take(4)}***, savedAt=$savedAt"
    }
}

/**
 * 解析 LoginActivity 里 `evaluateJavascript` 的返回结果。
 *
 * WebView 对返回字符串会再做一次 JSON 编码，所以拿到的通常是
 * `"{\"cookie\":\"...\"}"` 这种**被引号包裹**的形式，需要先 unquote。
 */
object CredentialParser {

    /** 代码里注入的 JS：返回一个 JSON 字符串。 */
    val EXTRACT_JS: String = """
        (function () {
            try {
                var w = localStorage.getItem('samantha_web_web_id');
                var t = localStorage.getItem('__tea_cache_tokens_497858');
                return JSON.stringify({
                    web_id_raw: w || '',
                    tea_raw: t || '',
                    login_flag: localStorage.getItem('flow_web_has_login') || ''
                });
            } catch (e) { return JSON.stringify({error: String(e)}); }
        })()
    """

    /**
     * 去掉 WebView 对字符串结果的 JSON 引号转义。
     * 输入 `"{\"a\":1}"` → 输出 `{"a":1}`；输入 `null` → 输出空串。
     */
    fun unwrapEvaluateResult(raw: String?): String {
        if (raw == null) return ""
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return ""
        if (!trimmed.startsWith("\"")) return trimmed
        return runCatching {
            val value = JSONTokener(trimmed).nextValue()
            if (value is String) value else ""
        }.getOrDefault("")
    }

    /**
     * 从三份素材组装凭证。
     *
     * @param evaluateResult `EXTRACT_JS` 的执行结果（已或未 unquote 均可）
     * @param cookieHeader `CookieManager.getCookie("https://www.doubao.com")`
     * @param fallbackDeviceId localStorage 缺失时的兜底（Android ID）
     */
    fun parse(
        evaluateResult: String?,
        cookieHeader: String?,
        userAgent: String,
        fallbackDeviceId: String,
        now: Long = System.currentTimeMillis(),
    ): DoubaoCredentials? {
        val json = unwrapEvaluateResult(evaluateResult)
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.has("error")) return null

        // 按 spec：device_id 来自 samantha_web_web_id.web_id，
        // web_id / tea_uuid 来自 __tea_cache_tokens_497858.web_id
        val samanthaWebId = readField(root.optString("web_id_raw"), "web_id")
        val teaWebId = readField(root.optString("tea_raw"), "web_id")
        val teaUserId = readField(root.optString("tea_raw"), "user_unique_id")

        val deviceId = firstNonBlank(samanthaWebId, teaUserId, teaWebId, fallbackDeviceId)
        val webId = firstNonBlank(teaWebId, samanthaWebId, fallbackDeviceId)
        val teaUuid = firstNonBlank(teaWebId, webId, fallbackDeviceId)
        val cookie = cookieHeader.orEmpty().trim()
        if (cookie.isEmpty()) return null

        val creds = DoubaoCredentials(
            cookie = cookie,
            deviceId = deviceId,
            webId = webId,
            teaUuid = teaUuid,
            userAgent = userAgent.ifBlank { DoubaoConfig.DEFAULT_USER_AGENT },
            savedAt = now,
        )
        return if (creds.isUsable) creds else null
    }

    /** 从形如 `{"web_id":"7623..."}` 的原始字符串里取字段。 */
    private fun readField(rawJson: String, key: String): String {
        if (rawJson.isBlank()) return ""
        return runCatching { JSONObject(rawJson).optString(key, "") }.getOrDefault("")
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    /**
     * 粗判登录态：`sessionid` / `sid_tt` 之一存在即认为已登录。
     * 真正的失效只能在 WSS 握手阶段发现（服务端会直接关闭连接）。
     */
    fun looksLoggedIn(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        return cookieHeader.split(';').any { part ->
            val name = part.trim().substringBefore('=')
            (name == "sessionid" || name == "sid_tt" || name == "sessionid_ss") &&
                part.substringAfter('=', "").isNotBlank()
        }
    }
}
