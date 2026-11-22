package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.core.FocusPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusPolicyTest {

    @Test
    fun `有连接且是真实输入框才响应`() {
        assertTrue(FocusPolicy.canAcceptVoiceInput(inputType = 0x00000001, hasInputConnection = true))
        assertTrue(FocusPolicy.canAcceptVoiceInput(inputType = 0x00020001, hasInputConnection = true))
    }

    @Test
    fun `没有 InputConnection 时不响应`() {
        assertFalse(FocusPolicy.canAcceptVoiceInput(inputType = 0x00000001, hasInputConnection = false))
    }

    @Test
    fun `TYPE_NULL 视为没有输入框`() {
        // 电视桌面 com.leslie.tvhome 就是这种情况，必须不触发
        assertFalse(FocusPolicy.canAcceptVoiceInput(inputType = 0, hasInputConnection = true))
    }
}
