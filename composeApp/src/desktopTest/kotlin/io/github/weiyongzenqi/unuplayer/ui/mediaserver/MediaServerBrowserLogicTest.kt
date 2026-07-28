package io.github.weiyongzenqi.unuplayer.ui.mediaserver

import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerHttpException
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerItem
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerItemKind
import io.github.weiyongzenqi.unuplayer.mediaserver.MediaServerPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaServerBrowserLogicTest {

    @Test
    fun `分页漂移产生的重复条目会被去重且保序`() {
        val existing = listOf(item("a"), item("b"), item("c"))
        val overlappingPage = listOf(item("c"), item("d"))

        val merged = mergeBrowserPageItems(existing, overlappingPage, isFirstPage = false)

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun `首页加载替换旧列表且同页重复也被去重`() {
        val existing = listOf(item("stale"))
        val firstPage = listOf(item("a"), item("a"), item("b"))

        val merged = mergeBrowserPageItems(existing, firstPage, isFirstPage = true)

        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    @Test
    fun `损坏的奇数长度浏览路径丢弃残段而不崩溃`() {
        val restored = restoreBrowserPath(listOf("id-1", "标题一", "id-2"))

        assertEquals(listOf(MediaServerBrowserLevel("id-1", "标题一")), restored)
        assertEquals(emptyList(), restoreBrowserPath(listOf("lonely")))
        assertEquals(emptyList(), restoreBrowserPath(emptyList()))
    }

    @Test
    fun `401 错误提示引导重新添加连接而非通用失败文案`() {
        val unauthorized = browseErrorMessage(MediaServerHttpException("list-libraries", 401), "目录加载失败")
        assertTrue(unauthorized.contains("重新添加"))

        assertEquals("目录加载失败", browseErrorMessage(MediaServerHttpException("list-libraries", 503), "目录加载失败"))
        assertEquals("目录加载失败", browseErrorMessage(IllegalStateException("其他错误"), "目录加载失败"))
    }

    @Test
    fun `直查首页为空且无更多页时触发递归兜底`() {
        assertTrue(shouldFallbackToRecursive(page(emptyList(), returned = 0, total = 0)))
        assertFalse(shouldFallbackToRecursive(page(listOf(item("a")), returned = 1, total = 1)))
        // 服务端返回了整页但被过滤为空(returned>0 → hasMore)时不兜底, 继续正常翻页。
        assertFalse(shouldFallbackToRecursive(page(emptyList(), returned = 100, total = 300)))
    }

    @Test
    fun `递归兜底查询只取可播类型且直查不带类型过滤`() {
        val direct = browserItemsQuery("parent-1", startIndex = 100, recursive = false)
        assertEquals(false, direct.recursive)
        assertTrue(direct.includeItemTypes.isEmpty())

        val fallback = browserItemsQuery("parent-1", startIndex = 0, recursive = true)
        assertEquals(true, fallback.recursive)
        assertEquals(
            setOf(MediaServerItemKind.MOVIE, MediaServerItemKind.EPISODE, MediaServerItemKind.VIDEO),
            fallback.includeItemTypes,
        )
    }

    private fun page(items: List<MediaServerItem>, returned: Int, total: Int?): MediaServerPage<MediaServerItem> =
        MediaServerPage(
            items = items,
            startIndex = 0,
            limit = 100,
            totalRecordCount = total,
            returnedItemCount = returned,
        )

    private fun item(id: String): MediaServerItem = MediaServerItem(
        id = id,
        name = "条目 $id",
        kind = MediaServerItemKind.EPISODE,
        isFolder = false,
        mediaType = "Video",
        container = "mkv",
        runTimeMs = null,
        overview = null,
        productionYear = null,
        seriesName = null,
        indexNumber = null,
        parentIndexNumber = null,
        primaryImageTag = null,
        userData = null,
    )
}
