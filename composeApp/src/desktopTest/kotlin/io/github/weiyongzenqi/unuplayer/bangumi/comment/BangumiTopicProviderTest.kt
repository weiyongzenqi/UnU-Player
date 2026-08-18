package io.github.weiyongzenqi.unuplayer.bangumi.comment

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BangumiTopicProviderTest {
    @Test
    fun `主题列表过滤非法条目并去重`() = runBlocking {
        val transport = FakeTransport(
            subjectTopics = { _, _, _ ->
                BangumiSubjectTopicsDto(
                    data = listOf(
                        topicDto(1, title = "正常"),
                        topicDto(2, state = 1),
                        topicDto(3, display = 0),
                        topicDto(0, title = "非法id"),
                        topicDto(4, title = "   "),
                        topicDto(1, title = "重复"),
                    ),
                    total = 6,
                )
            },
        )

        val page = BangumiCommentProvider(transport).getSubjectTopics(subjectId = 10)

        assertEquals(listOf(1L), page.topics.map { it.id })
        assertEquals("正常", page.topics.single().title)
        assertEquals(6, page.total)
    }

    @Test
    fun `主题分页total和hasMore边界正确`() = runBlocking {
        val transport = FakeTransport(
            subjectTopics = { _, limit, offset ->
                val end = minOf(offset + limit, 45)
                val items = ((offset + 1)..end).map { topicDto(it.toLong()) }
                BangumiSubjectTopicsDto(items, total = 45)
            },
        )
        val provider = BangumiCommentProvider(transport)

        val first = provider.getSubjectTopics(10)
        assertEquals(20, first.topics.size)
        assertTrue(first.hasMore)
        assertEquals(20, first.nextOffset)

        val last = provider.getSubjectTopics(10, offset = 40)
        assertEquals(5, last.topics.size)
        assertFalse(last.hasMore)
        assertEquals(45, last.nextOffset)
    }

    @Test
    fun `主题详情缺回帖时拒绝`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = { BangumiTopicDetailDto(id = it, replies = emptyList()) },
        )

        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(transport).getTopicDetail(1)
        }
        Unit
    }

    @Test
    fun `主题详情id与请求不一致时拒绝`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = 999,
                    replies = listOf(topicReplyDto(2)),
                )
            },
        )

        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(transport).getTopicDetail(1)
        }
        Unit
    }

    @Test
    fun `主题详情mainID不一致时拒绝`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    replies = listOf(topicReplyDto(2).copy(mainID = 999)),
                )
            },
        )

        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(transport).getTopicDetail(10)
        }
        Unit
    }

    @Test
    fun `mainID全零的响应正常通过`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    replies = listOf(
                        BangumiTopicReplyDto(
                            id = 101,
                            content = "主楼",
                            creatorID = 1,
                            user = BangumiUserDto(id = 1, nickname = "楼主"),
                        ),
                    ),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getTopicDetail(10)

        assertEquals(10, detail.topicId)
        assertEquals(101, detail.mainReply.id)
        assertEquals(emptyList(), detail.replies)
    }

    @Test
    fun `嵌套树拍平且主楼为第一条`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    replies = listOf(
                        BangumiTopicReplyDto(
                            id = 1,
                            content = "主楼",
                            creatorID = 1,
                            user = BangumiUserDto(id = 1, nickname = "楼主"),
                            replies = listOf(
                                BangumiTopicReplyDto(
                                    id = 2,
                                    content = "二楼",
                                    creatorID = 2,
                                    user = BangumiUserDto(id = 2, nickname = "二楼用户"),
                                    replies = listOf(
                                        BangumiTopicReplyDto(id = 3, content = "三楼", creatorID = 3),
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getTopicDetail(10)

        assertEquals(1, detail.mainReply.id)
        assertEquals(listOf(2L, 3L), detail.replies.map { it.id })
        assertEquals(1, detail.replies[0].relatedCommentId, "二楼父楼是主楼")
        assertEquals(2, detail.replies[1].relatedCommentId, "三楼父楼是二楼")
    }

    @Test
    fun `relatedID指向缺失楼时回落到嵌套父楼`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    replies = listOf(
                        BangumiTopicReplyDto(
                            id = 1,
                            content = "主楼",
                            creatorID = 1,
                            user = BangumiUserDto(id = 1, nickname = "楼主"),
                            replies = listOf(
                                BangumiTopicReplyDto(
                                    id = 2,
                                    content = "嵌套回帖",
                                    creatorID = 2,
                                    relatedID = 999999,
                                    user = BangumiUserDto(id = 2, nickname = "回帖者"),
                                ),
                            ),
                        ),
                    ),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getTopicDetail(10)

        assertEquals(1, detail.replies.single().relatedCommentId)
    }

    @Test
    fun `回帖标注被回复作者名且直接回复主楼不标注`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    replies = listOf(
                        BangumiTopicReplyDto(id = 1, content = "主楼", creatorID = 1, user = BangumiUserDto(id = 1, nickname = "楼主")),
                        BangumiTopicReplyDto(
                            id = 2,
                            content = "回复三楼",
                            creatorID = 2,
                            user = BangumiUserDto(id = 2, nickname = "甲"),
                            relatedID = 3,
                        ),
                        BangumiTopicReplyDto(id = 3, content = "二楼", creatorID = 3, user = BangumiUserDto(id = 3, nickname = "乙")),
                    ),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getTopicDetail(10)

        val replyToThird = detail.replies.first { it.id == 2L }
        assertEquals(3, replyToThird.relatedCommentId)
        assertEquals("乙", replyToThird.replyToAuthorName)
        val directReply = detail.replies.first { it.id == 3L }
        assertEquals(1, directReply.relatedCommentId)
        assertEquals(null, directReply.replyToAuthorName, "直接回复主楼不重复标注 @楼主")
    }

    @Test
    fun `回帖作者字段为creator时主楼与回帖作者映射正确`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    creator = BangumiUserDto(id = 9, nickname = "详情楼主"),
                    replies = listOf(
                        BangumiTopicReplyDto(
                            id = 1,
                            content = "主楼",
                            creatorID = 1,
                            creator = BangumiUserDto(
                                id = 1,
                                nickname = "楼主",
                                avatar = BangumiAvatarDto(small = "https://lain.bgm.tv/pic/user/s/1.jpg"),
                            ),
                        ),
                        BangumiTopicReplyDto(
                            id = 2,
                            content = "回帖",
                            creatorID = 2,
                            creator = BangumiUserDto(
                                id = 2,
                                nickname = "回帖者",
                                avatar = BangumiAvatarDto(medium = "https://lain.bgm.tv/pic/user/m/2.jpg"),
                            ),
                        ),
                    ),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getTopicDetail(10)

        assertEquals("楼主", detail.mainReply.author.displayName)
        assertEquals("https://lain.bgm.tv/pic/user/s/1.jpg", detail.mainReply.author.avatarUrl)
        assertEquals("回帖者", detail.replies.single().author.displayName)
        assertEquals("https://lain.bgm.tv/pic/user/m/2.jpg", detail.replies.single().author.avatarUrl)
    }

    @Test
    fun `主楼无作者字段时回落详情级creator`() = runBlocking {
        val transport = FakeTransport(
            topicDetail = {
                BangumiTopicDetailDto(
                    id = it,
                    creator = BangumiUserDto(
                        id = 9,
                        nickname = "详情楼主",
                        avatar = BangumiAvatarDto(large = "https://lain.bgm.tv/pic/user/l/9.jpg"),
                    ),
                    replies = listOf(BangumiTopicReplyDto(id = 1, content = "主楼", creatorID = 9)),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getTopicDetail(10)

        assertEquals("详情楼主", detail.mainReply.author.displayName)
        assertEquals("https://lain.bgm.tv/pic/user/l/9.jpg", detail.mainReply.author.avatarUrl)
    }

    @Test
    fun `收藏类型1到5有效且非法值置空`() = runBlocking {
        val transport = FakeTransport(
            seasonPages = mapOf(
                0 to BangumiSeasonCommentsDto(
                    data = listOf(
                        seasonComment(1).copy(type = 1),
                        seasonComment(2).copy(type = 0),
                        seasonComment(3).copy(type = 6),
                        seasonComment(4).copy(type = null),
                        seasonComment(5).copy(type = 5),
                    ),
                    total = 5,
                ),
            ),
        )

        val comments = BangumiCommentProvider(transport).getSeasonComments(10).comments

        assertEquals(1, comments[0].collectType)
        assertEquals(null, comments[1].collectType)
        assertEquals(null, comments[2].collectType)
        assertEquals(null, comments[3].collectType)
        assertEquals(5, comments[4].collectType)
    }

    @Test
    fun `同key主题列表与详情走缓存不重复请求网络`() = runBlocking {
        val transport = FakeTransport()
        val provider = BangumiCommentProvider(transport)

        provider.getSubjectTopics(10)
        provider.getSubjectTopics(10)
        assertEquals(1, transport.topicListCalls.size)

        provider.getTopicDetail(5)
        provider.getTopicDetail(5)
        assertEquals(1, transport.topicDetailCalls.size)

        provider.getSubjectTopics(10, refresh = true)
        assertEquals(2, transport.topicListCalls.size)
    }

    @Test
    fun `关闭番剧识别不会触发主题transport`() = runBlocking {
        val transport = FakeTransport()
        val provider = BangumiCommentProvider(transport, isEnabled = { false })

        assertFailsWith<BangumiCommentsDisabledException> { provider.getSubjectTopics(1) }
        assertFailsWith<BangumiCommentsDisabledException> { provider.getTopicDetail(1) }
        assertEquals(0, transport.topicListCalls.size)
        assertEquals(0, transport.topicDetailCalls.size)
    }

    @Test
    fun `长评列表过滤非法条目并去重`() = runBlocking {
        val transport = FakeTransport(
            subjectReviews = { _, _, _ ->
                BangumiSubjectReviewsDto(
                    data = listOf(
                        reviewDto(1, blogId = 101),
                        reviewDto(2, blogId = 0),
                        reviewDto(3, blogId = 103).copy(entry = null),
                        reviewDto(0, blogId = 104),
                        reviewDto(1, blogId = 101),
                    ),
                    total = 5,
                )
            },
        )

        val page = BangumiCommentProvider(transport).getSubjectReviews(subjectId = 10)

        assertEquals(listOf(1L), page.reviews.map { it.id })
        assertEquals(101, page.reviews.single().blogId)
        assertEquals(5, page.total)
    }

    @Test
    fun `长评详情主楼为blog正文且顶层回帖挂blogId下`() = runBlocking {
        val transport = FakeTransport(
            reviewDetail = { blogId ->
                BangumiBlogDetailDto(
                    id = blogId,
                    uid = 9,
                    title = "标题",
                    content = "正文内容",
                    createdAt = 100,
                    user = BangumiUserDto(id = 9, nickname = "长评作者"),
                )
            },
            reviewComments = { blogId ->
                listOf(
                    BangumiTopicReplyDto(
                        id = 11,
                        mainID = blogId,
                        relatedID = 0,
                        content = "顶层回帖",
                        creatorID = 2,
                        user = BangumiUserDto(id = 2, nickname = "回帖者"),
                        replies = listOf(
                            BangumiTopicReplyDto(
                                id = 12,
                                mainID = blogId,
                                content = "楼中楼",
                                creatorID = 3,
                                user = BangumiUserDto(id = 3, nickname = "楼中楼用户"),
                            ),
                        ),
                    ),
                )
            },
        )

        val detail = BangumiCommentProvider(transport).getReviewDetail(500)

        assertEquals(500, detail.reviewId)
        assertEquals(500, detail.mainReply.id, "主楼 id 即 blogId(树构建以此为顶层父)")
        assertEquals("长评作者", detail.mainReply.author.displayName)
        assertEquals(100, detail.mainReply.createdAtSeconds)
        assertEquals(listOf(11L, 12L), detail.replies.map { it.id })
        assertEquals(500, detail.replies[0].relatedCommentId, "顶层回帖 relatedID=0 回落到父楼 blogId")
        assertEquals(null, detail.replies[0].replyToAuthorName, "直接回复长评不标注 @作者")
        assertEquals(11, detail.replies[1].relatedCommentId, "楼中楼父楼为顶层回帖")
    }

    @Test
    fun `长评回帖mainID不一致时拒绝`() = runBlocking {
        val transport = FakeTransport(
            reviewComments = { blogId ->
                listOf(BangumiTopicReplyDto(id = 11, mainID = blogId + 1, content = "错位回帖", creatorID = 2))
            },
        )

        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(transport).getReviewDetail(500)
        }
        Unit
    }

    @Test
    fun `长评blogId不一致时拒绝`() = runBlocking {
        val transport = FakeTransport(
            reviewDetail = { BangumiBlogDetailDto(id = 999, uid = 1, title = "标题", content = "正文") },
        )

        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(transport).getReviewDetail(500)
        }
        Unit
    }

    @Test
    fun `长评列表与详情走缓存不重复请求网络`() = runBlocking {
        val transport = FakeTransport()
        val provider = BangumiCommentProvider(transport)

        provider.getSubjectReviews(10)
        provider.getSubjectReviews(10)
        assertEquals(1, transport.reviewListCalls.size)

        provider.getReviewDetail(500)
        provider.getReviewDetail(500)
        assertEquals(1, transport.reviewDetailCalls.size)
        assertEquals(1, transport.reviewCommentsCalls.size, "详情与回帖都只拉一次")

        provider.getSubjectReviews(10, refresh = true)
        assertEquals(2, transport.reviewListCalls.size)
    }

    private fun seasonComment(id: Long) = BangumiSeasonCommentDto(
        id = id,
        user = BangumiUserDto(id = id, nickname = "用户$id"),
        comment = "评论$id",
    )

    private fun topicDto(id: Long, title: String = "标题$id", state: Int = 0, display: Int = 1) =
        BangumiSubjectTopicDto(
            id = id,
            title = title,
            creatorID = id,
            state = state,
            display = display,
            creator = BangumiUserDto(id = id, nickname = "用户$id"),
        )

    private fun topicReplyDto(id: Long) = BangumiTopicReplyDto(
        id = id,
        content = "回帖$id",
        creatorID = id,
    )

    private fun reviewDto(id: Long, blogId: Long) = BangumiSubjectReviewDto(
        id = id,
        user = BangumiUserDto(id = id, nickname = "用户$id"),
        entry = BangumiReviewEntryDto(
            id = blogId,
            title = "长评标题$id",
            summary = "摘要$id",
            replies = 2,
            createdAt = 100 + id,
        ),
    )

    private class FakeTransport(
        private val seasonPages: Map<Int, BangumiSeasonCommentsDto> = emptyMap(),
        private val subjectTopics: (subjectId: Long, limit: Int, offset: Int) -> BangumiSubjectTopicsDto = { _, _, _ ->
            BangumiSubjectTopicsDto(emptyList(), 0)
        },
        private val topicDetail: (topicId: Long) -> BangumiTopicDetailDto = { topicId ->
            BangumiTopicDetailDto(
                id = topicId,
                replies = listOf(BangumiTopicReplyDto(id = 1, content = "主楼")),
            )
        },
        private val subjectReviews: (subjectId: Long, limit: Int, offset: Int) -> BangumiSubjectReviewsDto = { _, _, _ ->
            BangumiSubjectReviewsDto(emptyList(), 0)
        },
        private val reviewDetail: (blogId: Long) -> BangumiBlogDetailDto = { blogId ->
            BangumiBlogDetailDto(id = blogId, uid = 1, title = "长评", content = "正文")
        },
        private val reviewComments: (blogId: Long) -> List<BangumiTopicReplyDto> = { emptyList() },
    ) : BangumiCommentTransport {
        val topicListCalls = mutableListOf<Triple<Long, Int, Int>>()
        val topicDetailCalls = mutableListOf<Long>()
        val reviewListCalls = mutableListOf<Triple<Long, Int, Int>>()
        val reviewDetailCalls = mutableListOf<Long>()
        val reviewCommentsCalls = mutableListOf<Long>()

        override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int): BangumiSeasonCommentsDto =
            seasonPages[offset] ?: BangumiSeasonCommentsDto(emptyList(), 0)

        override suspend fun getEpisodes(subjectId: Long, limit: Int, offset: Int): BangumiEpisodesDto =
            BangumiEpisodesDto(emptyList(), 0)

        override suspend fun getEpisodeComments(episodeId: Long): List<BangumiEpisodeCommentDto> = emptyList()

        override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int): BangumiSubjectTopicsDto {
            topicListCalls += Triple(subjectId, limit, offset)
            return subjectTopics(subjectId, limit, offset)
        }

        override suspend fun getTopicDetail(topicId: Long): BangumiTopicDetailDto {
            topicDetailCalls += topicId
            return topicDetail(topicId)
        }

        override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int): BangumiSubjectReviewsDto {
            reviewListCalls += Triple(subjectId, limit, offset)
            return subjectReviews(subjectId, limit, offset)
        }

        override suspend fun getReviewDetail(blogId: Long): BangumiBlogDetailDto {
            reviewDetailCalls += blogId
            return reviewDetail(blogId)
        }

        override suspend fun getReviewComments(blogId: Long): List<BangumiTopicReplyDto> {
            reviewCommentsCalls += blogId
            return reviewComments(blogId)
        }
    }
}
