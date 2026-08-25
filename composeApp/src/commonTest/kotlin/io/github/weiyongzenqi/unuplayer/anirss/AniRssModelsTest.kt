package io.github.weiyongzenqi.unuplayer.anirss

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniRssModelsTest {

    @Test
    fun `连接地址限制为无附加内容的 HTTP 或 HTTPS 根地址`() {
        val http = validateAniRssBaseUrl("http://192.168.1.10:7789", cleartextConfirmed = false)
        assertFalse(http.isValid)
        assertTrue(http.requiresCleartextConfirmation)
        assertEquals(
            "http://192.168.1.10:7789",
            validateAniRssBaseUrl("http://192.168.1.10:7789/", cleartextConfirmed = true).normalizedUrl,
        )
        assertEquals(
            "https://ani-rss.example.test",
            validateAniRssBaseUrl("https://ani-rss.example.test/", cleartextConfirmed = false).normalizedUrl,
        )
        listOf(
            "ftp://ani-rss.example.test",
            "https://user:secret@ani-rss.example.test",
            "https://ani-rss.example.test/api",
            "https://ani-rss.example.test?key=value",
            "https://ani-rss.example.test#fragment",
        ).forEach { value -> assertFalse(validateAniRssBaseUrl(value, false).isValid, value) }
    }

    @Test
    fun `Mikan 条目号与相对封面可安全补全且禁止 HTTPS 降级`() {
        val pageUrl = "https://mikanani.me/Home/Bangumi/2353"

        assertEquals(2353L, aniRssMikanIdFromPageUrl(pageUrl))
        assertTrue(
            resolveAniRssMikanCoverUrl(pageUrl, "/images/Bangumi/2353.jpg")
                .orEmpty()
                .endsWith("/images/Bangumi/2353.jpg"),
        )
        assertNull(resolveAniRssMikanCoverUrl(pageUrl, "http://cdn.example.test/poster.jpg"))
        assertNull(resolveAniRssMikanCoverUrl(pageUrl, "file:///tmp/poster.jpg"))
        assertNull(aniRssMikanIdFromPageUrl("https://mikanani.me/Home/Bangumi/not-a-number"))
    }

    @Test
    fun `rssToAni 返回值只覆盖用户明确选择并保留 TMDB 标记与未知字段`() {
        val source = JsonObject(
            mapOf(
                "title" to JsonPrimitive("测试番剧 {tmdb-123}"),
                "customDownloadPath" to JsonPrimitive(false),
                "customDownloadPathTemplate" to JsonPrimitive("/server/default"),
                "customPriorityKeywords" to JsonArray(listOf(JsonPrimitive("服务端默认"))),
                "unknownFutureField" to JsonPrimitive("kept"),
                "url" to JsonPrimitive("https://rss.example/old.xml"),
            ),
        )
        val payload = patchAniRssPayload(
            source,
            request(
                primary = group("主组", "https://rss.example/main.xml"),
                standby = listOf(group("备用组", "https://rss.example/backup.xml")),
            ),
        )

        assertEquals("测试番剧 {tmdb-123}", payload.stringValue("title"))
        assertEquals("kept", payload.stringValue("unknownFutureField"))
        assertEquals("https://rss.example/main.xml", payload.stringValue("url"))
        assertEquals(false, payload.booleanValue("customDownloadPath"))
        assertEquals("/server/default", payload.stringValue("customDownloadPathTemplate"))
        assertEquals(
            listOf("服务端默认"),
            (payload["customPriorityKeywords"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(
            "https://rss.example/backup.xml",
            (((payload["standbyRssList"] as JsonArray).single()) as JsonObject).stringValue("url"),
        )
    }

    @Test
    fun `正则组合保持整体和逗号原文且不进入优先关键词`() {
        val regex = "1080P.*\\d{1,3},CHS"
        val combination = AniRssFilterCombination(
            listOf(
                AniRssFilterOption("1080P", regex),
                AniRssFilterOption("简体", "CHS|GB"),
            ),
        )
        val primary = group("字幕组", "https://rss.example/main.xml", listOf(combination))
        val payload = patchAniRssPayload(
            JsonObject(mapOf("url" to JsonPrimitive(primary.rss))),
            request(primary = primary).copy(filterCombinationsByRss = mapOf(primary.rss to combination)),
        )

        assertEquals(
            listOf("{{字幕组}}:$regex", "{{字幕组}}:CHS|GB"),
            (payload["match"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertNull(payload["customPriorityKeywords"])
    }

    @Test
    fun `备用字幕组筛选只替换该组规则并保留主组服务端默认`() {
        val primary = group("主组", "https://rss.example/main.xml")
        val standbyCombination = AniRssFilterCombination(
            listOf(AniRssFilterOption("简体 1080P", "1080P.*CHS")),
        )
        val standby = group(
            "备用组",
            "https://rss.example/backup.xml",
            combinations = listOf(standbyCombination),
        )
        val payload = patchAniRssPayload(
            JsonObject(
                mapOf(
                    "url" to JsonPrimitive(primary.rss),
                    "match" to JsonArray(
                        listOf(
                            JsonPrimitive("{{主组}}:服务端默认"),
                            JsonPrimitive("{{备用组}}:旧规则"),
                            JsonPrimitive("全局保留规则"),
                        ),
                    ),
                ),
            ),
            request(primary, standby = listOf(standby)).copy(
                filterCombinationsByRss = mapOf(standby.rss to standbyCombination),
            ),
        )

        assertEquals(
            listOf("{{主组}}:服务端默认", "全局保留规则", "{{备用组}}:1080P.*CHS"),
            (payload["match"] as JsonArray).map { (it as JsonPrimitive).content },
        )
    }

    @Test
    fun `高级字段仅在用户显式启用时写入`() {
        val primary = group("字幕组", "https://rss.example/main.xml")
        val payload = patchAniRssPayload(
            JsonObject(mapOf("url" to JsonPrimitive(primary.rss))),
            request(primary).copy(
                customDownloadPath = "/downloads/{title}",
                customPriorityKeywords = listOf("简日", "1080P", "简日"),
                episodeOffset = -12,
            ),
        )

        assertEquals(true, payload.booleanValue("customDownloadPath"))
        assertEquals("/downloads/{title}", payload.stringValue("customDownloadPathTemplate"))
        assertEquals(
            listOf("简日", "1080P"),
            (payload["customPriorityKeywords"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals("-12", (payload["offset"] as JsonPrimitive).content)
    }

    @Test
    fun `空优先关键词视为未设置并保留服务端默认`() {
        val primary = group("字幕组", "https://rss.example/main.xml")
        val source = JsonObject(
            mapOf(
                "url" to JsonPrimitive(primary.rss),
                "customPriorityKeywordsEnable" to JsonPrimitive(true),
                "customPriorityKeywords" to JsonArray(listOf(JsonPrimitive("服务端默认"))),
            ),
        )
        val payload = patchAniRssPayload(
            source,
            request(primary).copy(customPriorityKeywords = emptyList()),
        )

        // 空列表视为未启用, 不下发 enable=false 覆盖服务端默认。
        assertEquals(
            listOf("服务端默认"),
            (payload["customPriorityKeywords"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(true, payload.booleanValue("customPriorityKeywordsEnable"))
    }

    @Test
    fun `未验证身份需明确确认且冲突身份始终拒绝`() {
        val missing = group("无身份", "https://rss.example/main.xml", bangumiUrl = null)
        assertFailsWith<IllegalArgumentException> { request(missing).validate() }
        request(missing).copy(unverifiedIdentityConfirmed = true).validate()

        val conflict = group("错番", "https://rss.example/main.xml", bangumiUrl = "https://bgm.tv/subject/999")
        assertFailsWith<IllegalArgumentException> {
            request(conflict).copy(unverifiedIdentityConfirmed = true).validate()
        }

        val malformed = group("坏身份", "https://rss.example/main.xml", bangumiUrl = "https://example.test/subject/42")
        assertFailsWith<IllegalArgumentException> {
            request(malformed).copy(unverifiedIdentityConfirmed = true).validate()
        }
    }

    @Test
    fun `订阅列表只解析官方 weekList 根字段身份`() {
        val data = JsonObject(
            mapOf(
                "weekList" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "weekLabel" to JsonPrimitive("星期日"),
                                "items" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "id" to JsonPrimitive("ani-1"),
                                                "title" to JsonPrimitive("测试番剧 {tmdb-42}"),
                                                "bgmUrl" to JsonPrimitive("https://bgm.tv/subject/42"),
                                                "subgroup" to JsonPrimitive("字幕组"),
                                                "enable" to JsonPrimitive(true),
                                                "url" to JsonPrimitive("https://rss.example/main.xml"),
                                                "image" to JsonPrimitive("https://lain.bgm.tv/pic/cover/test.jpg"),
                                                "nested" to JsonObject(mapOf("bgmUrl" to JsonPrimitive("https://bgm.tv/subject/999"))),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val subscription = data.toAniRssSubscriptions().single()

        assertEquals("ani-1", subscription.id)
        assertEquals(42L, subscription.subjectId)
        assertEquals("测试番剧 {tmdb-42}", subscription.title)
        assertEquals("https://lain.bgm.tv/pic/cover/test.jpg", subscription.posterUrl)
        assertTrue(subscription.enabled)
    }

    @Test
    fun `订阅海报拒绝服务端本地文件路径`() {
        val data = JsonObject(
            mapOf(
                "weekList" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "items" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "id" to JsonPrimitive("ani-local-cover"),
                                                "title" to JsonPrimitive("测试番剧"),
                                                "image" to JsonPrimitive("/config/cache/cover/test.jpg"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertNull(data.toAniRssSubscriptions().single().posterUrl)
    }

    @Test
    fun `预览保留资源详情路径与遗漏集数`() {
        val preview = JsonObject(
            mapOf(
                "downloadPath" to JsonPrimitive("/downloads/Test"),
                "items" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "title" to JsonPrimitive("raw"),
                                "reName" to JsonPrimitive("S01E01.mkv"),
                                "episode" to JsonPrimitive(1.0),
                                "formatSize" to JsonPrimitive("1.2 GB"),
                                "hasDownloaded" to JsonPrimitive(true),
                            ),
                        ),
                    ),
                ),
                "omitList" to JsonArray(listOf(JsonPrimitive("2"), JsonPrimitive("3"))),
            ),
        ).toAniRssPreview()

        assertEquals("/downloads/Test", preview.downloadPath)
        assertEquals("S01E01.mkv", preview.items.single().renamedTitle)
        assertTrue(preview.items.single().alreadyDownloaded)
        assertEquals(listOf("2", "3"), preview.omittedEpisodes)
    }

    private fun request(
        primary: AniRssGroup,
        standby: List<AniRssGroup> = emptyList(),
    ) = AniRssCreateRequest(
        subjectId = 42L,
        title = "测试番剧",
        primaryGroup = primary,
        standbyGroups = standby,
    )

    private fun group(
        label: String,
        rss: String,
        combinations: List<AniRssFilterCombination> = emptyList(),
        bangumiUrl: String? = "https://bgm.tv/subject/42",
    ) = AniRssGroup(
        label = label,
        rss = rss,
        bangumiUrl = bangumiUrl,
        updateDay = null,
        tags = emptyList(),
        filterCombinations = combinations,
        resources = emptyList(),
        identityVerified = bangumiUrl == "https://bgm.tv/subject/42",
    )
}
