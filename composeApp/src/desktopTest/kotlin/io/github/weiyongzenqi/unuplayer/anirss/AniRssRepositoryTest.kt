package io.github.weiyongzenqi.unuplayer.anirss

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.weiyongzenqi.unuplayer.core.security.SecretStorage
import io.github.weiyongzenqi.unuplayer.domain.SettingsLoadState
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.SettingsWriteFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AniRssRepositoryTest {

    @Test
    fun `连接只接受官方 envelope 和受支持版本并保存已验证配置`() = runBlocking {
        withServer { baseUrl, server ->
            var receivedKey: String? = null
            server.createContext("/api/config") { exchange ->
                receivedKey = exchange.requestHeaders.getFirst("api-key")
                exchange.respond(200, envelope("""{"version":"3.2.16","standbyRss":true}"""))
            }
            val settings = MemorySettings()
            val secrets = MemorySecretStorage()
            val repository = repository(settings, secrets)

            val profile = repository.saveConnection(baseUrl, "new-api-key", cleartextConfirmed = true)

            assertEquals("3.2.16", profile.version)
            assertTrue(profile.standbyRssEnabled)
            assertEquals("new-api-key", receivedKey)
            assertEquals(baseUrl, settings.state.value.aniRssBaseUrl)
            assertEquals("new-api-key", secrets.getString(API_KEY))
        }
    }

    @Test
    fun `二级路径前缀部署下请求打到前缀路径并保存规范化地址`() = runBlocking {
        withServer { baseUrl, server ->
            var configCalls = 0
            var listCalls = 0
            server.createContext("/ani-rss/api/config") { exchange ->
                configCalls++
                exchange.respond(200, envelope(CONFIG))
            }
            server.createContext("/ani-rss/api/listAni") { exchange ->
                listCalls++
                exchange.respond(200, envelope("""{"releaseDateList":[],"weekList":[],"total":0}"""))
            }
            val settings = MemorySettings()
            val secrets = MemorySecretStorage()
            val repository = repository(settings, secrets)

            val profile = repository.saveConnection("$baseUrl/ani-rss/", "api-key", cleartextConfirmed = true)
            val subscriptions = repository.listSubscriptions()

            assertEquals("3.2.16", profile.version)
            assertEquals("$baseUrl/ani-rss", settings.state.value.aniRssBaseUrl)
            assertTrue(subscriptions.isEmpty())
            assertEquals(1, configCalls)
            assertEquals(1, listCalls)
        }
    }

    @Test
    fun `空对象缺 data 和旧版本不能冒充连接成功且不覆盖旧配置`() = runBlocking {
        withServer { baseUrl, server ->
            var responseMode = 0
            server.createContext("/api/config") { exchange ->
                when (responseMode) {
                    0 -> exchange.respond(200, "{}")
                    1 -> exchange.respond(200, successWithoutData())
                    else -> exchange.respond(200, envelope("""{"version":"3.0.0","standbyRss":false}"""))
                }
            }
            val settings = MemorySettings(
                SettingsState(aniRssBaseUrl = "https://old.example.test", aniRssCleartextConfirmed = false),
            )
            val secrets = MemorySecretStorage(mapOf(API_KEY to "old-key"))
            val repository = repository(settings, secrets)

            assertFailsWith<AniRssException> {
                repository.saveConnection(baseUrl, "new-key", cleartextConfirmed = true)
            }
            responseMode = 1
            assertFailsWith<AniRssException> {
                repository.saveConnection(baseUrl, "new-key", cleartextConfirmed = true)
            }
            responseMode = 2
            assertFailsWith<AniRssException> {
                repository.saveConnection(baseUrl, "new-key", cleartextConfirmed = true)
            }

            assertEquals("https://old.example.test", settings.state.value.aniRssBaseUrl)
            assertEquals("old-key", secrets.getString(API_KEY))
        }
    }

    @Test
    fun `Mikan 搜索按根结构解析并保持 regexList 组合与逗号原文`() = runBlocking {
        withServer { baseUrl, server ->
            var searchQuery: String? = null
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/mikan") { exchange ->
                searchQuery = exchange.requestURI.rawQuery
                exchange.respond(
                    200,
                    envelope(
                        """{
                          "seasons":[],
                          "weeks":[{"weekLabel":"星期日","items":[
                            {"title":"正确番剧","url":"https://mikanani.me/Home/Bangumi/100","bgmId":42,"cover":"/images/Bangumi/100.jpg","score":8.8,"exists":false},
                            {"title":"同名错误番剧","url":"https://mikanani.me/Home/Bangumi/999","bgmId":999}
                          ]}],
                          "totalItem":2
                        }""",
                    ),
                )
            }
            server.createContext("/api/mikanGroup") { exchange ->
                assertTrue(URLDecoder.decode(exchange.requestURI.rawQuery, Charsets.UTF_8).contains("url=https://mikanani.me/Home/Bangumi/100"))
                exchange.respond(
                    200,
                    envelope(
                        """[{
                          "label":"测试字幕组",
                          "rss":"https://mikanani.me/RSS/Bangumi?bangumiId=100&subgroupid=1",
                          "bgmUrl":"https://bgm.tv/subject/42",
                          "updateDay":"周日",
                          "items":[{"title":"[Group] E01","formatSize":"1.2 GB"}],
                          "groupRegex":{
                            "tags":["1080P","简体"],
                            "regexList":[[
                              {"label":"1080P","regex":"1080P.*\\d{1,3},CHS"},
                              {"label":"简体","regex":"CHS|GB"}
                            ]]
                          }
                        }]""",
                    ),
                )
            }
            val repository = connectedRepository(baseUrl)

            val candidates = repository.searchMikan(42L, "正确 番剧")
            val groups = repository.loadMikanGroups(42L, candidates.single(), allowUnverifiedIdentity = false)

            assertTrue(searchQuery.orEmpty().contains("text="))
            assertEquals(1, candidates.size)
            val candidate = candidates.single()
            assertTrue(candidate.identityVerified)
            assertEquals(42L, candidate.bangumiSubjectId)
            assertEquals(100L, candidate.mikanId)
            assertEquals("星期日", candidate.weekLabel)
            assertEquals("https://bgm.tv/subject/42", candidate.bangumiUrl)
            assertTrue(candidate.coverUrl.orEmpty().endsWith("/images/Bangumi/100.jpg"))
            assertEquals(listOf("1080P", "简体"), groups.single().tags)
            assertEquals("1080P.*\\d{1,3},CHS", groups.single().filterCombinations.single().options.first().regex)
        }
    }

    @Test
    fun `Mikan 非法 Bangumi 身份不能降级为手动确认`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/mikan") { exchange ->
                exchange.respond(
                    200,
                    envelope(
                        """{"weeks":[{"items":[{"title":"伪造身份","url":"https://mikanani.me/Home/Bangumi/100","bgmUrl":"https://example.test/subject/42"}]}]}""",
                    ),
                )
            }

            assertFailsWith<AniRssProtocolException> {
                connectedRepository(baseUrl).searchMikan(42L, "测试番剧")
            }
        }
    }

    @Test
    fun `Mikan 数字身份与链接身份冲突时拒绝候选`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/mikan") { exchange ->
                exchange.respond(
                    200,
                    envelope(
                        """{"weeks":[{"items":[{
                          "title":"身份冲突番剧",
                          "url":"https://mikanani.me/Home/Bangumi/100",
                          "bgmId":42,
                          "bgmUrl":"https://bgm.tv/subject/43"
                        }]}]}""",
                    ),
                )
            }

            assertFailsWith<AniRssProtocolException> {
                connectedRepository(baseUrl).searchMikan(42L, "测试番剧")
            }
        }
    }

    @Test
    fun `新增严格经过 rssToAni 与详细预览且 add 复用完全相同 payload`() = runBlocking {
        withServer { baseUrl, server ->
            var rssToAniBody: JsonObject? = null
            var previewBody: JsonObject? = null
            var addBody: JsonObject? = null
            var listCalls = 0
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/rssToAni") { exchange ->
                rssToAniBody = exchange.readJsonObject()
                exchange.respond(
                    200,
                    envelope(
                        """{
                          "id":"generated-id",
                          "title":"测试番剧 {tmdb-123}",
                          "url":"https://rss.example/main.xml",
                          "bgmUrl":"https://bgm.tv/subject/42",
                          "subgroup":"主组",
                          "enable":true,
                          "exclude":["720[Pp]"],
                          "globalExclude":true,
                          "unknownFutureField":"kept"
                        }""",
                    ),
                )
            }
            server.createContext("/api/previewAni") { exchange ->
                previewBody = exchange.readJsonObject()
                exchange.respond(
                    200,
                    envelope(
                        """{
                          "downloadPath":"/downloads/Test {tmdb-123}",
                          "items":[{"title":"raw","reName":"S01E01.mkv","episode":1.0,"formatSize":"1 GB","hasDownloaded":false,"subgroup":"主组"}],
                          "omitList":[2]
                        }""",
                    ),
                )
            }
            server.createContext("/api/listAni") { exchange ->
                listCalls++
                exchange.respond(200, envelope("""{"releaseDateList":[],"weekList":[],"total":0}"""))
            }
            server.createContext("/api/addAni") { exchange ->
                addBody = exchange.readJsonObject()
                exchange.respond(200, successWithoutData("添加订阅成功"))
            }
            val repository = connectedRepository(baseUrl)
            val combination = AniRssFilterCombination(
                listOf(AniRssFilterOption("1080P", "1080P.*\\d{1,3},CHS")),
            )
            val primary = group("主组", "https://rss.example/main.xml", listOf(combination))
            val standby = group("备用组", "https://rss.example/backup.xml")
            val request = AniRssCreateRequest(
                subjectId = 42L,
                title = "测试番剧",
                primaryGroup = primary,
                standbyGroups = listOf(standby),
                filterCombinationsByRss = mapOf(primary.rss to combination),
            )

            val prepared = repository.prepareSubscription(request)
            val preview = repository.preview(prepared)
            repository.add(prepared)
            val preparedRequestBody = requireNotNull(rssToAniBody)
            val previewRequestBody = requireNotNull(previewBody)

            assertEquals(setOf("url", "type", "bgmUrl", "subgroup", "enable"), preparedRequestBody.keys)
            assertEquals("mikan", preparedRequestBody.getValue("type").jsonPrimitive.content)
            assertEquals("测试番剧 {tmdb-123}", prepared.title)
            assertEquals("kept", previewRequestBody.getValue("unknownFutureField").jsonPrimitive.content)
            assertEquals(
                "{{主组}}:1080P.*\\d{1,3},CHS",
                previewRequestBody.getValue("match").jsonArray.single().jsonPrimitive.content,
            )
            assertNull(previewRequestBody["ld"])
            assertEquals(previewRequestBody, addBody)
            assertEquals("/downloads/Test {tmdb-123}", preview.downloadPath)
            assertEquals(listOf("2"), preview.omittedEpisodes)
            assertEquals(1, listCalls)
        }
    }

    @Test
    fun `新增响应丢失后列表确认已存在则收敛为成功`() = runBlocking {
        withServer { baseUrl, server ->
            var listCalls = 0
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/listAni") { exchange ->
                listCalls++
                val data = if (listCalls == 1) {
                    """{"releaseDateList":[],"weekList":[],"total":0}"""
                } else {
                    """{"releaseDateList":[],"weekList":[{"weekLabel":"星期日","items":[{
                      "id":"ani-42","title":"测试番剧","bgmUrl":"https://bgm.tv/subject/42","enable":true
                    }]}],"total":1}"""
                }
                exchange.respond(200, envelope(data))
            }
            server.createContext("/api/addAni") { exchange ->
                exchange.readJsonObject()
                exchange.respond(500, "服务端提交后连接异常")
            }
            val repository = connectedRepository(baseUrl)
            val prepared = AniRssPreparedSubscription(
                subjectId = 42L,
                title = "测试番剧",
                payload = JsonObject(mapOf("url" to JsonPrimitive("https://rss.example/main.xml"))),
            )

            repository.add(prepared)

            assertEquals(2, listCalls)
        }
    }

    @Test
    fun `新增失败且列表仍不存在时保留原始错误`() = runBlocking {
        withServer { baseUrl, server ->
            var listCalls = 0
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/listAni") { exchange ->
                listCalls++
                exchange.respond(200, envelope("""{"releaseDateList":[],"weekList":[],"total":0}"""))
            }
            server.createContext("/api/addAni") { exchange ->
                exchange.readJsonObject()
                exchange.respond(500, "写入失败")
            }
            val repository = connectedRepository(baseUrl)
            val prepared = AniRssPreparedSubscription(
                subjectId = 42L,
                title = "测试番剧",
                payload = JsonObject(mapOf("url" to JsonPrimitive("https://rss.example/main.xml"))),
            )

            val error = assertFailsWith<AniRssException> { repository.add(prepared) }

            assertTrue(error.message.orEmpty().contains("HTTP 500"))
            assertEquals(2, listCalls)
        }
    }

    @Test
    fun `订阅管理启停刷新与删除只使用安全参数`() = runBlocking {
        withServer { baseUrl, server ->
            var enableQuery: String? = null
            var enableBody: JsonArray? = null
            var refreshBody: JsonObject? = null
            var deleteQuery: String? = null
            var deleteBody: JsonArray? = null
            server.createContext("/api/config") { it.respond(200, envelope(CONFIG)) }
            server.createContext("/api/listAni") {
                it.respond(
                    200,
                    envelope(
                        """{"releaseDateList":["2026-08"],"weekList":[{"weekLabel":"星期日","items":[{
                          "id":"ani-1","title":"测试番剧","bgmUrl":"https://bgm.tv/subject/42","subgroup":"主组","enable":true,"url":"https://rss.example/main.xml","image":"https://lain.bgm.tv/pic/cover/test.jpg"
                        }]}],"total":1}""",
                    ),
                )
            }
            server.createContext("/api/batchEnable") { exchange ->
                enableQuery = exchange.requestURI.rawQuery
                enableBody = exchange.readJsonArray()
                exchange.respond(200, successWithoutData("修改完成"))
            }
            server.createContext("/api/refreshAni") { exchange ->
                refreshBody = exchange.readJsonObject()
                exchange.respond(200, successWithoutData("已开始刷新RSS"))
            }
            server.createContext("/api/deleteAni") { exchange ->
                deleteQuery = exchange.requestURI.rawQuery
                deleteBody = exchange.readJsonArray()
                exchange.respond(200, successWithoutData("删除订阅成功"))
            }
            val repository = connectedRepository(baseUrl)

            val subscription = repository.listSubscriptions().single()
            repository.setSubscriptionEnabled(subscription.id, false)
            repository.refreshSubscription(subscription.id)
            repository.deleteSubscription(subscription.id)

            assertEquals(42L, subscription.subjectId)
            assertEquals("https://lain.bgm.tv/pic/cover/test.jpg", subscription.posterUrl)
            assertEquals("value=false", enableQuery)
            assertEquals("ani-1", enableBody!!.single().jsonPrimitive.content)
            assertEquals("ani-1", refreshBody!!.getValue("id").jsonPrimitive.content)
            assertEquals("deleteFiles=false", deleteQuery)
            assertEquals("ani-1", deleteBody!!.single().jsonPrimitive.content)
        }
    }

    @Test
    fun `重定向在目标收到 API Key 前被拒绝`() = runBlocking {
        withServer { baseUrl, server ->
            var targetRequests = 0
            server.createContext("/target") { exchange ->
                targetRequests++
                exchange.respond(200, envelope(CONFIG))
            }
            server.createContext("/api/config") { exchange ->
                exchange.responseHeaders.add("Location", "$baseUrl/target")
                exchange.respond(302)
            }
            val secrets = MemorySecretStorage()
            val repository = repository(MemorySettings(), secrets)

            assertFailsWith<AniRssException> {
                repository.saveConnection(baseUrl, "new-api-key", cleartextConfirmed = true)
            }

            assertEquals(0, targetRequests)
            assertNull(secrets.getString(API_KEY))
        }
    }

    @Test
    fun `业务错误 code 被拒绝且不写入凭据`() = runBlocking {
        withServer { baseUrl, server ->
            server.createContext("/api/config") {
                it.respond(200, """{"code":403,"message":"API Key 无效","data":null}""")
            }
            val secrets = MemorySecretStorage()
            val repository = repository(MemorySettings(), secrets)

            val error = assertFailsWith<AniRssException> {
                repository.saveConnection(baseUrl, "bad-key", cleartextConfirmed = true)
            }

            assertTrue(error.message.orEmpty().contains("API Key 无效"))
            assertNull(secrets.getString(API_KEY))
        }
    }

    private suspend fun connectedRepository(baseUrl: String): AniRssRepositoryImpl {
        val repository = repository(MemorySettings(), MemorySecretStorage())
        repository.saveConnection(baseUrl, "api-key", cleartextConfirmed = true)
        return repository
    }

    private fun repository(settings: MemorySettings, secrets: MemorySecretStorage): AniRssRepositoryImpl =
        AniRssRepositoryImpl(
            settingsRepository = settings,
            secretStorage = secrets,
            httpClient = HttpClient(OkHttp) { followRedirects = false },
        )

    private suspend fun withServer(block: suspend (String, HttpServer) -> Unit) {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            start()
        }
        try {
            block("http://127.0.0.1:${server.address.port}", server)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.readJsonObject(): JsonObject =
        JSON.parseToJsonElement(requestBody.bufferedReader().use { it.readText() }).jsonObject

    private fun HttpExchange.readJsonArray(): JsonArray =
        JSON.parseToJsonElement(requestBody.bufferedReader().use { it.readText() }).jsonArray

    private fun HttpExchange.respond(status: Int, body: String = "") {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        if (bytes.isEmpty()) {
            sendResponseHeaders(status, -1)
        } else {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        close()
    }

    private class MemorySettings(initial: SettingsState = SettingsState()) : SettingsRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state = mutableState
        override val loadState = MutableStateFlow<SettingsLoadState>(SettingsLoadState.Loaded)
        override val writeFailure = MutableStateFlow<SettingsWriteFailure?>(null)

        override suspend fun update(transform: (SettingsState) -> SettingsState) {
            mutableState.value = transform(mutableState.value)
        }

        override suspend fun retryLastUpdate() = Unit
        override suspend fun dismissWriteFailure() = Unit
        override suspend fun retryLoad() = Unit
        override suspend fun useDefaultsAfterLoadFailure() = Unit
        override suspend fun awaitLoaded() = Unit
    }

    private class MemorySecretStorage(initial: Map<String, String> = emptyMap()) : SecretStorage {
        private val values = initial.toMutableMap()

        override suspend fun getString(key: String): String? = values[key]
        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    private fun group(
        label: String,
        rss: String,
        combinations: List<AniRssFilterCombination> = emptyList(),
    ) = AniRssGroup(
        label = label,
        rss = rss,
        bangumiUrl = "https://bgm.tv/subject/42",
        updateDay = null,
        tags = emptyList(),
        filterCombinations = combinations,
        resources = emptyList(),
        identityVerified = true,
    )

    private fun envelope(data: String): String = """{"code":200,"message":"success","data":$data}"""

    private fun successWithoutData(message: String = "success"): String =
        """{"code":200,"message":"$message","t":1787443200000}"""

    private companion object {
        const val API_KEY = "aniRssApiKey"
        const val CONFIG = """{"version":"3.2.16","standbyRss":true}"""
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
