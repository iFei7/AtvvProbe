package com.ifei7.murmur.ime.ui

import android.os.Handler
import android.os.Looper
import com.ifei7.murmur.ime.MurmurPrefs
import com.ifei7.murmur.ime.core.PcmChunker
import com.ifei7.murmur.ime.net.DoubaoAsrClient
import com.ifei7.murmur.ime.util.Mlog

/**
 * 设置页里的「自测识别」：把一段测试 PCM 按实时速率推给豆包，验证凭证与协议是否可用。
 *
 * 这条链路完全绕开 Android 输入法框架，用来把「ASR 通不通」和「IME 焦点/按键」两个问题分开定位。
 */
class SelfTestRunner(private val prefs: MurmurPrefs) {

    interface Callback {
        fun onStatus(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var client: DoubaoAsrClient? = null
    private var sender: Thread? = null

    @Volatile
    private var cancelled = false

    fun run(pcm: ByteArray, callback: Callback) {
        val creds = prefs.loadCredentials()
        if (creds == null) {
            callback.onError("还没登录豆包")
            return
        }
        if (pcm.isEmpty()) {
            callback.onError("测试音频为空")
            return
        }
        cancelled = false
        val chunker = PcmChunker()
        val asr = DoubaoAsrClient(creds, object : DoubaoAsrClient.Listener {
            override fun onOpen() {
                callback.onStatus("已连接豆包，开始送音频（${pcm.size / 32000.0}s）")
                sender = Thread({
                    var offset = 0
                    while (!cancelled && offset < pcm.size) {
                        val len = minOf(PcmChunker.DEFAULT_CHUNK_SIZE, pcm.size - offset)
                        chunker.accept(pcm.copyOfRange(offset, offset + len)) { chunk ->
                            client?.sendPcm(chunk)
                        }
                        offset += len
                        try {
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                    // 送完等 1.5s 让服务端吐最终结果
                    try {
                        Thread.sleep(1500)
                    } catch (e: InterruptedException) {
                        // ignore
                    }
                    if (!cancelled) client?.close()
                }, "murmur-selftest").apply { isDaemon = true; start() }
            }

            override fun onResult(text: String) {
                if (text.isNotBlank()) handler.post { callback.onStatus("实时: $text") }
            }

            override fun onFinish(text: String) {
                handler.post { callback.onFinal(if (text.isNotBlank()) text else "") }
            }

            override fun onFailure(reason: String, error: Throwable?) {
                Mlog.asrE("自测失败: $reason", error)
                handler.post { callback.onError("连接失败: $reason") }
            }

            override fun onClosed(code: Int, reason: String) {
                handler.post { callback.onStatus("连接已关闭（$code）") }
            }
        })
        client = asr
        asr.connect()
    }

    fun cancel() {
        cancelled = true
        sender?.interrupt()
        sender = null
        client?.cancel()
        client = null
    }
}
