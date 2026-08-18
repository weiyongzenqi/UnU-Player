package io.github.weiyongzenqi.unuplayer.webdav

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * C-06: 空 displayname(空标签, 非缺失)回退 href 末段。
 *
 * 某些 WebDAV 服务器对目录返回 `<D:displayname></D:displayname>`(空串而非省略标签),
 * 旧实现 `currentDisplayName ?: href末段` 只对 null 兜底, 空串穿透 -> entry.name="" 被
 * [filterWebDavSelfEntry] 的 isNotEmpty 过滤 -> 整目录显示为空。修后空串与缺失等价, 取 href 末段。
 */
class PropfindParserDisplayNameTest {

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
        assertEquals(0L, parse("2024-02-30T00:00:00Z"))
        assertEquals(0L, parse("2024-01-01T00:00:00Zgarbage"))
        assertEquals(0L, parse("Mon, 01 Jan 2024 00:00:00 GMT garbage"))
    }
}
