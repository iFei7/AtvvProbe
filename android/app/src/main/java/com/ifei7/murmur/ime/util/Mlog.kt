package com.ifei7.murmur.ime.util

import android.util.Log

/**
 * 统一日志出口。语音/音频数据量很大，默认只输出状态信息；
 * 把 [enabled] 打开后会输出逐帧细节（**永不打印 cookie / token**）。
 */
object Mlog {

    @Volatile
    var enabled: Boolean = false

    private const val IME_TAG = "MurmurIme"
    private const val ASR_TAG = "DoubaoAsr"
    private const val SOCK_TAG = "MurmurSock"
    private const val TRIGGER_TAG = "RemoteTrigger"

    fun ime(msg: String) = Log.i(IME_TAG, msg)

    fun imeE(msg: String, t: Throwable? = null) = Log.e(IME_TAG, msg, t)

    fun verbose(msg: String) {
        if (enabled) Log.d(IME_TAG, msg)
    }

    fun asr(msg: String) = Log.i(ASR_TAG, msg)

    fun asrV(msg: String) {
        if (enabled) Log.d(ASR_TAG, msg)
    }

    fun asrE(msg: String, t: Throwable? = null) = Log.e(ASR_TAG, msg, t)

    fun sock(msg: String) = Log.i(SOCK_TAG, msg)

    fun sockV(msg: String) {
        if (enabled) Log.d(SOCK_TAG, msg)
    }

    fun sockE(msg: String, t: Throwable? = null) = Log.e(SOCK_TAG, msg, t)

    fun trigger(msg: String) = Log.i(TRIGGER_TAG, msg)

    fun triggerE(msg: String, t: Throwable? = null) = Log.e(TRIGGER_TAG, msg, t)
}
