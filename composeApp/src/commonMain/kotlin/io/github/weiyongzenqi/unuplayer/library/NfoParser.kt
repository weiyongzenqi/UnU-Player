package io.github.weiyongzenqi.unuplayer.library

/**
 * NFO/INI 解析(expect/actual, Android 用 XmlPullParser 平台自带)。
 * 解析刮削格式: tvshow.nfo/season.nfo/episode.nfo(XML) + bangumi.ini(INI)。
 * 返回 data class 见 ScrapedModels; 解析失败返回 null(调用方跳过)。
 */
expect object NfoParser {
    fun parseTvShowNfo(xml: String): TvShowNfo?
    fun parseSeasonNfo(xml: String): SeasonNfo?
    fun parseEpisodeNfo(xml: String): EpisodeNfo?
    fun parseBangumiIni(text: String): BangumiIni?
}
