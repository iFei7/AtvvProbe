package com.ifei7.murmur.ime.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 调试用「目标输入框」。
 *
 * TV 上没有容易用遥控器导航到的输入框，这里提供一个启动即聚焦的 EditText，
 * 用来验证：输入法能被选中 → 按键能到 `onKeyDown` → `commitText` 能进输入框。
 *
 * ```bash
 * adb shell am start -n com.ifei7.murmur.ime/.ui.TestTargetActivity
 * ```
 */
class TestTargetActivity : Activity() {

    lateinit var input: EditText
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            TextView(this).apply {
                text = "Murmur 输入法测试框"
                textSize = 22f
                setTextColor(Color.BLACK)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "把光标放在下面这个输入框里，然后按遥控器语音键。\n" +
                    "调试：adb shell am broadcast -a com.ifei7.murmur.ime.DEBUG_COMMIT --es text 测试文字"
                textSize = 14f
                setPadding(0, 12, 0, 24)
                setTextColor(0xFF6B7280.toInt())
            },
        )

        input = EditText(this).apply {
            hint = "在这里说话…"
            textSize = 20f
            isSingleLine = false
            gravity = Gravity.TOP
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                240,
            )
        }
        root.addView(input)

        setContentView(root)
        input.requestFocus()
        input.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}
