package io.github.weiyongzenqi.unuplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NoPlaybackChanges = MutableStateFlow(0L)

/**
 * 播放记录仓库(commonMain 接口, androidMain 用 SQLDelight 实现)。
 *
 * 记录以 media_key 唯一(WebDAV=url, 本地=contentUri 优先 fallback fileHash)。
 * 性能: media_key UNIQUE 索引命中 O(log n); 历史列表分页; 高频位置写用 [updatePosition]
 * (单行 UPDATE) 比 [upsert] (整行 INSERT OR REPLACE) 轻, 节流策略在调用方(PlayerScreen)。
 *
 * 弹幕匹配信息(danmaku_episode_id 等)同表存, 恢复播放时套用省哈希/网络(见 3c)。
 */
interface PlaybackRecordRepository {
    /**
     * 成功写入播放记录后递增。详情页据此重读当前季进度；默认值让测试替身保持兼容。
     */
    val changeVersion: StateFlow<Long>
        get() = NoPlaybackChanges

    /** 命中查询(续播/弹幕套用前查)。无记录返回 null。 */
    suspend fun getByMediaKey(mediaKey: String): PlaybackRecord?

    /** 批量查询(浏览列表"已播放进度"披露式查询用)。返回 media_key -> 记录 的映射。 */
    suspend fun getByMediaKeys(mediaKeys: List<String>): Map<String, PlaybackRecord>

    /** 整行写入(新建或更新; INSERT OR REPLACE)。id 自增忽略, sync_* 传 0。 */
    suspend fun upsert(record: PlaybackRecord)

    /**
     * 播放入口专用 upsert: sync_version 由 SQL 在事务内原子 +1(基于写入时的行内版本),
     * 消除调用方"快照读 -> 内存 v+1 -> upsert"在事务外的 Lamport 回退窗口(B-1):
     * 读与写之间 pull 合并高版本时, 旧快照 v+1 会把高版本回退。调用方传的 sync_version
     * 被忽略(该字段保持 0 即可); 其余字段语义与 [upsert] 一致(含 EpisodeProgress 镜像双写)。
     */
    suspend fun upsertEntry(record: PlaybackRecord)

    /**
     * 退出播放时存: 仅更新位置/时长/进度/完成态/时间, 不碰弹幕匹配字段
     * (避免整行 upsert 覆盖 3c 存的匹配信息)。记录不存在时 no-op。
     */
    suspend fun finishPlayback(
        mediaKey: String, positionMs: Long, durationMs: Long,
        watchProgress: Double, isCompleted: Long, lastPlayedAt: Long,
    )

    /** 仅更新位置(高频节流写, 比 upsert 轻)。 */
    suspend fun updatePosition(mediaKey: String, positionMs: Long, watchProgress: Double, lastPlayedAt: Long)

    /** 仅更新弹幕匹配信息(匹配成功后存, 下次播放套用省 hash+网络)。 */
    suspend fun updateDanmaku(
        mediaKey: String, episodeId: Long, animeId: Long,
        animeTitle: String, episodeTitle: String, matchMethod: String,
    )

    /** 历史列表分页(按 last_played_at 倒序)。 */
    suspend fun listPage(limit: Long, offset: Long): List<PlaybackRecord>

    /** 三元组(tmdbId+季+集)语义进度(跨刮削库续播锚点)。仅 tmdbId 刮削且 episode>0 的记录有对应行; 无则 null。 */
    suspend fun getEpisodeProgressByTriple(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): EpisodeProgress?

    /** 批量按三元组查(详情页剧集进度条补齐用)。返回 key="tmdb-season-episode" -> EpisodeProgress 映射。 */
    suspend fun getEpisodeProgressByTriples(tripleKeys: List<String>): Map<String, EpisodeProgress>

    suspend fun deleteByKey(mediaKey: String)
    suspend fun deleteAll()
    suspend fun count(): Long

    /** P2 同步: 全量读(push 用)。 */
    suspend fun listAll(): List<PlaybackRecord>
    suspend fun listAllEpisodeProgress(): List<EpisodeProgress>
    suspend fun getPlaybackHistoryEpoch(): Long = 0L
    suspend fun listPlaybackRecordDeletions(): List<PlaybackRecordDeletion> = emptyList()
    suspend fun listEpisodeProgressDeletions(): List<EpisodeProgressDeletion> = emptyList()

    /** 严格同步批量合并；生产实现必须在一个数据库事务内重读并应用全部候选。 */
    suspend fun applySyncMergeBatch(batch: PlaybackSyncMergeBatch): PlaybackSyncMergeResult {
        // 仅为轻量测试替身保留兼容默认实现；Android/Desktop SQLDelight 实现会覆盖为单事务版本。
        var records = 0
        var progress = 0
        batch.records.forEach { if (applyMergedRecordIfNewer(it)) records++ }
        batch.episodeProgress.forEach { if (applyMergedEpisodeProgressIfNewer(it)) progress++ }
        return PlaybackSyncMergeResult(mergedRecords = records, mergedProgress = progress)
    }

    /** P2 同步: 合并写入(pull 后 Coordinator 决策胜出方写入, 无 last_played_at 守卫, sync_status 置 0)。 */
    suspend fun applyMergedRecord(record: PlaybackRecord)
    suspend fun applyMergedEpisodeProgress(progress: EpisodeProgress)

    /**
     * 版本比较后原子合并(媒体库导入播放用): 仅在 record 比本地更新时写入。
     * 事务内 读-判-写, 消除"快照读+内存判断+逐条 upsert"的并发窗口(播放器并发写不会被旧导入数据覆盖)。
     * 比较规则与同步 pull 一致: sync_version 逻辑时钟优先, 平手比 last_played_at。
     * @return 是否实际写入。
     */
    suspend fun applyMergedRecordIfNewer(record: PlaybackRecord): Boolean
    suspend fun applyMergedEpisodeProgressIfNewer(progress: EpisodeProgress): Boolean
}
