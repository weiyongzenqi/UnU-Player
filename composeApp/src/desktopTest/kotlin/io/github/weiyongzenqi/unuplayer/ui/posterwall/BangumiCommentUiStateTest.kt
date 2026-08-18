package io.github.weiyongzenqi.unuplayer.ui.posterwall

import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentAuthor
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentPage
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiCommentProviderContract
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeCommentThread
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiEpisodeRef
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReview
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReviewDetail
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiReviewPage
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiRichText
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopicDetail
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopicPage
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.util.formatLogDate
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
    fun `相对时间三天内相对显示超过三天显示实际日期`() {
        val now = platformTimeMillis() / 1000
        assertEquals("时间未知", relativeBangumiTime(0))
        assertEquals("刚刚", relativeBangumiTime(now))
        assertEquals("刚刚", relativeBangumiTime(now + 3_600), "未来时间戳按刚刚处理")
        assertEquals("5 分钟前", relativeBangumiTime(now - 5 * 60))
        assertEquals("2 小时前", relativeBangumiTime(now - 2 * 3_600))
        assertEquals("2 天前", relativeBangumiTime(now - 2 * 86_400))
        // 恰好 3 天边界内仍相对显示(整数除法按整天计); 超过 3 天显示实际日期(与 formatLogDate 一致)
        assertEquals("2 天前", relativeBangumiTime(now - 3 * 86_400 + 60))
        val old = now - 4 * 86_400
        assertEquals(formatLogDate(old * 1000), relativeBangumiTime(old))
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

            override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiTopicPage(emptyList(), 0, offset, limit)

            override suspend fun getTopicDetail(topicId: Long, refresh: Boolean): BangumiTopicDetail =
                throw NotImplementedError("本测试未使用讨论版")

            override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiReviewPage =
                BangumiReviewPage(emptyList(), 0, offset, limit)

            override suspend fun getReviewDetail(blogId: Long, refresh: Boolean): BangumiReviewDetail =
                throw NotImplementedError("本测试未使用长评")

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

            override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiTopicPage(emptyList(), 0, offset, limit)

            override suspend fun getTopicDetail(topicId: Long, refresh: Boolean): BangumiTopicDetail =
                throw NotImplementedError("本测试未使用讨论版")

            override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiReviewPage =
                BangumiReviewPage(emptyList(), 0, offset, limit)

            override suspend fun getReviewDetail(blogId: Long, refresh: Boolean): BangumiReviewDetail =
                throw NotImplementedError("本测试未使用长评")

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

            override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiTopicPage(emptyList(), 0, offset, limit)

            override suspend fun getTopicDetail(topicId: Long, refresh: Boolean): BangumiTopicDetail =
                throw NotImplementedError("本测试未使用讨论版")

            override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int, refresh: Boolean): BangumiReviewPage =
                BangumiReviewPage(emptyList(), 0, offset, limit)

            override suspend fun getReviewDetail(blogId: Long, refresh: Boolean): BangumiReviewDetail =
                throw NotImplementedError("本测试未使用长评")

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
    fun `详情页未切到评论时预加载长评第一页且不重复请求`() = runBlocking {
        var reviewLoads = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiCommentPage(emptyList(), 0, offset, limit)

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
            override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiTopicPage(emptyList(), 0, offset, limit)

            override suspend fun getTopicDetail(topicId: Long, refresh: Boolean): BangumiTopicDetail =
                throw NotImplementedError("本测试未使用讨论版")

            override suspend fun getSubjectReviews(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiReviewPage {
                reviewLoads++
                return BangumiReviewPage(listOf(review(subjectId)), 1, offset, limit)
            }

            override suspend fun getReviewDetail(blogId: Long, refresh: Boolean): BangumiReviewDetail =
                throw NotImplementedError("本测试未使用长评")

            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)

        state.configure(
            key = 1,
            subject = 10,
            episodes = emptyList(),
            offset = 0,
            active = false,
            preloadFirstPage = true,
            initialMode = BangumiCommentMode.REVIEWS,
        )
        withTimeout(2_000) {
            while (state.reviews.singleOrNull()?.id != 10L) delay(10)
        }

        state.configure(
            key = 1,
            subject = 10,
            episodes = emptyList(),
            offset = 0,
            active = true,
            preloadFirstPage = true,
            initialMode = BangumiCommentMode.REVIEWS,
        )

        assertEquals(1, reviewLoads)
        assertEquals(10, state.reviews.single().id)
    }

    @Test
    fun `切季会取消旧预加载且只发布新季长评第一页`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiCommentPage(emptyList(), 0, offset, limit)

            override suspend fun getSubjectReviews(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiReviewPage = if (subjectId == 10L) {
                firstStarted.complete(Unit)
                awaitCancellation()
            } else {
                BangumiReviewPage(listOf(review(subjectId)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
            override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int, refresh: Boolean) =
                BangumiTopicPage(emptyList(), 0, offset, limit)

            override suspend fun getTopicDetail(topicId: Long, refresh: Boolean): BangumiTopicDetail =
                throw NotImplementedError("本测试未使用讨论版")

            override suspend fun getReviewDetail(blogId: Long, refresh: Boolean): BangumiReviewDetail =
                throw NotImplementedError("本测试未使用长评")

            override suspend fun clear() = Unit
        }
        val state = BangumiCommentUiState(provider, this)

        state.configure(
            key = 1,
            subject = 10,
            episodes = emptyList(),
            offset = 0,
            active = false,
            preloadFirstPage = true,
            initialMode = BangumiCommentMode.REVIEWS,
        )
        firstStarted.await()
        state.configure(
            key = 2,
            subject = 20,
            episodes = emptyList(),
            offset = 0,
            active = false,
            preloadFirstPage = true,
            initialMode = BangumiCommentMode.REVIEWS,
        )

        withTimeout(2_000) {
            while (state.reviews.singleOrNull()?.id != 20L) delay(10)
        }
        assertEquals(20, state.subjectId)
        assertEquals(20, state.reviews.single().id)
    }

    private fun thread(id: Long) = BangumiEpisodeCommentThread(
        id = id,
        author = BangumiCommentAuthor(id, "user", "用户"),
        createdAtSeconds = 1,
        content = BangumiRichText(emptyList()),
        replies = emptyList(),
        reactionCount = 0,
    )

    private fun review(id: Long) = BangumiReview(
        id = id,
        blogId = id,
        title = "长评标题",
        author = BangumiCommentAuthor(id, "user", "用户"),
        summary = BangumiRichText(emptyList()),
        replyCount = 0,
        createdAtSeconds = 1,
    )
}
