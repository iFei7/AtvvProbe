package com.ifei7.murmur.ime.core

/**
 * 「能不能响应语音键」的判定规则。
 *
 * 抽成纯函数是为了能用 JVM 单测覆盖，避免只能靠真机验证。
 */
object FocusPolicy {

    /** Android 的 `EditorInfo.TYPE_NULL`：应用只想要按键，不是真的文本输入框。 */
    const val TYPE_NULL = 0

    /**
     * @param inputType 最近一次 `onStartInput` 拿到的 `EditorInfo.inputType`
     * @param hasInputConnection 当前是否持有 `InputConnection`
     */
    fun canAcceptVoiceInput(inputType: Int, hasInputConnection: Boolean): Boolean =
        hasInputConnection && inputType != TYPE_NULL
}
