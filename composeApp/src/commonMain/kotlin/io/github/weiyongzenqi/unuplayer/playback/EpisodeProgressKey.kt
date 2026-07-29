package io.github.weiyongzenqi.unuplayer.playback

/**
 * 生成 EpisodeProgress 三元组查询 key。
 * 格式: "tmdb-season-episode"，与 SQL 查询 episodeProgressGetByTriples 的拼串一致。
 */
fun episodeProgressKey(tmdbId: Long, seasonNumber: Long, episodeNumber: Long): String =
    "$tmdbId-$seasonNumber-$episodeNumber"
