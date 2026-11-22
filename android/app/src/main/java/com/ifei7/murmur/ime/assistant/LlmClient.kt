package com.ifei7.murmur.ime.assistant

import com.ifei7.murmur.ime.util.Mlog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** LLM 会话配置（OpenAI 兼容端点）。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 助手的 LLM 抽象：给定用户文本，异步回调回复 / 错误。
 * 回调在后台线程触发，调用方自行切线程。
 */
interface AssistantLlm {
    fun ask(userText: String, onReply: (String) -> Unit, onError: (String) -> Unit)
}

/**
 * OpenAI 兼容 chat completions 客户端（POST {baseUrl}/chat/completions）。
 *
 * 安全约束：
 * - apiKey 只存在于 MurmurPrefs（应用私有 SP），**永不写日志**；
 * - baseUrl / apiKey / model 默认留空，由用户在设置页自己填写。
 */
class LlmClient(
    private val configProvider: () -> LlmConfig,
    private val client: OkHttpClient = defaultClient(),
) : AssistantLlm {

    override fun ask(userText: String, onReply: (String) -> Unit, onError: (String) -> Unit) {
        val cfg = configProvider()
        if (!cfg.isConfigured) {
            onError("请先在设置里配置助手 LLM（Base URL / API Key / 模型名）")
            return
        }
        val url = cfg.baseUrl.trim().trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + cfg.apiKey)
            .post(
                buildRequestBody(cfg.model, SYSTEM_PROMPT, userText)
                    .toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()
        Mlog.assistant("请求 LLM：model=${cfg.model}，文本 ${userText.length} 字")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Mlog.assistantE("LLM 请求失败: ${e.message}", e)
                onError(e.message ?: e.javaClass.simpleName)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        Mlog.assistantE("LLM HTTP ${it.code}")
                        onError("HTTP ${it.code}")
                        return
                    }
                    val reply = parseReply(body)
                    if (reply.isNullOrBlank()) {
                        Mlog.assistantE("LLM 回复解析失败")
                        onError("回复解析失败")
                    } else {
                        onReply(reply)
                    }
                }
            }
        })
    }

    companion object {
        const val SYSTEM_PROMPT = "你是电视语音助手，用简短中文口语回复"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** 构造 chat completions 请求体（纯函数，便于单测）。 */
        fun buildRequestBody(model: String, systemPrompt: String, userText: String): String {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", userText))
            return JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.7)
                .toString()
        }

        /** 解析响应里的 choices[0].message.content（纯函数，便于单测）。 */
        fun parseReply(raw: String): String? = runCatching {
            val root = JSONObject(raw)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
            message.optString("content", "").trim().ifBlank { null }
        }.getOrNull()
    }
}
