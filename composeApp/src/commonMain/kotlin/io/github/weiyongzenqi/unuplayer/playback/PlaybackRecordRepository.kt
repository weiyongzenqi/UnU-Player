package io.github.weiyongzenqi.unuplayer.playback

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
    /** 命中查询(续播/弹幕套用前查)。无记录返回 null。 */
    suspend fun getByMediaKey(mediaKey: String): PlaybackRecord?

    /** 批量查询(浏览列表"已播放进度"披露式查询用)。返回 media_key -> 记录 的映射。 */
    suspend fun getByMediaKeys(mediaKeys: List<String>): Map<String, PlaybackRecord>

    /** 整行写入(新建或更新; INSERT OR REPLACE)。id 自增忽略, sync_* 传 0。 */
    suspend fun upsert(record: PlaybackRecord)

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

    suspend fun deleteByKey(mediaKey: String)
    suspend fun deleteAll()
    suspend fun count(): Long
}
