package io.github.weiyongzenqi.unuplayer.danmaku.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [danmakuManualCacheKey] 纯函数回归测试。
 *
 * 媒体服务器手动匹配缓存的 key 修复: 原用 playUrl(含每次都变的 PlaySessionId) -> 跨会话必失效 + 污染 LRU;
 * 修复后媒体服务器用 recordKey(稳定), WebDAV 仍用 playUrl, 本地仍用 hash。
 * 三处(查缓存 / 自动命中存 / 手动匹配存)必须一致, 漏一处会导致存取 key 不匹配。
 */
class DanmakuManualCacheKeyTest {

    @Test
    fun `媒体服务器优先用 recordKey 不被 isWebDav 抢占`() {
        // 媒体服务器 URL 也是 http 开头(isWebDav=true), 但 isMediaServer 优先 -> recordKey
        val key = danmakuManualCacheKey(
            isMediaServer = true,
            isWebDav = true,
            recordKey = "jellyfin:conn-1:item-1",
            playUrl = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=abc-123",
            localHash = null,
        )
        assertEquals("jellyfin:conn-1:item-1", key)
    }

    @Test
    fun `媒体服务器 key 跨 PlaySessionId 稳定`() {
        // 同一 item 两次播放, PlaySessionId 变, key 不变
        val k1 = danmakuManualCacheKey(
            isMediaServer = true, isWebDav = true,
            recordKey = "jellyfin:conn-1:item-1",
            playUrl = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=aaa",
            localHash = null,
        )
        val k2 = danmakuManualCacheKey(
            isMediaServer = true, isWebDav = true,
            recordKey = "jellyfin:conn-1:item-1",
            playUrl = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=bbb",
            localHash = null,
        )
        assertEquals(k1, k2)
        assertEquals("jellyfin:conn-1:item-1", k1)
    }

    @Test
    fun `WebDAV 用 playUrl`() {
        assertEquals(
            "https://media.example.test/dav/anime/S01E01.mkv",
            danmakuManualCacheKey(
                isMediaServer = false,
                isWebDav = true,
                recordKey = "webdav:conn-1:/anime/S01E01.mkv",
                playUrl = "https://media.example.test/dav/anime/S01E01.mkv",
                localHash = "fake-hash",
            ),
        )
    }

    @Test
    fun `本地用 hash`() {
        assertEquals(
            "abc123hash",
            danmakuManualCacheKey(
                isMediaServer = false,
                isWebDav = false,
                recordKey = "local:content://media/external/video/1",
                playUrl = "content://media/external/video/1",
                localHash = "abc123hash",
            ),
        )
    }

    @Test
    fun `本地 hash 未就绪返回 null`() {
        assertNull(
            danmakuManualCacheKey(
                isMediaServer = false,
                isWebDav = false,
                recordKey = "local:x",
                playUrl = "file:///tmp/x.mkv",
                localHash = null,
            ),
        )
    }

    @Test
    fun `三处存取 key 一致性 媒体服务器场景`() {
        // 模拟 PlayerScreen 三处: 查缓存 / 自动命中存 / 手动匹配存
        val common = listOf(
            mapOf("isMediaServer" to "true", "isWebDav" to "true"),
        ).single()
        val recordKey = "jellyfin:conn-1:item-1"
        val playUrl = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=xyz"

        val queryKey = danmakuManualCacheKey(
            isMediaServer = common["isMediaServer"].toBoolean(),
            isWebDav = common["isWebDav"].toBoolean(),
            recordKey = recordKey, playUrl = playUrl, localHash = null,
        )
        val autoSaveKey = danmakuManualCacheKey(
            isMediaServer = common["isMediaServer"].toBoolean(),
            isWebDav = common["isWebDav"].toBoolean(),
            recordKey = recordKey, playUrl = playUrl, localHash = null,
        )
        val manualSaveKey = danmakuManualCacheKey(
            isMediaServer = common["isMediaServer"].toBoolean(),
            isWebDav = common["isWebDav"].toBoolean(),
            recordKey = recordKey, playUrl = playUrl, localHash = null,
        )
        assertEquals(queryKey, autoSaveKey)
        assertEquals(autoSaveKey, manualSaveKey)
        assertEquals(recordKey, queryKey)
    }
}

private fun String.toBoolean(): Boolean = this == "true"
