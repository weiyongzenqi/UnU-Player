package io.github.weiyongzenqi.unuplayer.bangumi.comment

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BangumiCommentProviderTest {
    @Test
    fun `季评论分页合并会去重且正确终止`() = runBlocking {
        val transport = FakeTransport(
            seasonPages = mapOf(
                0 to BangumiSeasonCommentsDto(listOf(seasonComment(1), seasonComment(2)), total = 3),
                2 to BangumiSeasonCommentsDto(listOf(seasonComment(2), seasonComment(3)), total = 3),
            ),
        )
        val provider = BangumiCommentProvider(transport)

        val first = provider.getSeasonComments(10, limit = 2)
        val second = provider.getSeasonComments(10, limit = 2, offset = first.nextOffset)
        val merged = mergeSeasonCommentPages(first.comments, second)

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertTrue(!second.hasMore)
    }

    @Test
    fun `剧集索引会加载多页并去重`() = runBlocking {
        val first = (1..200).map { episode(it.toLong(), it.toDouble()) }
        val transport = FakeTransport(
            episodePages = mapOf(
                0 to BangumiEpisodesDto(first, total = 201, limit = 200, offset = 0),
                200 to BangumiEpisodesDto(listOf(episode(200, 200.0), episode(201, 201.0)), total = 201, limit = 200, offset = 200),
            ),
        )

        val episodes = BangumiCommentProvider(transport).resolveEpisodes(10)

        assertEquals(201, episodes.size)
        assertEquals(listOf(0, 200), transport.episodeOffsets)
    }

    @Test
    fun `单集评论只按当前subject的季内ep映射且拒绝冲突`() {
        val episodes = listOf(
            BangumiEpisodeRef(1, 0, 1.0, 1.0, "", 0),
            BangumiEpisodeRef(2, 0, 13.0, 13.0, "", 0),
            BangumiEpisodeRef(3, 1, 1.0, 1.0, "特别篇", 0),
        )
        assertEquals(1L, assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(1, episodes)).episode.id)
        assertEquals(2L, assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(13, episodes)).episode.id)
        assertIs<BangumiEpisodeMapping.NotFound>(mapBangumiEpisode(1, episodes.filter { it.type != 0 }))
        assertIs<BangumiEpisodeMapping.Conflict>(mapBangumiEpisode(1, episodes + episodes.first().copy(id = 4)))
        assertIs<BangumiEpisodeMapping.InvalidLocalEpisode>(mapBangumiEpisode(0, episodes))
    }

    @Test
    fun `我推第二季本地第二集定位当前subject的ep2而不是第一季或sort`() {
        val firstSeasonEpisode = BangumiEpisodeRef(1002, 0, 2.0, 12.0, "第一季第12话", 0)
        val secondSeasonEpisode = BangumiEpisodeRef(1349008, 0, 13.0, 2.0, "传话游戏", 0)
        val wrongContinuousEpisode = BangumiEpisodeRef(2013, 0, 13.0, 13.0, "错误的连续集号", 0)

        assertEquals(
            1349008L,
            assertIs<BangumiEpisodeMapping.Mapped>(
                mapBangumiEpisode(2, listOf(firstSeasonEpisode, secondSeasonEpisode, wrongContinuousEpisode)),
            ).episode.id,
        )
        assertIs<BangumiEpisodeMapping.NotFound>(
            mapBangumiEpisode(2, listOf(wrongContinuousEpisode)),
        )
    }

    @Test
    fun `官方分段坐标夹具始终按季内ep定位首末集`() {
        val mushokuPart2 = listOf(
            BangumiEpisodeRef(1002052, 0, 12.0, 1.0, "持有魔眼的女人", 0),
            BangumiEpisodeRef(1002063, 0, 23.0, 12.0, "醒来，一步向前", 0),
        )
        val oshiSeason2 = listOf(
            BangumiEpisodeRef(1349007, 0, 12.0, 1.0, "东京BLADE", 0),
            BangumiEpisodeRef(1363721, 0, 24.0, 13.0, "愿望", 0),
        )

        assertEquals(
            1002052L,
            assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(1, mushokuPart2)).episode.id,
        )
        assertEquals(
            1002063L,
            assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(12, mushokuPart2)).episode.id,
        )
        assertEquals(
            1349007L,
            assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(1, oshiSeason2)).episode.id,
        )
        assertEquals(
            1363721L,
            assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(13, oshiSeason2)).episode.id,
        )
    }

    @Test
    fun `库集号为TMDB全系列编号时减回漂移匹配条目内ep`() {
        // 我推的孩子第二季: 库按 TMDB 合并季组织(本地 E15), Bangumi 条目内 ep 只有 1..13,
        // offset=-11 时 15 + (-11) = 4 应命中"情感演技"(id=1349010)。
        val oshiSeason2 = listOf(
            BangumiEpisodeRef(1349007, 0, 12.0, 1.0, "东京BLADE", 0),
            BangumiEpisodeRef(1349010, 0, 15.0, 4.0, "情感演技", 0),
            BangumiEpisodeRef(1363721, 0, 24.0, 13.0, "愿望", 0),
        )

        assertEquals(
            1349010L,
            assertIs<BangumiEpisodeMapping.Mapped>(mapBangumiEpisode(15, oshiSeason2, bangumiOffset = -11L)).episode.id,
        )
        // 无漂移时不做换算, 保持 NotFound 语义。
        assertIs<BangumiEpisodeMapping.NotFound>(mapBangumiEpisode(15, oshiSeason2))
        // 换算后越界同样 NotFound。
        assertIs<BangumiEpisodeMapping.NotFound>(mapBangumiEpisode(10, oshiSeason2, bangumiOffset = -11L))
    }

    @Test
    fun `拒绝subject和episode mainID不一致的响应`() = runBlocking {
        val subjectMismatch = FakeTransport(
            episodePages = mapOf(
                0 to BangumiEpisodesDto(listOf(episode(1, 1.0).copy(subject_id = 99)), total = 1),
            ),
        )
        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(subjectMismatch).resolveEpisodes(10)
        }

        val episodeMismatch = FakeTransport(
            episodeComments = listOf(BangumiEpisodeCommentDto(id = 1, mainID = 999, content = "错误集")),
        )
        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(episodeMismatch).getEpisodeComments(100)
        }
        val replyMismatch = FakeTransport(
            episodeComments = listOf(
                BangumiEpisodeCommentDto(
                    id = 1,
                    mainID = 100,
                    content = "主楼",
                    replies = listOf(
                        BangumiEpisodeReplyDto(id = 2, mainID = 999, content = "跨集回复"),
                    ),
                ),
            ),
        )
        assertFailsWith<BangumiCommentContractException> {
            BangumiCommentProvider(replyMismatch).getEpisodeComments(100)
        }
        Unit
    }

    @Test
    fun `关闭番剧识别不会触发transport`() = runBlocking {
        val transport = FakeTransport()
        val provider = BangumiCommentProvider(transport, isEnabled = { false })

        assertFailsWith<BangumiCommentsDisabledException> { provider.getSeasonComments(1) }
        assertFailsWith<BangumiCommentsDisabledException> { provider.resolveEpisodes(1) }
        assertFailsWith<BangumiCommentsDisabledException> { provider.getEpisodeComments(1) }
        assertEquals(0, transport.calls)
    }

    @Test
    fun `单集回复和reaction转换为只读领域模型`() = runBlocking {
        val transport = FakeTransport(
            episodeComments = listOf(
                BangumiEpisodeCommentDto(
                    id = 1,
                    mainID = 100,
                    creatorID = 8,
                    content = "正文(bgm38)",
                    replies = listOf(BangumiEpisodeReplyDto(id = 2, mainID = 100, creatorID = 9, content = "回复")),
                    reactions = listOf(BangumiReactionDto(users = listOf(BangumiUserDto(id = 10)))),
                ),
            ),
        )

        val thread = BangumiCommentProvider(transport).getEpisodeComments(100).single()

        assertEquals(1, thread.reactionCount)
        assertEquals(2, thread.replies.single().id)
    }

    @Test
    fun `头像按白名单过滤且保留 Bangumi 缓存查询参数`() = runBlocking {
        val transport = FakeTransport(
            seasonPages = mapOf(
                0 to BangumiSeasonCommentsDto(
                    data = listOf(
                        seasonComment(1).copy(
                            user = BangumiUserDto(
                                id = 1,
                                nickname = "有效头像",
                                avatar = BangumiAvatarDto(
                                    small = "https://lain.bangumi.lol/pic/user/s/1.jpg?r=1",
                                ),
                            ),
                        ),
                        seasonComment(2).copy(
                            user = BangumiUserDto(
                                id = 2,
                                nickname = "非法头像",
                                avatar = BangumiAvatarDto(
                                    small = "https://other.example.test/pic/user/s/2.jpg",
                                ),
                            ),
                        ),
                    ),
                    total = 2,
                ),
            ),
        )
        val comments = BangumiCommentProvider(
            api = transport,
            allowedAvatarHosts = setOf("lain.bangumi.lol"),
        ).getSeasonComments(subjectId = 10).comments

        assertEquals("https://lain.bangumi.lol/pic/user/s/1.jpg?r=1", comments[0].author.avatarUrl)
        assertEquals(null, comments[1].author.avatarUrl)
    }

    @Test
    fun `乱序楼中楼仍能解析被回复用户`() = runBlocking {
        val transport = FakeTransport(
            episodeComments = listOf(
                BangumiEpisodeCommentDto(
                    id = 1,
                    mainID = 100,
                    content = "",
                    creatorID = 8,
                    user = BangumiUserDto(id = 8, nickname = "主楼"),
                    replies = listOf(
                        BangumiEpisodeReplyDto(
                            id = 3,
                            mainID = 100,
                            relatedID = 2,
                            creatorID = 10,
                            content = "",
                            user = BangumiUserDto(id = 10, nickname = "后回复者"),
                        ),
                        BangumiEpisodeReplyDto(
                            id = 2,
                            mainID = 100,
                            relatedID = 1,
                            creatorID = 9,
                            content = "",
                            user = BangumiUserDto(id = 9, nickname = "被回复者"),
                        ),
                    ),
                ),
            ),
        )

        val replies = BangumiCommentProvider(transport).getEpisodeComments(100).single().replies

        assertEquals("被回复者", replies[0].replyToAuthorName)
        assertEquals(null, replies[1].replyToAuthorName, "直接回复主楼不需要重复显示 @主楼")
        assertEquals(2L, replies[0].relatedCommentId)
    }

    @Test
    fun `第三方响应的重复评论和回复ID会在领域层去重`() = runBlocking {
        val duplicatedThread = BangumiEpisodeCommentDto(
            id = 1,
            mainID = 100,
            content = "",
            replies = listOf(
                BangumiEpisodeReplyDto(id = 2, mainID = 100, content = "", creatorID = 9),
                BangumiEpisodeReplyDto(id = 2, mainID = 100, content = "", creatorID = 9),
            ),
        )
        val transport = FakeTransport(
            seasonPages = mapOf(
                0 to BangumiSeasonCommentsDto(
                    data = listOf(seasonComment(1), seasonComment(1)),
                    total = 2,
                ),
            ),
            episodeComments = listOf(duplicatedThread, duplicatedThread),
        )
        val provider = BangumiCommentProvider(transport)

        assertEquals(listOf(1L), provider.getSeasonComments(10).comments.map { it.id })
        val threads = provider.getEpisodeComments(100)
        assertEquals(listOf(1L), threads.map { it.id })
        assertEquals(listOf(2L), threads.single().replies.map { it.id })
    }

    @Test
    fun `缓存按TTL容量和single flight工作`() = runBlocking {
        var now = 0L
        var loads = 0
        val cache = CommentMemoryCache<Int, Int>(ttlMillis = 10, maxEntries = 2, nowMillis = { now })

        val first = async { cache.getOrLoad(1) { delay(30); ++loads } }
        val second = async { cache.getOrLoad(1) { ++loads } }
        assertEquals(1, first.await())
        assertEquals(1, second.await())
        assertEquals(1, loads)

        cache.getOrLoad(2) { ++loads }
        cache.getOrLoad(3) { ++loads }
        assertEquals(2, cache.size())
        now = 11
        assertEquals(4, cache.getOrLoad(1) { ++loads })
    }

    @Test
    fun `leader取消会传播且不会留下悬空single flight`() = runBlocking {
        val cache = CommentMemoryCache<Int, Int>()
        val job = async { cache.getOrLoad(1) { awaitCancellation() } }
        delay(20)
        job.cancelAndJoin()

        assertEquals(7, cache.getOrLoad(1) { 7 })
    }

    @Test
    fun `leader取消清理前的同key请求会重新竞选而不是继承取消`() = runBlocking {
        val cache = CommentMemoryCache<Int, Int>()
        val leaderCancelled = CompletableDeferred<Unit>()
        val allowLeaderCleanup = CompletableDeferred<Unit>()
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad(1) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        leaderCancelled.complete(Unit)
                        allowLeaderCleanup.await()
                    }
                }
            }
        }

        leader.cancel()
        leaderCancelled.await()
        val follower = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad(1) { 7 }
        }
        allowLeaderCleanup.complete(Unit)

        assertEquals(7, withTimeout(2_000) { follower.await() })
        leader.cancelAndJoin()
    }

    @Test
    fun `清空缓存会取消仍在请求中的leader`() = runBlocking {
        val cache = CommentMemoryCache<Int, Int>()
        val started = CompletableDeferred<Unit>()
        val loaderCancelled = CompletableDeferred<Unit>()
        val job = async {
            cache.getOrLoad(1) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    loaderCancelled.complete(Unit)
                }
            }
        }
        started.await()

        cache.clear()
        job.cancelAndJoin()

        loaderCancelled.await()
        assertTrue(job.isCancelled)
        assertEquals(7, cache.getOrLoad(1) { 7 })
    }

    private fun seasonComment(id: Long) = BangumiSeasonCommentDto(
        id = id,
        user = BangumiUserDto(id = id, nickname = "用户$id"),
        comment = "评论$id",
    )

    private fun episode(id: Long, sort: Double) = BangumiEpisodeDto(id = id, subject_id = 10, type = 0, sort = sort)

    private class FakeTransport(
        private val seasonPages: Map<Int, BangumiSeasonCommentsDto> = emptyMap(),
        private val episodePages: Map<Int, BangumiEpisodesDto> = emptyMap(),
        private val episodeComments: List<BangumiEpisodeCommentDto> = emptyList(),
    ) : BangumiCommentTransport {
        var calls = 0
        val episodeOffsets = mutableListOf<Int>()

        override suspend fun getSeasonComments(subjectId: Long, limit: Int, offset: Int): BangumiSeasonCommentsDto {
            calls++
            return seasonPages[offset] ?: BangumiSeasonCommentsDto(emptyList(), 0)
        }

        override suspend fun getEpisodes(subjectId: Long, limit: Int, offset: Int): BangumiEpisodesDto {
            calls++
            episodeOffsets += offset
            return episodePages[offset] ?: BangumiEpisodesDto(emptyList(), 0)
        }

        override suspend fun getEpisodeComments(episodeId: Long): List<BangumiEpisodeCommentDto> {
            calls++
            return episodeComments
        }

        override suspend fun getSubjectTopics(subjectId: Long, limit: Int, offset: Int): BangumiSubjectTopicsDto {
            calls++
            return BangumiSubjectTopicsDto(emptyList(), 0)
        }

        override suspend fun getTopicDetail(topicId: Long): BangumiTopicDetailDto {
            calls++
            return BangumiTopicDetailDto(id = topicId, replies = listOf(BangumiTopicReplyDto(id = 1, content = "主楼")))
        }

        override suspend fun getSubjectReviews(subjectId: Long, limit: Int, offset: Int): BangumiSubjectReviewsDto {
            calls++
            return BangumiSubjectReviewsDto(emptyList(), 0)
        }

        override suspend fun getReviewDetail(blogId: Long): BangumiBlogDetailDto {
            calls++
            return BangumiBlogDetailDto(id = blogId, title = "长评", content = "正文")
        }

        override suspend fun getReviewComments(blogId: Long): List<BangumiTopicReplyDto> {
            calls++
            return emptyList()
        }
    }
}
