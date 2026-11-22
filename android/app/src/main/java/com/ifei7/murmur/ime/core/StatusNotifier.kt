package com.ifei7.murmur.ime.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast

/**
 * 极简状态浮层。
 *
 * TV 上没有触摸屏，也不该弹出软键盘，所以这里用 Toast 做状态提示
 * （待机 / 录音中 / 识别中 / 出错），不申请悬浮窗权限、不额外占用屏幕。
 */
class StatusNotifier(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private var toast: Toast? = null
    private var lastText: String = ""
    private var lastAt: Long = 0L

    fun show(text: String) {
        if (text.isBlank()) return
        handler.post {
            val now = System.currentTimeMillis()
            // 同样的文案 800ms 内不重复弹，避免实时结果刷屏
            if (text == lastText && now - lastAt < 800) return@post
            lastText = text
            lastAt = now
            runCatching {
                toast?.cancel()
                val tv = TextView(context)
                tv.text = text
                tv.setPadding(32, 18, 32, 18)
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.textSize = 18f
                tv.gravity = Gravity.CENTER
                tv.setBackgroundColor(0xCC1F2430.toInt())

                @Suppress("DEPRECATION")
                val t = Toast.makeText(context, text, Toast.LENGTH_SHORT)
                t.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 160)
                @Suppress("DEPRECATION")
                t.setView(tv)
                toast = t
                t.show()
            }.onFailure {
                // 自定义 Toast 在个别 ROM 上会抛异常，退回系统默认样式
                runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun cancel() {
        handler.post {
            runCatching { toast?.cancel() }
            toast = null
        }
    }
}
