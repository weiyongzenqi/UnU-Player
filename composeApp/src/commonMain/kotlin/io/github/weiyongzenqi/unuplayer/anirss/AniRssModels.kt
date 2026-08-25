package io.github.weiyongzenqi.unuplayer.anirss

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class AniRssConnectionState(
    val baseUrl: String = "",
    val configured: Boolean = false,
    val cleartextConfirmed: Boolean = false,
)

data class AniRssServerProfile(
    val version: String,
    val standbyRssEnabled: Boolean,
)

data class AniRssBaseUrlValidation(
    val normalizedUrl: String? = null,
    val requiresCleartextConfirmation: Boolean = false,
    val errorMessage: String? = null,
) {
    val isValid: Boolean get() = normalizedUrl != null
}

fun validateAniRssBaseUrl(value: String, cleartextConfirmed: Boolean): AniRssBaseUrlValidation {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return AniRssBaseUrlValidation(errorMessage = "地址不能为空")
    if (trimmed.length > 512) return AniRssBaseUrlValidation(errorMessage = "地址过长")
    val parsed = runCatching { Url(trimmed) }.getOrNull()
        ?: return AniRssBaseUrlValidation(errorMessage = "地址格式无效")
    if (parsed.protocolOrNull != URLProtocol.HTTP && parsed.protocolOrNull != URLProtocol.HTTPS) {
        return AniRssBaseUrlValidation(errorMessage = "只支持 http:// 或 https://")
    }
    if (parsed.host.isBlank()) return AniRssBaseUrlValidation(errorMessage = "缺少主机名")
    if (parsed.user != null || parsed.password != null) {
        return AniRssBaseUrlValidation(errorMessage = "地址不能包含用户名或密码")
    }
    if (parsed.encodedQuery.isNotEmpty() || parsed.trailingQuery || '#' in trimmed) {
        return AniRssBaseUrlValidation(errorMessage = "地址不能包含查询参数或片段")
    }
    if (parsed.encodedPath.trim('/').isNotEmpty()) {
        return AniRssBaseUrlValidation(errorMessage = "请填写 Ani-RSS 服务根地址，不要附加 /api 路径")
    }
    val cleartext = parsed.protocolOrNull == URLProtocol.HTTP
    if (cleartext && !cleartextConfirmed) {
        return AniRssBaseUrlValidation(
            requiresCleartextConfirmation = true,
            errorMessage = "HTTP 会明文传输 API Key，需先确认风险",
        )
    }
    return AniRssBaseUrlValidation(
        normalizedUrl = trimmed.trimEnd('/'),
        requiresCleartextConfirmation = cleartext,
    )
}

data class AniRssMikanCandidate(
    val title: String,
    val pageUrl: String,
    val bangumiUrl: String?,
    val bangumiSubjectId: Long?,
    val mikanId: Long?,
    val weekLabel: String?,
    val coverUrl: String?,
    val score: Double?,
    val alreadyExists: Boolean,
    val identityVerified: Boolean,
)

data class AniRssFilterOption(
    val label: String,
    val regex: String,
)

/** 一个内层列表是一套不可拆分的官方资源筛选组合。 */
data class AniRssFilterCombination(
    val options: List<AniRssFilterOption>,
) {
    val label: String get() = options.joinToString(" · ") { it.label }
}

data class AniRssGroupResource(
    val title: String,
    val formatSize: String?,
    val createdAt: String?,
)

data class AniRssGroup(
    val label: String,
    val rss: String,
    val bangumiUrl: String?,
    val updateDay: String?,
    val tags: List<String>,
    val filterCombinations: List<AniRssFilterCombination>,
    val resources: List<AniRssGroupResource>,
    val identityVerified: Boolean,
)

data class AniRssCreateRequest(
    val subjectId: Long,
    val title: String,
    val primaryGroup: AniRssGroup,
    val standbyGroups: List<AniRssGroup> = emptyList(),
    /** 按 RSS 记录用户明确选择的完整筛选组合；未出现的组保留 rssToAni 默认 match。 */
    val filterCombinationsByRss: Map<String, AniRssFilterCombination> = emptyMap(),
    /** 仅上游缺少 bgmUrl 身份时允许用户显式确认；有冲突身份时始终拒绝。 */
    val unverifiedIdentityConfirmed: Boolean = false,
    val customDownloadPath: String? = null,
    /** 只有用户主动开启高级设置时才非空；不会再复制服务端全局默认值。 */
    val customPriorityKeywords: List<String>? = null,
    /** null 表示保留 /api/rssToAni 的服务端默认值。 */
    val episodeOffset: Int? = null,
)

class AniRssPreparedSubscription internal constructor(
    val subjectId: Long,
    val title: String,
    internal val payload: JsonObject,
)

data class AniRssSubscription(
    val id: String,
    val subjectId: Long?,
    val title: String,
    val subgroup: String?,
    val enabled: Boolean,
    val rssUrl: String?,
    /** Ani-RSS Ani.image 的远端封面；本地 cover 路径不跨服务端文件系统。 */
    val posterUrl: String?,
)

data class AniRssPreviewItem(
    val title: String,
    val renamedTitle: String?,
    val episode: Double?,
    val formatSize: String?,
    val alreadyDownloaded: Boolean,
    val subgroup: String?,
    val publishedAt: String?,
)

data class AniRssPreview(
    val downloadPath: String,
    val items: List<AniRssPreviewItem>,
    val omittedEpisodes: List<String>,
)

fun AniRssCreateRequest.validate(): AniRssCreateRequest {
    require(subjectId > 0L) { "Bangumi subject ID 无效" }
    require(title.isNotBlank()) { "番剧标题不能为空" }
    val groups = listOf(primaryGroup) + standbyGroups
    require(groups.distinctBy { it.rss }.size == groups.size) { "主 RSS 与备用 RSS 不能重复" }
    groups.forEach { group ->
        require(isAniRssHttpUrl(group.rss)) { "字幕组 RSS 必须是有效的 http(s) 地址" }
        val groupSubjectId = group.bangumiUrl?.let(::aniRssSubjectIdFromBangumiUrl)
        require(group.bangumiUrl == null || groupSubjectId != null) { "字幕组 Bangumi 身份格式无效" }
        require(groupSubjectId == null || groupSubjectId == subjectId) { "字幕组关联到了其他 Bangumi 番剧" }
        require(groupSubjectId == subjectId || unverifiedIdentityConfirmed) { "字幕组缺少 Bangumi 身份，请先明确确认" }
    }
    val groupByRss = groups.associateBy(AniRssGroup::rss)
    filterCombinationsByRss.forEach { (rss, combination) ->
        val group = groupByRss[rss] ?: throw IllegalArgumentException("资源筛选组合不属于已选择的字幕组")
        require(combination in group.filterCombinations) { "资源筛选组合不属于对应字幕组" }
    }
    customDownloadPath?.let { require(it.trim().isNotEmpty()) { "自定义下载路径不能为空" } }
    customPriorityKeywords?.let { keywords ->
        require(keywords.map(String::trim).all(String::isNotEmpty)) { "优先关键词不能包含空项" }
    }
    return this
}

/** 只在 /api/rssToAni 的完整 Ani 上修改用户明确选择的字段，未知字段原样保留。 */
internal fun patchAniRssPayload(servicePayload: JsonObject, request: AniRssCreateRequest): JsonObject {
    request.validate()
    val values = servicePayload.toMutableMap()
    values["url"] = JsonPrimitive(request.primaryGroup.rss)
    values["subgroup"] = JsonPrimitive(request.primaryGroup.label)
    values["bgmUrl"] = JsonPrimitive(request.primaryGroup.bangumiUrl ?: "https://bgm.tv/subject/${request.subjectId}")
    values["enable"] = JsonPrimitive(true)
    values["standbyRssList"] = JsonArray(
        request.standbyGroups.map { group ->
            JsonObject(
                mapOf(
                    "label" to JsonPrimitive(group.label),
                    "url" to JsonPrimitive(group.rss),
                    "offset" to JsonPrimitive(0),
                ),
            )
        },
    )
    if (request.filterCombinationsByRss.isNotEmpty()) {
        val selectedGroups = listOf(request.primaryGroup) + request.standbyGroups
        val explicitlyChangedPrefixes = selectedGroups.mapNotNull { group ->
            group.takeIf { it.rss in request.filterCombinationsByRss }?.let { "{{${it.label}}}:" }
        }
        val preservedMatches = (values["match"] as? JsonArray).orEmpty().mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }.filterNot { value -> explicitlyChangedPrefixes.any(value::startsWith) }
        val selectedMatches = selectedGroups.flatMap { group ->
            request.filterCombinationsByRss[group.rss].orEmptyOptions().map { option ->
                "{{${group.label}}}:${option.regex}"
            }
        }
        values["match"] = JsonArray((preservedMatches + selectedMatches).distinct().map(::JsonPrimitive))
    }
    request.customDownloadPath?.trim()?.let { path ->
        values["customDownloadPath"] = JsonPrimitive(true)
        values["customDownloadPathTemplate"] = JsonPrimitive(path)
    }
    // 只在有关键词时显式下发并启用; 空/未设置不下发 enable 字段, 保留 /api/rssToAni 的服务端默认。
    request.customPriorityKeywords?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?.let { normalized ->
            values["customPriorityKeywordsEnable"] = JsonPrimitive(true)
            values["customPriorityKeywords"] = JsonArray(normalized.map(::JsonPrimitive))
        }
    request.episodeOffset?.let { values["offset"] = JsonPrimitive(it) }
    return requireAniRssPayload(JsonObject(values))
}

private fun AniRssFilterCombination?.orEmptyOptions(): List<AniRssFilterOption> = this?.options.orEmpty()

internal fun requireAniRssPayload(payload: JsonObject): JsonObject {
    val mainUrl = payload.stringValue("url") ?: throw IllegalArgumentException("Ani-RSS 主 RSS 不能为空")
    require(isAniRssHttpUrl(mainUrl)) { "Ani-RSS 主 RSS 必须是有效的 http(s) 地址" }
    val standbyUrls = (payload["standbyRssList"] as? JsonArray).orEmpty().map { element ->
        val item = element as? JsonObject ?: throw IllegalArgumentException("Ani-RSS 备用 RSS 结构无效")
        item.stringValue("url") ?: throw IllegalArgumentException("Ani-RSS 备用 RSS 地址不能为空")
    }
    require(standbyUrls.all(::isAniRssHttpUrl)) { "Ani-RSS 备用 RSS 必须是有效的 http(s) 地址" }
    require(standbyUrls.distinct().size == standbyUrls.size && mainUrl !in standbyUrls) { "Ani-RSS RSS 地址不能重复" }
    return payload
}

internal fun JsonElement.toAniRssSubscriptions(): List<AniRssSubscription> {
    val root = this as? JsonObject ?: throw AniRssProtocolException("/api/listAni data 应为对象")
    val weeks = root["weekList"] as? JsonArray ?: throw AniRssProtocolException("/api/listAni 缺少 weekList")
    return weeks.flatMap { weekElement ->
        val week = weekElement as? JsonObject ?: throw AniRssProtocolException("weekList 元素应为对象")
        val items = week["items"] as? JsonArray ?: throw AniRssProtocolException("weekList.items 应为数组")
        items.map { itemElement ->
            val item = itemElement as? JsonObject ?: throw AniRssProtocolException("订阅元素应为对象")
            val id = item.stringValue("id") ?: throw AniRssProtocolException("订阅缺少 id")
            AniRssSubscription(
                id = id,
                subjectId = item.stringValue("bgmUrl")?.let(::aniRssSubjectIdFromBangumiUrl),
                title = item.stringValue("title") ?: throw AniRssProtocolException("订阅缺少 title"),
                subgroup = item.stringValue("subgroup"),
                enabled = item.booleanValue("enable") ?: false,
                rssUrl = item.stringValue("url"),
                posterUrl = item.stringValue("image")?.takeIf(::isAniRssHttpUrl),
            )
        }
    }.distinctBy { it.id }
}

internal fun JsonElement.toAniRssPreview(): AniRssPreview {
    val root = this as? JsonObject ?: throw AniRssProtocolException("/api/previewAni data 应为对象")
    val items = root["items"] as? JsonArray ?: throw AniRssProtocolException("/api/previewAni 缺少 items")
    val omitted = root["omitList"] as? JsonArray ?: JsonArray(emptyList())
    return AniRssPreview(
        downloadPath = root.stringValue("downloadPath").orEmpty(),
        items = items.map { element ->
            val item = element as? JsonObject ?: throw AniRssProtocolException("preview items 元素应为对象")
            AniRssPreviewItem(
                title = item.stringValue("title") ?: throw AniRssProtocolException("预览资源缺少 title"),
                renamedTitle = item.stringValue("reName"),
                episode = (item["episode"] as? JsonPrimitive)?.doubleOrNull,
                formatSize = item.stringValue("formatSize"),
                alreadyDownloaded = item.booleanValue("hasDownloaded") ?: false,
                subgroup = item.stringValue("subgroup"),
                publishedAt = item.stringValue("pubDate"),
            )
        },
        omittedEpisodes = omitted.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        },
    )
}

internal fun aniRssSubjectIdFromBangumiUrl(value: String): Long? {
    val parsed = runCatching { Url(value.trim()) }.getOrNull() ?: return null
    if (parsed.protocolOrNull != URLProtocol.HTTP && parsed.protocolOrNull != URLProtocol.HTTPS) return null
    if (parsed.host.lowercase() !in ANI_RSS_BANGUMI_HOSTS) return null
    val match = ANI_RSS_SUBJECT_PATH.matchEntire(parsed.encodedPath) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
}

internal fun aniRssMikanIdFromPageUrl(value: String): Long? {
    val parsed = runCatching { Url(value.trim()) }.getOrNull() ?: return null
    if (parsed.protocolOrNull != URLProtocol.HTTP && parsed.protocolOrNull != URLProtocol.HTTPS) return null
    if (parsed.host.isBlank() || parsed.user != null || parsed.password != null) return null
    val match = ANI_RSS_MIKAN_PATH.matchEntire(parsed.encodedPath) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
}

/**
 * `/api/mikan` 的 cover 可能是相对 Mikan 条目页的路径。先补齐为可加载的绝对地址，
 * 同时拒绝凭据 URL 与从 HTTPS 页面降级到 HTTP 的封面。
 */
internal fun resolveAniRssMikanCoverUrl(pageUrl: String, value: String?): String? {
    val target = value?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= MAX_ANI_RSS_IMAGE_URL_LENGTH && it.none(Char::isISOControl)
    } ?: return null
    if (ANI_RSS_URL_SCHEME.containsMatchIn(target) &&
        !target.startsWith("http://", ignoreCase = true) &&
        !target.startsWith("https://", ignoreCase = true)
    ) return null
    val base = runCatching { Url(pageUrl) }.getOrNull() ?: return null
    if (aniRssMikanIdFromPageUrl(pageUrl) == null) return null
    val origin = "${base.protocol.name}://${base.host}${base.port.takeIf { it != 0 }?.let { ":$it" }.orEmpty()}"
    val resolved = when {
        target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true) -> target
        target.startsWith("//") -> "${base.protocol.name}:$target"
        target.startsWith('/') -> origin + target
        else -> origin + base.encodedPath.substringBeforeLast('/', missingDelimiterValue = "") + "/" + target
    }.substringBefore('#')
    if (!isAniRssHttpUrl(resolved)) return null
    val parsed = runCatching { Url(resolved) }.getOrNull() ?: return null
    if (base.protocolOrNull == URLProtocol.HTTPS && parsed.protocolOrNull != URLProtocol.HTTPS) return null
    return parsed.toString()
}

internal fun isAniRssHttpUrl(value: String): Boolean = runCatching {
    val normalized = value.trim()
    if (!normalized.startsWith("http://", ignoreCase = true) &&
        !normalized.startsWith("https://", ignoreCase = true)
    ) return@runCatching false
    val parsed = Url(normalized)
    parsed.protocolOrNull in setOf(URLProtocol.HTTP, URLProtocol.HTTPS) &&
        parsed.host.isNotBlank() && parsed.user == null && parsed.password == null
}.getOrDefault(false)

internal fun JsonObject.stringValue(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.booleanValue(vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.booleanOrNull
}

internal fun JsonObject.longValue(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.longOrNull
}

private val ANI_RSS_BANGUMI_HOSTS = setOf("bgm.tv", "bangumi.tv", "chii.in")
private val ANI_RSS_SUBJECT_PATH = Regex("/subject/(\\d+)/?")
private val ANI_RSS_MIKAN_PATH = Regex("/Home/Bangumi/(\\d+)/?", RegexOption.IGNORE_CASE)
private val ANI_RSS_URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private const val MAX_ANI_RSS_IMAGE_URL_LENGTH = 2048

open class AniRssException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class AniRssProtocolException(message: String) : AniRssException("Ani-RSS 协议不兼容：$message")
class AniRssConflictException(message: String) : AniRssException(message)
