package io.github.weiyongzenqi.unuplayer.library

import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * NFO/INI 解析 actual(桌面用 javax.xml.stream StAX, 对应 androidMain 的 NfoParser.android.kt)。
 *
 * Android 用 XmlPullParser(kxml2), 桌面用 StAX(XMLStreamReader), 两者事件流模型相似:
 * - START_TAG -> START_ELEMENT
 * - TEXT -> CHARACTERS / CDATA
 * - END_TAG -> END_ELEMENT
 * - END_DOCUMENT -> hasNext() 返回 false
 *
 * 解析逻辑与 android 版逐行对齐:
 * - isNamespaceAware 使 localName 返回不带前缀的 local name(兼容 D:/d:/无前缀)
 * - textBuf 累积 CHARACTERS/CDATA 事件(某些解析器分多次发文本)
 * - tvshow.nfo 的 <actor> 子树用 insideActor 标志排除内部 <tmdbid> 等误取
 * - 解析失败(格式错/必需字段缺)返回 null, 调用方跳过
 *
 * 安全: 禁用外部实体(IS_SUPPORTING_EXTERNAL_ENTITIES=false)防 XXE,
 * StAX 默认开启外部实体支持, 与 Android XmlPullParser 不同(后者默认安全)。
 */
actual object NfoParser {

    private fun createReader(xml: String) = XMLInputFactory.newInstance().also { factory ->
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        // 防 XXE: StAX 默认支持外部实体, 这里禁用
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }.createXMLStreamReader(StringReader(xml))

    /**
     * 解析 tvshow.nfo(根 <tvshow>)。
     * <actor> 子树内的 <tmdbid>/<title> 等被 insideActor 守卫排除。
     * title 为空返回 null。多 <genre>/<studio> 按出现顺序累加。
     */
    actual fun parseTvShowNfo(xml: String): TvShowNfo? = runCatching {
        val reader = createReader(xml)
        try {
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

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "actor" -> { insideActor = true; resetText() }
                        "tmdbid", "title", "originaltitle", "year", "plot",
                        "rating", "releasedate", "genre", "studio" -> resetText()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        textBuf.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
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
        } finally {
            reader.close()
        }
    }.getOrNull()

    /** 解析 season.nfo(根 <season>)。seasonNumber 缺返回 null。 */
    actual fun parseSeasonNfo(xml: String): SeasonNfo? = runCatching {
        val reader = createReader(xml)
        try {
            var seasonNumber: Int? = null
            var title: String? = null
            var year: Int? = null
            var releaseDate: String? = null

            val textBuf = StringBuilder()
            fun resetText() { textBuf.clear() }
            fun collectedText(): String = textBuf.toString().trim()

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "seasonnumber", "title", "year", "releasedate" -> resetText()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        textBuf.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName) {
                            "seasonnumber" -> { seasonNumber = collectedText().toIntOrNull(); resetText() }
                            "title" -> { title = collectedText().ifEmpty { null }; resetText() }
                            "year" -> { year = collectedText().toIntOrNull(); resetText() }
                            "releasedate" -> { releaseDate = collectedText().ifEmpty { null }; resetText() }
                        }
                    }
                }
            }

            seasonNumber?.let { SeasonNfo(it, title, year, releaseDate) }
        } finally {
            reader.close()
        }
    }.getOrNull()

    /** 解析 episode.nfo(根 <episodedetails>)。字段全可空。 */
    actual fun parseEpisodeNfo(xml: String): EpisodeNfo? = runCatching {
        val reader = createReader(xml)
        try {
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

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "title", "plot", "rating", "year", "aired",
                        "episode", "season", "runtime" -> resetText()
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        textBuf.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName) {
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
            }

            EpisodeNfo(title, plot, rating, year, aired, episode, season, runtime)
        } finally {
            reader.close()
        }
    }.getOrNull()

    /**
     * 解析 bangumi.ini(INI 文本, 非 XML)。找 [Bangumi] 段, id=(Long,必需), offset=(Int,默认0)。
     * id 缺返回 null。实现: 按 \n split, 遇 [Bangumi] 开始记录, 遇下一个 [xxx] 段结束;
     * 行内 trim().split("=", limit=2) 解析 key/value。
     *
     * 纯字符串解析, 与 android 版完全一致。
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
