package com.ifei7.murmur.ime.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import com.ifei7.murmur.ime.MurmurPrefs
import com.ifei7.murmur.ime.R
import com.ifei7.murmur.ime.audio.AudioRecordSource
import com.ifei7.murmur.ime.util.Mlog

/**
 * 语音助手前台服务：常驻低优先级通知 + 对话会话。
 *
 * 触发方式 v1：
 * - 通知卡片上的「开始对话 / 停止」按钮；
 * - 设置页的手动启停。
 * 不接语音键（后续任务）。
 *
 * 音频采集复用 audio/AudioRecordSource.kt（16k / mono / Int16 / VOICE_RECOGNITION）。
 */
class AssistantService : Service(), AssistantController.Host {

    private lateinit var prefs: MurmurPrefs
    private lateinit var controller: AssistantController
    private lateinit var speaker: TtsSpeaker

    override fun onCreate() {
        super.onCreate()
        prefs = MurmurPrefs(this)
        Mlog.enabled = prefs.verboseLog
        ensureChannel()
        // 前台服务必须立刻挂通知
        startForeground(NOTIF_ID, buildNotification(getString(R.string.assistant_status_idle)))

        speaker = TtsSpeaker(this) { message -> showError(message) }
        controller = AssistantController(
            credentialsProvider = { prefs.loadCredentials() },
            finishGraceMs = prefs.finishGraceMs,
            host = this,
            asrFactory = { creds, listener -> DoubaoAsrSession(creds, listener) },
            sourceFactory = { AudioRecordSource() },
            llm = LlmClient({ prefs.assistantLlmConfig() }),
            speaker = speaker,
            scheduler = MainScheduler(),
        )
        Mlog.assistant("助手服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TURN -> {
                if (!hasRecordPermission()) {
                    showError("没有录音权限：adb shell pm grant ${packageName} android.permission.RECORD_AUDIO")
                } else {
                    controller.start("通知")
                }
            }
            ACTION_STOP_TURN -> controller.stop("通知")
            else -> Mlog.assistant("onStartCommand action=${intent?.action ?: "null"}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller.release()
        speaker.shutdown()
        Mlog.assistant("助手服务已销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- AssistantController.Host ----------------

    override fun onStateChanged(state: AssistantState) {
        Mlog.assistant("状态 -> $state")
        notify(buildNotification(state.label))
    }

    override fun onStatus(text: String) {
        notify(buildNotification(text))
    }

    override fun onAssistantReply(text: String) {
        // 回复文本展示在通知栏；TTS 可用时同时播报
        notify(buildNotification(getString(R.string.assistant_reply_prefix) + text))
    }

    override fun onError(text: String) {
        showError(text)
        notify(buildNotification(text))
    }

    // ---------------- 通知 ----------------

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.assistant_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val startIntent = PendingIntent.getService(
            this, REQ_START,
            Intent(this, AssistantService::class.java).setAction(ACTION_START_TURN),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, REQ_STOP,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP_TURN),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.assistant_notif_title))
            .setContentText(statusText)
            .setOngoing(true)
            .addAction(0, getString(R.string.assistant_action_start), startIntent)
            .addAction(0, getString(R.string.assistant_action_stop), stopIntent)
            .build()
    }

    private fun notify(notification: Notification) {
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification) }
    }

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showError(message: String) {
        Mlog.assistantE(message)
        runCatching {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_START_TURN = "com.ifei7.murmur.ime.assistant.START_TURN"
        const val ACTION_STOP_TURN = "com.ifei7.murmur.ime.assistant.STOP_TURN"

        const val CHANNEL_ID = "murmur_assistant"
        const val NOTIF_ID = 2001

        private const val REQ_START = 3001
        private const val REQ_STOP = 3002
    }
}
