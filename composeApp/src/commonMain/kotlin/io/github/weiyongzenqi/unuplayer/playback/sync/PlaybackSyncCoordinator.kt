package io.github.weiyongzenqi.unuplayer.playback.sync

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.PlaybackSyncMergeBatch
import io.github.weiyongzenqi.unuplayer.playback.episodeProgressKey
import io.github.weiyongzenqi.unuplayer.playback.mergeEpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.mergePlaybackRecordDimensions
import io.github.weiyongzenqi.unuplayer.playback.newerProgressDeletion
import io.github.weiyongzenqi.unuplayer.playback.newerRecordDeletion
import io.github.weiyongzenqi.unuplayer.playback.progressDeletionKey
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel
import io.github.weiyongzenqi.unuplayer.webdav.WebDavClient
import io.github.weiyongzenqi.unuplayer.webdav.WebDavException
import io.github.weiyongzenqi.unuplayer.webdav.resolveWebDavUrl
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatch
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatchDeletion
import io.github.weiyongzenqi.unuplayer.schedule.newerScheduleWatch
import io.github.weiyongzenqi.unuplayer.schedule.newerScheduleWatchDeletion
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * P2 WebDAV 播放记录同步协调器。
 *
 * 提供 push/pull/sync 三操作:
 * - push: 推送本地全量记录到 /.unuplayer/playback/v2/<deviceId>.json.gz(gzip 压缩二进制流)
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
    private val syncDirPath: String = CURRENT_SYNC_DIR,
    private val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxRemoteFiles: Int = DEFAULT_MAX_REMOTE_FILES,
    private val maxTotalPayloadBytes: Int = DEFAULT_MAX_TOTAL_PAYLOAD_BYTES,
    /**
     * 跨设备稳定媒体身份生成(media_key -> identity)。media_key 含本机 connectionId, 跨设备重装/新建
     * 连接后同文件 key 不同; push 端带身份进 DTO, pull 端按身份匹配合并, 避免重复记录。
     * null = 不启用身份匹配(回落按 media_key 精确匹配, 旧行为)。由调用方按连接仓库构造。
     */
    private val mediaIdentityResolver: (suspend (String) -> String?)? = null,
    /**
     * 按身份归属到本地 WebDAV 连接(端点+账号指纹匹配)。pull 拉到无本地匹配记录的身份时, 落库到
     * 本地连接的 media_key, 避免"以远端 connId 落库的 ghost 记录"以后与本机播放记录重复。
     * null = 无匹配时不迁移(按远端 key 落库, 旧行为)。
     */
    private val localTargetByIdentity: (suspend (String) -> LocalSyncTarget?)? = null,
    /** 与播放记录位于同一 SQLDelight 数据库；用于读取标记快照，合并写入仍走 playback 单事务。 */
    private val scheduleRepository: ScrapedLibraryRepository? = null,
) {
    private val legacySyncDirPath: String? =
        if (syncDirPath == CURRENT_SYNC_DIR) LEGACY_SYNC_DIR else null

    init {
        require(syncDirPath.startsWith("/") && syncDirPath.endsWith("/")) {
            "同步目录须为绝对路径且以 / 结尾"
        }
        require(syncDirPath != LEGACY_SYNC_DIR) { "旧同步目录仅允许作为 v2 的只读导入来源" }
        require(maxRemoteFiles > 0 && maxTotalPayloadBytes > 0) { "同步远端预算必须为正数" }
    }

    /**
     * 同步结果: 推送/拉取/合并的计数 + 错误信息。
     */
    data class PlaybackSyncResult(
        val success: Boolean,
        val pushed: Int = 0,
        val pushedProgress: Int = 0,
        val pulled: Int = 0,
        val mergedRecords: Int = 0,
        val mergedProgress: Int = 0,
        val pushedScheduleWatches: Int = 0,
        val mergedScheduleWatches: Int = 0,
        val error: String? = null,
        val errorCode: PlaybackSyncErrorCode? = null,
    )

    enum class PlaybackSyncErrorCode {
        BASE_PAYLOAD_EXCEEDS_LIMIT,
        DELETION_METADATA_EXCEEDS_LIMIT,
        ACTIVE_ENTRY_EXCEEDS_LIMIT,
    }

    /** 身份归属到的本地 WebDAV 连接目标。 */
    data class LocalSyncTarget(val connectionId: String, val baseUrl: String)

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
            mergedScheduleWatches = pullResult.mergedScheduleWatches,
        )
    }

    /**
     * 推送本地全量记录到 /.unuplayer/playback/v2/<deviceId>.json.gz(gzip 压缩二进制流)。
     * 排除 position=0 且未完成的记录(进入播放即 upsert，同步过去会跨设备重置进度)。
     * 超限 LRU 截断(按 last_played_at 降序保留最新)。
     */
    suspend fun push(): PlaybackSyncResult {
        val deviceId = deviceIdProvider()
        val records = repository.listAll()
        val progress = repository.listAllEpisodeProgress()
        val historyEpoch = repository.getPlaybackHistoryEpoch()
        val recordDeletions = repository.listPlaybackRecordDeletions()
        val progressDeletions = repository.listEpisodeProgressDeletions()
        val scheduleWatches = scheduleRepository?.listScheduleWatches().orEmpty()
        val scheduleWatchDeletions = scheduleRepository?.listScheduleWatchDeletions().orEmpty()

        // position=0 过滤: 排除"无进度且未完成"的记录
        val filtRecords = records.filterNot { it.position_ms <= 0L && it.is_completed == 0L }
        val filtProgress = progress.filterNot { it.position_ms <= 0L && it.is_completed == 0L }

        val recordDtos = filtRecords.map { it.toSyncDto(resolveMediaIdentity(it.media_key)) }
        val progressDtos = filtProgress.map {
            // B-2: EpisodeProgress 同样带身份, pull 侧才能把远端 connId 的 ghost media_key 归置到本地连接。
            it.toSyncDto(it.media_key?.let { key -> resolveMediaIdentity(key) })
        }
        val initialPayload = PlaybackSyncPayload(
            deviceId = deviceId,
            deviceName = deviceNameProvider(),
            records = recordDtos,
            episodeProgress = progressDtos,
            schemaVersion = CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION,
            historyEpoch = historyEpoch,
            recordDeletions = recordDeletions.map { deletion ->
                deletion.toSyncDto(resolveMediaIdentity(deletion.mediaKey))
            },
            progressDeletions = progressDeletions.map { deletion ->
                deletion.toSyncDto(deletion.mediaKey?.let { resolveMediaIdentity(it) })
            },
            scheduleWatches = scheduleWatches.map { it.toSyncDto() },
            scheduleWatchDeletions = scheduleWatchDeletions.map { it.toSyncDto() },
        )
        if (!initialPayload.hasSafeLogicalVersions() || !initialPayload.hasValidScheduleWatchData()) {
            logger?.appEvent("playback-sync", "本地同步状态逻辑版本超出安全范围或格式不安全，已拒绝上传", LogLevel.WARN)
            return PlaybackSyncResult(success = false, error = "推送失败: 本地同步状态逻辑版本超出安全范围或格式不安全")
        }
        val budgetResult = fitPayloadToBudget(initialPayload)
        val payload = when (budgetResult) {
            is PayloadBudgetResult.Fitted -> budgetResult.payload
            is PayloadBudgetResult.Rejected -> return budgetResult.toSyncResult()
        }
        val json = playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), payload)

        // 递归建目录: WebDAV MKCOL 只能建一层, 父目录不存在时建子目录会 409。
        // /.unuplayer/、/.unuplayer/playback/ 与 v2 子目录分别建; 已存在(405)视成功。
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

        return PlaybackSyncResult(
            success = true,
            pushed = payload.records.size,
            pushedProgress = payload.episodeProgress.size,
            pushedScheduleWatches = payload.scheduleWatches.size,
        )
    }

    /**
     * 从同步目录拉取所有设备 payload，合并决策后写入本地。
     * 合并决策: remote.sync_version > local.sync_version 写入; 平手比 last_played_at;
     * local 胜时不写。
     */
    suspend fun pull(): PlaybackSyncResult {
        // 递归建目录(push 用; pull 允许目录不存在=首次拉取服务器尚无记录)。
        ensureSyncDirs()

        // v2 有独立目录，旧 0.1.7 只会枚举父目录的直接文件，不会看到 v2 子目录。
        // v2 尚无任何快照时兼容只读导入旧目录；只要 v2 保持至少一个快照，就不再读取旧目录，避免混版本回流。
        val primaryResult = runSuspendCatching { client.listDirectoryAll(syncDirPath) }
        var jsonFiles: List<MediaEntry>
        var selectedLegacySnapshots = false
        if (primaryResult.isSuccess) {
            jsonFiles = primaryResult.getOrThrow().filter(::isSyncJsonFile)
            if (jsonFiles.isEmpty() && legacySyncDirPath != null) {
                val legacyResult = runSuspendCatching { client.listDirectoryAll(legacySyncDirPath) }
                if (legacyResult.isSuccess) {
                    jsonFiles = legacyResult.getOrThrow().filter(::isSyncJsonFile)
                    if (jsonFiles.isNotEmpty()) selectedLegacySnapshots = true
                } else if (!isMissingDirectory(legacyResult.exceptionOrNull())) {
                    return directoryFailure(legacySyncDirPath, legacyResult.exceptionOrNull())
                }
            }
        } else if (legacySyncDirPath != null && isMissingDirectory(primaryResult.exceptionOrNull())) {
            val legacyResult = runSuspendCatching { client.listDirectoryAll(legacySyncDirPath) }
            if (legacyResult.isFailure && !isMissingDirectory(legacyResult.exceptionOrNull())) {
                return directoryFailure(legacySyncDirPath, legacyResult.exceptionOrNull())
            }
            jsonFiles = legacyResult.getOrNull()?.filter(::isSyncJsonFile).orEmpty()
            if (jsonFiles.isNotEmpty()) selectedLegacySnapshots = true
        } else if (isMissingDirectory(primaryResult.exceptionOrNull())) {
            return PlaybackSyncResult(success = true, pulled = 0)
        } else {
            return directoryFailure(syncDirPath, primaryResult.exceptionOrNull())
        }

        if (jsonFiles.isEmpty()) {
            return PlaybackSyncResult(success = true, pulled = 0)
        }

        if (jsonFiles.size > maxRemoteFiles) {
            return PlaybackSyncResult(success = false, error = "同步目录文件数超过安全上限")
        }

        // 先严格拉取并验证全部候选。任一文件读取、解压或解析失败时必须在第一次本地写库前返回，
        // sync() 也会因此跳过本轮 push，避免用空/陈旧本地状态覆盖唯一可恢复远端副本。
        // 同时限制累计解压文本预算，避免多个合法 8MiB 文件在 validatedPayloads 中叠加耗尽内存。
        val validatedPayloads = mutableListOf<PlaybackSyncPayload>()
        var totalPayloadBytes = 0
        for (entry in jsonFiles) {
            val bytesResult = runSuspendCatching { client.fetchBytesStrict(entry.path) }
            if (bytesResult.isFailure) {
                val error = bytesResult.exceptionOrNull()
                return PlaybackSyncResult(
                    success = false,
                    error = "拉取 ${entry.name} 失败: ${error?.message ?: error?.let { it::class.simpleName } ?: "未知"}",
                )
            }

            val textResult = runCatching { gzipDecompress(bytesResult.getOrThrow()) }
            if (textResult.isFailure) {
                return PlaybackSyncResult(success = false, error = "解压 ${entry.name} 失败")
            }

            val text = textResult.getOrThrow()
            val textBytes = text.encodeToByteArray().size
            totalPayloadBytes += textBytes
            if (totalPayloadBytes > maxTotalPayloadBytes) {
                return PlaybackSyncResult(success = false, error = "同步快照解压总量超过安全上限")
            }
            val payloadResult = runCatching {
                val element = playbackSyncJson.parseToJsonElement(text)
                val declaredSchema = (element as? JsonObject)?.get("schemaVersion")
                if (!selectedLegacySnapshots) {
                    val declaredVersion = (declaredSchema as? JsonPrimitive)?.intOrNull
                    if (declaredVersion != CURRENT_PLAYBACK_SYNC_SCHEMA_VERSION) {
                        error("同步快照协议版本不受支持")
                    }
                } else if (declaredSchema != null) {
                    error("旧同步快照不得声明协议版本")
                }
                playbackSyncJson.decodeFromString(PlaybackSyncPayload.serializer(), text)
            }
            if (payloadResult.isFailure) {
                return PlaybackSyncResult(
                    success = false,
                    error = "解析 ${entry.name} 失败: ${payloadResult.exceptionOrNull()?.message ?: "未知"}",
                )
            }
            val payload = payloadResult.getOrThrow()
            if ((!selectedLegacySnapshots && !payload.hasSupportedSchemaVersion()) ||
                !payload.hasSafeLogicalVersions() || !payload.hasValidScheduleWatchData()
            ) {
                return PlaybackSyncResult(success = false, error = "同步快照格式或逻辑版本不安全")
            }
            if (!payload.hasConsistentMediaIdentityPaths()) {
                return PlaybackSyncResult(success = false, error = "同步快照媒体身份与媒体键不一致")
            }
            validatedPayloads += payload
        }

        // 远端时间戳截断基准: 跨设备时钟超前时把写入值钳到本机 now, 防冻结本机节流写/退出写
        val nowMillis = platformTimeMillis()

        // 预载本地全量(避免逐条查)。用可变 map 以便合并写入后更新缓存，防止后续低版本覆盖高版本。
        val localEpoch = repository.getPlaybackHistoryEpoch()
        val remoteEpoch = validatedPayloads.maxOfOrNull { it.historyEpoch.coerceAtLeast(0L) } ?: localEpoch
        val targetEpoch = maxOf(localEpoch, remoteEpoch)
        val localRecords = repository.listAll().associateBy { it.media_key }

        // 本地 media_key -> 稳定身份 映射: remote 记录带身份时优先按身份定位本地记录,
        // 使跨设备同一文件(不同 connectionId)进入同一记录的版本比较, 不产生重复记录。
        val identityToLocalKey = mutableMapOf<String, String>()
        localRecords.values.forEach { record ->
            resolveMediaIdentity(record.media_key)?.let { identityToLocalKey.putIfAbsent(it, record.media_key) }
        }

        suspend fun resolveLocalMediaKey(identity: String?, remoteMediaKey: String): Pair<String, String?>? {
            if (identity == null) return null
            val path = parseSyncMediaIdentityPath(identity) ?: return null
            if (parseWebDavMediaKeyPath(remoteMediaKey) != path) return null
            identityToLocalKey[identity]?.let { return it to null }
            val localTarget = runSuspendCatching { localTargetByIdentity?.invoke(identity) }.getOrNull() ?: return null
            return ("webdav:${localTarget.connectionId}:$path") to localTarget.baseUrl
        }

        val recordsByKey = linkedMapOf<String, PlaybackRecord>()
        val recordDeletionsByKey = linkedMapOf<String, io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordDeletion>()
        val progressByKey = linkedMapOf<String, EpisodeProgress>()
        val progressDeletionsByKey = linkedMapOf<String, io.github.weiyongzenqi.unuplayer.playback.EpisodeProgressDeletion>()
        val scheduleWatchesBySubject = linkedMapOf<Long, ScheduleWatch>()
        val scheduleWatchDeletionsBySubject = linkedMapOf<Long, ScheduleWatchDeletion>()

        for (payload in validatedPayloads) {
            // 标记状态与“清空全部播放记录”的 history epoch 无关；所有已验证设备都参加标记仲裁。
            // 只有播放记录、集级进度及其 tombstone 才按最高 epoch 过滤。
            for (watch in payload.scheduleWatches) {
                val candidate = watch.toScheduleWatch(nowMillis)
                scheduleWatchesBySubject[candidate.subjectId] = newerScheduleWatch(
                    scheduleWatchesBySubject[candidate.subjectId],
                    candidate,
                )
            }
            for (deletion in payload.scheduleWatchDeletions) {
                val candidate = deletion.toScheduleWatchDeletion(nowMillis)
                scheduleWatchDeletionsBySubject[candidate.subjectId] = newerScheduleWatchDeletion(
                    scheduleWatchDeletionsBySubject[candidate.subjectId],
                    candidate,
                )
            }
            if (payload.historyEpoch.coerceAtLeast(0L) != targetEpoch) continue
            for (r in payload.records) {
                val remap = resolveLocalMediaKey(r.media_identity, r.media_key)
                val targetKey = remap?.first ?: r.media_key
                val localTargetBaseUrl = remap?.second
                val local = localRecords[targetKey]
                val resolvedUrl = if (r.media_identity != null && local != null && local.url.isNotBlank()) {
                    local.url
                } else {
                    resolveRecordUrl(r, targetKey, localTargetBaseUrl)
                }
                val candidate = r.toRecord(resolvedUrl).copy(
                    media_key = targetKey,
                    last_played_at = r.last_played_at.coerceAtLeast(0L).coerceAtMost(nowMillis),
                    sync_version = r.sync_version.coerceAtLeast(0L),
                    danmaku_sync_version = r.danmaku_sync_version.coerceAtLeast(0L),
                    danmaku_updated_at = r.danmaku_updated_at.coerceAtLeast(0L).coerceAtMost(nowMillis),
                )
                recordsByKey[targetKey] = mergePlaybackRecordDimensions(recordsByKey[targetKey], candidate)
                r.media_identity?.let { identityToLocalKey.putIfAbsent(it, targetKey) }
            }
            for (deletion in payload.recordDeletions) {
                val remap = resolveLocalMediaKey(deletion.media_identity, deletion.media_key)
                val targetKey = remap?.first ?: deletion.media_key
                val candidate = deletion.toDeletion().copy(
                    mediaKey = targetKey,
                    deletedAt = deletion.deleted_at.coerceAtLeast(0L).coerceAtMost(nowMillis),
                    syncVersion = deletion.sync_version.coerceAtLeast(0L),
                )
                recordDeletionsByKey[targetKey] = newerRecordDeletion(recordDeletionsByKey[targetKey], candidate)
                deletion.media_identity?.let { identityToLocalKey.putIfAbsent(it, targetKey) }
            }
            for (r in payload.episodeProgress) {
                val key = episodeProgressKey(r.tmdb_id, r.season_number, r.episode_number)
                val remappedKey = r.media_key?.let { mk -> resolveLocalMediaKey(r.media_identity, mk)?.first } ?: r.media_key
                val candidate = r.toProgress().copy(
                    media_key = remappedKey,
                    last_played_at = r.last_played_at.coerceAtLeast(0L).coerceAtMost(nowMillis),
                    sync_version = r.sync_version.coerceAtLeast(0L),
                )
                progressByKey[key] = mergeEpisodeProgress(progressByKey[key], candidate)
                r.media_identity?.let { identity -> remappedKey?.let { identityToLocalKey.putIfAbsent(identity, it) } }
            }
            for (deletion in payload.progressDeletions) {
                val remappedKey = deletion.media_key?.let { mk -> resolveLocalMediaKey(deletion.media_identity, mk)?.first } ?: deletion.media_key
                val candidate = deletion.toDeletion().copy(
                    mediaKey = remappedKey,
                    deletedAt = deletion.deleted_at.coerceAtLeast(0L).coerceAtMost(nowMillis),
                    syncVersion = deletion.sync_version.coerceAtLeast(0L),
                )
                val key = progressDeletionKey(candidate.tmdbId, candidate.seasonNumber, candidate.episodeNumber)
                progressDeletionsByKey[key] = newerProgressDeletion(progressDeletionsByKey[key], candidate)
            }
        }

        val mergeResult = runSuspendCatching {
            repository.applySyncMergeBatch(
                PlaybackSyncMergeBatch(
                    historyEpoch = targetEpoch,
                    records = recordsByKey.values.toList(),
                    episodeProgress = progressByKey.values.toList(),
                    recordDeletions = recordDeletionsByKey.values.toList(),
                    progressDeletions = progressDeletionsByKey.values.toList(),
                    scheduleWatches = scheduleWatchesBySubject.values.toList(),
                    scheduleWatchDeletions = scheduleWatchDeletionsBySubject.values.toList(),
                ),
            )
        }
        if (mergeResult.isFailure) {
            val error = mergeResult.exceptionOrNull()
            return PlaybackSyncResult(success = false, error = "合并同步数据失败: ${error?.message ?: error?.let { it::class.simpleName }}")
        }
        val merged = mergeResult.getOrThrow()
        if (merged.mergedScheduleWatches > 0) {
            runSuspendCatching { scheduleRepository?.invalidateScheduleWatchObservers() }
                .onFailure { error ->
                    logger?.appEvent(
                        "playback-sync",
                        "已标记番剧同步已提交，但页面状态通知失败: ${error::class.simpleName}",
                        LogLevel.WARN,
                    )
                }
        }
        return PlaybackSyncResult(
            success = true,
            pulled = validatedPayloads.size,
            mergedRecords = merged.mergedRecords + merged.mergedRecordDeletions,
            mergedProgress = merged.mergedProgress + merged.mergedProgressDeletions,
            mergedScheduleWatches = merged.mergedScheduleWatches,
        )
    }

    private data class ActivePayloadItem(
        val record: PlaybackSyncRecord? = null,
        val progress: PlaybackSyncEpisodeProgress? = null,
        val lastPlayedAt: Long,
        val stableKey: String,
    )

    private sealed interface PayloadBudgetResult {
        data class Fitted(val payload: PlaybackSyncPayload) : PayloadBudgetResult

        data class Rejected(
            val reason: PlaybackSyncErrorCode,
            val initialBytes: Int,
            val baseBytes: Int,
            val mandatoryBytes: Int,
            val activeCount: Int,
            val recordDeletionCount: Int,
            val progressDeletionCount: Int,
        ) : PayloadBudgetResult
    }

    /** tombstone/epoch 是强制元数据，只对 active 记录做确定性新近优先裁剪。 */
    private fun fitPayloadToBudget(payload: PlaybackSyncPayload): PayloadBudgetResult {
        fun encodedSize(candidate: PlaybackSyncPayload): Int =
            playbackSyncJson.encodeToString(PlaybackSyncPayload.serializer(), candidate)
                .encodeToByteArray().size

        val initialBytes = encodedSize(payload)
        if (initialBytes <= maxPayloadBytes) return PayloadBudgetResult.Fitted(payload)

        val items = buildList {
            payload.records.forEach { record ->
                add(
                    ActivePayloadItem(
                        record = record,
                        lastPlayedAt = record.last_played_at,
                        stableKey = "R:${record.media_key}",
                    ),
                )
            }
            payload.episodeProgress.forEach { progress ->
                add(
                    ActivePayloadItem(
                        progress = progress,
                        lastPlayedAt = progress.last_played_at,
                        stableKey = "P:${progress.tmdb_id}:${progress.season_number}:${progress.episode_number}",
                    ),
                )
            }
        }.sortedWith(compareByDescending<ActivePayloadItem> { it.lastPlayedAt }.thenBy { it.stableKey })

        fun withNewest(count: Int): PlaybackSyncPayload {
            val kept = items.take(count)
            return payload.copy(
                records = kept.mapNotNull { it.record },
                episodeProgress = kept.mapNotNull { it.progress },
            )
        }

        val baseBytes = encodedSize(
            payload.copy(
                records = emptyList(),
                episodeProgress = emptyList(),
                recordDeletions = emptyList(),
                progressDeletions = emptyList(),
            ),
        )
        val mandatoryBytes = encodedSize(withNewest(0))
        if (mandatoryBytes > maxPayloadBytes) {
            val reason = if (baseBytes > maxPayloadBytes) {
                PlaybackSyncErrorCode.BASE_PAYLOAD_EXCEEDS_LIMIT
            } else {
                PlaybackSyncErrorCode.DELETION_METADATA_EXCEEDS_LIMIT
            }
            return PayloadBudgetResult.Rejected(
                reason = reason,
                initialBytes = initialBytes,
                baseBytes = baseBytes,
                mandatoryBytes = mandatoryBytes,
                activeCount = items.size,
                recordDeletionCount = payload.recordDeletions.size,
                progressDeletionCount = payload.progressDeletions.size,
            )
        }

        var low = 0
        var high = items.size
        var bestCount = -1
        var bestPayload: PlaybackSyncPayload? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = withNewest(middle)
            val size = encodedSize(candidate)
            if (size <= maxPayloadBytes) {
                bestCount = middle
                bestPayload = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (items.isNotEmpty() && bestCount <= 0) {
            return PayloadBudgetResult.Rejected(
                reason = PlaybackSyncErrorCode.ACTIVE_ENTRY_EXCEEDS_LIMIT,
                initialBytes = initialBytes,
                baseBytes = baseBytes,
                mandatoryBytes = mandatoryBytes,
                activeCount = items.size,
                recordDeletionCount = payload.recordDeletions.size,
                progressDeletionCount = payload.progressDeletions.size,
            )
        }
        return PayloadBudgetResult.Fitted(requireNotNull(bestPayload))
    }

    private fun PayloadBudgetResult.Rejected.toSyncResult(): PlaybackSyncResult {
        val counts = "active=$activeCount, 记录删除=$recordDeletionCount, 进度删除=$progressDeletionCount"
        val sizes = "初始=$initialBytes 字节, 基础=$baseBytes 字节, 强制元数据=$mandatoryBytes 字节, 上限=$maxPayloadBytes 字节"
        val detail = when (reason) {
            PlaybackSyncErrorCode.BASE_PAYLOAD_EXCEEDS_LIMIT ->
                "同步基础载荷超过上限（$counts；$sizes）"
            PlaybackSyncErrorCode.DELETION_METADATA_EXCEEDS_LIMIT ->
                "删除事件元数据超过同步载荷上限（$counts；$sizes）。为防止离线设备恢复已删除记录，" +
                    "未自动丢弃删除事件；请在设置的播放记录中主动“清空全部播放记录”后重试同步"
            PlaybackSyncErrorCode.ACTIVE_ENTRY_EXCEEDS_LIMIT ->
                "最新一条有效记录在保留删除事件后仍超过同步载荷上限（$counts；$sizes）"
        }
        logger?.appEvent("playback-sync", "分类=${reason.name}; $detail", LogLevel.WARN)
        return PlaybackSyncResult(
            success = false,
            error = "推送失败: $detail",
            errorCode = reason,
        )
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

    private fun isSyncJsonFile(entry: MediaEntry): Boolean =
        !entry.isDirectory && entry.name.endsWith(".json.gz", ignoreCase = true)

    private fun isMissingDirectory(error: Throwable?): Boolean =
        (error as? WebDavException)?.statusCode?.let { it in setOf(404, 405, 409) } == true

    private fun directoryFailure(path: String, error: Throwable?): PlaybackSyncResult {
        val message = error?.message ?: error?.let { it::class.simpleName } ?: "未知"
        return PlaybackSyncResult(success = false, error = "枚举同步目录 $path 失败: $message")
    }

    /**
     * 重算合并记录的播放 url。WebDAV 记录的 media_key 含 path(connId 跨设备不稳), 用目标连接 baseUrl + path 重算合法 url。
     * [baseUrlOverride] 为身份归属的本地连接 baseUrl(优先), 否则用同步连接 client。
     * 非 WebDAV / 无法解析 path 的记录返回 null(toRecord 退回 media_key 兜底; 这类记录恢复靠三元组续播, url 非关键)。
     */
    private fun resolveRecordUrl(remote: PlaybackSyncRecord, targetKey: String, baseUrlOverride: String? = null): String? {
        if (remote.source_kind.trim().uppercase() != "WEBDAV") return null
        val path = parseWebDavMediaKeyPath(targetKey) ?: return null
        return if (baseUrlOverride != null) {
            runCatching { resolveWebDavUrl(baseUrlOverride, path) }.getOrNull()
        } else {
            runCatching { client.resolvePlayUrl(path) }.getOrNull()
        }
    }

    /** 生成跨设备稳定媒体身份; resolver 异常按 null 处理(不阻断同步)。 */
    private suspend fun resolveMediaIdentity(mediaKey: String): String? =
        runSuspendCatching { mediaIdentityResolver?.invoke(mediaKey) }.getOrNull()

    companion object {
        internal const val CURRENT_SYNC_DIR = "/.unuplayer/playback/v2/"
        internal const val LEGACY_SYNC_DIR = "/.unuplayer/playback/"
        const val DEFAULT_MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_REMOTE_FILES = 64
        const val DEFAULT_MAX_TOTAL_PAYLOAD_BYTES = 32 * 1024 * 1024
    }
}
