package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.assistant.LlmClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LlmClient 请求体构造 / 响应解析纯逻辑测试（无网络）。 */
class LlmClientTest {

    @Test
    fun `请求体包含 model 与 system user 两条消息`() {
        val body = LlmClient.buildRequestBody("deepseek-chat", LlmClient.SYSTEM_PROMPT, "你好")

        val json = JSONObject(body)
        assertEquals("deepseek-chat", json.getString("model"))

        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(LlmClient.SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("你好", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun `系统提示词要求简短中文口语`() {
        assertTrue(LlmClient.SYSTEM_PROMPT.contains("电视"))
        assertTrue(LlmClient.SYSTEM_PROMPT.contains("简短中文"))
    }

    @Test
    fun `解析正常响应取 choices0 message content`() {
        val raw = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("role", "assistant").put("content", "  今天是晴天  "),
                    ),
                ),
            )
            .toString()
        assertEquals("今天是晴天", LlmClient.parseReply(raw))
    }

    @Test
    fun `解析空 content 返回 null`() {
        val raw = JSONObject()
            .put(
                "choices",
                JSONArray().put(JSONObject().put("message", JSONObject().put("content", "  "))),
            )
            .toString()
        assertNull(LlmClient.parseReply(raw))
    }

    @Test
    fun `缺 choices 或空 choices 返回 null`() {
        assertNull(LlmClient.parseReply(JSONObject().put("id", "x").toString()))
        assertNull(LlmClient.parseReply(JSONObject().put("choices", JSONArray()).toString()))
    }

    @Test
    fun `缺 message 或非法 JSON 返回 null`() {
        val noMessage = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")))
            .toString()
        assertNull(LlmClient.parseReply(noMessage))
        assertNull(LlmClient.parseReply("not a json"))
        assertNull(LlmClient.parseReply(""))
    }

    @Test
    fun `错误响应解析不出内容返回 null 不抛异常`() {
        val raw = JSONObject()
            .put("error", JSONObject().put("message", "Invalid API key").put("code", 401))
            .toString()
        assertNull(LlmClient.parseReply(raw))
    }
}
