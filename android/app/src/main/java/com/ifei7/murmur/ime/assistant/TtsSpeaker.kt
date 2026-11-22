package com.ifei7.murmur.ime.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.ifei7.murmur.ime.util.Mlog
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** TTS 播报抽象：给定回复文本，播报完成后回调（回调在 TTS 线程触发）。 */
interface AssistantSpeaker {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
    fun shutdown()
}

/**
 * 系统 TTS 播报器（android.speech.tts.TextToSpeech）。
 *
 * 降级策略：初始化失败 / 缺中文引擎时通过 [onUnavailable] 提示
 * 「未找到中文 TTS 引擎」，之后 [speak] 直接完成回调——回复只留在通知栏，
 * 对话闭环不断。
 */
class TtsSpeaker(
    context: Context,
    private val onUnavailable: (String) -> Unit,
) : AssistantSpeaker {

    private var tts: TextToSpeech? = null

    @Volatile
    private var available = false

    private var notifiedUnavailable = false

    private val utteranceIds = AtomicLong(0)

    /** 当前播报的完成回调（QUEUE_FLUSH 打断旧播报时被替换）。 */
    @Volatile
    private var doneCallback: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Mlog.assistantE("TTS 初始化失败 status=$status")
                notifyUnavailable()
                return@TextToSpeech
            }
            val result = runCatching {
                tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            }.getOrNull()
            val ok = result != null &&
                result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ok) {
                Mlog.assistantE("TTS 缺少中文引擎 setLanguage=$result")
                notifyUnavailable()
                return@TextToSpeech
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    Mlog.assistant("TTS 播报完成")
                    deliverDone()
                }

                override fun onError(utteranceId: String?) {
                    Mlog.assistantE("TTS 播报失败 utteranceId=$utteranceId")
                    deliverDone()
                }
            })
            available = true
            Mlog.assistant("TTS 就绪（中文）")
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!available) {
            // 降级：不播报，直接继续对话流程（回复文本走通知栏）
            onDone()
            return
        }
        doneCallback = onDone
        val id = "assistant_${utteranceIds.incrementAndGet()}"
        val result = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            Mlog.assistantE("TTS speak 返回 ERROR")
            deliverDone()
        }
    }

    override fun stop() {
        doneCallback = null
        runCatching { tts?.stop() }
    }

    override fun shutdown() {
        doneCallback = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun deliverDone() {
        doneCallback?.invoke()
        doneCallback = null
    }

    private fun notifyUnavailable() {
        if (notifiedUnavailable) return
        notifiedUnavailable = true
        onUnavailable("未找到中文 TTS 引擎，回复只显示在通知栏")
    }
}
