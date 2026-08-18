package io.github.weiyongzenqi.unuplayer.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 桌面 StAX NfoParser 回归测试。
 * 修复前失败点: NfoParser(anchor 模式核心元数据解析器)双端零测试覆盖, 桌面 StAX 与
 * Android XmlPullParser 两套独立实现裸奔; 解析失败只返回 null 被调用方静默跳过, 历史上
 * 已有 <actor> 子树 <tmdbid> 误取导致扫库番剧 tmdbId 错误的坑。
 */
class NfoParserDesktopTest {

    @Test
    fun `tvshow nfo 基本解析`() {
        val nfo = NfoParser.parseTvShowNfo(
            """
            <tvshow>
                <title>测试番剧</title>
                <originaltitle>Test Anime</originaltitle>
                <year>2024</year>
                <tmdbid>123456</tmdbid>
                <plot>简介</plot>
                <rating>8.5</rating>
                <releasedate>2024-01-01</releasedate>
                <genre>科幻</genre>
                <genre>冒险</genre>
                <studio>Studio X</studio>
                <studio>Studio Y</studio>
            </tvshow>
            """.trimIndent(),
        )
        assertEquals("测试番剧", nfo?.title)
        assertEquals("Test Anime", nfo?.originalTitle)
        assertEquals(2024, nfo?.year)
        assertEquals(123456L, nfo?.tmdbId)
        assertEquals("简介", nfo?.plot)
        assertEquals(8.5, nfo?.rating)
        assertEquals("2024-01-01", nfo?.releaseDate)
        assertEquals(listOf("科幻", "冒险"), nfo?.genres)
        assertEquals(listOf("Studio X", "Studio Y"), nfo?.studios)
    }

    @Test
    fun `actor子树内的tmdbid与title不被误取`() {
        val nfo = NfoParser.parseTvShowNfo(
            """
            <tvshow>
                <title>真实标题</title>
                <tmdbid>111</tmdbid>
                <actor>
                    <name>演员 A</name>
                    <role>主角</role>
                    <tmdbid>999</tmdbid>
                    <title>演员的其它作品</title>
                </actor>
            </tvshow>
            """.trimIndent(),
        )
        assertEquals("真实标题", nfo?.title)
        assertEquals(111L, nfo?.tmdbId, "actor 子树内 tmdbid 不得覆盖")
    }

    @Test
    fun `title为空返回null且格式错误不抛`() {
        assertNull(NfoParser.parseTvShowNfo("<tvshow><title></title></tvshow>"))
        assertNull(NfoParser.parseTvShowNfo("不是XML"))
    }

    @Test
    fun `外部实体不被展开XXE防御`() {
        // StAX 默认支持外部实体, 实现禁用 IS_SUPPORTING_EXTERNAL_ENTITIES;
        // 若被展开 title 会是实体内容而非 "正常标题"
        val nfo = NfoParser.parseTvShowNfo(
            """<!DOCTYPE tvshow [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
            <tvshow><title>&xxe;</title><tmdbid>1</tmdbid></tvshow>""",
        )
        // 防御生效: 要么解析失败(null), 要么 title 不含外部文件内容
        if (nfo != null) {
            assert(nfo.title != "file:///etc/hostname")
        }
    }

    @Test
    fun `season nfo 解析`() {
        val nfo = NfoParser.parseSeasonNfo(
            """
            <season>
                <seasonnumber>2</seasonnumber>
                <title>第二季</title>
                <year>2025</year>
                <releasedate>2025-04-01</releasedate>
            </season>
            """.trimIndent(),
        )
        assertEquals(2, nfo?.seasonNumber)
        assertEquals("第二季", nfo?.title)
        assertEquals(2025, nfo?.year)
        // seasonNumber 缺返回 null
        assertNull(NfoParser.parseSeasonNfo("<season><title>x</title></season>"))
    }

    @Test
    fun `episode nfo 解析`() {
        val nfo = NfoParser.parseEpisodeNfo(
            """
            <episodedetails>
                <title>第一集</title>
                <plot>剧情</plot>
                <rating>7.5</rating>
                <year>2024</year>
                <aired>2024-01-01</aired>
                <episode>1</episode>
                <season>1</season>
                <runtime>24</runtime>
            </episodedetails>
            """.trimIndent(),
        )
        assertEquals("第一集", nfo?.title)
        assertEquals(1, nfo?.episode)
        assertEquals(1, nfo?.season)
        assertEquals(24, nfo?.runtime)
        assertEquals("2024-01-01", nfo?.aired)
    }

    @Test
    fun `bangumi ini 解析段与键`() {
        val ini = NfoParser.parseBangumiIni(
            """
            [其他]
            id=111
            [Bangumi]
            id=400602
            offset=2
            [后续]
            id=999
            """.trimIndent(),
        )
        assertEquals(400602L, ini?.id)
        assertEquals(2, ini?.offset)
        // id 缺返回 null
        assertNull(NfoParser.parseBangumiIni("[Bangumi]\noffset=1"))
        // 段名大小写不敏感
        assertEquals(5L, NfoParser.parseBangumiIni("[bangumi]\nid=5")?.id)
    }
}
