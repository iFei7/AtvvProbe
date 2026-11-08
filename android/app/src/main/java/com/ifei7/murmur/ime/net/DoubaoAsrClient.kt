package com.ifei7.murmur.ime.net

import com.ifei7.murmur.ime.util.Mlog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 豆包 WSS 流式 ASR 客户端。
 *
 * 上行没有握手帧、没有结束帧：连上就直接灌裸 PCM，结束时关闭连接即可。
 * 认证完全依赖 Cookie 头（含 httpOnly 的 sessionid），另外必须带 `Origin`。
 *
 * 所有回调都在 OkHttp 的读线程上触发，调用方需自行切回主线程。
 */
class DoubaoAsrClient(
    private val credentials: DoubaoCredentials,
    private val listener: Listener,
    private val client: OkHttpClient = defaultClient(),
) {

    interface Listener {
        fun onOpen()
        fun onResult(text: String)
        fun onFinish(text: String)
        fun onFailure(reason: String, error: Throwable?)
        fun onClosed(code: Int, reason: String)
    }

    private var webSocket: WebSocket? = null

    @Volatile
    private var closedByUs = false

    /** 已收到的最后一条文本（服务端是「全量覆盖」，不是增量）。 */
    @Volatile
    var latestText: String = ""
        private set

    /** 拿到过一个 result 帧之后才认为会话真的建立成功。 */
    @Volatile
    var sawResult: Boolean = false
        private set

    val url: String by lazy {
        DoubaoConfig.buildUrl(
            deviceId = credentials.deviceId,
            webId = credentials.webId,
            teaUuid = credentials.teaUuid,
            webTabId = UUID.randomUUID().toString(),
        )
    }

    fun connect() {
        val request = Request.Builder()
            .url(url)
            .header("Origin", DoubaoConfig.ORIGIN)
            .header("Cookie", credentials.cookie)
            .header("User-Agent", credentials.userAgent)
            .build()
        Mlog.asr("connecting: host=${DoubaoConfig.WS_URL}")
        webSocket = client.newWebSocket(request, SocketListener())
    }

    /** 发送一段裸 PCM（16kHz / mono / Int16LE）。 */
    fun sendPcm(data: ByteArray) {
        val ws = webSocket ?: return
        if (data.isEmpty()) return
        val ok = ws.send(ByteString.of(*data))
        if (!ok) Mlog.asrE("sendPcm enqueue 失败（发送缓冲已满或连接已关闭）")
    }

    /**
     * 正常收尾：停止发送，等待服务端吐最后一帧 result / finish，再由调用方 close。
     * 这里只做“不再发数据”的语义标记，真正的关闭时机由 [VoiceController] 控制。
     */
    fun markStopping() {
        closedByUs = true
    }

    fun close() {
        closedByUs = true
        runCatching { webSocket?.close(1000, "1000-") }
        webSocket = null
    }

    fun cancel() {
        closedByUs = true
        runCatching { webSocket?.cancel() }
        webSocket = null
    }

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Mlog.asr("open code=${response.code}")
            listener.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = AsrMessageParser.parse(text)
            if (msg == null) {
                Mlog.asrV("忽略无法解析的帧: ${text.take(80)}")
                return
            }
            if (msg.isError) {
                val desc = AsrMessageParser.describeCode(msg.code)
                Mlog.asrE("服务端错误 code=${msg.code} $desc")
                listener.onFailure(desc, null)
                return
            }
            if (msg.isFinish) {
                Mlog.asr("finish, text=${msg.text.length} 字")
                listener.onFinish(if (msg.text.isNotBlank()) msg.text else latestText)
                return
            }
            if (msg.event == AsrMessageParser.EVENT_RESULT) {
                sawResult = true
                if (msg.text.isNotBlank()) latestText = msg.text
                listener.onResult(msg.text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // 服务端正常只会发文本帧，这里保底处理。
            onMessage(webSocket, bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Mlog.asr("closing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Mlog.asr("closed code=$code reason=$reason")
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName
            Mlog.asrE("ws failure: $detail", t)
            listener.onFailure(detail, t)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
