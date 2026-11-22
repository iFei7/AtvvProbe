package com.ifei7.murmur.ime.core

import android.os.Handler
import android.os.Looper
import com.ifei7.murmur.ime.MurmurPrefs
import com.ifei7.murmur.ime.core.PcmChunker
import com.ifei7.murmur.ime.audio.PcmSource
import com.ifei7.murmur.ime.audio.PcmSourceType
import com.ifei7.murmur.ime.net.DoubaoAsrClient
import com.ifei7.murmur.ime.net.DoubaoCredentials
import com.ifei7.murmur.ime.util.Mlog

/**
 * 语音输入会话的编排器：状态机 + 音频源 + 豆包 WSS + 文本提交。
 *
 * 线程模型：所有 [start]/[stop]/[cancel] 必须在主线程调用；
 * 音频源与 WebSocket 的回调统一 post 回主线程再处理。
 */
class VoiceController(
    private val prefs: MurmurPrefs,
    private val host: Host,
    private val sourceFactory: (PcmSourceType) -> PcmSource,
) {

    /** 与 IME 交互的最小契约。 */
    interface Host {
        /** 是否处于「输入法激活且当前有输入框焦点」状态。 */
        fun hasInputFocus(): Boolean

        /** 把最终文本提交到当前输入框。 */
        fun commitText(text: String)

        fun onStateChanged(state: VoiceState)

        /** 状态浮层文案（可为实时识别结果）。 */
        fun onStatus(text: String)

        /** 出错提示。 */
        fun onError(text: String)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var state: VoiceState = VoiceState.IDLE
        private set

    private var client: DoubaoAsrClient? = null
    private var source: PcmSource? = null
    private var chunker = PcmChunker()

    @Volatile
    private var latestText: String = ""

    private var sentBytes = 0L

    val currentState: VoiceState get() = state

    /** 会话超时保护：超过这个时长自动收尾，避免忘关麦克风。 */
    private val maxSessionMs = 60_000L

    private val autoStop = Runnable {
        if (state == VoiceState.RECORDING) {
            Mlog.ime("会话超过 ${maxSessionMs}ms，自动停止")
            stop("超时自动停止")
        }
    }

    private val finishRunnable = Runnable { finishNow() }

    /**
     * 语音键按下：待机则开始，录音中则停止（toggle 语义）。
     * 没有输入焦点时直接拒绝——这是产品硬约束。
     */
    fun toggle(reason: String) {
        when (state) {
            VoiceState.IDLE -> start(reason)
            VoiceState.CONNECTING, VoiceState.RECORDING -> stop(reason)
            VoiceState.FINISHING -> Mlog.ime("正在收尾，忽略本次 $reason")
        }
    }

    fun start(reason: String) {
        if (state.isBusy) {
            Mlog.ime("会话进行中（$state），忽略 start($reason)")
            return
        }
        if (!host.hasInputFocus()) {
            // 关键约束：只在输入法激活且拿到 InputConnection 时才响应
            Mlog.ime("无输入焦点，拒绝启动（$reason）")
            host.onStatus("当前没有输入框，已忽略")
            return
        }
        val creds: DoubaoCredentials = prefs.loadCredentials() ?: run {
            Mlog.imeE("未登录豆包，无法启动语音输入")
            host.onError("请先在设置里登录豆包")
            return
        }

        latestText = ""
        sentBytes = 0L
        chunker = PcmChunker()
        setState(VoiceState.CONNECTING)
        host.onStatus("连接豆包…")

        val asr = DoubaoAsrClient(creds, AsrListener())
        client = asr
        asr.connect()
        Mlog.ime("开始语音输入（$reason），音频源=${prefs.sourceType.key}")
    }

    fun stop(reason: String) {
        if (state != VoiceState.RECORDING && state != VoiceState.CONNECTING) return
        Mlog.ime("停止送音频（$reason），已发送 $sentBytes 字节")
        setState(VoiceState.FINISHING)
        handler.removeCallbacks(autoStop)
        runCatching { source?.stop() }
        client?.markStopping()
        host.onStatus("识别中…")
        handler.postDelayed(finishRunnable, prefs.finishGraceMs)
    }

    /** 放弃本次识别，不提交任何文本。 */
    fun cancel(reason: String) {
        if (!state.isBusy) return
        Mlog.ime("取消会话（$reason）")
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(autoStop)
        runCatching { source?.stop() }
        client?.cancel()
        client = null
        latestText = ""
        setState(VoiceState.IDLE)
        host.onStatus(VoiceState.IDLE.label())
    }

    fun release() {
        cancel("service 销毁")
    }

    private fun startSource() {
        val type = prefs.sourceType
        val src = sourceFactory(type)
        source = src
        src.start(object : PcmSource.Listener {
            override fun onPcm(data: ByteArray) {
                if (state != VoiceState.RECORDING) return
                val c = client ?: return
                chunker.accept(data) { chunk ->
                    c.sendPcm(chunk)
                    sentBytes += chunk.size
                }
            }

            override fun onSourceEnded() {
                handler.post {
                    if (state == VoiceState.RECORDING) {
                        Mlog.ime("音频源结束，自动收尾")
                        stop("音频源结束")
                    }
                }
            }

            override fun onSourceError(message: String, error: Throwable?) {
                handler.post {
                    Mlog.imeE("音频源错误: $message", error)
                    host.onError(message)
                    cancel("音频源错误")
                }
            }
        })
        Mlog.ime("音频源已启动: ${src.name}")
    }

    private fun finishNow() {
        if (state == VoiceState.IDLE) return
        handler.removeCallbacks(finishRunnable)
        handler.removeCallbacks(autoStop)
        runCatching { source?.stop() }
        client?.close()
        client = null
        val text = latestText.trim()
        setState(VoiceState.IDLE)
        if (text.isEmpty()) {
            Mlog.ime("本次没有识别到文字")
            host.onStatus("未识别到文字")
        } else {
            Mlog.ime("提交文本 ${text.length} 字")
            host.commitText(text)
            host.onStatus("已输入 ${text.length} 字")
        }
        latestText = ""
    }

    /** 服务端已经给最终结果，直接提交，不用等宽限期。 */
    private fun finishWithText(text: String) {
        if (state == VoiceState.IDLE) return
        latestText = text
        finishNow()
    }

    private fun setState(next: VoiceState) {
        state = next
        host.onStateChanged(next)
    }

    private inner class AsrListener : DoubaoAsrClient.Listener {

        override fun onOpen() {
            handler.post {
                if (state != VoiceState.CONNECTING) return@post
                setState(VoiceState.RECORDING)
                startSource()
                host.onStatus(VoiceState.RECORDING.label())
                handler.postDelayed(autoStop, maxSessionMs)
            }
        }

        override fun onResult(text: String) {
            handler.post {
                if (text.isNotBlank()) {
                    latestText = text
                    host.onStatus(text)
                }
            }
        }

        override fun onFinish(text: String) {
            handler.post {
                if (text.isNotBlank()) latestText = text
                Mlog.ime("收到 finish，提交文本 ${latestText.length} 字")
                finishWithText(latestText)
            }
        }

        override fun onFailure(reason: String, error: Throwable?) {
            handler.post {
                if (state == VoiceState.IDLE) return@post
                Mlog.imeE("ASR 连接失败: $reason", error)
                // 已经拿到过文字就先提交，避免白说
                val text = latestText.trim()
                handler.removeCallbacks(autoStop)
                runCatching { source?.stop() }
                client?.cancel()
                client = null
                setState(VoiceState.IDLE)
                if (text.isNotEmpty()) {
                    host.commitText(text)
                    host.onStatus("网络异常，已输入 ${text.length} 字")
                } else {
                    host.onError("豆包连接失败：$reason")
                }
            }
        }

        override fun onClosed(code: Int, reason: String) {
            handler.post {
                if (state == VoiceState.FINISHING || state == VoiceState.RECORDING) {
                    Mlog.ime("连接被关闭（$code $reason），收尾")
                    finishNow()
                }
            }
        }
    }
}
