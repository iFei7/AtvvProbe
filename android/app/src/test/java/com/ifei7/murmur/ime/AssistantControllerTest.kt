package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.assistant.AssistantController
import com.ifei7.murmur.ime.assistant.AssistantLlm
import com.ifei7.murmur.ime.assistant.AssistantSpeaker
import com.ifei7.murmur.ime.assistant.AssistantState
import com.ifei7.murmur.ime.assistant.AsrListener
import com.ifei7.murmur.ime.assistant.AsrSession
import com.ifei7.murmur.ime.assistant.Scheduler
import com.ifei7.murmur.ime.audio.PcmSource
import com.ifei7.murmur.ime.net.DoubaoCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 助手状态机纯逻辑测试：不依赖网络 / Android Handler，
 * 用假 ASR / 音频源 / LLM / TTS + 同步调度器驱动全部转换。
 */
class AssistantControllerTest {

    // ---------------- 假实现 ----------------

    /** 同步调度器：post 立即执行；postDelayed 存起来由测试手动触发。 */
    private class FakeScheduler : Scheduler {
        val delayed = ArrayList<Pair<Runnable, Long>>()

        override fun post(r: Runnable) = r.run()

        override fun postDelayed(r: Runnable, delayMs: Long) {
            delayed.add(r to delayMs)
        }

        override fun remove(r: Runnable) {
            delayed.removeAll { it.first === r }
        }

        /** 触发所有挂起的延时任务（含期间新产生的），返回触发次数。 */
        fun runDelayed(): Int {
            var count = 0
            while (delayed.isNotEmpty()) {
                val (r, _) = delayed.removeAt(0)
                r.run()
                count++
            }
            return count
        }
    }

    private class FakeAsr : AsrSession {
        var connected = false
        var closed = false
        var cancelled = false
        var stopped = false
        val sent = ArrayList<ByteArray>()

        override fun connect() {
            connected = true
        }

        override fun sendPcm(data: ByteArray) {
            sent.add(data)
        }

        override fun markStopping() {
            stopped = true
        }

        override fun close() {
            closed = true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeSource : PcmSource {
        var started = false
        var stopped = false
        var listener: PcmSource.Listener? = null

        override val name: String = "fake"

        override fun start(listener: PcmSource.Listener) {
            started = true
            this.listener = listener
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            stopped = true
        }
    }

    /** 可编程的假 LLM：ask 的回调由测试手动触发。 */
    private class FakeLlm : AssistantLlm {
        val asked = ArrayList<String>()
        var onReply: ((String) -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        override fun ask(userText: String, onReply: (String) -> Unit, onError: (String) -> Unit) {
            asked.add(userText)
            this.onReply = onReply
            this.onError = onError
        }
    }

    private class FakeSpeaker : AssistantSpeaker {
        val spoken = ArrayList<String>()
        var onDone: (() -> Unit)? = null

        override fun speak(text: String, onDone: () -> Unit) {
            spoken.add(text)
            this.onDone = onDone
        }

        override fun stop() {
            onDone = null
        }

        override fun shutdown() {
            onDone = null
        }
    }

    private class FakeHost : AssistantController.Host {
        val states = ArrayList<AssistantState>()
        val statuses = ArrayList<String>()
        val replies = ArrayList<String>()
        val errors = ArrayList<String>()

        override fun onStateChanged(state: AssistantState) {
            states.add(state)
        }

        override fun onStatus(text: String) {
            statuses.add(text)
        }

        override fun onAssistantReply(text: String) {
            replies.add(text)
        }

        override fun onError(text: String) {
            errors.add(text)
        }
    }

    // ---------------- 夹具 ----------------

    private val scheduler = FakeScheduler()
    private val host = FakeHost()
    private val asr = FakeAsr()
    private val source = FakeSource()
    private val llm = FakeLlm()
    private val speaker = FakeSpeaker()

    private var hasCredentials = true

    private val creds = DoubaoCredentials(
        cookie = "sessionid=x",
        deviceId = "1234567",
        webId = "7654321",
        teaUuid = "uuid",
        userAgent = "ua",
        savedAt = 0L,
    )

    private lateinit var controller: AssistantController

    /** ASR 事件入口（工厂里捕获的那个 listener）。 */
    private var asrListener: AsrListener? = null

    @Before
    fun setUp() {
        controller = AssistantController(
            credentialsProvider = { if (hasCredentials) creds else null },
            finishGraceMs = 1200L,
            host = host,
            asrFactory = { _, listener ->
                asrListener = listener
                asr
            },
            sourceFactory = { source },
            llm = llm,
            speaker = speaker,
            scheduler = scheduler,
            maxSessionMs = 60_000L,
        )
    }

    /** 驱动到 RECORDING：start → onOpen。 */
    private fun driveToRecording() {
        controller.start("test")
        assertEquals(AssistantState.CONNECTING, controller.currentState)
        asrListener?.onOpen()
        assertEquals(AssistantState.RECORDING, controller.currentState)
        assertTrue(source.started)
    }

    // ---------------- 用例 ----------------

    @Test
    fun `无凭证时启动被拒绝并停留待机`() {
        hasCredentials = false
        controller.start("test")
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertEquals(1, host.errors.size)
        assertTrue(host.errors[0].contains("登录"))
        assertNull(asrListener)
    }

    @Test
    fun `start 到 CONNECTING 再到 RECORDING 且启动音频源`() {
        driveToRecording()
        assertEquals(AssistantState.RECORDING, controller.currentState)
        assertEquals(listOf("连接豆包…", "正在聆听…"), host.statuses)
        assertTrue(asr.connected)
    }

    @Test
    fun `忙碌时再次 start 被忽略`() {
        driveToRecording()
        val before = host.states.size
        controller.start("again")
        assertEquals(AssistantState.RECORDING, controller.currentState)
        assertEquals(before, host.states.size)
    }

    @Test
    fun `final 文本触发 THINKING 再到 SPEAKING 并回调回复`() {
        driveToRecording()
        asrListener?.onFinish("今天天气怎么样")
        assertEquals(AssistantState.THINKING, controller.currentState)
        assertEquals(listOf("今天天气怎么样"), llm.asked)
        assertTrue(asr.closed)
        assertTrue(source.stopped)

        llm.onReply?.invoke("今天是晴天")
        assertEquals(AssistantState.SPEAKING, controller.currentState)
        assertEquals(listOf("今天是晴天"), host.replies)
        assertEquals(listOf("今天是晴天"), speaker.spoken)

        speaker.onDone?.invoke()
        // TTS 播报完自动开下一轮（连续对话）
        assertEquals(AssistantState.CONNECTING, controller.currentState)
    }

    @Test
    fun `LLM 出错回到待机并提示错误`() {
        driveToRecording()
        asrListener?.onFinish("你好")
        llm.onError?.invoke("HTTP 401")
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertEquals(1, host.errors.size)
        assertTrue(host.errors[0].contains("401"))
    }

    @Test
    fun `空文本 finish 回到待机且不请求 LLM`() {
        driveToRecording()
        asrListener?.onFinish("")
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertTrue(llm.asked.isEmpty())
    }

    @Test
    fun `录音中 stop 进入 FINISHING 宽限后带着部分文本进 THINKING`() {
        driveToRecording()
        asrListener?.onResult("帮我打开")
        controller.stop("通知")
        assertEquals(AssistantState.FINISHING, controller.currentState)
        assertTrue(asr.stopped)
        assertTrue(source.stopped)

        scheduler.runDelayed()
        assertEquals(AssistantState.THINKING, controller.currentState)
        assertEquals(listOf("帮我打开"), llm.asked)
    }

    @Test
    fun `录音中 stop 且无文本时宽限后回待机`() {
        driveToRecording()
        controller.stop("通知")
        assertEquals(AssistantState.FINISHING, controller.currentState)
        scheduler.runDelayed()
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertTrue(llm.asked.isEmpty())
    }

    @Test
    fun `finish 提前于宽限期时直接进入 THINKING`() {
        driveToRecording()
        controller.stop("通知")
        assertEquals(AssistantState.FINISHING, controller.currentState)
        asrListener?.onFinish("你好呀")
        assertEquals(AssistantState.THINKING, controller.currentState)
        // 宽限任务已被取消，之后触发不影响状态
        scheduler.runDelayed()
        assertEquals(AssistantState.THINKING, controller.currentState)
    }

    @Test
    fun `录音超时无文本自动回待机`() {
        driveToRecording()
        val fired = scheduler.runDelayed()
        assertEquals(1, fired) // 只有 autoStop
        assertEquals(AssistantState.IDLE, controller.currentState)
    }

    @Test
    fun `录音超时有部分文本则继续走 LLM`() {
        driveToRecording()
        asrListener?.onResult("现在几点")
        scheduler.runDelayed()
        assertEquals(AssistantState.THINKING, controller.currentState)
        assertEquals(listOf("现在几点"), llm.asked)
    }

    @Test
    fun `THINKING 中 stop 后迟到的 LLM 回复被丢弃`() {
        driveToRecording()
        asrListener?.onFinish("你好")
        assertEquals(AssistantState.THINKING, controller.currentState)
        controller.stop("通知")
        assertEquals(AssistantState.IDLE, controller.currentState)

        llm.onReply?.invoke("迟到的回复")
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertTrue(host.replies.isEmpty())
    }

    @Test
    fun `SPEAKING 中 stop 终止播报且回调失效`() {
        driveToRecording()
        asrListener?.onFinish("你好")
        llm.onReply?.invoke("回复")
        assertEquals(AssistantState.SPEAKING, controller.currentState)
        controller.stop("通知")
        assertEquals(AssistantState.IDLE, controller.currentState)

        speaker.onDone?.invoke()
        assertEquals(AssistantState.IDLE, controller.currentState)
    }

    @Test
    fun `ASR 失败时已有部分文本则继续走 LLM`() {
        driveToRecording()
        asrListener?.onResult("打开电视")
        asrListener?.onFailure("连接被重置", null)
        assertEquals(AssistantState.THINKING, controller.currentState)
        assertEquals(listOf("打开电视"), llm.asked)
    }

    @Test
    fun `ASR 失败且无文本回待机并报错`() {
        driveToRecording()
        asrListener?.onFailure("握手失败", null)
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertEquals(1, host.errors.size)
    }

    @Test
    fun `cancel 从任意状态回待机`() {
        driveToRecording()
        controller.cancel("测试")
        assertEquals(AssistantState.IDLE, controller.currentState)
        assertTrue(asr.cancelled)
        assertTrue(source.stopped)
    }

    @Test
    fun `音频PCM只在该录音状态下转发给ASR`() {
        driveToRecording()
        val pcm = ByteArray(3200) // 恰好一个 chunk
        source.listener?.onPcm(pcm)
        assertEquals(1, asr.sent.size)

        // 收尾后不再转发
        asrListener?.onFinish("你好")
        source.listener?.onPcm(pcm)
        assertEquals(1, asr.sent.size)
    }
}
