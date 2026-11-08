package com.ifei7.murmur.ime

import android.content.Context
import android.content.SharedPreferences
import com.ifei7.murmur.ime.audio.PcmSourceType
import com.ifei7.murmur.ime.audio.SocketConfig
import com.ifei7.murmur.ime.net.DoubaoCredentials
import java.security.SecureRandom

/**
 * 应用配置与凭证存储。
 *
 * ⚠️ 凭证只落在应用私有 SharedPreferences，不写日志、不进源码、不进 git。
 */
class MurmurPrefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- 豆包凭证 ----------------

    fun loadCredentials(): DoubaoCredentials? {
        val cookie = sp.getString(K_COOKIE, "") ?: ""
        if (cookie.isBlank()) return null
        val creds = DoubaoCredentials(
            cookie = cookie,
            deviceId = sp.getString(K_DEVICE_ID, "") ?: "",
            webId = sp.getString(K_WEB_ID, "") ?: "",
            teaUuid = sp.getString(K_TEA_UUID, "") ?: "",
            userAgent = sp.getString(K_UA, "") ?: "",
            savedAt = sp.getLong(K_SAVED_AT, 0L),
        )
        return if (creds.isUsable) creds else null
    }

    fun saveCredentials(creds: DoubaoCredentials) {
        sp.edit()
            .putString(K_COOKIE, creds.cookie)
            .putString(K_DEVICE_ID, creds.deviceId)
            .putString(K_WEB_ID, creds.webId)
            .putString(K_TEA_UUID, creds.teaUuid)
            .putString(K_UA, creds.userAgent)
            .putLong(K_SAVED_AT, creds.savedAt)
            .apply()
    }

    fun clearCredentials() {
        sp.edit()
            .remove(K_COOKIE)
            .remove(K_DEVICE_ID)
            .remove(K_WEB_ID)
            .remove(K_TEA_UUID)
            .remove(K_UA)
            .remove(K_SAVED_AT)
            .apply()
    }

    // ---------------- 音频源 ----------------

    var sourceType: PcmSourceType
        get() = PcmSourceType.fromKey(sp.getString(K_SOURCE, PcmSourceType.MIC.key))
        set(value) = sp.edit().putString(K_SOURCE, value.key).apply()

    var testPcmPath: String
        get() = sp.getString(K_TEST_PCM, "") ?: ""
        set(value) = sp.edit().putString(K_TEST_PCM, value).apply()

    // ---------------- socket ----------------

    var socketUseUnix: Boolean
        get() = sp.getBoolean(K_SOCK_UNIX, true)
        set(value) = sp.edit().putBoolean(K_SOCK_UNIX, value).apply()

    var socketUnixName: String
        get() = sp.getString(K_SOCK_NAME, DEFAULT_SOCKET_NAME) ?: DEFAULT_SOCKET_NAME
        set(value) = sp.edit().putString(K_SOCK_NAME, value).apply()

    var socketTcpPort: Int
        get() = sp.getInt(K_SOCK_PORT, DEFAULT_TCP_PORT)
        set(value) = sp.edit().putInt(K_SOCK_PORT, value).apply()

    /** 握手 token，首次随机生成，由用户在 daemon 侧配置成一样的值。 */
    val socketToken: String
        get() {
            val existing = sp.getString(K_SOCK_TOKEN, null)
            if (!existing.isNullOrBlank()) return existing
            val token = randomToken()
            sp.edit().putString(K_SOCK_TOKEN, token).apply()
            return token
        }

    fun socketConfig(): SocketConfig = SocketConfig(
        useUnix = socketUseUnix,
        unixName = socketUnixName,
        tcpPort = socketTcpPort,
    )

    /** 重新生成握手 token（旧 token 立即失效）。 */
    fun regenerateSocketToken(): String {
        val token = randomToken()
        sp.edit().putString(K_SOCK_TOKEN, token).apply()
        return token
    }

    // ---------------- 触发 ----------------

    /** 遥控器语音键可能的键码，逗号分隔；默认覆盖 VOICE_ASSIST / F5 / MUTE / HEADSETHOOK。 */
    var triggerKeyCodes: Set<Int>
        get() = (sp.getString(K_KEYS, DEFAULT_KEYS.joinToString(",")) ?: "")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        set(value) = sp.edit().putString(K_KEYS, value.joinToString(",")).apply()

    // ---------------- 调试 ----------------

    var verboseLog: Boolean
        get() = sp.getBoolean(K_DEBUG, false)
        set(value) = sp.edit().putBoolean(K_DEBUG, value).apply()

    /** 停止后等待服务端末帧的宽限时间。 */
    var finishGraceMs: Long
        get() = sp.getLong(K_GRACE, DEFAULT_GRACE_MS)
        set(value) = sp.edit().putLong(K_GRACE, value).apply()

    companion object {
        private const val FILE = "murmur_prefs"

        private const val K_COOKIE = "cred_cookie"
        private const val K_DEVICE_ID = "cred_device_id"
        private const val K_WEB_ID = "cred_web_id"
        private const val K_TEA_UUID = "cred_tea_uuid"
        private const val K_UA = "cred_ua"
        private const val K_SAVED_AT = "cred_saved_at"

        private const val K_SOURCE = "source_type"
        private const val K_TEST_PCM = "test_pcm_path"

        private const val K_SOCK_UNIX = "sock_unix"
        private const val K_SOCK_NAME = "sock_name"
        private const val K_SOCK_PORT = "sock_port"
        private const val K_SOCK_TOKEN = "sock_token"

        private const val K_KEYS = "trigger_keys"
        private const val K_DEBUG = "verbose_log"
        private const val K_GRACE = "finish_grace_ms"

        const val DEFAULT_SOCKET_NAME = "murmur_ime"
        const val DEFAULT_TCP_PORT = 47821
        const val DEFAULT_GRACE_MS = 1200L

        val DEFAULT_KEYS = setOf(
            219, // KEYCODE_VOICE_ASSIST
            136, // KEYCODE_F5
            91, // KEYCODE_MUTE
            79, // KEYCODE_HEADSETHOOK
        )

        private fun randomToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
