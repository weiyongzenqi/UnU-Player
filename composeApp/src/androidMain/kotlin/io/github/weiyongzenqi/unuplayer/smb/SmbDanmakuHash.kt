package io.github.weiyongzenqi.unuplayer.smb

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.danmaku.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 解出用于弹幕路径匹配的真实 SMB 相对路径；非法 locator 保留原值供上层安全回退。 */
internal fun smbDanmakuMatchPath(url: String): String =
    SmbPlaybackLocator.parse(url)?.path ?: url

/**
 * 读取 SMB 文件前 16 MiB 计算弹弹play 哈希。只在 TMDB/播放记录未命中时调用，避免每次起播额外读 NAS。
 */
internal suspend fun calculateSmbDanmakuHash(
    url: String,
    repository: SmbConnectionRepository,
): Pair<Long, String>? = withContext(Dispatchers.IO) {
    val locator = SmbPlaybackLocator.parse(url) ?: return@withContext null
    val connection = repository.loadAll().firstOrNull { it.id == locator.connectionId }
        ?.takeUnless { it.credentialUnavailable }
        ?: return@withContext null

    runSuspendCatching {
        AndroidSmbClient(connection).use { client ->
            client.openRead(locator.path).use { file ->
                val digest = Crypto.md5Accumulator()
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                val bytesToHash = minOf(file.size, HASH_PREFIX_BYTES)
                var offset = 0L
                while (offset < bytesToHash) {
                    currentCoroutineContext().ensureActive()
                    val count = file.read(
                        offset,
                        buffer,
                        (bytesToHash - offset).coerceAtMost(buffer.size.toLong()).toInt(),
                    )
                    if (count <= 0) return@runSuspendCatching null
                    digest.update(buffer, 0, count)
                    offset += count
                }
                file.size to digest.hexDigest()
            }
        }
    }.getOrNull()
}

private const val HASH_PREFIX_BYTES = 16L * 1024L * 1024L
private const val HASH_BUFFER_BYTES = 1024 * 1024
