package io.github.weiyongzenqi.unuplayer.library

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * NFO/INI 解析 actual(Android 用 XmlPullParser 平台自带)。
 *
 * 解析刮削格式: tvshow.nfo/season.nfo/episode.nfo(XML) + bangumi.ini(INI)。
 * XML 解析复用 PropfindParser 风格(XmlPullParserFactory + isNamespaceAware +
 * setInput(StringReader) + while eventType!=END_DOCUMENT + START_TAG/TEXT/END_TAG +
 * textBuf 累积 TEXT 事件)。解析失败(格式错/必需字段缺)返回 null, 调用方跳过。
 * XML 实体(&amp; 等)由 XmlPullParser.getText 自动解码, 无需手动处理。
 *
 * 关键坑: tvshow.nfo 的 <actor> 子树内含 <tmdbid>(演员 tmdbid), 若不区分层级,
 * actor 内的 <tmdbid> 会覆盖番剧 tmdbid(误取 2431907 而非 312949)。
 * 解决: 用 insideActor 标志, 遇 <actor> START_TAG 置 true, </actor> END_TAG 复位 false,
 * 所有取值逻辑加 !insideActor 守卫(tmdbid/title/year/plot/rating/releasedate/genre/studio)。
 * season.nfo/episode.nfo 无嵌套子树, 直接按标签名取。
 */
actual object NfoParser {

    /**
     * 解析 tvshow.nfo(根 <tvshow>)。
     * <actor> 子树内的 <tmdbid>/<title> 等被 insideActor 守卫排除。
     * title 为空返回 null。多 <genre>/<studio> 按出现顺序累加。
     */
    actual fun parseTvShowNfo(xml: String): TvShowNfo? = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var tmdbId: Long? = null
        var title = ""
        var originalTitle: String? = null
        var year: Int? = null
        var plot: String? = null
        var rating: Double? = null
        var releaseDate: String? = null
        val genres = mutableListOf<String>()
        val studios = mutableListOf<String>()
        var insideActor = false

        val textBuf = StringBuilder()
        fun resetText() { textBuf.clear() }
        fun collectedText(): String = textBuf.toString().trim()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "actor" -> { insideActor = true; resetText() }
                    "tmdbid", "title", "originaltitle", "year", "plot",
                    "rating", "releasedate", "genre", "studio" -> resetText()
                }
                XmlPullParser.TEXT -> parser.text?.let(textBuf::append)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "actor" -> insideActor = false
                        "tmdbid" -> {
                            if (!insideActor) tmdbId = collectedText().toLongOrNull()
                            resetText()
                        }
                        "title" -> {
                            // 取首个非 actor 内的 title
                            if (!insideActor && title.isEmpty()) title = collectedText()
                            resetText()
                        }
                        "originaltitle" -> {
                            if (!insideActor) originalTitle = collectedText().ifEmpty { null }
                            resetText()
                        }
                        "year" -> {
                            if (!insideActor) year = collectedText().toIntOrNull()
                            resetText()
                        }
                        "plot" -> {
                            if (!insideActor) plot = collectedText().ifEmpty { null }
                            resetText()
                        }
                        "rating" -> {
                            if (!insideActor) rating = collectedText().toDoubleOrNull()
                            resetText()
                        }
                        "releasedate" -> {
                            if (!insideActor) releaseDate = collectedText().ifEmpty { null }
                            resetText()
                        }
                        "genre" -> {
                            if (!insideActor) collectedText().takeIf { it.isNotEmpty() }?.let(genres::add)
                            resetText()
                        }
                        "studio" -> {
                            if (!insideActor) collectedText().takeIf { it.isNotEmpty() }?.let(studios::add)
                            resetText()
                        }
                    }
                }
            }
            parser.next()
        }

        if (title.isEmpty()) null else TvShowNfo(
            tmdbId = tmdbId,
            title = title,
            originalTitle = originalTitle,
            year = year,
            plot = plot,
            rating = rating,
            releaseDate = releaseDate,
            genres = genres,
            studios = studios,
        )
    }.getOrNull()

    /** 解析 season.nfo(根 <season>)。seasonNumber 缺返回 null。 */
    actual fun parseSeasonNfo(xml: String): SeasonNfo? = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var seasonNumber: Int? = null
        var title: String? = null
        var year: Int? = null
        var releaseDate: String? = null

        val textBuf = StringBuilder()
        fun resetText() { textBuf.clear() }
        fun collectedText(): String = textBuf.toString().trim()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "seasonnumber", "title", "year", "releasedate" -> resetText()
                }
                XmlPullParser.TEXT -> parser.text?.let(textBuf::append)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "seasonnumber" -> { seasonNumber = collectedText().toIntOrNull(); resetText() }
                        "title" -> { title = collectedText().ifEmpty { null }; resetText() }
                        "year" -> { year = collectedText().toIntOrNull(); resetText() }
                        "releasedate" -> { releaseDate = collectedText().ifEmpty { null }; resetText() }
                    }
                }
            }
            parser.next()
        }

        seasonNumber?.let { SeasonNfo(it, title, year, releaseDate) }
    }.getOrNull()

    /** 解析 episode.nfo(根 <episodedetails>)。字段全可空。 */
    actual fun parseEpisodeNfo(xml: String): EpisodeNfo? = runCatching {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var title: String? = null
        var plot: String? = null
        var rating: Double? = null
        var year: Int? = null
        var aired: String? = null
        var episode: Int? = null
        var season: Int? = null
        var runtime: Int? = null

        val textBuf = StringBuilder()
        fun resetText() { textBuf.clear() }
        fun collectedText(): String = textBuf.toString().trim()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "title", "plot", "rating", "year", "aired",
                    "episode", "season", "runtime" -> resetText()
                }
                XmlPullParser.TEXT -> parser.text?.let(textBuf::append)
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title" -> { title = collectedText().ifEmpty { null }; resetText() }
                        "plot" -> { plot = collectedText().ifEmpty { null }; resetText() }
                        "rating" -> { rating = collectedText().toDoubleOrNull(); resetText() }
                        "year" -> { year = collectedText().toIntOrNull(); resetText() }
                        "aired" -> { aired = collectedText().ifEmpty { null }; resetText() }
                        "episode" -> { episode = collectedText().toIntOrNull(); resetText() }
                        "season" -> { season = collectedText().toIntOrNull(); resetText() }
                        "runtime" -> { runtime = collectedText().toIntOrNull(); resetText() }
                    }
                }
            }
            parser.next()
        }

        EpisodeNfo(title, plot, rating, year, aired, episode, season, runtime)
    }.getOrNull()

    /**
     * 解析 bangumi.ini(INI 文本, 非 XML)。找 [Bangumi] 段, id=(Long,必需), offset=(Int,默认0)。
     * id 缺返回 null。实现: 按 \n split, 遇 [Bangumi] 开始记录, 遇下一个 [xxx] 段结束;
     * 行内 trim().split("=", limit=2) 解析 key/value。
     */
    actual fun parseBangumiIni(text: String): BangumiIni? {
        var inSection = false
        var id: Long? = null
        var offset = 0
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                if (line.equals("[Bangumi]", ignoreCase = true)) {
                    inSection = true
                    continue
                } else {
                    if (inSection) break  // 进入下一个段, [Bangumi] 段结束
                    continue
                }
            }
            if (!inSection) continue
            val parts = line.split("=", limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim()
            val value = parts[1].trim()
            when (key.lowercase()) {
                "id" -> id = value.toLongOrNull()
                "offset" -> offset = value.toIntOrNull() ?: 0
            }
        }
        return id?.let { BangumiIni(it, offset) }
    }
}
