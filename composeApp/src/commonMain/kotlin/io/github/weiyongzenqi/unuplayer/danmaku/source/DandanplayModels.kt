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
    val airDate: String? = null,
)

// === search/anime (keyword, 回退链 + 在线刮削用) ===
@Serializable
data class DandanplaySearchAnimeResponse(
    val success: Boolean = true,
    val animes: List<DandanplayAnimeSummary> = emptyList(),
)

/**
 * 弹弹 search/anime 搜索结果(在线刮削用)。字段对齐弹弹开放 API 实际响应:
 * bangumiId 是 bgm.tv 条目 id 桥(可跨源直连 Bangumi subject), imageUrl 为季照,
 * startDate 首播, intro 简介。搜索源不含季级 episodes(bangumi/{animeId} 才有)。
 */
@Serializable
data class DandanplayAnimeSummary(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val type: String? = null,
    val typeDescription: String? = null,
    val bangumiId: String? = null,
    val imageUrl: String? = null,
    val startDate: String? = null,
    val episodeCount: Int? = null,
    val rating: Double? = null,
    val intro: String? = null,
)

// === bangumi/{animeId} (含 episodeNumber, 回退链按集数定位用; 在线刮削用) ===
@Serializable
data class DandanplayBangumiResponse(
    val success: Boolean = true,
    val bangumi: DandanplayBangumi? = null,
)

@Serializable
data class DandanplayBangumi(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val imageUrl: String? = null,
    val episodes: List<DandanplayEpisode> = emptyList(),
    /** 同作品其他季(多季映射/补全用; 各带季照与评分)。 */
    val relateds: List<DandanplayRelated> = emptyList(),
)

@Serializable
data class DandanplayRelated(
    val animeId: Long = 0,
    val animeTitle: String = "",
    val imageUrl: String? = null,
    val rating: Double? = null,
    val startDate: String? = null,
)

/** 弹弹play /bangumi/shin 响应。不同服务端版本使用 bangumis/animes/items 之一，均兼容。 */
@Serializable
data class DandanplayShinResponse(
    val success: Boolean = true,
    val bangumiList: List<DandanplayShinAnime> = emptyList(),
    val bangumis: List<DandanplayShinAnime> = emptyList(),
    val animes: List<DandanplayShinAnime> = emptyList(),
    val items: List<DandanplayShinAnime> = emptyList(),
) {
    fun allItems(): List<DandanplayShinAnime> = (bangumiList + bangumis + animes + items)
        .distinctBy { it.animeId to it.bangumiId }
}

@Serializable
data class DandanplayShinAnime(
    val animeId: Long = 0,
    val bangumiId: String? = null,
    val airDay: Int? = null,
    val imageUrl: String? = null,
    val searchKeyword: String? = null,
    val animeTitle: String? = null,
    val isOnAir: Boolean = true,
    val rating: Double? = null,
    val isRestricted: Boolean = false,
)
