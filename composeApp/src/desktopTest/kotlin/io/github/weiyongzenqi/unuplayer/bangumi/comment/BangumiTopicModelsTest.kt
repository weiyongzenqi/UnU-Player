package io.github.weiyongzenqi.unuplayer.bangumi.comment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BangumiTopicModelsTest {
    @Test
    fun `树按relatedCommentId分组保序且顶层为主楼直属`() {
        val replies = listOf(
            reply(4, 2),
            reply(2, 1),
            reply(3, 1),
            reply(5, 4),
        )

        val tree = buildBangumiTopicReplyTree(replies, mainId = 1)

        assertEquals(listOf(2L, 3L), tree.map { it.reply.id })
        assertEquals(listOf(4L), tree[0].children.map { it.reply.id })
        assertEquals(listOf(5L), tree[0].children[0].children.map { it.reply.id })
    }

    @Test
    fun `深度封顶时后代拍平收纳不丢失`() {
        // 六层链 2→3→4→5→6→7, maxDepth=4: 第 4 层节点(5)的 children 拍平收纳 6 和 7
        val replies = (2..7).map { reply(it.toLong(), it.toLong() - 1) }

        val tree = buildBangumiTopicReplyTree(replies, mainId = 1, maxDepth = 4)

        var node = tree.single()
        assertEquals(2L, node.reply.id)
        repeat(3) { node = node.children.single() }
        assertEquals(5L, node.reply.id)
        assertEquals(listOf(6L, 7L), node.children.map { it.reply.id })
        assertTrue(node.children.all { it.children.isEmpty() })
    }

    @Test
    fun `环引用不爆栈且节点只挂载一次`() {
        // 3 同时被 2 和 5 引用(5→3 构成环), visited 保证 3 只挂载一次且其余节点不丢失
        val replies = listOf(reply(2, 1), reply(3, 2), reply(4, 3), reply(5, 3), reply(3, 5))

        val tree = buildBangumiTopicReplyTree(replies, mainId = 1)

        assertEquals(listOf(2L), tree.map { it.reply.id })
        assertEquals(listOf(3L), tree[0].children.map { it.reply.id })
        assertEquals(listOf(4L, 5L), tree[0].children[0].children.map { it.reply.id })
        assertTrue(tree[0].children[0].children[1].children.isEmpty(), "5→3 的环引用被切断")
    }

    @Test
    fun `自引用环只挂载一次且孤儿节点不出现`() {
        // 2 引用自身: 顶层挂载 2, 其 children 不再重复挂载 2; 3 的父是 2 正常挂载
        val replies = listOf(reply(2, 1), reply(2, 2), reply(3, 2))

        val tree = buildBangumiTopicReplyTree(replies, mainId = 1)

        assertEquals(listOf(2L), tree.map { it.reply.id })
        assertEquals(listOf(3L), tree[0].children.map { it.reply.id })
    }

    @Test
    fun `mergeTopicPages按id去重保序`() {
        val a = topic(1)
        val b = topic(2)
        val c = topic(3)

        val merged = mergeTopicPages(
            listOf(a, b),
            BangumiTopicPage(listOf(b, c), total = 3, offset = 2, limit = 2),
        )

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    @Test
    fun `collectType标签只认识1到5`() {
        assertEquals(null, bangumiCollectTypeLabel(null))
        assertEquals(null, bangumiCollectTypeLabel(0))
        assertEquals("想看", bangumiCollectTypeLabel(1))
        assertEquals("看过", bangumiCollectTypeLabel(2))
        assertEquals("在看", bangumiCollectTypeLabel(3))
        assertEquals("搁置", bangumiCollectTypeLabel(4))
        assertEquals("抛弃", bangumiCollectTypeLabel(5))
        assertEquals(null, bangumiCollectTypeLabel(6))
    }

    private fun reply(id: Long, related: Long) = BangumiEpisodeCommentReply(
        id = id,
        author = BangumiCommentAuthor(id, "user", "用户$id"),
        createdAtSeconds = 1,
        content = BangumiRichText(emptyList()),
        relatedCommentId = related,
    )

    private fun topic(id: Long) = BangumiTopic(
        id = id,
        title = "话题$id",
        author = BangumiCommentAuthor(id, "user", "用户$id"),
        replyCount = 0,
        createdAtSeconds = 1,
        updatedAtSeconds = 1,
    )
}
