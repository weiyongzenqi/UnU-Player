package io.github.weiyongzenqi.unuplayer.danmaku.source

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.weiyongzenqi.unuplayer.core.platform.Storage

/**
 * 手动匹配结果缓存条目(per-file 记忆: 下次播同一文件自动用, 不再弹手动匹配)。
 *
 * @param episodeId    弹幕库 ID(拉弹幕用)
 * @param animeId      番剧 ID
 * @param animeTitle   番剧标题(日志/toast 用)
 * @param episodeTitle 剧集标题
 * @param updatedAt    更新时间戳, 用于 LRU 截断(调用方填 platformTimeMillis())
 */
@Serializable
data class ManualMatchCacheEntry(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
    val updatedAt: Long,
)

/**
 * 手动匹配结果 per-file 缓存仓库。用 [Storage] 持久化为 JSON map
 * (仿 [io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository])。
 *
 * key = fileHash(前 16MB MD5, 文件指纹稳定；本地 content:// 不作为跨来源缓存 key)。map 序列化 JSON 存 Storage。
 * 容量上限 [MAX](LRU 按 updatedAt 截断), 防无限增长。
 */
class ManualMatchCacheRepository(private val storage: Storage) {

    private val key = "manual_match_cache"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), ManualMatchCacheEntry.serializer())

    /** 读改写互斥锁(B7 修复): save/clear 是 loadAll→改 map→整表回写, 并发会丢更新; load 只读不加锁。 */
    private val mutex = Mutex()

    /** 按 fileHash 取缓存的手动匹配结果; 无则 null。 */
    suspend fun load(fileHash: String): ManualMatchCacheEntry? = loadAll()[fileHash]

    /** 写入/更新 fileHash 对应的缓存条目; 超容量按 updatedAt 淘汰最旧。 */
    suspend fun save(fileHash: String, entry: ManualMatchCacheEntry) {
        mutex.withLock {
            val map = loadAll().toMutableMap()
            map[fileHash] = entry
            val trimmed = if (map.size > MAX) {
                map.entries.sortedByDescending { it.value.updatedAt }.take(MAX).associate { it.key to it.value }
            } else {
                map
            }
            storage.putString(key, json.encodeToString(serializer, trimmed))
        }
    }

    /** 清除 fileHash 对应的缓存(换集重选时用)。 */
    suspend fun clear(fileHash: String) {
        mutex.withLock {
            val map = loadAll().toMutableMap()
            if (map.remove(fileHash) != null) {
                storage.putString(key, json.encodeToString(serializer, map))
            }
        }
    }

    private suspend fun loadAll(): Map<String, ManualMatchCacheEntry> {
        val raw = storage.getString(key) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
    }

    private companion object {
        const val MAX = 200
    }
}
