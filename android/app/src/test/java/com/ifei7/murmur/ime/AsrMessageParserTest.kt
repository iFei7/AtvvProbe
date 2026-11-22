package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.net.AsrMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrMessageParserTest {

    @Test
    fun `解析 result 事件`() {
        val msg = AsrMessageParser.parse("""{"event":"result","result":{"Text":"你好世界"},"code":0,"message":""}""")
        assertNotNull(msg)
        assertEquals(AsrMessageParser.EVENT_RESULT, msg!!.event)
        assertEquals("你好世界", msg.text)
        assertFalse(msg.isFinish)
        assertFalse(msg.isError)
    }

    @Test
    fun `空 Text 表示没识别到语音`() {
        val msg = AsrMessageParser.parse("""{"event":"result","result":{"Text":""},"code":0,"message":""}""")
        assertEquals("", msg!!.text)
        assertFalse(msg.isFinish)
    }

    @Test
    fun `解析 finish 事件`() {
        val msg = AsrMessageParser.parse("""{"event":"finish","result":null,"code":0,"message":""}""")
        assertTrue(msg!!.isFinish)
        assertEquals("", msg.text)
    }

    @Test
    fun `超时错误码`() {
        val msg = AsrMessageParser.parse("""{"event":"error","result":null,"code":709599053,"message":"timeout"}""")
        assertTrue(msg!!.isError)
        assertTrue(msg.isFinish)
        assertTrue(AsrMessageParser.describeCode(709599053).contains("超时"))
    }

    @Test
    fun `非法请求错误码`() {
        val msg = AsrMessageParser.parse("""{"event":"error","result":null,"code":709599054,"message":"invalid"}""")
        assertTrue(msg!!.isError)
        assertTrue(AsrMessageParser.describeCode(709599054).contains("无效"))
    }

    @Test
    fun `非 JSON 文本被忽略`() {
        assertNull(AsrMessageParser.parse("ping"))
        assertNull(AsrMessageParser.parse(""))
        assertNull(AsrMessageParser.parse("{}"))
    }
}
