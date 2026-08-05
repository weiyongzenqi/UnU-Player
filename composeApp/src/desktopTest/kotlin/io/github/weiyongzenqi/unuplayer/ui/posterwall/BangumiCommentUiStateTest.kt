package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentAuthor
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentPage
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeCommentThread
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeRef
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichText
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BangumiCommentUiStateTest {
    @Test
    fun `评论自动加载只在已布局且接近列表末尾时触发`() {
        assertFalse(shouldAutoLoadComments(lastVisibleIndex = -1, totalItemsCount = 2))
        assertFalse(shouldAutoLoadComments(lastVisibleIndex = 15, totalItemsCount = 20))
        assertTrue(shouldAutoLoadComments(lastVisibleIndex = 16, totalItemsCount = 20))
    }

    @Test
    fun `快速切集会取消旧请求且只发布新集评论`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiCommentPage(emptyList(), 0, offset, limit)

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = listOf(
                BangumiEpisodeRef(1, 0, 1.0, 1.0, "第一集", 1),
                BangumiEpisodeRef(2, 0, 2.0, 2.0, "第二集", 1),
            )

            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean): List<BangumiEpisodeCommentThread> =
                if (episodeId == 1L) {
                    firstStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    listOf(thread(episodeId))
                }

            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)
        state.configure(
            key = 1,
            subject = 10,
            episodes = listOf(
                LocalCommentEpisode(101, 1, "第一集"),
                LocalCommentEpisode(102, 2, "第二集"),
            ),
            offset = 0,
            active = false,
        )

        state.selectMode(BangumiCommentMode.EPISODE)
        firstStarted.await()
        state.selectEpisode(102)

        withTimeout(2_000) {
            while (state.episodeComments.singleOrNull()?.id != 2L) delay(10)
        }
        assertEquals(102, state.selectedLocalEpisodeId)
        assertEquals(2, state.episodeComments.single().id)
    }

    @Test
    fun `单集评论首次只展示二十条并按批展开`() = runBlocking {
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiCommentPage(emptyList(), 0, offset, limit)

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = listOf(
                BangumiEpisodeRef(1, 0, 1.0, 1.0, "第一集", 45),
            )

            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) =
                (1L..45L).map(::thread)

            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)
        state.configure(
            key = 1,
            subject = 10,
            episodes = listOf(LocalCommentEpisode(101, 1, "第一集")),
            offset = 0,
            active = false,
        )

        state.selectMode(BangumiCommentMode.EPISODE)
        withTimeout(2_000) {
            while (state.episodeComments.size != 45) delay(10)
        }

        assertEquals(EPISODE_COMMENT_BATCH_SIZE, state.visibleEpisodeComments.size)
        assertTrue(state.episodeHasMore)
        state.showMoreEpisodeComments()
        assertEquals(40, state.visibleEpisodeComments.size)
        state.showMoreEpisodeComments()
        assertEquals(45, state.visibleEpisodeComments.size)
        assertFalse(state.episodeHasMore)
    }

    @Test
    fun `同一配置隐藏再恢复会保留评论和展开数量`() = runBlocking {
        var indexLoads = 0
        var commentLoads = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiCommentPage(emptyList(), 0, offset, limit)

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean): List<BangumiEpisodeRef> {
                indexLoads++
                return listOf(BangumiEpisodeRef(1, 0, 1.0, 1.0, "第一集", 45))
            }

            override suspend fun getEpisodeComments(
                episodeId: Long,
                refresh: Boolean,
            ): List<BangumiEpisodeCommentThread> {
                commentLoads++
                return (1L..45L).map(::thread)
            }

            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)
        val episode = LocalCommentEpisode(101, 1, "第一集")

        state.configure(
            key = 1,
            subject = 10,
            episodes = listOf(episode),
            offset = 0,
            active = true,
            initialMode = BangumiCommentMode.EPISODE,
            preferredEpisodeId = episode.id,
        )
        withTimeout(2_000) {
            while (state.episodeComments.size != 45) delay(10)
        }
        state.showMoreEpisodeComments()

        state.deactivate()
        state.configure(
            key = 1,
            subject = 10,
            episodes = listOf(episode),
            offset = 0,
            active = true,
            initialMode = BangumiCommentMode.EPISODE,
            preferredEpisodeId = episode.id,
        )

        assertEquals(45, state.episodeComments.size)
        assertEquals(40, state.visibleEpisodeComments.size)
        assertEquals(1, indexLoads)
        assertEquals(1, commentLoads)
    }

    @Test
    fun `详情页未切到评论时预加载季度第一页且不重复请求`() = runBlocking {
        var seasonLoads = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage {
                seasonLoads++
                return BangumiCommentPage(listOf(seasonComment(subjectId)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)

        state.configure(
            key = 1,
            subject = 10,
            episodes = emptyList(),
            offset = 0,
            active = false,
            preloadSeasonFirstPage = true,
            initialMode = BangumiCommentMode.SEASON,
        )
        withTimeout(2_000) {
            while (state.seasonComments.singleOrNull()?.id != 10L) delay(10)
        }

        state.configure(
            key = 1,
            subject = 10,
            episodes = emptyList(),
            offset = 0,
            active = true,
            preloadSeasonFirstPage = true,
            initialMode = BangumiCommentMode.SEASON,
        )

        assertEquals(1, seasonLoads)
        assertEquals(10, state.seasonComments.single().id)
    }

    @Test
    fun `切季会取消旧预加载且只发布新季度第一页`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage = if (subjectId == 10L) {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                BangumiCommentPage(listOf(seasonComment(subjectId)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)

        state.configure(
            key = 1,
            subject = 10,
            episodes = emptyList(),
            offset = 0,
            active = false,
            preloadSeasonFirstPage = true,
            initialMode = BangumiCommentMode.SEASON,
        )
        firstStarted.await()
        state.configure(
            key = 2,
            subject = 20,
            episodes = emptyList(),
            offset = 0,
            active = false,
            preloadSeasonFirstPage = true,
            initialMode = BangumiCommentMode.SEASON,
        )

        withTimeout(2_000) {
            while (state.seasonComments.singleOrNull()?.id != 20L) delay(10)
        }
        assertEquals(20, state.subjectId)
        assertEquals(20, state.seasonComments.single().id)
    }

    private fun thread(id: Long) = BangumiEpisodeCommentThread(
        id = id,
        author = BangumiCommentAuthor(id, "user", "用户"),
        createdAtSeconds = 1,
        content = BangumiRichText(emptyList()),
        replies = emptyList(),
        reactionCount = 0,
    )

    private fun seasonComment(id: Long) = BangumiSeasonComment(
        id = id,
        author = BangumiCommentAuthor(id, "user", "用户"),
        rating = null,
        updatedAtSeconds = 1,
        content = BangumiRichText(emptyList()),
    )
}
