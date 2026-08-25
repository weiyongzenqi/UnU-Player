package io.github.weiyongzenqi.unuplayer.bangumi

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BangumiAssociationTest {
    @Test
    fun `季度身份键不依赖扫描生成的season id`() {
        assertEquals(
            "tmdb-tv:209867:season:1:offset:0",
            BangumiSeasonIdentity.keyFor(209867, 9, "/show-a", 1),
        )
        assertEquals(
            "tmdb-tv:209867:season:1:offset:-11",
            BangumiSeasonIdentity.keyFor(209867, 9, "/show-b", 1, -11),
        )
        assertEquals(
            "show:9:/show-a:season:2",
            BangumiSeasonIdentity.keyFor(null, 9, "/show-a", 2),
        )
    }

    @Test
    fun `旧TMDB季键只在无分段歧义或subject吻合时继承`() {
        val firstPart = link(BangumiLinkState.CONFIRMED, BangumiLinkSource.MANUAL, 277554)

        assertEquals(
            firstPart,
            selectStoredBangumiSeasonLink(null, firstPart, scannedSubjectId = null, sameSeasonSegmentCount = 1),
        )
        assertEquals(
            firstPart,
            selectStoredBangumiSeasonLink(null, firstPart, scannedSubjectId = 277554, sameSeasonSegmentCount = 2),
        )
        assertNull(
            selectStoredBangumiSeasonLink(null, firstPart, scannedSubjectId = 325585, sameSeasonSegmentCount = 2),
        )
        val currentSecondPart = firstPart.copy(
            identityKey = "tmdb-tv:94664:season:1:offset:-11",
            subjectId = 325585,
        )
        assertEquals(
            currentSecondPart,
            selectStoredBangumiSeasonLink(
                currentSecondPart,
                firstPart,
                scannedSubjectId = 325585,
                sameSeasonSegmentCount = 2,
            ),
        )
    }

    @Test
    fun `手动和禁用高于扫描值而自动值低于扫描值`() {
        val manual = link(BangumiLinkState.CONFIRMED, BangumiLinkSource.MANUAL, 100)
        assertEquals(100, resolveEffectiveBangumiLink(manual, 200)?.subjectId)
        assertNull(resolveEffectiveBangumiLink(link(BangumiLinkState.DISABLED, BangumiLinkSource.MANUAL, null), 200))
        assertEquals(
            EffectiveBangumiLinkSource.SCANNED,
            resolveEffectiveBangumiLink(link(BangumiLinkState.CONFIRMED, BangumiLinkSource.EXT_LINKER, 100), 200)?.source,
        )
        assertEquals(100, resolveEffectiveBangumiLink(link(BangumiLinkState.CONFIRMED, BangumiLinkSource.EXT_LINKER, 100), null)?.subjectId)
    }

    @Test
    fun `非正 subjectId 不得成为有效关联`() {
        assertEquals(
            200L,
            resolveEffectiveBangumiLink(
                link(BangumiLinkState.CONFIRMED, BangumiLinkSource.MANUAL, 0),
                200,
            )?.subjectId,
            "无效手动值不得压过有效扫描值",
        )
        assertNull(
            resolveEffectiveBangumiLink(
                link(BangumiLinkState.CONFIRMED, BangumiLinkSource.EXT_LINKER, -1),
                null,
            ),
        )
        assertNull(resolveEffectiveBangumiLink(null, 0))
        assertNull(resolveEffectiveBangumiLink(null, -1))
    }

    @Test
    fun `季度精确映射还需Bangumi月份校验才自动确认`() = runBlocking {
        val exact = candidate(400602, "2023-09", seasonExact = true, BangumiCandidateSource.EXT_LINKER)
        val catalog = FakeCatalog(subject = candidate(400602, "2023-09", source = BangumiCandidateSource.ID_LOOKUP))
        val service = BangumiAssociationService(catalog, listOf(BangumiTmdbBridge { _, _ -> listOf(exact) }))

        val matched = service.discover(209867, 1, "葬送的芙莉莲", null, "2023-09-29")
        assertEquals(400602, matched.autoVerified?.subjectId)
        assertFalse(matched.conflict)

        val mismatch = service.discover(209867, 1, "葬送的芙莉莲", null, "2023-10-01")
        assertNull(mismatch.autoVerified)
        assertTrue(mismatch.candidates.any { it.subjectId == 400602L })
    }

    @Test
    fun `系列级候选不自动确认`() = runBlocking {
        val service = BangumiAssociationService(
            FakeCatalog(subject = candidate(12, "2002-04", source = BangumiCandidateSource.ID_LOOKUP)),
            listOf(BangumiTmdbBridge { _, _ -> listOf(candidate(12, "2002-04", false, BangumiCandidateSource.EXT_LINKER)) }),
        )
        val result = service.discover(37527, 1, "人形电脑天使心", null, "2002-04-02")
        assertNull(result.autoVerified)
    }

    @Test
    fun `多个季度精确映射进入冲突而不自动选择`() = runBlocking {
        val exactCandidates = listOf(
            candidate(100, "2023-09", seasonExact = true, BangumiCandidateSource.EXT_LINKER),
            candidate(200, "2023-09", seasonExact = true, BangumiCandidateSource.EXT_LINKER),
        )
        val service = BangumiAssociationService(
            FakeCatalog(subject = exactCandidates.first()),
            listOf(BangumiTmdbBridge { _, _ -> exactCandidates }),
        )

        val result = service.discover(209867, 1, "测试条目", null, "2023-09-01")

        assertTrue(result.conflict)
        assertNull(result.autoVerified)
        assertEquals(setOf(100L, 200L), result.candidates.map { it.subjectId }.toSet())
    }

    private fun link(state: BangumiLinkState, source: BangumiLinkSource, id: Long?) = BangumiSeasonLink(
        identityKey = "tmdb-tv:1:season:1",
        subjectId = id,
        state = state,
        source = source,
        evidence = null,
        updatedAt = 1,
        verifiedAt = null,
    )

    private fun candidate(
        id: Long,
        date: String,
        seasonExact: Boolean = false,
        source: BangumiCandidateSource,
    ) = BangumiCandidate(
        subjectId = id,
        title = "测试条目",
        date = date,
        type = 2,
        sources = setOf(source),
        seasonExact = seasonExact,
    )

    private class FakeCatalog(private val subject: BangumiCandidate) : BangumiCatalog {
        override suspend fun search(keyword: String, limit: Int): List<BangumiCandidate> = emptyList()
        override suspend fun getSubject(subjectId: Long): BangumiCandidate? = subject.takeIf { it.subjectId == subjectId }
    }
}
