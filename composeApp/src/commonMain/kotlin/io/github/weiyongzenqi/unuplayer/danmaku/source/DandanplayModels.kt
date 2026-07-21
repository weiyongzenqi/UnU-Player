package io.github.weiyongzenqi.unuplayer.danmaku.source

import kotlinx.serialization.Serializable

/**
 * 弹弹play API v2 请求/响应模型。
 *
 * 字段尽量给默认值 + [kotlinx.serialization.json.Json] 设 ignoreUnknownKeys,
 * 以容忍服务器增删字段。p 字段是逗号分隔字符串, 在 Provider 层拆分。
 */

// === match ===
@Serializable
data class DandanplayMatchRequest(
    val fileName: String,
    val fileHash: String,
    val fileSize: Long,
    val matchMode: String,
)

@Serializable
data class DandanplayMatchResponse(
    val isMatched: Boolean = false,
    val matches: List<DandanplayMatch> = emptyList(),
    val errorCode: Int = 0,
    val success: Boolean = true,
    val errorMessage: String? = null,
)

@Serializable
data class DandanplayMatch(
    val episodeId: Long = 0,
    val animeId: Long = 0,
    val animeTitle: String = "",
    val episodeTitle: String = "",
    val shift: Int = 0,
)

// === comment ===
@Serializable
data class DandanplayCommentResponse(
    val count: Int = 0,
    val comments: List<DandanplayComment> = emptyList(),
)

/** 弹弹play comment: p="time,mode,color,uid", m=文本, cid=弹幕ID。 */
@Serializable
data class DandanplayComment(
    val cid: Long = 0,
    val p: String = "",
    val m: String = "",
)

// === search/episodes (tmdbId) ===
@Serializable
data class DandanplaySearchEpisodesResponse(
    val success: Boolean = true,
    val animes: List<DandanplayAnime> = emptyList(),
)

@Serializable
data class DandanplayAnime(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val type: String? = null,
    val episodes: List<DandanplayEpisode> = emptyList(),
)

@Serializable
data class DandanplayEpisode(
    val episodeId: Long = 0,
    val episodeTitle: String = "",
    // 弹弹play bangumi 详情的 episodeNumber 是**字符串**(如 "3"), 不是数字;
    // search/episodes 的 episode 无此字段。用 String? 兼容, 匹配时 toIntOrNull。
    val episodeNumber: String? = null,
)

// === search/anime (keyword, 回退链用) ===
@Serializable
data class DandanplaySearchAnimeResponse(
    val success: Boolean = true,
    val animes: List<DandanplayAnimeSummary> = emptyList(),
)

@Serializable
data class DandanplayAnimeSummary(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val typeDescription: String? = null,
)

// === bangumi/{animeId} (含 episodeNumber, 回退链按集数定位用) ===
@Serializable
data class DandanplayBangumiResponse(
    val success: Boolean = true,
    val bangumi: DandanplayBangumi? = null,
)

@Serializable
data class DandanplayBangumi(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val episodes: List<DandanplayEpisode> = emptyList(),
)
