package io.github.weiyongzenqi.unuplayer.anirss

import io.github.weiyongzenqi.unuplayer.core.network.APP_USER_AGENT
import io.github.weiyongzenqi.unuplayer.core.security.SecretStorage
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.webdav.createStrictHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

interface AniRssRepository {
    suspend fun connectionState(): AniRssConnectionState
    suspend fun serverProfile(): AniRssServerProfile
    suspend fun saveConnection(baseUrl: String, apiKey: String, cleartextConfirmed: Boolean): AniRssServerProfile
    suspend fun clearConnection()
    suspend fun searchMikan(subjectId: Long, query: String): List<AniRssMikanCandidate>
    suspend fun loadMikanGroups(
        subjectId: Long,
        candidate: AniRssMikanCandidate,
        allowUnverifiedIdentity: Boolean,
    ): List<AniRssGroup>
    suspend fun prepareSubscription(request: AniRssCreateRequest): AniRssPreparedSubscription
    suspend fun isSubscribed(subjectId: Long): Boolean
    suspend fun subscribedSubjectIds(): Set<Long>
    suspend fun listSubscriptions(): List<AniRssSubscription>
    suspend fun preview(subscription: AniRssPreparedSubscription): AniRssPreview
    suspend fun add(subscription: AniRssPreparedSubscription)
    suspend fun setSubscriptionEnabled(id: String, enabled: Boolean)
    suspend fun refreshSubscription(id: String)
    /** 只删除订阅配置，不删除下载器任务或本地文件。 */
    suspend fun deleteSubscription(id: String)
}

class AniRssRepositoryImpl(
    private val settingsRepository: SettingsRepository,
    private val secretStorage: SecretStorage,
    httpClient: HttpClient = createStrictHttpClient(),
) : AniRssRepository {
    private val client = httpClient
    private val connectionMutex = Mutex()
    private val preparationMutex = Mutex()
    private val createMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        encodeDefaults = true
    }

    override suspend fun connectionState(): AniRssConnectionState = connectionMutex.withLock {
        connectionStateLocked()
    }

    private suspend fun connectionStateLocked(): AniRssConnectionState {
        settingsRepository.awaitLoaded()
        val settings = settingsRepository.state.value
        val hasKey = try {
            !secretStorage.getString(API_KEY_SECRET).isNullOrBlank()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        return AniRssConnectionState(
            baseUrl = settings.aniRssBaseUrl,
            configured = settings.aniRssBaseUrl.isNotBlank() && hasKey,
            cleartextConfirmed = settings.aniRssCleartextConfirmed,
        )
    }

    override suspend fun serverProfile(): AniRssServerProfile = parseServerProfile(
        execute("/api/config", JsonObject(emptyMap())),
    )

    override suspend fun saveConnection(
        baseUrl: String,
        apiKey: String,
        cleartextConfirmed: Boolean,
    ): AniRssServerProfile = connectionMutex.withLock {
        val validation = validateAniRssBaseUrl(baseUrl, cleartextConfirmed)
        val normalized = requireNotNull(validation.normalizedUrl) { validation.errorMessage ?: "Ani-RSS 地址无效" }
        settingsRepository.awaitLoaded()
        val oldSettings = settingsRepository.state.value
        val oldKey = secretStorage.getString(API_KEY_SECRET)
        val newKey = apiKey.trim().takeIf(String::isNotEmpty) ?: oldKey
        require(
            !newKey.isNullOrBlank() && newKey.length <= 512 && newKey.none(Char::isISOControl),
        ) { "API Key 不能为空、不能包含控制字符且长度不能超过 512" }

        val profile = try {
            parseServerProfile(
                executeRequest(
                    path = "/api/config",
                    body = JsonObject(emptyMap()),
                    parameters = emptyMap(),
                    base = normalized,
                    apiKey = newKey,
                ).requireData("/api/config"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw AniRssException("Ani-RSS 连接验证失败：${error.message ?: "未知错误"}", error)
        }

        try {
            secretStorage.putString(API_KEY_SECRET, newKey)
            settingsRepository.update {
                it.copy(
                    aniRssBaseUrl = normalized,
                    aniRssCleartextConfirmed = validation.requiresCleartextConfirmation,
                )
            }
            val saved = settingsRepository.state.value
            check(
                settingsRepository.writeFailure.value == null &&
                    saved.aniRssBaseUrl == normalized &&
                    saved.aniRssCleartextConfirmed == validation.requiresCleartextConfirmation,
            ) { "Ani-RSS 设置保存失败" }
            profile
        } catch (error: Throwable) {
            val restoreFailure = withContext(NonCancellable) {
                restoreConnection(oldSettings.aniRssBaseUrl, oldSettings.aniRssCleartextConfirmed, oldKey)
            }
            restoreFailure?.let(error::addSuppressed)
            if (error is CancellationException) throw error
            throw AniRssException("Ani-RSS 连接保存失败", error)
        }
    }

    override suspend fun clearConnection(): Unit = connectionMutex.withLock {
        settingsRepository.awaitLoaded()
        val oldSettings = settingsRepository.state.value
        val oldKey = secretStorage.getString(API_KEY_SECRET)
        try {
            secretStorage.remove(API_KEY_SECRET)
            settingsRepository.update {
                it.copy(aniRssBaseUrl = "", aniRssCleartextConfirmed = false)
            }
            val saved = settingsRepository.state.value
            check(
                settingsRepository.writeFailure.value == null &&
                    saved.aniRssBaseUrl.isEmpty() && !saved.aniRssCleartextConfirmed,
            ) { "Ani-RSS 设置清除失败" }
        } catch (error: Throwable) {
            val restoreFailure = withContext(NonCancellable) {
                restoreConnection(oldSettings.aniRssBaseUrl, oldSettings.aniRssCleartextConfirmed, oldKey)
            }
            restoreFailure?.let(error::addSuppressed)
            if (error is CancellationException) throw error
            throw AniRssException("Ani-RSS 断开连接失败", error)
        }
    }

    private suspend fun restoreConnection(baseUrl: String, cleartextConfirmed: Boolean, apiKey: String?): Throwable? {
        var firstFailure: Throwable? = null
        try {
            if (apiKey == null) secretStorage.remove(API_KEY_SECRET) else secretStorage.putString(API_KEY_SECRET, apiKey)
        } catch (error: Throwable) {
            firstFailure = error
        }
        try {
            settingsRepository.update {
                it.copy(aniRssBaseUrl = baseUrl, aniRssCleartextConfirmed = cleartextConfirmed)
            }
        } catch (error: Throwable) {
            if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
        }
        return firstFailure
    }

    override suspend fun searchMikan(subjectId: Long, query: String): List<AniRssMikanCandidate> {
        require(subjectId > 0L) { "Bangumi subject ID 无效" }
        val searchText = query.trim()
        require(searchText.isNotEmpty()) { "搜索词不能为空" }
        val data = execute(
            path = "/api/mikan",
            body = JsonObject(emptyMap()),
            parameters = mapOf("text" to searchText),
        ) as? JsonObject ?: throw AniRssProtocolException("/api/mikan data 应为对象")
        val weeks = data["weeks"] as? JsonArray ?: throw AniRssProtocolException("/api/mikan 缺少 weeks")
        return weeks.flatMap { weekElement ->
            val week = weekElement as? JsonObject ?: throw AniRssProtocolException("mikan weeks 元素应为对象")
            val weekLabel = week.stringValue("weekLabel")
            val items = week["items"] as? JsonArray ?: throw AniRssProtocolException("mikan week.items 应为数组")
            items.mapNotNull { itemElement ->
                val item = itemElement as? JsonObject ?: throw AniRssProtocolException("mikan item 应为对象")
                val pageUrl = item.stringValue("url") ?: throw AniRssProtocolException("mikan item 缺少 url")
                val mikanId = aniRssMikanIdFromPageUrl(pageUrl)
                    ?: throw AniRssProtocolException("mikan item url 非法")
                val bangumiUrl = item.stringValue("bgmUrl")
                val urlSubjectId = bangumiUrl?.let(::aniRssSubjectIdFromBangumiUrl)
                if (bangumiUrl != null && urlSubjectId == null) {
                    throw AniRssProtocolException("mikan item bgmUrl 非法")
                }
                val numericSubjectId = item.longValue("bgmId")?.takeIf { it > 0L }
                if (urlSubjectId != null && numericSubjectId != null && urlSubjectId != numericSubjectId) {
                    throw AniRssProtocolException("mikan item Bangumi 身份冲突")
                }
                val candidateSubjectId = urlSubjectId ?: numericSubjectId
                if (candidateSubjectId != null && candidateSubjectId != subjectId) return@mapNotNull null
                AniRssMikanCandidate(
                    title = item.stringValue("title") ?: throw AniRssProtocolException("mikan item 缺少 title"),
                    pageUrl = pageUrl,
                    bangumiUrl = bangumiUrl ?: candidateSubjectId?.let { "https://bgm.tv/subject/$it" },
                    bangumiSubjectId = candidateSubjectId,
                    mikanId = mikanId,
                    weekLabel = weekLabel,
                    coverUrl = resolveAniRssMikanCoverUrl(pageUrl, item.stringValue("cover")),
                    score = (item["score"] as? JsonPrimitive)?.doubleOrNull,
                    alreadyExists = item.booleanValue("exists") ?: false,
                    identityVerified = candidateSubjectId == subjectId,
                )
            }
        }.distinctBy { it.pageUrl }
    }

    override suspend fun loadMikanGroups(
        subjectId: Long,
        candidate: AniRssMikanCandidate,
        allowUnverifiedIdentity: Boolean,
    ): List<AniRssGroup> {
        require(subjectId > 0L) { "Bangumi subject ID 无效" }
        require(aniRssMikanIdFromPageUrl(candidate.pageUrl) != null) { "Mikan 番剧地址无效" }
        require(candidate.identityVerified || allowUnverifiedIdentity) { "候选缺少 Bangumi 身份，请先明确确认" }
        val data = execute(
            path = "/api/mikanGroup",
            body = JsonObject(emptyMap()),
            parameters = mapOf("url" to candidate.pageUrl),
        ) as? JsonArray ?: throw AniRssProtocolException("/api/mikanGroup data 应为数组")
        val groups = data.mapNotNull { element ->
            val item = element as? JsonObject ?: throw AniRssProtocolException("mikanGroup 元素应为对象")
            val rss = item.stringValue("rss") ?: throw AniRssProtocolException("字幕组缺少 rss")
            if (!isAniRssHttpUrl(rss)) throw AniRssProtocolException("字幕组 rss 非 http(s) 地址")
            val bangumiUrl = item.stringValue("bgmUrl") ?: candidate.bangumiUrl
            val groupSubjectId = bangumiUrl?.let(::aniRssSubjectIdFromBangumiUrl)
            if (bangumiUrl != null && groupSubjectId == null) {
                throw AniRssProtocolException("字幕组 bgmUrl 非法")
            }
            if (groupSubjectId != null && groupSubjectId != subjectId) return@mapNotNull null
            val regex = item["groupRegex"] as? JsonObject
            AniRssGroup(
                label = item.stringValue("label") ?: throw AniRssProtocolException("字幕组缺少 label"),
                rss = rss,
                bangumiUrl = bangumiUrl,
                updateDay = item.stringValue("updateDay"),
                tags = regex.stringArray("tags"),
                filterCombinations = regex.filterCombinations(),
                resources = item.resourceItems(),
                identityVerified = groupSubjectId == subjectId,
            )
        }.distinctBy { it.rss }
        if (groups.isEmpty()) throw AniRssException("Ani-RSS 没有返回可用于当前番剧的字幕组")
        return groups
    }

    override suspend fun prepareSubscription(request: AniRssCreateRequest): AniRssPreparedSubscription =
        preparationMutex.withLock {
            val validated = request.validate()
            val servicePayload = execute(
                path = "/api/rssToAni",
                body = JsonObject(
                    mapOf(
                        "url" to JsonPrimitive(validated.primaryGroup.rss),
                        "type" to JsonPrimitive("mikan"),
                        "bgmUrl" to JsonPrimitive(
                            validated.primaryGroup.bangumiUrl ?: "https://bgm.tv/subject/${validated.subjectId}",
                        ),
                        "subgroup" to JsonPrimitive(validated.primaryGroup.label),
                        "enable" to JsonPrimitive(true),
                    ),
                ),
            ) as? JsonObject ?: throw AniRssProtocolException("/api/rssToAni data 应为完整 Ani 对象")
            val payload = patchAniRssPayload(servicePayload, validated)
            AniRssPreparedSubscription(
                subjectId = validated.subjectId,
                title = payload.stringValue("title") ?: validated.title,
                payload = payload,
            )
        }

    override suspend fun isSubscribed(subjectId: Long): Boolean {
        require(subjectId > 0L)
        return subjectId in subscribedSubjectIds()
    }

    override suspend fun subscribedSubjectIds(): Set<Long> =
        listSubscriptions().mapNotNullTo(linkedSetOf()) { it.subjectId }

    override suspend fun listSubscriptions(): List<AniRssSubscription> =
        execute("/api/listAni", JsonObject(emptyMap())).toAniRssSubscriptions()

    override suspend fun preview(subscription: AniRssPreparedSubscription): AniRssPreview =
        execute("/api/previewAni", requireAniRssPayload(subscription.payload)).toAniRssPreview()

    override suspend fun add(subscription: AniRssPreparedSubscription): Unit = createMutex.withLock {
        if (isSubscribed(subscription.subjectId)) {
            throw AniRssConflictException("Ani-RSS 中已经存在这部番剧的订阅")
        }
        try {
            executeUnit("/api/addAni", requireAniRssPayload(subscription.payload))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (original: Throwable) {
            // POST 可能已在服务端提交但响应途中断开；只在列表确认目标状态已达成时收敛为成功。
            val added = try {
                isSubscribed(subscription.subjectId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (!added) throw original
        }
    }

    override suspend fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        val normalized = requireSubscriptionId(id)
        executeUnit(
            path = "/api/batchEnable",
            body = JsonArray(listOf(JsonPrimitive(normalized))),
            parameters = mapOf("value" to enabled.toString()),
        )
    }

    override suspend fun refreshSubscription(id: String) {
        executeUnit(
            path = "/api/refreshAni",
            body = JsonObject(mapOf("id" to JsonPrimitive(requireSubscriptionId(id)))),
        )
    }

    override suspend fun deleteSubscription(id: String) {
        executeUnit(
            path = "/api/deleteAni",
            body = JsonArray(listOf(JsonPrimitive(requireSubscriptionId(id)))),
            parameters = mapOf("deleteFiles" to "false"),
        )
    }

    private fun requireSubscriptionId(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 128 && it.none(Char::isISOControl)) { "Ani-RSS 订阅 ID 无效" }
    }

    private suspend fun execute(
        path: String,
        body: JsonElement,
        parameters: Map<String, String> = emptyMap(),
    ): JsonElement {
        return executeEnvelope(path, body, parameters).requireData(path)
    }

    /** Result<Void> 写接口只要求成功信封；官方实现允许省略未赋值的 data。 */
    private suspend fun executeUnit(
        path: String,
        body: JsonElement,
        parameters: Map<String, String> = emptyMap(),
    ) {
        executeEnvelope(path, body, parameters)
    }

    private suspend fun executeEnvelope(
        path: String,
        body: JsonElement,
        parameters: Map<String, String>,
    ): AniRssEnvelope {
        require(path in ALLOWED_PATHS) { "不允许的 Ani-RSS 路径" }
        val connection = connectionMutex.withLock {
            settingsRepository.awaitLoaded()
            val settings = settingsRepository.state.value
            val validation = validateAniRssBaseUrl(
                settings.aniRssBaseUrl,
                settings.aniRssCleartextConfirmed,
            )
            ConnectionSnapshot(
                base = requireNotNull(validation.normalizedUrl) { "Ani-RSS 尚未配置" },
                apiKey = secretStorage.getString(API_KEY_SECRET)?.takeIf(String::isNotBlank)
                    ?: throw AniRssException("Ani-RSS API Key 尚未配置"),
            )
        }
        return executeRequest(path, body, parameters, connection.base, connection.apiKey)
    }

    private suspend fun executeRequest(
        path: String,
        body: JsonElement,
        parameters: Map<String, String>,
        base: String,
        apiKey: String,
    ): AniRssEnvelope = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        require(path in ALLOWED_PATHS) { "不允许的 Ani-RSS 路径" }
        client.prepareRequest(base + path) {
            method = HttpMethod.Post
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, APP_USER_AGENT)
            header("api-key", apiKey)
            contentType(ContentType.Application.Json)
            parameters.forEach { (name, value) -> parameter(name, value) }
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val detail = try {
                    readLimitedBody(response.bodyAsChannel(), MAX_ERROR_RESPONSE_BYTES).let(::extractAniRssErrorMessage)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                throw AniRssException(
                    "Ani-RSS HTTP ${response.status.value}${detail?.let { "：$it" }.orEmpty()}",
                )
            }
            val bodyText = readLimitedBody(response.bodyAsChannel(), MAX_RESPONSE_BYTES)
            val root = runCatching { json.parseToJsonElement(bodyText) }.getOrElse {
                throw AniRssProtocolException("响应不是合法 JSON")
            } as? JsonObject ?: throw AniRssProtocolException("响应 envelope 应为对象")
            val code = (root["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: throw AniRssProtocolException("响应 envelope 缺少 code")
            if (code != ANI_RSS_SUCCESS_CODE) {
                val detail = root.stringValue("message", "msg", "error")
                throw AniRssException(detail?.take(180) ?: "Ani-RSS 请求失败（code=$code）")
            }
            AniRssEnvelope(
                dataPresent = root.containsKey("data"),
                data = root["data"] ?: JsonNull,
            )
        }
    } ?: throw AniRssException("Ani-RSS 请求超时")

    private fun parseServerProfile(data: JsonElement): AniRssServerProfile {
        val config = data as? JsonObject ?: throw AniRssProtocolException("/api/config data 应为对象")
        val version = config.stringValue("version") ?: throw AniRssProtocolException("/api/config 缺少 version")
        requireSupportedVersion(version)
        return AniRssServerProfile(
            version = version,
            standbyRssEnabled = config.booleanValue("standbyRss") ?: false,
        )
    }

    private fun requireSupportedVersion(version: String) {
        val parsed = version.trim().removePrefix("v").split('.').take(3).map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: throw AniRssProtocolException("无法识别服务版本 $version")
        }
        val normalized = parsed + List(3 - parsed.size) { 0 }
        val comparison = normalized.indices.asSequence()
            .map { index -> normalized[index].compareTo(MIN_SUPPORTED_VERSION[index]) }
            .firstOrNull { it != 0 }
            ?: 0
        require(comparison >= 0) {
            "Ani-RSS $version 过旧，最低支持 v${MIN_SUPPORTED_VERSION.joinToString(".")}"
        }
    }

    private fun JsonObject?.stringArray(name: String): List<String> {
        val value = this?.get(name) ?: return emptyList()
        val array = value as? JsonArray ?: throw AniRssProtocolException("groupRegex.$name 应为数组")
        return array.map { element ->
            (element as? JsonPrimitive)?.contentOrNull ?: throw AniRssProtocolException("groupRegex.$name 元素应为字符串")
        }
    }

    private fun JsonObject?.filterCombinations(): List<AniRssFilterCombination> {
        val value = this?.get("regexList") ?: return emptyList()
        val rows = value as? JsonArray ?: throw AniRssProtocolException("groupRegex.regexList 应为二维数组")
        return rows.map { rowElement ->
            val row = rowElement as? JsonArray ?: throw AniRssProtocolException("regexList 行应为数组")
            AniRssFilterCombination(
                options = row.map { optionElement ->
                    val option = optionElement as? JsonObject ?: throw AniRssProtocolException("regexList 选项应为对象")
                    AniRssFilterOption(
                        label = option.stringValue("label") ?: throw AniRssProtocolException("regexList 选项缺少 label"),
                        regex = option.stringValue("regex") ?: throw AniRssProtocolException("regexList 选项缺少 regex"),
                    )
                },
            )
        }.filter { it.options.isNotEmpty() }
    }

    private fun JsonObject.resourceItems(): List<AniRssGroupResource> {
        val array = this["items"] as? JsonArray ?: return emptyList()
        return array.map { element ->
            val item = element as? JsonObject ?: throw AniRssProtocolException("字幕组资源应为对象")
            AniRssGroupResource(
                title = item.stringValue("title") ?: throw AniRssProtocolException("字幕组资源缺少 title"),
                formatSize = item.stringValue("formatSize"),
                createdAt = item.stringValue("createdAt"),
            )
        }
    }

    private suspend fun readLimitedBody(channel: ByteReadChannel, maxBytes: Int): String = try {
        var bytes = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            if (total == bytes.size) {
                val next = (bytes.size * 2).coerceAtMost(maxBytes + 1)
                if (next == bytes.size) break
                bytes = bytes.copyOf(next)
            }
            val read = channel.readAvailable(bytes, total, bytes.size - total)
            if (read <= 0) break
            total += read
        }
        if (total > maxBytes) throw AniRssException("Ani-RSS 响应超过大小上限")
        bytes.copyOf(total).decodeToString()
    } finally {
        channel.cancel(null)
    }

    private fun extractAniRssErrorMessage(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val fromJson = (parsed as? JsonObject)?.stringValue("message", "msg", "error", "detail")
        return (fromJson ?: body.lineSequence().firstOrNull { it.isNotBlank() })
            ?.trim()
            ?.take(180)
            ?.takeIf(String::isNotEmpty)
    }

    private companion object {
        const val API_KEY_SECRET = "aniRssApiKey"
        const val ANI_RSS_SUCCESS_CODE = 200
        const val REQUEST_TIMEOUT_MS = 45_000L
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 64 * 1024
        val MIN_SUPPORTED_VERSION = listOf(3, 0, 1)
        val ALLOWED_PATHS = setOf(
            "/api/config",
            "/api/mikan",
            "/api/mikanGroup",
            "/api/rssToAni",
            "/api/previewAni",
            "/api/addAni",
            "/api/listAni",
            "/api/batchEnable",
            "/api/refreshAni",
            "/api/deleteAni",
        )
    }

    private data class ConnectionSnapshot(
        val base: String,
        val apiKey: String,
    )

    private data class AniRssEnvelope(
        val dataPresent: Boolean,
        val data: JsonElement,
    ) {
        fun requireData(path: String): JsonElement {
            if (!dataPresent) throw AniRssProtocolException("$path 响应 envelope 缺少 data")
            return data
        }
    }
}
