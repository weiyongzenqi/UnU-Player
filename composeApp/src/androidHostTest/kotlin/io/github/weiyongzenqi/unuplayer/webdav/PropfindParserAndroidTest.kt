package io.github.weiyongzenqi.unuplayer.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android PropfindParser(XmlPullParser actual)测试(FP3-3)。
 *
 * 桌面 StAX 实现已有 PropfindParserDisplayNameTest, Android actual 此前零覆盖;
 * 双端解析语义须一致(displayname 空串回退 href 末段/collection 判定/日期解析)。
 */
class PropfindParserAndroidTest {

    @Test
    fun `空 displayname 标签回退 href 末段`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Anime/</D:href>
                <D:propstat><D:prop>
                  <D:displayname></D:displayname>
                  <D:resourcetype><D:collection/></D:resourcetype>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entries = parsePropfindResponse(xml)

        assertEquals(1, entries.size)
        assertEquals("Anime", entries.single().name)
        assertEquals("/Anime/", entries.single().path)
        assertTrue(entries.single().isDirectory)
    }

    @Test
    fun `缺失 displayname 仍回退 href 末段`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/Anime/Episode01.mkv</D:href>
                <D:propstat><D:prop>
                  <D:getcontentlength>8</D:getcontentlength>
                  <D:resourcetype/>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entries = parsePropfindResponse(xml)

        assertEquals(1, entries.size)
        assertEquals("Episode01.mkv", entries.single().name)
        assertEquals(8L, entries.single().size)
        assertFalse(entries.single().isDirectory)
    }

    @Test
    fun `非空 displayname 优先于 href 末段`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/01.mkv</D:href>
                <D:propstat><D:prop>
                  <D:displayname>第一集.mkv</D:displayname>
                  <D:resourcetype/>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entries = parsePropfindResponse(xml)

        assertEquals(1, entries.size)
        assertEquals("第一集.mkv", entries.single().name)
    }

    @Test
    fun `空白 displayname 视为空并回退 href 末段`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/lib/Movies/</D:href>
                <D:propstat><D:prop>
                  <D:displayname>   </D:displayname>
                  <D:resourcetype><D:collection/></D:resourcetype>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val entries = parsePropfindResponse(xml)

        assertEquals(1, entries.size)
        assertEquals("Movies", entries.single().name)
    }

    @Test
    fun `getlastmodified 严格解析RFC与ISO偏移小数秒并拒绝尾随`() {
        fun parse(date: String): Long = parsePropfindResponse(
            """
                <D:multistatus xmlns:D="DAV:">
                  <D:response>
                    <D:href>/dav/a.mkv</D:href>
                    <D:propstat><D:prop><D:getlastmodified>$date</D:getlastmodified></D:prop></D:propstat>
                  </D:response>
                </D:multistatus>
            """.trimIndent(),
        ).single().lastModified

        assertEquals(1_704_067_200_000L, parse("Mon, 01 Jan 2024 00:00:00 GMT"))
        assertEquals(1_704_067_200_000L, parse("Mon, 01 Jan 2024 08:00:00 +0800"))
        assertEquals(1_704_067_200_123L, parse("2024-01-01T08:00:00.123+08:00"))
        assertEquals(1_704_067_200_000L, parse("2024-01-01T00:00:00Z"))
        assertEquals(0L, parse("2024-02-30T00:00:00Z"))
        assertEquals(0L, parse("2024-01-01T00:00:00Zgarbage"))
        assertEquals(0L, parse("Mon, 01 Jan 2024 00:00:00 GMT garbage"))
    }

    @Test
    fun `多 response 与多前缀命名空间均解析`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:" xmlns:D="DAV:">
              <D:response>
                <d:href>/a/</d:href>
                <d:propstat><d:prop>
                  <d:displayname>目录A</d:displayname>
                  <d:resourcetype><D:collection/></d:resourcetype>
                </d:prop></d:propstat>
              </D:response>
              <response>
                <href>/a/01.mkv</href>
                <propstat><prop>
                  <displayname>01.mkv</displayname>
                  <resourcetype/>
                </prop></propstat>
              </response>
            </d:multistatus>
        """.trimIndent()

        val entries = parsePropfindResponse(xml)

        assertEquals(2, entries.size)
        assertEquals("目录A", entries[0].name)
        assertTrue(entries[0].isDirectory)
        assertEquals("01.mkv", entries[1].name)
        assertFalse(entries[1].isDirectory)
    }
}
