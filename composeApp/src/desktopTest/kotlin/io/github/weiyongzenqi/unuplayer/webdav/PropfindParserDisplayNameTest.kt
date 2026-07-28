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
}
