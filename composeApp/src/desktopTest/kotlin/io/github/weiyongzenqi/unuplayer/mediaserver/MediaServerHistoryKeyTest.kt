package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaServerHistoryKeyTest {

    @Test
    fun `稳定键可在连接 id 改变后映射同一服务器用户`() {
        val current = connection(id = "new-random-id")
        val key = parseMediaServerHistoryKey(
            mediaServerHistoryMediaKey(
                MediaServerVendor.JELLYFIN,
                current.serverId,
                current.userId,
                "item:episode-01",
            ),
        )

        assertEquals(MediaSourceKind.JELLYFIN, key?.sourceKind)
        assertEquals("item:episode-01", key?.itemId)
        assertEquals(current.id, resolveMediaServerHistoryConnectionId(requireNotNull(key), listOf(current)))
    }

    @Test
    fun `稳定键不会串到同服务器的其他用户`() {
        val original = connection(id = "original", userId = "user-a")
        val otherUser = connection(id = "other", userId = "user-b")
        val key = requireNotNull(
            parseMediaServerHistoryKey(
                mediaServerHistoryMediaKey(
                    MediaServerVendor.JELLYFIN,
                    original.serverId,
                    original.userId,
                    "item-id",
                ),
            ),
        )

        assertNull(resolveMediaServerHistoryConnectionId(key, listOf(otherUser)))
    }

    @Test
    fun `旧连接键只在原连接仍存在时兼容`() {
        val current = connection(id = "legacy-connection")
        val key = requireNotNull(parseMediaServerHistoryKey("jellyfin:legacy-connection:item:id"))

        assertEquals("item:id", key.itemId)
        assertEquals(current.id, resolveMediaServerHistoryConnectionId(key, listOf(current)))
        assertNull(resolveMediaServerHistoryConnectionId(key, listOf(current.copy(id = "new-id"))))
    }

    @Test
    fun `重复稳定身份优先选择凭据可用的连接`() {
        val unavailable = connection(id = "locked").copy(credentialUnavailable = true)
        val available = connection(id = "ready")
        val key = requireNotNull(
            parseMediaServerHistoryKey(
                mediaServerHistoryMediaKey(
                    MediaServerVendor.JELLYFIN,
                    available.serverId,
                    available.userId,
                    "item-id",
                ),
            ),
        )

        assertEquals(
            available.id,
            resolveMediaServerHistoryConnectionId(key, listOf(unavailable, available)),
        )
    }

    private fun connection(
        id: String,
        userId: String = "user-id",
    ) = MediaServerConnectionSummary(
        id = id,
        vendor = MediaServerVendor.JELLYFIN,
        name = "Jellyfin",
        baseUrl = "https://media.example.test",
        serverId = "server-id",
        serverVersion = "10.11.11",
        userId = userId,
        username = "user",
        credentialUnavailable = false,
    )
}
