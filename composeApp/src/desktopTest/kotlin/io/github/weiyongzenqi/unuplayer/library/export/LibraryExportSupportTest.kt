package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LibraryExportSupportTest {
    @Test
    fun `导入拒绝非正的已确认 Bangumi subjectId`() {
        fun link(subjectId: Long?) = BangumiLinkExport(
            identityKey = "tmdb-tv:1:season:1",
            subjectId = subjectId,
            state = "CONFIRMED",
            source = "MANUAL",
            evidence = null,
            updatedAt = 1,
            verifiedAt = 1,
        )

        assertNull(link(null).toBangumiSeasonLinkOrNull())
        assertNull(link(0).toBangumiSeasonLinkOrNull())
        assertNull(link(-1).toBangumiSeasonLinkOrNull())
        assertEquals(1L, link(1).toBangumiSeasonLinkOrNull()?.subjectId)
    }

    @Test
    fun `禁用关联允许不携带 subjectId`() {
        val imported = BangumiLinkExport(
            identityKey = "tmdb-tv:1:season:1",
            subjectId = null,
            state = "DISABLED",
            source = "MANUAL",
            evidence = null,
            updatedAt = 1,
            verifiedAt = null,
        ).toBangumiSeasonLinkOrNull()

        assertEquals(BangumiLinkState.DISABLED, imported?.state)
        assertNull(imported?.subjectId)
    }

    @Test
    fun `在线图片恢复目标名保留 role identity`() {
        val key = "online-scrape/key"
        val entries = listOf(
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, "poster", "image.jpg"))),
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, "fanart", "image.jpg"))),
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, "season1-poster", "image.jpg"))),
            requireNotNull(parseOnlineImageEntry(onlineImageEntryName(key, onlineEpisodeImageRole(1, 1), "image.jpg"))),
        )
        val targets = entries.map(::onlineImageRestoreBasename)
        assertEquals(4, targets.toSet().size)
        assertEquals("poster-image.jpg", targets[0])
        assertNotEquals(targets[0], targets[1])
    }
}
