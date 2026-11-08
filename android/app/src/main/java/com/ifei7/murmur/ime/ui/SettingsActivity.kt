package com.ifei7.murmur.ime.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ifei7.murmur.ime.BuildConfig
import com.ifei7.murmur.ime.MurmurPrefs
import com.ifei7.murmur.ime.audio.MicProbe
import com.ifei7.murmur.ime.audio.PcmSourceType
import com.ifei7.murmur.ime.audio.WavReader
import com.ifei7.murmur.ime.audio.WavWriter
import com.ifei7.murmur.ime.bluetooth.RemoteVoiceTrigger
import com.ifei7.murmur.ime.util.Mlog
import java.io.File

/**
 * 设置页：输入法开关引导、豆包登录、音频源选择、测试音频导入、自测识别。
 *
 * TV 上没有触摸，全部控件都必须能被遥控器 D-pad 聚焦操作（Button / RadioButton / CheckBox 默认可以）。
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: MurmurPrefs
    private lateinit var statusView: TextView
    private lateinit var socketView: TextView
    private lateinit var resultView: TextView
    private lateinit var micResultView: TextView
    private lateinit var triggerLogView: TextView
    private lateinit var selfTest: SelfTestRunner
    private lateinit var root: LinearLayout

    /** 正在执行的遥控器语音使能（同一时间只允许一个）。 */
    private var remoteTrigger: RemoteVoiceTrigger? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MurmurPrefs(this)
        selfTest = SelfTestRunner(prefs)

        // 电视盒子上主题的 windowBackground 不一定生效，这里显式给一个浅色底，
        // 否则深色文字会落在深色窗口上，看不见。
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF7F8FA.toInt()) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        buildUi()
        // 默认音频源就是麦克风，首次进来就把权限要了
        if (prefs.sourceType == PcmSourceType.MIC) ensureMicPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        selfTest.cancel()
        remoteTrigger?.cancel()
        remoteTrigger = null
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        root.removeAllViews()

        root.addView(title("Murmur 语音输入法"))
        root.addView(
            TextView(this).apply {
                text = "版本 ${BuildConfig.VERSION_NAME} · 只在输入框内响应遥控器语音键"
                setTextColor(0xFF6B7280.toInt())
                textSize = 14f
            },
        )

        statusView = TextView(this).apply {
            setPadding(0, dp(16), 0, dp(16))
            textSize = 16f
            setTextColor(0xFF111827.toInt())
        }
        root.addView(statusView)

        // 1) 输入法启用
        root.addView(section("① 启用输入法"))
        root.addView(
            button("打开系统输入法设置") {
                startActivitySafely(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        )
        root.addView(
            button("切换输入法") {
                val imm = getSystemService(InputMethodManager::class.java)
                imm?.showInputMethodPicker()
            },
        )

        // 2) 豆包登录
        root.addView(section("② 登录豆包"))
        root.addView(
            button("登录豆包（网页登录）") {
                startActivity(Intent(this, LoginActivity::class.java))
            },
        )
        root.addView(
            button("清除登录状态") {
                prefs.clearCredentials()
                refreshStatus()
                toast("已清除")
            },
        )

        // 3) 音频源
        root.addView(section("③ 音频源"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        PcmSourceType.entries.forEach { type ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = type.label
                isChecked = prefs.sourceType == type
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        prefs.sourceType = type
                        if (type == PcmSourceType.MIC) ensureMicPermission()
                        refreshStatus()
                    }
                }
            }
            group.addView(rb)
        }
        root.addView(group)

        root.addView(
            button("麦克风自检（录 3 秒）") {
                ensureMicPermission()
                runMicProbe()
            },
        )
        micResultView = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            text = "插上 USB 麦克风后点上面的按钮，对着它说话，能看到 RMS / 峰值"
        }
        root.addView(micResultView)

        // 4) 测试音频
        root.addView(section("④ 测试音频（联调用）"))
        root.addView(
            TextView(this).apply {
                text = "把 16kHz / 单声道 / 16bit 的 wav 放到 files/murmur_test.wav：\n" +
                    "adb push test.wav /data/local/tmp/ && " +
                    "adb shell run-as ${packageName} cp /data/local/tmp/test.wav files/"
                setTextColor(0xFF6B7280.toInt())
                textSize = 13f
            },
        )
        root.addView(
            button("加载 files/murmur_test.wav") {
                loadTestAudio(File(filesDir, "murmur_test.wav"))
            },
        )
        root.addView(
            button("从文件选择器导入") {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "audio/*" }
                runCatching { startActivityForResult(intent, REQ_PICK_AUDIO) }
                    .onFailure { toast("这台设备没有文件选择器，请用 adb push") }
            },
        )
        root.addView(
            button("自测识别（不走输入法）") {
                runSelfTest()
            },
        )
        resultView = TextView(this).apply {
            setPadding(0, dp(12), 0, dp(8))
            textSize = 16f
            setTextColor(0xFF111827.toInt())
            text = "识别结果会显示在这里"
        }
        root.addView(resultView)

        // 5) socket 调试
        root.addView(section("⑤ daemon socket"))
        socketView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(socketView)
        root.addView(
            button("切换 Unix / TCP 模式") {
                prefs.socketUseUnix = !prefs.socketUseUnix
                refreshStatus()
                toast("重启输入法后生效")
            },
        )
        root.addView(
            button("重新生成握手 token") {
                prefs.regenerateSocketToken()
                refreshStatus()
                toast("token 已更新，记得同步 daemon")
            },
        )

        // 6) 遥控器语音使能（实验）
        root.addView(section("⑥ 遥控器语音使能（实验）"))
        root.addView(
            TextView(this).apply {
                text = "用标准 BLE GATT 给已配对的语音遥控器发「语音开始」命令序列。\n" +
                    "点按钮后请立刻连续按遥控器任意键保持唤醒（它的应答会晚一拍，\n" +
                    "空闲睡眠时完全不回），序列发完后按语音键看遥控器亮不亮。\n" +
                    "每步结果见下方日志（logcat tag: RemoteTrigger）"
                setTextColor(0xFF6B7280.toInt())
                textSize = 13f
            },
        )
        root.addView(
            button("发送语音开始命令序列") {
                startRemoteTrigger()
            },
        )
        triggerLogView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF374151.toInt())
            setPadding(0, dp(8), 0, dp(8))
            text = "还没有执行过"
        }
        root.addView(triggerLogView)

        // 7) 调试
        root.addView(section("⑦ 调试"))
        root.addView(
            CheckBox(this).apply {
                text = "输出详细日志（不包含 cookie）"
                isChecked = prefs.verboseLog
                setOnCheckedChangeListener { _, checked ->
                    prefs.verboseLog = checked
                    Mlog.enabled = checked
                }
            },
        )
        root.addView(
            EditText(this).apply {
                hint = "触发键码，逗号分隔（默认 219,136,91,79）"
                inputType = InputType.TYPE_CLASS_TEXT
                setText(prefs.triggerKeyCodes.joinToString(","))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val keys = text.toString().split(',').mapNotNull { it.trim().toIntOrNull() }
                        if (keys.isNotEmpty()) prefs.triggerKeyCodes = keys.toSet()
                    }
                }
            },
        )
    }

    private fun refreshStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.startsWith(packageName) == true
        val creds = prefs.loadCredentials()

        statusView.text = buildString {
            append(if (enabled) "✅ 输入法已启用" else "❌ 输入法未启用（点上面的按钮去开启）")
            append('\n')
            append(if (selected) "✅ 当前正在使用" else "⚠️ 不是当前输入法（当前：${current ?: "?"}）")
            append('\n')
            append(if (creds != null) "✅ 豆包已登录（${creds.describeForLog()}）" else "❌ 未登录豆包")
            append('\n')
            append("音频源：${prefs.sourceType.label}")
        }

        socketView.text = buildString {
            append("模式：${if (prefs.socketUseUnix) "Unix abstract socket" else "TCP 127.0.0.1"}\n")
            if (prefs.socketUseUnix) {
                append("名字：@${prefs.socketUnixName}\n")
            } else {
                append("端口：${prefs.socketTcpPort}\n")
            }
            append("token：${prefs.socketToken}\n")
            append("帧格式：type(1)+payload；0x01 控制/0x02 PCM/0x03 握手/0x04 状态")
        }
    }

    /** 录 3 秒，显示 RMS / 峰值 / 判定，并落盘成 wav 供试听（不需要登录豆包）。 */
    private fun runMicProbe() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("没有录音权限，先授权")
            return
        }
        micResultView.text = "正在录音 3 秒，请对着麦克风说话…"
        Thread({
            val outcome = runCatching { MicProbe.record(3000) }
            runOnUiThread {
                outcome.onSuccess { r ->
                    micResultView.text = buildString {
                        append("录了 ${r.durationMs}ms（${r.sampleCount} 采样）\n")
                        append("RMS = %.1f    峰值 = %d    电平 = %.1f dBFS\n".format(r.rms, r.peak, r.dbfs))
                        append("判定：${r.verdict}\n")
                        append(saveSelfTestWav(r.pcm))
                    }
                }.onFailure { e ->
                    micResultView.text = "自检失败：${e.message}"
                }
            }
        }, "murmur-micprobe").start()
    }

    /** 把自检录音存到 App 专属外部目录（adb 可直接 pull），失败不影响 RMS 判定显示。 */
    private fun saveSelfTestWav(pcm: ByteArray): String {
        val file = File(getExternalFilesDir(null), SELFTEST_WAV_NAME)
        return runCatching {
            val written = WavWriter.write(file, pcm)
            "已保存: ${file.absolutePath} (${written / 1024}KB)"
        }.getOrElse {
            Mlog.imeE("保存自检录音失败", it)
            "保存录音失败（不影响判定）：${it.message}"
        }
    }

    /** 触发遥控器语音使能：连接已配对遥控器，按 btsnoop 还原的序列逐条写 GATT。 */
    private fun startRemoteTrigger() {
        if (remoteTrigger != null) {
            toast("语音使能正在执行中，请稍候")
            return
        }
        triggerLogView.text = ""
        remoteTrigger = RemoteVoiceTrigger(this, object : RemoteVoiceTrigger.Listener {
            override fun onLog(line: String) {
                appendTriggerLog(line)
            }

            override fun onFinished(success: Boolean, summary: String) {
                appendTriggerLog(if (success) "✔ $summary" else "✘ $summary")
                toast(
                    if (success) "命令已发送，按语音键看遥控器亮不亮"
                    else "语音使能失败：$summary"
                )
                remoteTrigger = null
            }

            override fun onNeedPickDevice(candidates: List<BluetoothDevice>) {
                showDevicePicker(candidates)
            }
        }).also { it.start() }
    }

    /** TV 没有触摸，用列表弹框选（D-pad 可聚焦）。 */
    private fun showDevicePicker(candidates: List<BluetoothDevice>) {
        val names = candidates.map { "${it.name ?: "无名"}（${it.address}）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择语音遥控器")
            .setItems(names) { _, which ->
                remoteTrigger?.start(candidates[which])
            }
            .setNegativeButton("取消") { _, _ -> remoteTrigger = null }
            .show()
    }

    private fun appendTriggerLog(line: String) {
        triggerLogView.append(line + "\n")
    }

    private fun runSelfTest() {
        val pcmFile = File(filesDir, "murmur_test.pcm")
        val wavFile = File(filesDir, "murmur_test.wav")
        val source = when {
            pcmFile.exists() && pcmFile.length() > 0 -> pcmFile
            wavFile.exists() && wavFile.length() > 0 -> wavFile
            else -> null
        }
        if (source == null) {
            toast("先把测试音频放进来（见上面说明）")
            return
        }
        val pcm = runCatching {
            if (source.name.endsWith(".wav")) WavReader.parse(source.readBytes()).pcm else source.readBytes()
        }.getOrElse {
            resultView.text = "解析失败：${it.message}"
            return
        }
        resultView.text = "开始自测，共 ${pcm.size / 32000.0} 秒音频…"
        selfTest.run(pcm, object : SelfTestRunner.Callback {
            override fun onStatus(text: String) {
                resultView.text = text
            }

            override fun onFinal(text: String) {
                resultView.text = if (text.isBlank()) "自测结束：没有识别到文字" else "识别结果：$text"
            }

            override fun onError(message: String) {
                resultView.text = "自测失败：$message"
            }
        })
    }

    private fun loadTestAudio(file: File) {
        if (!file.exists()) {
            toast("找不到 ${file.absolutePath}")
            return
        }
        val parsed = runCatching { WavReader.parse(file.readBytes()) }
        parsed.onSuccess { result ->
            File(filesDir, "murmur_test.pcm").writeBytes(result.pcm)
            toast("已导入 ${result.pcm.size / 32000.0} 秒音频")
        }.onFailure { e ->
            // 也可能是裸 PCM，直接接受
            File(filesDir, "murmur_test.pcm").writeBytes(file.readBytes())
            toast("按裸 PCM 导入（wav 解析失败：${e.message}）")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                val pcm = runCatching { WavReader.parse(bytes).pcm }
                    .getOrElse { bytes }
                File(filesDir, "murmur_test.pcm").writeBytes(pcm)
                toast("已导入 ${pcm.size / 32000.0} 秒音频")
            }.onFailure { toast("导入失败：${it.message}") }
        }
    }

    private fun ensureMicPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    // ---------------- 小工具 ----------------

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setTextColor(0xFF111827.toInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(0xFF1F2937.toInt())
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun startActivitySafely(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("无法打开：${it.message}") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PICK_AUDIO = 1001
        private const val REQ_MIC = 1002
        const val SELFTEST_WAV_NAME = "murmur_selftest.wav"
    }
}
