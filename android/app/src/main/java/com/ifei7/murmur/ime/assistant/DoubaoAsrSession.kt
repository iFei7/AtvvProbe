package com.ifei7.murmur.ime.assistant

import com.ifei7.murmur.ime.net.DoubaoAsrClient
import com.ifei7.murmur.ime.net.DoubaoCredentials

/**
 * 把 [DoubaoAsrClient] 适配成助手用的 [AsrSession]。
 * 事件原样透传（线程模型由 [AssistantController] 统一处理）。
 */
class DoubaoAsrSession(
    credentials: DoubaoCredentials,
    listener: AsrListener,
) : AsrSession {

    private val client = DoubaoAsrClient(credentials, object : DoubaoAsrClient.Listener {
        override fun onOpen() = listener.onOpen()

        override fun onResult(text: String) = listener.onResult(text)

        override fun onFinish(text: String) = listener.onFinish(text)

        override fun onFailure(reason: String, error: Throwable?) = listener.onFailure(reason, error)

        override fun onClosed(code: Int, reason: String) = listener.onClosed(code, reason)
    })

    override fun connect() = client.connect()

    override fun sendPcm(data: ByteArray) = client.sendPcm(data)

    override fun markStopping() = client.markStopping()

    override fun close() = client.close()

    override fun cancel() = client.cancel()
}
