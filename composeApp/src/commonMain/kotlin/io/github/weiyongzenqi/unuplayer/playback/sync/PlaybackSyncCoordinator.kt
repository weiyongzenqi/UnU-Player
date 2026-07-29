package io.github.weiyongzenqi.unuplayer.playback.sync

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.episodeProgressKey
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import io.github.weiyongzenqi.unuplayer.webdav.WebDavException

/**
 * P2 WebDAV 播放记录同步协调器。
 *
 * 提供 push/pull/sync 三操作:
 * - push: 推送本地全量记录到 /.unuplayer/playback/<deviceId>.json.gz(gzip 压缩二进制流)
 * - pull: 从同步目录拉取所有设备 payload, 合并决策后写入本地
 * - sync: 先 pull 后 push（重装空库先拉回旧记录，再推含恢复数据的新文件）
 *
 * 合并决策(LWW): remote.sync_version > local.sync_version 写入; 平手比 last_played_at;
 * local 胜时不写（幂等，避免无意义写）。applyMerged 是 force upsert，只对胜出方调用。
 */
class PlaybackSyncCoordinator(
    private val repository: PlaybackRecordRepository,
    private val client: WebDavClient,
    private val deviceIdProvider: suspend () -> String,
    private val deviceNameProvider: () -> String = { "UnU Player" },
    private val logger: AppLogger? = null,
    private val syncDirPath: String = DEFAULT_SYNC_DIR,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    init {
        require(syncDirPath.startsWith("/") && syncDirPath.endsWith("/")) {
            "同步目录须为绝对路径且以 / 结尾"
        }
    }

    /**
     * 同步结果: 推送/拉取/合并的计数 + 错误信息。
     */
    data class PlaybackSyncResult(
        val success: Boolean,
        val pushed: Int = 0,
        val pulled: Int = 0,
        val mergedRecords: Int = 0,
        val mergedProgress: Int = 0,
        val error: String? = null,
    )

    /**
     * 同步入口: 先 pull 后 push。
     * 重装空库先拉回旧记录合并，再推含恢复数据的新文件。
     */
    suspend fun sync(): PlaybackSyncResult {
        val pullResult = pull()
        if (!pullResult.success) return pullResult
        val pushResult = push()
        return pushResult.copy(
            pulled = pullResult.pulled,
            mergedRecords = pullResult.mergedRecords,
            mergedProgress = pullResult.mergedProgress,
        )
    }

    /**
     * 推送本地全量记录到 /.unuplayer/playback/<deviceId>.json.gz(gzip 压缩二进制流)。
     * 排除 position=0 且未完成的记录(进入播放即 upsert，同步过去会跨设备重置进度)。
     * 超限 LRU 截断(按 last_played_at 降序保留最新)。
     */
    suspend fun push(): PlaybackSyncResult {
        val deviceId = deviceIdProvider()
        val records = repository.listAll()
        val progress = repository.listAllEpisodeProgress()

        // position=0 过滤: 排除"无进度且未完成"的记录
        val filtRecords = records.filterNot { it.position_ms <= 0L && it.is_completed == 0L }
        val filtProgress = progress.filterNot { it.position_ms <= 0L && it.is_completed == 0L }

        val recordDtos = filtRecords.map { it.toSyncDto() }
        val progressDtos = filtProgress.map { it.toSyncDto() }

        var payload = PlaybackSyncPayload(
            deviceId = deviceId,
            deviceName = deviceNameProvider(),
            records = recordDtos,
            episodeProgress = progressDtos,
        )

        // LRU 截断: 超限时按 last_played_at 升序移除最旧项
        var json = playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload)
        var attempts = 0
        while (json.encodeToByteArray().size > maxPayloadBytes && attempts < MAX_LRU_TRUNCATE_ATTEMPTS) {
            val minRecord = payload.records.minByOrNull { it.last_played_at }
            val minProgress = payload.episodeProgress.minByOrNull { it.last_played_at }

            if (minRecord == null && minProgress == null) break

            // 选择 last_played_at 最小的项删除
            payload = when {
                minRecord != null && (minProgress == null || minRecord.last_played_at <= minProgress.last_played_at) -> {
                    payload.copy(records = payload.records.filterNot { it.media_key == minRecord.media_key })
                }
                minProgress != null -> {
                    payload.copy(episodeProgress = payload.episodeProgress.filterNot {
                        it.tmdb_id == minProgress.tmdb_id && it.season_number == minProgress.season_number && it.episode_number == minProgress.episode_number
                    })
                }
                else -> break
            }
            json = playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload)
            attempts++
        }

        if (json.encodeToByteArray().size > maxPayloadBytes) {
            logger?.appEvent(
                "playback-sync",
                "同步载荷超 ${maxPayloadBytes / 1024 / 1024}MiB, 已 LRU 截断但压缩前仍有 ${json.length / 1024}KiB",
                LogLevel.WARN,
            )
        }

        // 递归建目录: WebDAV MKCOL 只能建一层, 父目录不存在时建子目录会 409。
        // /.unuplayer/ 与 /.unuplayer/playback/ 分别建; 已存在(405)视成功。best-effort, 失败不阻断(目录可能已存在)。
        ensureSyncDirs()

        // 最终上传前 gzip 压缩(LRU 截断用压缩前 JSON 文本字节数判断, 保守)
        val compressed = gzipCompress(json)
        val pushResult = runSuspendCatching {
            client.uploadBytes("${syncDirPath}${deviceId}.json.gz", compressed)
        }

        if (pushResult.isFailure) {
            val err = pushResult.exceptionOrNull()
            return PlaybackSyncResult(
                success = false,
                error = "推送失败: ${err?.message ?: err?.let { it::class.simpleName } ?: "未知"}",
            )
        }

        if (pushResult.getOrNull() != true) {
            return PlaybackSyncResult(success = false, error = "推送失败: 服务器拒绝(非 2xx)")
        }

        return PlaybackSyncResult(success = true, pushed = filtRecords.size)
    }

    /**
     * 从同步目录拉取所有设备 payload，合并决策后写入本地。
     * 合并决策: remote.sync_version > local.sync_version 写入; 平手比 last_played_at;
     * local 胜时不写。
     */
    suspend fun pull(): PlaybackSyncResult {
        // 递归建目录(push 用; pull 允许目录不存在=首次拉取服务器尚无记录)。
        ensureSyncDirs()

        // 枚举同步目录: 失败时区分"目录不存在"(首次拉取, 当空处理)与"真错误"(认证/网络/服务器)。
        val entriesResult = runSuspendCatching { client.listDirectoryAll(syncDirPath) }
        if (entriesResult.isFailure) {
            val err = entriesResult.exceptionOrNull()
            val message = err?.message ?: err?.let { it::class.simpleName } ?: "未知"
            // 404/405/409 = 目录不存在(服务器首次同步, 尚无/.unuplayer/playback/), 当作空目录成功返回。
            // 不把这些当失败——否则重装空库首次 pull 就报错, 与"先 pull 后 push"语义冲突。
            // T2-m1: 结构化状态码判定, 替代旧的 message 子串匹配——失败消息内嵌 URL, 地址含
            // "404"/"405"/"409" 等数字(如端口 8404)时子串匹配会把真错误误判为空成功。现在只有
            // WebDavException 明确携带这三个码才当空; statusCode=null(纯连接异常/超时/响应超限)
            // 与其他状态码(401/403/5xx 等)一律按真失败返回。
            val treatedAsEmpty = (err as? WebDavException)?.statusCode?.let { it in setOf(404, 405, 409) } == true
            if (treatedAsEmpty) {
                return PlaybackSyncResult(success = true, pulled = 0)
            }
            return PlaybackSyncResult(
                success = false,
                error = "枚举同步目录失败: $message",
            )
        }

        val entries = entriesResult.getOrNull() ?: emptyList()
        val jsonFiles = entries.filter { !it.isDirectory && it.name.endsWith(".json.gz", ignoreCase = true) }

        if (jsonFiles.isEmpty()) {
            return PlaybackSyncResult(success = true, pulled = 0)
        }

        // 预载本地全量(避免逐条查)。用可变 map 以便合并写入后更新缓存，防止后续低版本覆盖高版本。
        val localRecords = repository.listAll().associateBy { it.media_key }.toMutableMap()
        val localProgress = repository.listAllEpisodeProgress().associateBy {
            episodeProgressKey(it.tmdb_id, it.season_number, it.episode_number)
        }.toMutableMap()

        var mergedRecords = 0
        var mergedProgress = 0
        var pulled = 0

        for (entry in jsonFiles) {
            // 拉 gzip 字节并解压: fetchBytes 失败(网络/404)跳过; gzipDecompress 失败(损坏/非 gzip)跳过
            val bytes = runSuspendCatching { client.fetchBytes(entry.path) }.getOrNull()
            if (bytes == null) {
                logger?.appEvent(
                    "playback-sync",
                    "拉取 ${entry.name} 失败或为空, 跳过",
                    LogLevel.WARN,
                )
                continue
            }

            val text = runCatching { gzipDecompress(bytes) }.getOrNull()
            if (text == null) {
                logger?.appEvent(
                    "playback-sync",
                    "解压 ${entry.name} 失败, 跳过",
                    LogLevel.WARN,
                )
                continue
            }

            val payload = runCatching {
                playbackSyncJson.decodeFromString(PlaybackSyncPayload.serializer(), text)
            }.getOrNull()

            if (payload == null) {
                logger?.appEvent(
                    "playback-sync",
                    "解析 ${entry.name} 失败, 跳过",
                    LogLevel.WARN,
                )
                continue
            }

            // 合并 PlaybackRecord
            for (r in payload.records) {
                val local = localRecords[r.media_key]
                if (shouldRemoteWinRecord(local, r)) {
                    // WebDAV 记录: 用当前连接 baseUrl + media_key 的 path 重算 url(DTO 不带 url, 重算保证恢复路径合法)
                    val resolvedUrl = resolveRecordUrl(r)
                    val merged = r.toRecord(resolvedUrl)
                    repository.applyMergedRecord(merged)
                    localRecords[r.media_key] = merged // 更新缓存，防后续低版本覆盖高版本
                    mergedRecords++
                }
            }

            // 合并 EpisodeProgress
            for (r in payload.episodeProgress) {
                val key = episodeProgressKey(r.tmdb_id, r.season_number, r.episode_number)
                val local = localProgress[key]
                if (shouldRemoteWinProgress(local, r)) {
                    val merged = r.toProgress()
                    repository.applyMergedEpisodeProgress(merged)
                    localProgress[key] = merged // 更新缓存
                    mergedProgress++
                }
            }

            pulled++
        }

        return PlaybackSyncResult(
            success = true,
            pulled = pulled,
            mergedRecords = mergedRecords,
            mergedProgress = mergedProgress,
        )
    }

    /** 合并决策: remote 是否胜出(应写入本地)。比 sync_version 逻辑时钟，平手回落 last_played_at。 */
    private fun shouldRemoteWinRecord(local: PlaybackRecord?, remote: PlaybackSyncRecord): Boolean {
        if (local == null) return true // 本地无，纳入
        return when {
            remote.sync_version > local.sync_version -> true
            remote.sync_version < local.sync_version -> false
            else -> remote.last_played_at > local.last_played_at // 版本平手比墙钟；平手(<=)不写
        }
    }

    /** EpisodeProgress 合并决策: 同 PlaybackRecord。 */
    private fun shouldRemoteWinProgress(local: EpisodeProgress?, remote: PlaybackSyncEpisodeProgress): Boolean {
        if (local == null) return true
        return when {
            remote.sync_version > local.sync_version -> true
            remote.sync_version < local.sync_version -> false
            else -> remote.last_played_at > local.last_played_at
        }
    }

    /**
     * 递归建同步目录(WebDAV MKCOL 只建一层)。按路径段从外到内逐层 MKCOL, 已存在(405)视成功。
     * best-effort: 失败仅 WARN 不阻断(目录可能已存在, 或服务器不支持 MKCOL 但 PUT 会自动建)。
     */
    private suspend fun ensureSyncDirs() {
        val segments = syncDirPath.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current = "$current/$segment"
            runSuspendCatching { client.mkcol("$current/") }
                .onFailure { error ->
                    logger?.appEvent(
                        "playback-sync",
                        "建目录 $current/ 失败(可能已存在): ${error::class.simpleName}",
                        LogLevel.WARN,
                    )
                }
        }
    }

    /**
     * 重算合并记录的播放 url。WebDAV 记录的 media_key 含 path(connId 跨设备不稳), 用当前连接 baseUrl + path 重算合法 url。
     * 非 WebDAV / 无法解析 path 的记录返回 null(toRecord 退回 media_key 兜底; 这类记录恢复靠三元组续播, url 非关键)。
     */
    private fun resolveRecordUrl(remote: PlaybackSyncRecord): String? {
        if (remote.source_kind.trim().uppercase() != "WEBDAV") return null
        val path = parseWebDavMediaKeyPath(remote.media_key) ?: return null
        return runCatching { client.resolvePlayUrl(path) }.getOrNull()
    }

    private companion object {
        const val DEFAULT_SYNC_DIR = "/.unuplayer/playback/"
        const val DEFAULT_MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
        const val MAX_LRU_TRUNCATE_ATTEMPTS = 100
    }
}