package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry

/**
 * 弹幕匹配方式(用于日志/调试, 告知用户当前弹幕是怎么匹配到的)。
 */
enum class DanmakuMatchMethod {
    /** tmdbId 快速匹配(URL/文件名正则提 tmdbId -> search/episodes -> bangumi 集数定位)。 */
    TMDB_QUICK,

    /** 哈希匹配(前 16MB MD5 + fileSize -> match 接口 hashAndFileName)。 */
    HASH,

    /** 文件名模糊搜索回落(search/anime -> bangumi 集数定位; fd:// 等无法哈希的场景)。 */
    FILENAME_SEARCH,

    /** 手动匹配(用户在手动匹配对话框搜番+选集指定 episodeId)。 */
    MANUAL,

    /** 未匹配。 */
    NONE,
}

/**
 * 弹幕匹配结果(文件 -> 番剧节目)。
 *
 * @param episodeId    弹幕库 ID(拉弹幕用)
 * @param animeId      番剧 ID
 * @param animeTitle   番剧标题
 * @param episodeTitle 剧集标题
 * @param shift        弹幕时间偏移(秒), 弹弹play match 接口返回; 正数=弹幕延后, 负数=提前
 * @param matchMethod  匹配方式(见 [DanmakuMatchMethod]), 用于日志输出
 */
data class DanmakuMatchResult(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val episodeTitle: String,
    val shift: Int,
    val matchMethod: DanmakuMatchMethod = DanmakuMatchMethod.NONE,
)

/**
 * 弹幕数据源接口。各源(弹弹play / B站 / ...)实现, 统一产出 [DanmakuEntry]。
 *
 * 单向: 文件信息 -> match -> episodeId -> fetch -> 弹幕列表。
 * 实现应走 IO 协程(网络/磁盘), 不阻塞调用方。
 */
interface DanmakuSourceProvider {
    /**
     * 用文件名 + 哈希 + 大小匹配番剧节目。
     * @return 匹配结果; null = 未匹配(调用方走回退链)
     */
    suspend fun match(fileName: String, fileHash: String, fileSize: Long): DanmakuMatchResult?

    /**
     * 按 episodeId 拉弹幕, 归一化为 [DanmakuEntry] 列表(已按时间排序)。
     */
    suspend fun fetch(episodeId: Long): List<DanmakuEntry>
}
