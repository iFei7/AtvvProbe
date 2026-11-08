package com.ifei7.murmur.ime

import com.ifei7.murmur.ime.net.DoubaoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoConfigTest {

    private val url = DoubaoConfig.buildUrl(
        deviceId = "7623404988207515145",
        webId = "7623404978401396250",
        teaUuid = "7623404978401396250",
        webTabId = "0b0e7b34-1a2c-4a3d-9f00-123456789abc",
    )

    @Test
    fun `端点与协议正确`() {
        assertTrue(url.startsWith("wss://ws-samantha.doubao.com/samantha/audio/asr?"))
    }

    @Test
    fun `固定参数齐全`() {
        listOf(
            "version_code=20800",
            "language=zh",
            "device_platform=web",
            "aid=497858",
            "real_aid=497858",
            "pkg_type=release_version",
            "pc_version=3.12.3",
            "samantha_web=1",
            "use-olympus-account=1",
            "format=pcm",
        ).forEach { assertTrue("缺少参数 $it", url.contains(it)) }
    }

    @Test
    fun `凭证参数按抓包顺序插入`() {
        assertTrue(
            url.contains(
                "pkg_type=release_version&device_id=7623404988207515145" +
                    "&web_id=7623404978401396250&tea_uuid=7623404978401396250",
            ),
        )
    }

    @Test
    fun `web_tab_id 放在最后`() {
        assertTrue(url.endsWith("&web_tab_id=0b0e7b34-1a2c-4a3d-9f00-123456789abc"))
    }

    @Test
    fun `region 与 sys_region 是空值但要保留等号`() {
        assertTrue(url.contains("region=&sys_region="))
    }

    @Test
    fun `特殊字符会被百分号编码`() {
        val encoded = DoubaoConfig.buildUrl("a b", "c&d", "e=f", "g")
        assertTrue(encoded.contains("device_id=a%20b"))
        assertTrue(encoded.contains("web_id=c%26d"))
        assertTrue(encoded.contains("tea_uuid=e%3Df"))
    }

    @Test
    fun `参数数量固定为 16 个`() {
        val query = url.substringAfter('?')
        assertEquals(16, query.split('&').size)
    }
}
