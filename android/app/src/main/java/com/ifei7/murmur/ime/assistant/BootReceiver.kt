package com.ifei7.murmur.ime.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ifei7.murmur.ime.MurmurPrefs
import com.ifei7.murmur.ime.util.Mlog

/**
 * 开机自启助手服务。
 * 默认关闭，由设置页「语音助手 → 开机自启」开关（MurmurPrefs）控制。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = MurmurPrefs(context)
        if (!prefs.assistantBootStart) {
            Mlog.assistant("开机自启未开启，跳过")
            return
        }
        Mlog.assistant("开机自启语音助手服务")
        runCatching {
            context.startForegroundService(Intent(context, AssistantService::class.java))
        }.onFailure { Mlog.assistantE("开机自启失败", it) }
    }
}
