package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.net.CredentialParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialParserTest {

    private val cookieWithSession =
        "ttwid=1%7Cabc; sessionid=4ff1753345ceacf7b4f378da7b377373; sid_tt=4ff17; " +
            "passport_csrf_token=xyz"

    private fun evaluatePayload(): String {
        // 模拟 WebView evaluateJavascript 的返回：整体被 JSON 引号包裹并转义
        val webIdRaw = JSONObject()
            .put("web_id", "7623404988207515145")
            .put("tt_wid", "x")
            .toString()
        val teaRaw = JSONObject().put("web_id", "7623404978401396250").toString()
        val inner = JSONObject()
            .put("web_id_raw", webIdRaw)
            .put("tea_raw", teaRaw)
            .put("login_flag", "true")
            .toString()
        return JSONObject.quote(inner)
    }

    private fun emptyPayload(): String {
        val inner = JSONObject()
            .put("web_id_raw", "")
            .put("tea_raw", "")
            .toString()
        return JSONObject.quote(inner)
    }

    @Test
    fun `能去掉 evaluateJavascript 的引号转义`() {
        val raw = "\"{\\\"a\\\":1}\""
        assertEquals("{\"a\":1}", CredentialParser.unwrapEvaluateResult(raw))
    }

    @Test
    fun `null 结果返回空串`() {
        assertEquals("", CredentialParser.unwrapEvaluateResult("null"))
        assertEquals("", CredentialParser.unwrapEvaluateResult(null))
    }

    @Test
    fun `完整解析出 deviceId webId teaUuid`() {
        val creds = CredentialParser.parse(
            evaluateResult = evaluatePayload(),
            cookieHeader = cookieWithSession,
            userAgent = "UA/1.0",
            fallbackDeviceId = "android-id",
            now = 1000L,
        )
        assertNotNull(creds)
        assertEquals("7623404988207515145", creds!!.deviceId)
        assertEquals("7623404978401396250", creds.webId)
        assertEquals("7623404978401396250", creds.teaUuid)
        assertEquals("UA/1.0", creds.userAgent)
        assertEquals(1000L, creds.savedAt)
        assertTrue(creds.isUsable)
    }

    @Test
    fun `localStorage 缺字段时用 Android ID 兜底`() {
        val creds = CredentialParser.parse(
            evaluateResult = emptyPayload(),
            cookieHeader = cookieWithSession,
            userAgent = "",
            fallbackDeviceId = "android-id",
        )
        assertNotNull(creds)
        assertEquals("android-id", creds!!.deviceId)
        assertEquals("android-id", creds.webId)
    }

    @Test
    fun `没有 cookie 返回 null`() {
        assertNull(
            CredentialParser.parse(
                evaluateResult = evaluatePayload(),
                cookieHeader = "  ",
                userAgent = "",
                fallbackDeviceId = "android-id",
            ),
        )
    }

    @Test
    fun `登录态判断只看 session cookie`() {
        assertTrue(CredentialParser.looksLoggedIn(cookieWithSession))
        assertFalse(CredentialParser.looksLoggedIn("ttwid=1; passport_csrf_token=abc"))
        assertFalse(CredentialParser.looksLoggedIn(null))
        assertFalse(CredentialParser.looksLoggedIn("sessionid=; sid_tt="))
    }

    @Test
    fun `日志描述不包含 cookie 原文`() {
        val creds = CredentialParser.parse(
            evaluateResult = evaluatePayload(),
            cookieHeader = cookieWithSession,
            userAgent = "UA",
            fallbackDeviceId = "android-id",
        )!!
        val desc = creds.describeForLog()
        assertFalse(desc.contains("4ff1753345ceacf7b4f378da7b377373"))
        assertTrue(desc.contains("sessionid=true"))
    }
}
