package com.ifei7.murmur.ime.assistant

import com.ifei7.murmur.ime.audio.PcmSource
import com.ifei7.murmur.ime.core.PcmChunker
import com.ifei7.murmur.ime.net.DoubaoCredentials
import com.ifei7.murmur.ime.util.Mlog

/**
 * 语音助手会话状态机。
 *
 * 与 [com.ifei7.murmur.ime.core.VoiceState] 的差别：
 * - 没有「提交文本到输入框」这一步，最终识别文本交给 LLM；
 * - 多出 THINKING / SPEAKING 两态（LLM 请求、TTS 播报）；
 * - TTS 播报完自动开启下一轮聆听（连续对话），直到用户停止或超时。
 */
enum class AssistantState(val label: String) {
    /** 待机：等待触发。 */
    IDLE("待机"),

    /** 已触发，正在连豆包 WSS。 */
    CONNECTING("连接豆包…"),

    /** 正在录音 / 送音频。 */
    RECORDING("正在聆听…"),

    /** 已停止送音频，等服务端最终识别结果。 */
    FINISHING("识别中…"),

    /** 拿到最终文本，正在请求 LLM。 */
    THINKING("思考中…"),

    /** LLM 回复到位，正在 TTS 播报。 */
    SPEAKING("正在回答…"),
    ;

    val isBusy: Boolean get() = this != IDLE
}

/**
 * 可注入的调度器：主线程 post / 延时任务。
 * 生产环境用 [MainScheduler]；单测注入同步假实现来驱动超时 / 宽限逻辑。
 */
interface Scheduler {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, delayMs: Long)
    fun remove(r: Runnable)
}

class MainScheduler : Scheduler {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun post(r: Runnable) {
        handler.post(r)
    }

    override fun postDelayed(r: Runnable, delayMs: Long) {
        handler.postDelayed(r, delayMs)
    }

    override fun remove(r: Runnable) {
        handler.removeCallbacks(r)
    }
}

/**
 * ASR 会话的最小契约：把 [com.ifei7.murmur.ime.net.DoubaoAsrClient]
 * 从控制器里解耦出来，单测可以用假实现驱动状态机。
 */
interface AsrSession {
    fun connect()
    fun sendPcm(data: ByteArray)
    fun markStopping()
    fun close()
    fun cancel()
}

/** ASR 事件回调（控制器内部会自行切回主线程）。 */
interface AsrListener {
    fun onOpen()
    fun onResult(text: String)
    fun onFinish(text: String)
    fun onFailure(reason: String, error: Throwable?)
    fun onClosed(code: Int, reason: String)
}

/**
 * 语音助手编排器：状态机 + 音频源 + 豆包 WSS + LLM + TTS。
 *
 * 与 VoiceController 的关键差异：
 * - 没有 hasInputFocus 门禁（助手不依赖输入框）；
 * - 识别终点不是 commitText，而是 onFinalText → LLM → onAssistantReply → TTS；
 * - TTS 播报完自动开下一轮（连续对话）。
 *
 * 线程模型：所有公开方法必须在主线程调用；ASR / LLM / TTS 的回调统一
 * post 回主线程再处理。异步回调（LLM / TTS / 延时任务）用回合号 [gen]
 * 防串：用户中途停止后，迟到的回调一律丢弃。
 */
class AssistantController(
    private val credentialsProvider: () -> DoubaoCredentials?,
    private val finishGraceMs: Long,
    private val host: Host,
    private val asrFactory: (DoubaoCredentials, AsrListener) -> AsrSession,
    private val sourceFactory: () -> PcmSource,
    private val llm: AssistantLlm,
    private val speaker: AssistantSpeaker,
    private val scheduler: Scheduler,
    /** 单轮聆听超时保护：超过这个时长自动收尾，避免忘关麦克风。 */
    private val maxSessionMs: Long = 60_000L,
) {

    /** 与前台服务交互的最小契约。 */
    interface Host {
        /** 状态变化（更新通知栏）。 */
        fun onStateChanged(state: AssistantState)

        /** 状态浮层 / 通知栏文案。 */
        fun onStatus(text: String)

        /** LLM 回复文本（通知栏展示；TTS 不可用时这是唯一出口）。 */
        fun onAssistantReply(text: String)

        /** 出错提示（Toast，不崩溃）。 */
        fun onError(text: String)
    }

    private var state: AssistantState = AssistantState.IDLE
    val currentState: AssistantState get() = state

    private var asr: AsrSession? = null
    private var source: PcmSource? = null
    private var chunker = PcmChunker()

    /** 回合号：每次开新一轮 / 回到待机时 +1，用来丢弃迟到的异步回调。 */
    private var gen = 0

    @Volatile
    private var latestText: String = ""

    private var sentBytes = 0L

    private val autoStop = Runnable {
        if (state == AssistantState.RECORDING) {
            Mlog.assistant("聆听超过 ${maxSessionMs}ms，自动收尾")
            if (latestText.isNotBlank()) {
                handleFinalText()
            } else {
                host.onStatus("超时，未识别到文字")
                cancel("超时")
            }
        }
    }

    private val finishRunnable = Runnable { handleFinalText() }

    /** 开始一轮新对话（待机时响应用户触发）。 */
    fun start(reason: String) {
        if (state.isBusy) {
            Mlog.assistant("会话进行中（$state），忽略 start($reason)")
            return
        }
        beginTurn(reason)
    }

    /**
     * 用户主动停止（通知按钮）：
     * 聆听中 → 走收尾流程，已说出的内容仍然交给 LLM；
     * 思考 / 播报中 → 直接终止。
     */
    fun stop(reason: String) {
        when (state) {
            AssistantState.RECORDING, AssistantState.CONNECTING -> {
                Mlog.assistant("停止聆听（$reason），已发送 $sentBytes 字节")
                setState(AssistantState.FINISHING)
                host.onStatus(AssistantState.FINISHING.label)
                scheduler.remove(autoStop)
                stopSource()
                asr?.markStopping()
                scheduler.postDelayed(finishRunnable, finishGraceMs)
            }
            AssistantState.FINISHING -> Mlog.assistant("已在收尾，忽略 stop($reason)")
            AssistantState.THINKING, AssistantState.SPEAKING -> {
                Mlog.assistant("终止 $state（$reason）")
                cancel(reason)
            }
            AssistantState.IDLE -> Mlog.assistant("待机中，忽略 stop($reason)")
        }
    }

    /** 放弃整轮对话，回到待机。 */
    fun cancel(reason: String) {
        gen++
        scheduler.remove(autoStop)
        scheduler.remove(finishRunnable)
        stopSource()
        runCatching { asr?.cancel() }
        asr = null
        speaker.stop()
        latestText = ""
        setState(AssistantState.IDLE)
        host.onStatus(AssistantState.IDLE.label)
        Mlog.assistant("已取消（$reason）")
    }

    fun release() {
        cancel("service 销毁")
    }

    // ---------------- 内部流程 ----------------

    private fun beginTurn(reason: String) {
        gen++
        scheduler.remove(autoStop)
        scheduler.remove(finishRunnable)
        stopSource()
        runCatching { asr?.cancel() }
        asr = null
        latestText = ""
        sentBytes = 0L
        chunker = PcmChunker()

        val creds: DoubaoCredentials = credentialsProvider() ?: run {
            setState(AssistantState.IDLE)
            host.onStatus(AssistantState.IDLE.label)
            Mlog.assistantE("未登录豆包，无法启动助手")
            host.onError("请先在设置里登录豆包")
            return
        }

        setState(AssistantState.CONNECTING)
        host.onStatus(AssistantState.CONNECTING.label)
        asr = asrFactory(creds, AsrCb()).also { it.connect() }
        Mlog.assistant("开始新一轮对话（$reason）")
    }

    private fun startSource() {
        val src = sourceFactory()
        source = src
        src.start(object : PcmSource.Listener {
            override fun onPcm(data: ByteArray) {
                if (state != AssistantState.RECORDING) return
                val a = asr ?: return
                chunker.accept(data) { chunk ->
                    a.sendPcm(chunk)
                    sentBytes += chunk.size
                }
            }

            override fun onSourceEnded() {
                scheduler.post {
                    if (state == AssistantState.RECORDING) {
                        Mlog.assistant("音频源结束，自动收尾")
                        stop("音频源结束")
                    }
                }
            }

            override fun onSourceError(message: String, error: Throwable?) {
                scheduler.post {
                    Mlog.assistantE("音频源错误: $message", error)
                    host.onError(message)
                    cancel("音频源错误")
                }
            }
        })
        Mlog.assistant("音频源已启动: ${src.name}")
    }

    private fun stopSource() {
        runCatching { source?.stop() }
        source = null
    }

    /** 拿到最终识别文本（finish / 宽限期到）：交给 LLM。 */
    private fun handleFinalText() {
        if (state == AssistantState.IDLE || state == AssistantState.THINKING ||
            state == AssistantState.SPEAKING
        ) return
        scheduler.remove(finishRunnable)
        scheduler.remove(autoStop)
        stopSource()
        runCatching { asr?.close() }
        asr = null

        val text = latestText.trim()
        latestText = ""
        if (text.isEmpty()) {
            Mlog.assistant("本轮没有识别到文字")
            setState(AssistantState.IDLE)
            host.onStatus("未识别到文字")
            return
        }
        askLlm(text)
    }

    private fun askLlm(text: String) {
        val g = gen
        setState(AssistantState.THINKING)
        host.onStatus(AssistantState.THINKING.label)
        Mlog.assistant("最终文本 ${text.length} 字，请求 LLM")
        llm.ask(
            text,
            onReply = { reply -> scheduler.post { if (gen == g && state == AssistantState.THINKING) speakReply(reply) } },
            onError = { message -> scheduler.post { if (gen == g && state.isBusy) failTurn("助手请求失败：$message") } },
        )
    }

    private fun speakReply(reply: String) {
        val g = gen
        host.onAssistantReply(reply)
        setState(AssistantState.SPEAKING)
        host.onStatus(AssistantState.SPEAKING.label)
        Mlog.assistant("收到回复 ${reply.length} 字，开始播报")
        speaker.speak(reply) {
            scheduler.post {
                if (gen != g || state != AssistantState.SPEAKING) return@post
                host.onStatus("回复完成，继续聆听")
                beginTurn("连续对话")
            }
        }
    }

    private fun failTurn(message: String) {
        gen++
        stopSource()
        speaker.stop()
        setState(AssistantState.IDLE)
        Mlog.assistantE(message)
        host.onError(message)
        host.onStatus(message)
    }

    private fun setState(next: AssistantState) {
        state = next
        host.onStateChanged(next)
    }

    private inner class AsrCb : AsrListener {

        override fun onOpen() {
            scheduler.post {
                if (state != AssistantState.CONNECTING) return@post
                setState(AssistantState.RECORDING)
                startSource()
                host.onStatus(AssistantState.RECORDING.label)
                scheduler.postDelayed(autoStop, maxSessionMs)
            }
        }

        override fun onResult(text: String) {
            scheduler.post {
                if (text.isNotBlank()) {
                    latestText = text
                    host.onStatus(text)
                }
            }
        }

        override fun onFinish(text: String) {
            scheduler.post {
                if (text.isNotBlank()) latestText = text
                Mlog.assistant("收到 finish，最终文本 ${latestText.length} 字")
                handleFinalText()
            }
        }

        override fun onFailure(reason: String, error: Throwable?) {
            scheduler.post {
                if (state == AssistantState.IDLE || state == AssistantState.THINKING ||
                    state == AssistantState.SPEAKING
                ) return@post
                Mlog.assistantE("ASR 连接失败: $reason", error)
                // 已经拿到过文字就继续问 LLM，避免白说
                if (latestText.isNotBlank()) {
                    handleFinalText()
                } else {
                    failTurn("豆包连接失败：$reason")
                }
            }
        }

        override fun onClosed(code: Int, reason: String) {
            scheduler.post {
                if (state == AssistantState.RECORDING || state == AssistantState.FINISHING) {
                    Mlog.assistant("连接被关闭（$code $reason），收尾")
                    if (latestText.isNotBlank()) handleFinalText() else {
                        scheduler.remove(finishRunnable)
                        setState(AssistantState.IDLE)
                        host.onStatus("未识别到文字")
                    }
                }
            }
        }
    }
}
