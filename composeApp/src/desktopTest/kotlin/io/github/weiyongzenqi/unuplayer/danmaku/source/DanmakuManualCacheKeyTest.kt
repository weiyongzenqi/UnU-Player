package io.github.weiyongzenqi.unuplayer.danmaku.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [danmakuManualCacheKey] 纯函数回归测试。
 *
 * 媒体服务器手动匹配缓存的 key 修复: 原用 playUrl(含每次都变的 PlaySessionId) -> 跨会话必失效 + 污染 LRU;
 * 修复后媒体服务器用 recordKey，WebDAV/SMB 用稳定远程身份，本地用 hash。
 * 三处(查缓存 / 自动命中存 / 手动匹配存)必须一致, 漏一处会导致存取 key 不匹配。
 */
class DanmakuManualCacheKeyTest {

    @Test
    fun `媒体服务器优先用 recordKey`() {
        val key = danmakuManualCacheKey(
            isMediaServer = true,
            recordKey = "jellyfin:conn-1:item-1",
            stableRemoteKey = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=abc-123",
            fileHash = null,
        )
        assertEquals("jellyfin:conn-1:item-1", key)
    }

    @Test
    fun `媒体服务器 key 跨 PlaySessionId 稳定`() {
        // 同一 item 两次播放, PlaySessionId 变, key 不变
        val k1 = danmakuManualCacheKey(
            isMediaServer = true,
            recordKey = "jellyfin:conn-1:item-1",
            stableRemoteKey = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=aaa",
            fileHash = null,
        )
        val k2 = danmakuManualCacheKey(
            isMediaServer = true,
            recordKey = "jellyfin:conn-1:item-1",
            stableRemoteKey = "https://media.example.test/Videos/item-1/stream.mkv?PlaySessionId=bbb",
            fileHash = null,
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
                recordKey = "webdav:conn-1:/anime/S01E01.mkv",
                stableRemoteKey = "https://media.example.test/dav/anime/S01E01.mkv",
                fileHash = "fake-hash",
            ),
        )
    }

    @Test
    fun `SMB 用无凭据 mediaKey 不必预先计算哈希`() {
        assertEquals(
            "smb:conn-1:/anime/S01E01.mkv",
            danmakuManualCacheKey(
                isMediaServer = false,
                recordKey = "smb:conn-1:/anime/S01E01.mkv",
                stableRemoteKey = "smb:conn-1:/anime/S01E01.mkv",
                fileHash = null,
            ),
        )
    }

    @Test
    fun `本地用 hash`() {
        assertEquals(
            "abc123hash",
            danmakuManualCacheKey(
                isMediaServer = false,
                recordKey = "local:content://media/external/video/1",
                stableRemoteKey = null,
                fileHash = "abc123hash",
            ),
        )
    }

    @Test
    fun `本地 hash 未就绪返回 null`() {
        assertNull(
            danmakuManualCacheKey(
                isMediaServer = false,
                recordKey = "local:x",
                stableRemoteKey = null,
                fileHash = null,
            ),
        )
    }

    @Test
    fun `三处存取 key 一致性 媒体服务器场景`() {
        // 模拟 PlayerScreen 三处: 查缓存 / 自动命中存 / 手动匹配存
        val recordKey = "jellyfin:conn-1:item-1"

        val queryKey = danmakuManualCacheKey(
            isMediaServer = true,
            recordKey = recordKey, stableRemoteKey = null, fileHash = null,
        )
        val autoSaveKey = danmakuManualCacheKey(
            isMediaServer = true,
            recordKey = recordKey, stableRemoteKey = null, fileHash = null,
        )
        val manualSaveKey = danmakuManualCacheKey(
            isMediaServer = true,
            recordKey = recordKey, stableRemoteKey = null, fileHash = null,
        )
        assertEquals(queryKey, autoSaveKey)
        assertEquals(autoSaveKey, manualSaveKey)
        assertEquals(recordKey, queryKey)
    }
}
