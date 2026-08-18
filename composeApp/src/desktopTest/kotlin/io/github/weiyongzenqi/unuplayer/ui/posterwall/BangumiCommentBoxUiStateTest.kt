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
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiSeasonComment
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopicDetail
import io.github.weiyongzenqi.unuplayer.bangumi.comment.BangumiTopicPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BangumiCommentBoxUiStateTest {

    @Test
    fun `快速切 subject 丢弃旧请求迟到结果且只发布新 subject 吐槽`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<BangumiCommentPage>()
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage = if (subjectId == 10L) {
                firstStarted.complete(Unit)
                firstResult.await()
            } else {
                BangumiCommentPage(listOf(boxComment(subjectId)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
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
        val state = BangumiCommentBoxUiState(provider, this)

        state.configure(subject = 10, active = true)
        firstStarted.await()
        state.configure(subject = 20, active = true)
        // 旧 subject A 的请求结果迟到: 已被 token 丢弃, 不得覆盖 B 的数据
        firstResult.complete(BangumiCommentPage(listOf(boxComment(10)), 1, 0, 20))

        withTimeout(2_000) {
            while (state.comments.singleOrNull()?.id != 20L) delay(10)
        }
        assertEquals(20, state.subjectId)
        assertEquals(20, state.comments.single().id)
    }

    @Test
    fun `loadMore 合并去重且加载中重复调用与无更多数据均被门控`() = runBlocking {
        var loads = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage {
                loads++
                return when (offset) {
                    0 -> BangumiCommentPage((1L..20L).map(::boxComment), 25, offset, limit)
                    else -> BangumiCommentPage((15L..25L).map(::boxComment), 25, offset, limit)
                }
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
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
        val state = BangumiCommentBoxUiState(provider, this)

        state.configure(subject = 10, active = true)
        withTimeout(2_000) {
            while (state.comments.size != 20) delay(10)
        }
        assertTrue(state.hasMore)

        state.loadMore()
        state.loadMore()  // loading 期间重复调用不重复请求
        withTimeout(2_000) {
            while (state.comments.size != 25) delay(10)
        }
        assertEquals(2, loads)  // 首次页 + loadMore 各一次
        assertEquals(25, state.comments.size)  // 1..20 与 15..25 按 id 去重后 25 条
        assertFalse(state.hasMore)

        state.loadMore()  // hasMore=false 门控
        assertEquals(2, loads)
    }

    @Test
    fun `未激活不请求且同 subject 再激活已有数据不重复请求`() = runBlocking {
        var loads = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage {
                loads++
                return BangumiCommentPage(listOf(boxComment(1)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
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
        val state = BangumiCommentBoxUiState(provider, this)

        state.configure(subject = 10, active = false)
        delay(100)
        assertEquals(0, loads)

        state.configure(subject = 10, active = true)
        withTimeout(2_000) {
            while (state.comments.singleOrNull()?.id != 1L) delay(10)
        }
        assertEquals(1, loads)

        state.configure(subject = 10, active = true)  // 已有数据不重复请求
        delay(100)
        assertEquals(1, loads)
    }

    @Test
    fun `切回旧 subject 立即恢复快照且零新请求`() = runBlocking {
        var loads = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage {
                loads++
                return BangumiCommentPage(listOf(boxComment(subjectId)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
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
        val state = BangumiCommentBoxUiState(provider, this)

        state.configure(subject = 10, active = true)
        withTimeout(2_000) {
            while (state.comments.singleOrNull()?.id != 10L) delay(10)
        }
        assertEquals(1, loads)

        state.configure(subject = 20, active = true)
        withTimeout(2_000) {
            while (state.comments.singleOrNull()?.id != 20L) delay(10)
        }
        assertEquals(2, loads)

        state.configure(subject = 10, active = true)
        assertEquals(10, state.comments.single().id)  // 快照同步回填, 无需等待
        assertEquals(2, loads)  // 零新请求
    }

    @Test
    fun `refresh 强制重取并透传 refresh 标记`() = runBlocking {
        var loads = 0
        var refreshCalls = 0
        val provider = object : BangumiCommentProviderContract {
            override suspend fun getSeasonComments(
                subjectId: Long,
                limit: Int,
                offset: Int,
                refresh: Boolean,
            ): BangumiCommentPage {
                loads++
                if (refresh) refreshCalls++
                return BangumiCommentPage(listOf(boxComment(subjectId)), 1, offset, limit)
            }

            override suspend fun resolveEpisodes(subjectId: Long, refresh: Boolean) = emptyList<BangumiEpisodeRef>()
            override suspend fun getEpisodeComments(episodeId: Long, refresh: Boolean) = emptyList<BangumiEpisodeCommentThread>()
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
        val state = BangumiCommentBoxUiState(provider, this)

        state.configure(subject = 10, active = true)
        withTimeout(2_000) {
            while (state.comments.singleOrNull()?.id != 10L) delay(10)
        }
        state.refresh()
        withTimeout(2_000) {
            while (loads < 2) delay(10)
        }
        assertEquals(2, loads)
        assertEquals(1, refreshCalls)
    }

    private fun boxComment(id: Long) = BangumiSeasonComment(
        id = id,
        author = BangumiCommentAuthor(id, "user", "用户"),
        rating = null,
        updatedAtSeconds = 1,
        content = BangumiRichText(emptyList()),
    )
}
