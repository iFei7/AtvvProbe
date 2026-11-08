package com.ifei7.murmur.ime

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import com.ifei7.murmur.ime.audio.AudioRecordSource
import com.ifei7.murmur.ime.audio.FilePcmSource
import com.ifei7.murmur.ime.audio.MicProbe
import com.ifei7.murmur.ime.audio.PcmFrameCodec
import com.ifei7.murmur.ime.audio.PcmSource
import com.ifei7.murmur.ime.audio.PcmSourceType
import com.ifei7.murmur.ime.audio.SocketPcmSource
import com.ifei7.murmur.ime.audio.WavWriter
import com.ifei7.murmur.ime.core.FocusPolicy
import com.ifei7.murmur.ime.core.StatusNotifier
import com.ifei7.murmur.ime.core.VoiceController
import com.ifei7.murmur.ime.core.VoiceState
import com.ifei7.murmur.ime.util.Mlog
import java.io.File

/**
 * 语音输入法主体。
 *
 * 设计要点：
 * 1. **不弹软键盘**：`onCreateInputView` 只返回一个 1dp 的透明 View，
 *    目的是让 IME 窗口真实存在（否则系统不会把未消费的按键派发给我们）。
 * 2. **只在有焦点时响应**：`hasFocus` 由 `onStartInput` / `onFinishInput` 维护，
 *    桌面、视频播放等场景没有 InputConnection，按语音键不会有任何反应。
 * 3. **双触发**：IME 自身的按键事件 + 本地 socket 控制命令。
 */
class MurmurImeService : InputMethodService(), VoiceController.Host {

    private lateinit var prefs: MurmurPrefs
    private lateinit var controller: VoiceController
    private lateinit var socketSource: SocketPcmSource
    private lateinit var status: StatusNotifier

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 是否处于「输入法被某个输入框绑定」的状态。 */
    @Volatile
    private var hasFocus = false

    /**
     * 最近一次 onStartInput 拿到的 inputType。
     *
     * 电视桌面（com.leslie.tvhome 等）也会创建一个 `inputType=0`（TYPE_NULL）的
     * InputConnection，说明它只是想要按键、并不是真的文本输入框。为了满足
     * 「桌面上不触发」的产品约束，TYPE_NULL 一律视为没有输入焦点。
     */
    @Volatile
    private var lastInputType = 0

    /** 已经打印过的键码，避免刷屏，同时方便现场发现遥控器语音键的真实键码。 */
    private val seenKeyCodes = HashSet<Int>()

    private var debugReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        prefs = MurmurPrefs(this)
        Mlog.enabled = prefs.verboseLog
        status = StatusNotifier(this)

        createSocketSource()

        controller = VoiceController(prefs, this) { type -> createSource(type) }
        Mlog.ime("服务已创建，socket=${socketSource.name}")

        if (BuildConfig.DEBUG) registerDebugReceiver()
    }

    /** 创建（或重建）socket 监听。 */
    private fun createSocketSource() {
        socketSource = SocketPcmSource(prefs.socketConfig(), prefs.socketToken).apply {
            onCommand = { cmd -> mainHandler.post { handleSocketCommand(cmd) } }
            onClientChanged = { connected ->
                mainHandler.post {
                    status.show(if (connected) "已连接 daemon" else "daemon 已断开")
                }
            }
            startServer()
        }
    }

    private fun rebuildSocketSource() {
        runCatching { socketSource.stopServer() }
        createSocketSource()
        Mlog.ime("socket 已重建：${socketSource.name}")
    }

    /**
     * 调试通道（仅 debug 包注册），用于在没有 daemon / 没有音频的情况下验证：
     * - 输入法是否真的拿到了 InputConnection（commitText 是否生效）
     * - socket 协议是否能正常握手、回状态
     */
    private fun registerDebugReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_DEBUG_COMMIT)
            addAction(ACTION_DEBUG_SOCKET)
            addAction(ACTION_DEBUG_MIC_RECORD)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cmd = intent ?: return
                when (cmd.action) {
                    ACTION_DEBUG_COMMIT -> {
                        val text = cmd.getStringExtra("text").orEmpty()
                        Mlog.ime("DEBUG 提交文本: ${text.length} 字, 有焦点=${hasInputFocus()}")
                        if (hasInputFocus()) commitText(text) else status.show("没有输入焦点，忽略")
                    }
                    ACTION_DEBUG_SOCKET -> {
                        val mode = cmd.getStringExtra("mode")?.lowercase()
                        if (mode == "tcp") {
                            prefs.socketUseUnix = false
                        } else if (mode == "unix") {
                            prefs.socketUseUnix = true
                        }
                        cmd.getStringExtra("port")?.toIntOrNull()?.let { prefs.socketTcpPort = it }
                        rebuildSocketSource()
                        status.show("socket 已切到 ${socketSource.name}")
                    }
                    ACTION_DEBUG_MIC_RECORD -> handleDebugMicRecord(cmd)
                }
            }
        }
        debugReceiver = receiver
        registerReceiver(receiver, filter)
        Mlog.ime("调试广播已注册（仅 debug）")
    }

    /**
     * 录 N 秒麦克风并存成 wav（仅 debug 广播触发），用于 adb 远程录音：
     * 用户对着麦克风说话时不用碰 UI。录完 logcat 打出路径，`adb pull` 即可试听。
     */
    private fun handleDebugMicRecord(intent: Intent) {
        val seconds = intent.getIntExtra("seconds", DEFAULT_DEBUG_RECORD_SECONDS)
            .coerceIn(1, 60)
        status.show("调试录音 ${seconds}s，请说话…")
        Thread({
            val outcome = runCatching { MicProbe.record(seconds * 1000) }
            outcome.onSuccess { r ->
                val file = File(getExternalFilesDir(null), DEBUG_WAV_NAME)
                val result = runCatching {
                    val written = WavWriter.write(file, r.pcm)
                    "已保存 ${file.absolutePath} (${written / 1024}KB), rms=${"%.1f".format(r.rms)}, peak=${r.peak}"
                }.getOrElse { "保存失败：${it.message}" }
                Mlog.ime("DEBUG_MIC_RECORD: $result")
                mainHandler.post { status.show("录音完成，可 adb pull") }
            }.onFailure { e ->
                Mlog.imeE("DEBUG_MIC_RECORD 录音失败", e)
                mainHandler.post { status.show("调试录音失败：${e.message}") }
            }
        }, "murmur-debug-rec").start()
    }

    private fun createSource(type: PcmSourceType): PcmSource = when (type) {        PcmSourceType.SOCKET -> socketSource
        PcmSourceType.FILE -> {
            val configured = prefs.testPcmPath
            val liveFile = File(LIVE_PCM_PATH)
            when {
                configured.isNotBlank() -> FilePcmSource(File(configured))
                // daemon 直接往 /data/local/tmp 写的场景：文件需要 chmod 644 才能被 App 读
                liveFile.canRead() -> {
                    Mlog.ime("文件源使用 ${liveFile.absolutePath}（tail 模式）")
                    FilePcmSource(liveFile, tail = true)
                }
                else -> FilePcmSource(File(filesDir, "murmur_test.pcm"))
            }
        }
        PcmSourceType.MIC -> AudioRecordSource()
    }

    private fun handleSocketCommand(cmd: Int) {
        when (cmd) {
            PcmFrameCodec.CMD_START -> {
                if (!hasInputFocus()) {
                    // 产品硬约束：没有输入框就不打扰
                    Mlog.ime("socket 收到 START，但当前无输入焦点，拒绝")
                    socketSource.sendState(PcmFrameCodec.STATE_REJECTED)
                    return
                }
                controller.start("socket")
            }
            PcmFrameCodec.CMD_STOP -> {
                if (controller.currentState == VoiceState.RECORDING ||
                    controller.currentState == VoiceState.CONNECTING
                ) {
                    controller.stop("socket")
                }
            }
            PcmFrameCodec.CMD_CANCEL -> controller.cancel("socket")
            SocketPcmSource.COMMAND_HELLO -> status.show("daemon 已接入")
            else -> Mlog.ime("未知 socket 命令: $cmd")
        }
        socketSource.sendState(controller.currentState.wireCode())
    }

    // ---------------- IME 生命周期 ----------------

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        hasFocus = true
        lastInputType = attribute?.inputType ?: 0
        Mlog.ime(
            "onStartInput: pkg=${attribute?.packageName} field=${attribute?.fieldId} " +
                "inputType=$lastInputType 可输入=${lastInputType != 0}",
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hasFocus = true
    }

    override fun onFinishInput() {
        hasFocus = false
        lastInputType = 0
        Mlog.ime("onFinishInput")
        controller.cancel("输入焦点丢失")
        super.onFinishInput()
    }

    override fun onUnbindInput() {
        hasFocus = false
        controller.cancel("input unbind")
        super.onUnbindInput()
    }

    override fun onDestroy() {
        controller.release()
        socketSource.stopServer()
        status.cancel()
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        debugReceiver = null
        Mlog.ime("服务已销毁")
        super.onDestroy()
    }

    /**
     * 不提供软键盘，只给一个 1dp 的透明 View 撑起 IME 窗口。
     */
    override fun onCreateInputView(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            minimumHeight = 1
        }
    }

    override fun onEvaluateInputViewShown(): Boolean = true

    // ---------------- 按键触发 ----------------

    @SuppressLint("NewApi")
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (seenKeyCodes.add(keyCode)) {
            Mlog.ime("检测到键码 $keyCode（${keyCodeName(keyCode)}）hasFocus=$hasFocus")
        }
        if (keyCode in prefs.triggerKeyCodes) {
            if (event?.repeatCount == 0) {
                Mlog.ime("语音键触发（keyCode=$keyCode）")
                if (hasInputFocus()) {
                    controller.toggle("key:$keyCode")
                } else {
                    Mlog.ime("无输入焦点（inputType=$lastInputType），忽略语音键")
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in prefs.triggerKeyCodes) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun keyCodeName(keyCode: Int): String =
        runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrDefault("KEYCODE_$keyCode")

    // ---------------- VoiceController.Host ----------------

    override fun hasInputFocus(): Boolean =
        hasFocus && FocusPolicy.canAcceptVoiceInput(lastInputType, currentInputConnection != null)

    override fun commitText(text: String) {
        val ic: InputConnection = currentInputConnection ?: run {
            Mlog.imeE("提交失败：当前没有 InputConnection")
            return
        }
        runCatching {
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            ic.endBatchEdit()
        }.onFailure { Mlog.imeE("commitText 失败", it) }
    }

    override fun onStateChanged(state: VoiceState) {
        Mlog.ime("状态 -> ${state.name}")
        socketSource.sendState(state.wireCode())
    }

    override fun onStatus(text: String) {
        status.show(text)
    }

    override fun onError(text: String) {
        lastError = text
        status.show(text)
    }

    private var lastError: String = ""

    companion object {
        /** daemon 侧约定路径（需要 chmod 644 才能被 App 读取）。 */
        const val LIVE_PCM_PATH = "/data/local/tmp/murmur.pcm"

        const val ACTION_DEBUG_COMMIT = "com.ifei7.murmur.ime.DEBUG_COMMIT"
        const val ACTION_DEBUG_SOCKET = "com.ifei7.murmur.ime.DEBUG_SOCKET"
        const val ACTION_DEBUG_MIC_RECORD = "com.ifei7.murmur.ime.DEBUG_MIC_RECORD"

        /** DEBUG_MIC_RECORD 的 extra：录音秒数（1~60，默认 5）。 */
        const val DEFAULT_DEBUG_RECORD_SECONDS = 5

        /** 调试录音落盘文件名（App 专属外部目录下）。 */
        const val DEBUG_WAV_NAME = "murmur_debug.wav"
    }
}
