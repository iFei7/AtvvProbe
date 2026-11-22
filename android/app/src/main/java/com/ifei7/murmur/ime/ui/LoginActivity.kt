package com.ifei7.murmur.ime.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import com.ifei7.murmur.ime.MurmurPrefs
import com.ifei7.murmur.ime.net.CredentialParser
import com.ifei7.murmur.ime.util.Mlog

/**
 * 打开豆包网页版让用户手动登录，登录成功后把 Cookie + localStorage 里的设备标识抓下来。
 *
 * 安全约束：
 * - 不注册任何 `addJavascriptInterface`，只通过 `evaluateJavascript` 单向取值；
 * - 关闭 `allowFileAccess` / `allowContentAccess`，避免网页读到本地文件；
 * - 抓到的凭证只写入应用私有 SharedPreferences，不打印到日志。
 */
class LoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var prefs: MurmurPrefs
    private lateinit var hint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    private val poller = object : Runnable {
        override fun run() {
            if (finished) return
            tryExtract()
            handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MurmurPrefs(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        webView = WebView(this)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                false

            override fun onPageFinished(view: WebView, url: String) {
                Mlog.ime("登录页加载完成: ${url.take(60)}")
                view.evaluateJavascript(CredentialParser.EXTRACT_JS, null)
            }
        }

        // verifier 常量不需要额外处理；这里保持简单，WebView 独占全屏
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        hint = TextView(this).apply {
            text = "请登录豆包，登录成功后会自动返回\n（本输入法没有软键盘，登录时可按 MENU 键切换成系统输入法）"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP },
        )

        setContentView(root)
        webView.loadUrl("https://www.doubao.com/chat")
        handler.postDelayed(poller, 2500)
    }

    /**
     * 我们的输入法故意不提供软键盘，但登录页需要打字。
     * 遥控器按 MENU 时弹出系统输入法选择器，让用户临时切到 AOSP LatinIME。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            runCatching {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
            Toast.makeText(this, "选择系统输入法来输入账号", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun tryExtract() {
        if (finished) return
        webView.evaluateJavascript(CredentialParser.EXTRACT_JS) { raw ->
            val cookie = runCatching {
                CookieManager.getInstance().getCookie("https://www.doubao.com")
            }.getOrNull()
            if (!CredentialParser.looksLoggedIn(cookie)) {
                hint.text = "等待登录…（未检测到 sessionid）"
                return@evaluateJavascript
            }
            val androidId = runCatching {
                Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull().orEmpty()
            val creds = CredentialParser.parse(
                evaluateResult = raw,
                cookieHeader = cookie,
                userAgent = runCatching { webView.settings.userAgentString }.getOrDefault(""),
                fallbackDeviceId = androidId,
            )
            if (creds == null) {
                hint.text = "已登录，但 device_id / web_id 还没生成，稍等…"
                return@evaluateJavascript
            }
            finished = true
            prefs.saveCredentials(creds)
            Mlog.ime("凭证已保存: ${creds.describeForLog()}")
            Toast.makeText(this, "豆包登录状态已保存", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onBackPressed() {
        finished = true
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
