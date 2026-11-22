package com.ifei7.murmur.ime.audio

import android.net.LocalServerSocket
import android.net.LocalSocket
import com.ifei7.murmur.ime.util.Mlog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * socket 通道配置。
 *
 * 默认走 **Unix domain socket（abstract namespace）**，名字 `murmur_ime`。
 * native daemon 侧连接方式：
 * ```c
 * struct sockaddr_un addr; addr.sun_family = AF_UNIX;
 * addr.sun_path[0] = '\0'; strcpy(addr.sun_path + 1, "murmur_ime");
 * connect(fd, (struct sockaddr *)&addr, offsetof(struct sockaddr_un, sun_path) + 1 + strlen("murmur_ime"));
 * ```
 * 也可以切到 TCP 127.0.0.1:47821（方便 `adb shell` / socat 调试）。
 */
data class SocketConfig(
    val useUnix: Boolean = true,
    val unixName: String = "murmur_ime",
    val tcpPort: Int = 47821,
    val handshakeTimeoutMs: Int = 5_000,
) {
    fun describe(): String = if (useUnix) "unix:@$unixName" else "tcp:127.0.0.1:$tcpPort"
}

/**
 * 从本地 socket 读取 daemon 送来的 16k PCM。
 *
 * 同一条连接同时承载「控制命令（按/松语音键）」与「PCM 数据」，
 * 帧格式见 [PcmFrameCodec]。服务常驻，只在 IME 有输入焦点时把控制命令上抛。
 */
class SocketPcmSource(
    private val config: SocketConfig,
    private val token: String,
) : PcmSource {

    override val name: String = "socket(${config.describe()})"

    /** 收到 CONTROL 帧时回调（在 socket 读线程上）。 */
    @Volatile
    var onCommand: ((Int) -> Unit)? = null

    /** 有客户端接入 / 断开时回调，便于状态提示。 */
    @Volatile
    var onClientChanged: ((connected: Boolean) -> Unit)? = null

    @Volatile
    private var listener: PcmSource.Listener? = null

    @Volatile
    private var serving = false

    private var acceptThread: Thread? = null
    private var unixServer: LocalServerSocket? = null
    private var tcpServer: ServerSocket? = null

    @Volatile
    private var client: Client? = null

    /** 启动监听（常驻，服务 onCreate 时调用一次）。 */
    @Synchronized
    fun startServer() {
        if (serving) return
        serving = true
        acceptThread = Thread({ acceptLoop() }, "murmur-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stopServer() {
        serving = false
        runCatching { client?.close() }
        client = null
        runCatching { unixServer?.close() }
        runCatching { tcpServer?.close() }
        unixServer = null
        tcpServer = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    override fun start(listener: PcmSource.Listener) {
        this.listener = listener
        Mlog.sock("PCM 转发已启用（等待 daemon 数据）")
    }

    override fun stop() {
        listener = null
        Mlog.sock("PCM 转发已关闭")
    }

    override fun release() = stopServer()

    /** 给 daemon 回执当前状态。 */
    fun sendState(state: Int) {
        client?.send(PcmFrameCodec.state(state))
    }

    val isClientConnected: Boolean get() = client?.authenticated == true

    private fun acceptLoop() {
        try {
            val server: ServerHolder = if (config.useUnix) {
                val s = LocalServerSocket(config.unixName)
                Mlog.sock("Unix socket 已监听: @${config.unixName}")
                ServerHolder.Unix(s)
            } else {
                val s = ServerSocket(config.tcpPort, 4, InetAddress.getByName("127.0.0.1"))
                Mlog.sock("TCP 已监听: 127.0.0.1:${config.tcpPort}")
                ServerHolder.Tcp(s)
            }
            while (serving) {
                val streams = try {
                    server.accept()
                } catch (e: Exception) {
                    if (!serving) break
                    Mlog.sockE("accept 失败", e)
                    break
                }
                val c = Client(streams.first, streams.second)
                val previous = client
                client = c
                runCatching { previous?.close() }
                c.start()
            }
        } catch (e: Exception) {
            Mlog.sockE("socket 监听启动失败", e)
        } finally {
            serving = false
        }
    }

    private sealed class ServerHolder {
        class Unix(val socket: LocalServerSocket) : ServerHolder()
        class Tcp(val socket: ServerSocket) : ServerHolder()

        fun accept(): Pair<InputStream, OutputStream> = when (this) {
            is Unix -> {
                val s: LocalSocket = socket.accept()
                s.inputStream to s.outputStream
            }
            is Tcp -> {
                val s: Socket = socket.accept()
                s.soTimeout = 0
                s.getInputStream() to s.getOutputStream()
            }
        }
    }

    /** 一条客户端连接：先握手，再进入帧循环。 */
    private inner class Client(
        private val input: InputStream,
        private val output: OutputStream,
    ) {
        @Volatile
        var authenticated = false
            private set

        private var thread: Thread? = null
        private var writer: Thread? = null

        /** 所有写操作都丢给写线程：调用方可能在主线程（NetworkOnMainThreadException 保护）。 */
        private val outQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var closed = false

        fun start() {
            thread = Thread({ run() }, "murmur-client").apply {
                isDaemon = true
                start()
            }
            writer = Thread({ writeLoop() }, "murmur-client-w").apply {
                isDaemon = true
                start()
            }
        }

        private fun writeLoop() {
            try {
                while (!closed) {
                    val bytes = outQueue.take()
                    output.write(bytes)
                    output.flush()
                }
            } catch (e: InterruptedException) {
                // 正常退出
            } catch (e: Exception) {
                if (!closed) Mlog.sockE("回执写入失败", e)
            }
        }

        private fun run() {
            val decoder = FrameDecoder()
            try {
                // 1) 握手：第一条帧必须是 HELLO，token 对不上立刻断开
                val deadline = System.currentTimeMillis() + config.handshakeTimeoutMs
                val hello = waitForHello(decoder, deadline) ?: run {
                    Mlog.sockE("握手超时或连接已断开")
                    close()
                    return
                }
                if (hello.text() != token) {
                    Mlog.sockE("握手失败（token 不匹配）")
                    close()
                    return
                }
                authenticated = true
                Mlog.sock("客户端已接入（握手通过）")
                onClientChanged?.invoke(true)
                send(PcmFrameCodec.state(PcmFrameCodec.STATE_IDLE))
                onCommand?.invoke(COMMAND_HELLO)

                // 2) 帧循环
                val buffer = ByteArray(8192)
                while (!closed) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    decoder.feed(buffer, read) { frame ->
                        when (frame.type) {
                            PcmFrameCodec.TYPE_PCM -> listener?.onPcm(frame.payload)
                            PcmFrameCodec.TYPE_CONTROL -> {
                                val cmd = frame.byteValue()
                                Mlog.sockV("收到控制命令: $cmd")
                                onCommand?.invoke(cmd)
                            }
                            else -> Unit
                        }
                    }
                }
                Mlog.sock("客户端断开（EOF）")
            } catch (e: SocketTimeoutException) {
                Mlog.sockE("socket 读超时", e)
            } catch (e: Exception) {
                if (!closed) Mlog.sockE("socket 读循环异常", e)
            } finally {
                val wasAuth = authenticated
                authenticated = false
                if (wasAuth) onClientChanged?.invoke(false)
                close()
            }
        }

        /** 阻塞读取直到拿到 HELLO 或超时。 */
        private fun waitForHello(decoder: FrameDecoder, deadline: Long): Frame? {
            var hello: Frame? = null
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline && !closed) {
                val read = input.read(buffer)
                if (read < 0) return null
                decoder.feed(buffer, read) { frame ->
                    if (frame.type == PcmFrameCodec.TYPE_HELLO && hello == null) hello = frame
                }
                if (hello != null) return hello
            }
            return null
        }

        /** 线程安全：只入队，真正的 write 在 [writeLoop] 里做。 */
        fun send(bytes: ByteArray) {
            if (closed) return
            outQueue.offer(bytes)
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            writer?.interrupt()
            writer = null
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    companion object {
        /** 握手成功后的一发“伪命令”，让上层知道有客户端接入了。 */
        const val COMMAND_HELLO = 100
    }
}
