package io.github.weiyongzenqi.unuplayer.schedule

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiGatewayEndpoint
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeSubject
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ScheduleRepository {
    suspend fun load(forceRefresh: Boolean = false): ScheduleSnapshot

    /** 历史季度条目(网关 /sn 聚合); quarterMonth 必须是季度起始月 1/4/7/10。 */
    suspend fun loadSeason(year: Int, quarterMonth: Int, forceRefresh: Boolean = false): ScheduleSeasonSnapshot

    suspend fun searchAnime(query: String, limit: Int = 20): List<ScheduleEntry>
    suspend fun resolveAnime(subjectId: Long): ScheduleEntry?
    suspend fun setWatched(entry: ScheduleEntry, watched: Boolean)
    suspend fun setStatus(entry: ScheduleEntry, status: ScheduleStatus)
}

class ScheduleRepositoryImpl(
    private val scrapedRepository: ScrapedLibraryRepository,
    private val bangumiGateway: BangumiGatewayEndpoint = BangumiGatewayEndpoint(),
    private val dandanplayApi: DandanplayApi,
    private val bangumiEpisodes: BangumiScrapeApi,
    private val nowMillis: () -> Long = ::platformTimeMillis,
) : ScheduleRepository {
    private val mutex = Mutex()
    // resolveAnime 串行(共享 bangumiDataCache, 避免同一季度数据被并发重复请求); 搜索不持锁, 不互相阻塞。
    private val onlineMutex = Mutex()
    // 历史季度独立互斥与缓存: /sn 冷缓存首击可达十几秒, 不得阻塞周表刷新与详情解析。
    private val seasonMutex = Mutex()
    private val seasonCache = mutableMapOf<Pair<Int, Int>, ScheduleSeasonSnapshot>()
    private var memorySnapshot: ScheduleSnapshot? = null
    private val searchedSubjects = BoundedSubjectCache()
    private val bangumiDataCache = mutableMapOf<Pair<Int, Int>, List<BangumiDataItemDto>>()
    // 标题兜底候选缓存: 键=各库 (id, lastScannedAt), 库重扫后自动失效, 避免每次详情点选全库重扫。
    private val titlesMutex = Mutex()
    private var titlesCacheKey: List<Pair<Long, Long?>>? = null
    private var titlesCache: List<NormalizedLibraryTitle> = emptyList()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun load(forceRefresh: Boolean): ScheduleSnapshot = mutex.withLock {
        val now = nowMillis()
        val cached = memorySnapshot
        val watches = scrapedRepository.listScheduleWatches()
        if (!forceRefresh && cached != null && now - cached.refreshedAt < MEMORY_TTL_MS) {
            return@withLock cached.withScheduleWatchStatuses(watches).also { memorySnapshot = it }
        }
        val today = currentScheduleLocalDateTime()
        val (calendarBody, shinResult, dataResult) = coroutineScope {
            val calendar = async { bangumiGateway.calendar() }
            val shin = async {
                try {
                    OptionalScheduleSource(dandanplayApi.shin().allItems())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    OptionalScheduleSource(emptyList(), "弹弹play新番 ID 暂不可用")
                }
            }
            val seasonData = async {
                try {
                    OptionalScheduleSource(
                        json.decodeFromString<List<BangumiDataItemDto>>(
                            bangumiGateway.bangumiData(today.year, quarterStartMonth(today.month)),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    OptionalScheduleSource(emptyList(), "精确放送时刻暂不可用")
                }
            }
            Triple(calendar.await(), shin.await(), seasonData.await())
        }
        val warnings = listOfNotNull(shinResult.warning, dataResult.warning)
        val shinItems = shinResult.value
        val dataItems = dataResult.value

        val calendar = json.decodeFromString<List<BangumiCalendarDayDto>>(calendarBody)
        val shinBySubject = shinItems.asSequence()
            .filter { it.isOnAir && !it.isRestricted }
            .mapNotNull { item -> item.bangumiId?.toLongOrNull()?.takeIf { it > 0L }?.let { it to item } }
            .toMap()
        val dataBySubject = dataItems.mapNotNull { item -> item.toMetadata() }.associateBy { it.subjectId }

        val preliminaries = calendar.flatMap { day ->
            day.items.mapNotNull { item ->
                if (item.id <= 0L) return@mapNotNull null
                val metadata = dataBySubject[item.id]
                val localBroadcast = metadata?.broadcast?.let(::parseBroadcastLocal)
                ScheduleEntry(
                    subjectId = item.id,
                    title = item.nameCn.takeIf { it.isNotBlank() } ?: item.name,
                    originalTitle = item.name.takeIf { it.isNotBlank() && it != item.nameCn },
                    weekday = localBroadcast?.weekday ?: item.airWeekday.takeIf { it in 1..7 }
                        ?: day.weekday.id.coerceIn(1, 7),
                    broadcastTime = localBroadcast?.shortTime,
                    airDate = item.airDate,
                    posterUrl = item.images?.large?.takeIf { it.isNotBlank() }
                        ?: item.images?.common?.takeIf { it.isNotBlank() },
                    rating = item.rating?.score?.takeIf { it > 0.0 },
                    rank = item.rank?.takeIf { it > 0 },
                    watchingCount = item.collection?.doing?.takeIf { it > 0 },
                    animeId = shinBySubject[item.id]?.animeId?.takeIf { it > 0L },
                    tmdbId = metadata?.tmdbId,
                    libraryMatch = null,
                    watched = watches.any { it.subjectId == item.id && it.status != ScheduleStatus.NONE },
                    status = watches.firstOrNull { it.subjectId == item.id }?.status ?: ScheduleStatus.NONE,
                )
            }
        }.distinctBy { it.subjectId }

        val matches = scrapedRepository.findScheduleLibraryMatches(
            subjectIds = preliminaries.mapTo(linkedSetOf()) { it.subjectId },
            tmdbIds = preliminaries.mapNotNullTo(linkedSetOf()) { it.tmdbId },
            animeIds = preliminaries.mapNotNullTo(linkedSetOf()) { it.animeId },
        )
        val titles = normalizedLibraryTitles()

        val entries = preliminaries.map { entry ->
            val match = selectPreferredScheduleMatch(entry, matches) ?: titleHint(entry, titles)
            entry.copy(
                libraryMatch = match,
                tmdbId = match?.takeIf { it.confirmed }?.tmdbId?.takeIf { it > 0L } ?: entry.tmdbId,
                // 时间表状态是用户的观看计划，与是否已经入库相互独立；入库番剧也应保留想看/再看/丢弃。
                watched = entry.status != ScheduleStatus.NONE,
            )
        }
        ScheduleSnapshot(
            entries = entries.sortedWith(
                compareBy<ScheduleEntry> { it.weekday }
                    .thenByDescending { it.watchingCount ?: 0 }
                    .thenBy { it.title },
            ),
            refreshedAt = now,
            partialWarnings = warnings.distinct(),
        ).also { memorySnapshot = it }
    }

    override suspend fun setWatched(entry: ScheduleEntry, watched: Boolean) = mutex.withLock {
        setStatusLocked(entry, if (watched) ScheduleStatus.WANT else ScheduleStatus.NONE)
    }

    override suspend fun loadSeason(year: Int, quarterMonth: Int, forceRefresh: Boolean): ScheduleSeasonSnapshot = seasonMutex.withLock {
        require(year in 2000..2100 && quarterMonth in QUARTER_START_MONTHS) { "非法季度年月" }
        val now = nowMillis()
        val cacheKey = year to quarterMonth
        seasonCache[cacheKey]
            ?.takeIf { !forceRefresh && now - it.refreshedAt < MEMORY_TTL_MS }
            ?.let { return@withLock it }
        val watches = scrapedRepository.listScheduleWatches()
        val season = parseSeasonSubjects(
            body = bangumiGateway.seasonSubjects(year, quarterMonth),
            watches = watches,
            year = year,
            quarterMonth = quarterMonth,
            refreshedAt = now,
        )
        // 库关联与标题兜底复用周表链路; 季度条目无 tmdbId/animeId, 仅 subjectId 查库 + 标题匹配。
        val matches = scrapedRepository.findScheduleLibraryMatches(
            subjectIds = season.entries.mapTo(linkedSetOf()) { it.subjectId },
            tmdbIds = emptySet(),
            animeIds = emptySet(),
        )
        val titles = normalizedLibraryTitles()
        val entries = season.entries.map { entry ->
            entry.copy(
                libraryMatch = selectPreferredScheduleMatch(entry, matches) ?: titleHint(entry, titles),
            )
        }.sortedWith(
            compareBy<ScheduleEntry> { it.weekday }
                .thenByDescending { it.watchingCount ?: 0 }
                .thenBy { it.title },
        )
        season.copy(entries = entries).also { seasonCache[cacheKey] = it }
    }

    override suspend fun searchAnime(query: String, limit: Int): List<ScheduleEntry> {
        val keyword = query.trim().take(120)
        if (keyword.isEmpty()) return emptyList()
        // 标题搜索始终执行(纯数字关键词也走标题, 支持年份/编号搜索); 合法 subject id 时并入精确命中。
        val subjects = buildList {
            addAll(bangumiEpisodes.search(keyword, limit.coerceIn(1, 20)))
            digitSubjectIdCandidate(keyword)?.let { id ->
                bangumiEpisodes.getSubject(id)?.let { add(it) }
            }
            Unit
        }.distinctBy { it.subjectId }
        searchedSubjects.putAll(subjects)
        val watches = scrapedRepository.listScheduleWatches().associateBy { it.subjectId }
        return subjects.map { subject ->
            subject.toScheduleEntry(metadata = null, watch = watches[subject.subjectId])
        }
    }

    override suspend fun resolveAnime(subjectId: Long): ScheduleEntry? = onlineMutex.withLock {
        if (subjectId <= 0L) return@withLock null
        val subject = searchedSubjects.get(subjectId) ?: bangumiEpisodes.getSubject(subjectId)?.also {
            searchedSubjects.put(it)
        } ?: return@withLock null
        val metadata = loadBangumiDataMetadata(subject)
        val watch = scrapedRepository.listScheduleWatches().firstOrNull { it.subjectId == subjectId }
        val preliminary = subject.toScheduleEntry(metadata, watch)
        val matches = scrapedRepository.findScheduleLibraryMatches(
            subjectIds = setOf(subjectId),
            tmdbIds = setOfNotNull(metadata?.tmdbId),
            animeIds = emptySet(),
        )
        val match = selectPreferredScheduleMatch(preliminary, matches) ?: titleHint(preliminary, normalizedLibraryTitles())
        preliminary.copy(
            libraryMatch = match,
            tmdbId = match?.takeIf { it.confirmed }?.tmdbId?.takeIf { it > 0L } ?: preliminary.tmdbId,
        )
    }

    override suspend fun setStatus(entry: ScheduleEntry, status: ScheduleStatus) = mutex.withLock {
        setStatusLocked(entry, status)
    }

    private suspend fun setStatusLocked(entry: ScheduleEntry, status: ScheduleStatus) {
        val watch = entry.toScheduleWatchOrNull(nowMillis(), status)
        if (watch == null || status == ScheduleStatus.NONE) scrapedRepository.deleteScheduleWatch(entry.subjectId)
        else scrapedRepository.upsertScheduleWatch(watch)
        memorySnapshot = memorySnapshot?.let { snapshot ->
            snapshot.copy(
                entries = snapshot.entries.map { current ->
                    if (current.subjectId != entry.subjectId) current
                    else current.copy(
                        watched = status != ScheduleStatus.NONE,
                        status = status,
                    )
                },
            )
        }
    }

    private suspend fun loadBangumiDataMetadata(subject: BangumiScrapeSubject): BangumiDataMetadata? {
        val dateMatch = YYYY_MM_PREFIX.find(subject.date.orEmpty()) ?: return null
        val year = dateMatch.groupValues[1].toIntOrNull()?.takeIf { it in 2000..2100 } ?: return null
        val month = dateMatch.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val quarterMonth = quarterStartMonth(month)
        val cacheKey = year to quarterMonth
        val items = bangumiDataCache[cacheKey] ?: try {
            json.decodeFromString<List<BangumiDataItemDto>>(
                bangumiGateway.bangumiData(year, quarterMonth),
            ).also { bangumiDataCache[cacheKey] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return null
        }
        return items.asSequence().mapNotNull { it.toMetadata() }.firstOrNull { it.subjectId == subject.subjectId }
    }

    private fun BangumiScrapeSubject.toScheduleEntry(
        metadata: BangumiDataMetadata?,
        watch: ScheduleWatch?,
    ): ScheduleEntry {
        val localBroadcast = metadata?.broadcast?.let(::parseBroadcastLocal)
        val status = watch?.status ?: ScheduleStatus.NONE
        return ScheduleEntry(
            subjectId = subjectId,
            title = title,
            originalTitle = originalTitle,
            weekday = localBroadcast?.weekday ?: 0,
            broadcastTime = localBroadcast?.shortTime,
            airDate = date,
            posterUrl = posterUrl,
            rating = rating,
            rank = null,
            watchingCount = null,
            animeId = watch?.animeId,
            // ScheduleWatch 里的 TMDB ID 只是旧快照提示；重新打开详情时必须以当前
            // bangumi-data 或已确认本地库身份为准，不能让历史错配继续覆盖 Bangumi 海报。
            tmdbId = metadata?.tmdbId,
            libraryMatch = null,
            watched = status != ScheduleStatus.NONE,
            status = status,
        )
    }

    /**
     * 标题兜底候选(全库可见番剧标题), 带缓存。
     * 键=各库 (id, lastScannedAt): 库重扫后自动失效; 拉取失败保留旧缓存, 避免瞬时故障污染空标题。
     */
    private suspend fun normalizedLibraryTitles(): List<NormalizedLibraryTitle> = titlesMutex.withLock {
        val key = try {
            scrapedRepository.listLibraries().map { it.id to it.lastScannedAt }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock titlesCache
        }
        if (titlesCacheKey == key) return@withLock titlesCache
        val fresh = try {
            scrapedRepository.listVisibleShowTitles().map { show ->
                NormalizedLibraryTitle(
                    normalized = normalizeScheduleTitle(show.title),
                    showId = show.showId,
                    libraryId = show.libraryId,
                    title = show.title,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return@withLock titlesCache
        }
        titlesCacheKey = key
        titlesCache = fresh
        fresh
    }

    private fun titleHint(entry: ScheduleEntry, titles: List<NormalizedLibraryTitle>): ScheduleLibraryMatch? {
        val candidates = setOfNotNull(entry.title, entry.originalTitle).map(::normalizeScheduleTitle).filter { it.length >= 4 }
        if (candidates.isEmpty()) return null
        val matches = titles.filter { local -> candidates.any { candidate -> candidate == local.normalized } }
        if (matches.size != 1) return null
        val match = matches.single()
        return ScheduleLibraryMatch(
            subjectId = entry.subjectId,
            animeId = entry.animeId,
            tmdbId = entry.tmdbId,
            showId = match.showId,
            libraryId = match.libraryId,
            localTitle = match.title,
            source = ScheduleLibraryMatchSource.TITLE_HINT,
        )
    }

    private data class NormalizedLibraryTitle(
        val normalized: String,
        val showId: Long,
        val libraryId: Long,
        val title: String,
    )

    private companion object {
        const val MEMORY_TTL_MS = 60L * 60L * 1_000L
    }
}

private fun ScheduleSnapshot.withScheduleWatchStatuses(watches: List<ScheduleWatch>): ScheduleSnapshot {
    val watchesBySubject = watches.associateBy { it.subjectId }
    return copy(
        entries = entries.map { entry ->
            val watch = watchesBySubject[entry.subjectId]
            entry.copy(
                watched = watch != null && watch.status != ScheduleStatus.NONE,
                status = watch?.status ?: ScheduleStatus.NONE,
            )
        },
    )
}

/**
 * 进程级有界搜索主体缓存(search/resolve 共享, 内部互斥)。
 * 容量上限 + 最近访问置尾(FIFO/LRU 近似), 避免长会话搜索大量番剧持续驻留内存。
 */
private class BoundedSubjectCache(private val maxSize: Int = 128) {
    private val mutex = Mutex()
    private val subjects = linkedMapOf<Long, BangumiScrapeSubject>()

    suspend fun get(subjectId: Long): BangumiScrapeSubject? = mutex.withLock { subjects[subjectId] }

    suspend fun put(subject: BangumiScrapeSubject) = mutex.withLock {
        subjects.remove(subject.subjectId)
        subjects[subject.subjectId] = subject
        evict()
    }

    suspend fun putAll(list: List<BangumiScrapeSubject>) = mutex.withLock {
        list.forEach { subject ->
            subjects.remove(subject.subjectId)
            subjects[subject.subjectId] = subject
        }
        evict()
    }

    private fun evict() {
        while (subjects.size > maxSize) subjects.remove(subjects.keys.first())
    }
}

/** 纯数字关键词可能是合法 Bangumi subject id; 限 1..9 位且 >0, 超长数字不作为 id 候选。 */
internal fun digitSubjectIdCandidate(keyword: String): Long? =
    keyword.takeIf { it.length in 1..9 && it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0L }

internal fun ScheduleEntry.toScheduleWatchOrNull(watchedAt: Long, status: ScheduleStatus = this.status): ScheduleWatch? {
    return ScheduleWatch(
        subjectId = subjectId,
        title = title,
        airWeekday = weekday,
        animeId = animeId,
        tmdbId = tmdbId,
        watchedAt = watchedAt,
        status = status,
    )
}

/**
 * 按可信度选择唯一库内目标。同一优先级若指向多个番剧/季度则视为歧义，不依赖 SQL 返回顺序随机打开。
 */
internal fun selectPreferredScheduleMatch(
    entry: ScheduleEntry,
    matches: List<ScheduleLibraryMatch>,
): ScheduleLibraryMatch? {
    val candidates = matches.asSequence()
        .filter { match ->
            match.subjectId == entry.subjectId ||
                (entry.tmdbId != null && match.tmdbId == entry.tmdbId && uniqueTmdbMatch(entry, matches, match)) ||
                (entry.animeId != null && match.animeId == entry.animeId)
        }
        .mapNotNull { match ->
            val priority = SCHEDULE_MATCH_PRIORITY.indexOf(match.source)
            priority.takeIf { it >= 0 }?.let { it to match }
        }
        .toList()
    val bestPriority = candidates.minOfOrNull { it.first } ?: return null
    val bestTargets = candidates.asSequence()
        .filter { it.first == bestPriority }
        .map { it.second }
        .distinctBy(ScheduleLibraryMatch::physicalSegmentKey)
        .toList()
    val selected = bestTargets.singleOrNull() ?: return null
    return selected.copy(
        subjectId = entry.subjectId,
        animeId = selected.animeId ?: entry.animeId,
        tmdbId = selected.tmdbId ?: entry.tmdbId,
    )
}

/** 时间表没有可靠季号时，只有单一 TMDB 季候选才可确认，避免多季作品随机命中。 */
private fun uniqueTmdbMatch(
    entry: ScheduleEntry,
    matches: List<ScheduleLibraryMatch>,
    candidate: ScheduleLibraryMatch,
): Boolean = matches.asSequence()
    .filter { it.tmdbId == entry.tmdbId }
    .map(ScheduleLibraryMatch::physicalSegmentKey)
    .distinct()
    .singleOrNull() == candidate.physicalSegmentKey()

private data class SchedulePhysicalSegmentKey(
    val libraryId: Long,
    val showId: Long,
    val seasonNumber: Int?,
    val bangumiOffset: Long,
)

private fun ScheduleLibraryMatch.physicalSegmentKey(): SchedulePhysicalSegmentKey = SchedulePhysicalSegmentKey(
    libraryId = libraryId,
    showId = showId,
    seasonNumber = seasonNumber,
    bangumiOffset = bangumiOffset,
)

private val SCHEDULE_MATCH_PRIORITY = listOf(
    ScheduleLibraryMatchSource.PERSISTED,
    ScheduleLibraryMatchSource.SCANNED,
    ScheduleLibraryMatchSource.TMDB,
    ScheduleLibraryMatchSource.DANDANPLAY,
)

internal fun quarterStartMonth(month: Int): Int = ((month.coerceIn(1, 12) - 1) / 3) * 3 + 1

/** 季度起始月(日漫冬/春/夏/秋档期), 网关 /sn 与客户端共用同一约定。 */
internal val QUARTER_START_MONTHS = setOf(1, 4, 7, 10)

/**
 * /sn 聚合响应解析为季度快照(纯函数): nsfw 剔除、date 推星期、关联观看标记;
 * 无有效首播日期或非法 id 的条目丢弃——时间表语义是放送表, 无星期归属的条目
 * 仍可经搜索/详情链路触达, 不在列表层兜底猜测。
 */
internal fun parseSeasonSubjects(
    body: String,
    watches: List<ScheduleWatch>,
    year: Int,
    quarterMonth: Int,
    refreshedAt: Long,
): ScheduleSeasonSnapshot {
    val response = SEASON_JSON.decodeFromString<SeasonResponseDto>(body)
    val watchesBySubject = watches.associateBy { it.subjectId }
    val entries = response.data.asSequence()
        .filterNot { it.nsfw }
        .mapNotNull { subject ->
            val weekday = subject.date?.let(::isoDateToScheduleWeekday) ?: return@mapNotNull null
            subject.toScheduleEntry(watchesBySubject[subject.id], weekday)
        }
        .toList()
    return ScheduleSeasonSnapshot(
        year = year,
        quarterMonth = quarterMonth,
        entries = entries,
        total = response.total,
        truncated = response.truncated,
        refreshedAt = refreshedAt,
    )
}

/** 网关瘦身后仅保留列表卡片字段的 /sn 条目; 未知字段忽略, 全字段带默认值容忍缺项。 */
@Serializable
private data class SeasonResponseDto(
    val data: List<SeasonSubjectDto> = emptyList(),
    val total: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
private data class SeasonSubjectDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val date: String? = null,
    val platform: String? = null,
    val images: SeasonSubjectImagesDto? = null,
    val rating: SeasonSubjectRatingDto? = null,
    val collection: SeasonSubjectCollectionDto? = null,
    val nsfw: Boolean = false,
) {
    fun toScheduleEntry(watch: ScheduleWatch?, weekday: Int): ScheduleEntry? {
        if (id <= 0L) return null
        val status = watch?.status ?: ScheduleStatus.NONE
        return ScheduleEntry(
            subjectId = id,
            // name_cn 缺失时 title 已回退到 name, originalTitle 再记同名只会重复展示。
            title = nameCn.takeIf { it.isNotBlank() } ?: name,
            originalTitle = name.takeIf { nameCn.isNotBlank() && it.isNotBlank() && it != nameCn },
            weekday = weekday,
            broadcastTime = null,
            airDate = date,
            posterUrl = images?.large?.takeIf { it.isNotBlank() }
                ?: images?.common?.takeIf { it.isNotBlank() },
            rating = rating?.score?.takeIf { it > 0.0 },
            rank = rating?.rank?.takeIf { it > 0 },
            watchingCount = collection?.doing?.takeIf { it > 0 },
            // 弹弹 animeId 是当季概念, 历史季为空; ScheduleWatch 里的旧值仍带出供已标记页使用。
            animeId = watch?.animeId,
            tmdbId = null,
            libraryMatch = null,
            watched = status != ScheduleStatus.NONE,
            status = status,
            platform = platform,
        )
    }
}

@Serializable
private data class SeasonSubjectImagesDto(val large: String? = null, val common: String? = null)

@Serializable
private data class SeasonSubjectRatingDto(val score: Double = 0.0, val rank: Int = 0)

@Serializable
private data class SeasonSubjectCollectionDto(val doing: Int = 0)

private val SEASON_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

internal fun normalizeScheduleTitle(value: String): String = value.lowercase()
    .filter { it.isLetterOrDigit() }
    .removeSuffix("season1")
    .removeSuffix("第1季")

internal fun parseBroadcastLocal(value: String): ScheduleLocalDateTime? {
    val iso = value.trim().removePrefix("R/").substringBefore('/')
    return utcIsoToScheduleLocalDateTime(iso)
}

internal data class BangumiDataMetadata(
    val subjectId: Long,
    val tmdbId: Long?,
    val broadcast: String?,
)

private data class OptionalScheduleSource<T>(
    val value: T,
    val warning: String? = null,
)

@Serializable
private data class BangumiCalendarDayDto(
    val weekday: BangumiWeekdayDto = BangumiWeekdayDto(),
    val items: List<BangumiCalendarItemDto> = emptyList(),
)

@Serializable
private data class BangumiWeekdayDto(
    val id: Int = 1,
)

@Serializable
private data class BangumiCalendarItemDto(
    val id: Long = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("air_weekday") val airWeekday: Int? = null,
    val images: BangumiCalendarImagesDto? = null,
    val rating: BangumiCalendarRatingDto? = null,
    val rank: Int? = null,
    val collection: BangumiCalendarCollectionDto? = null,
)

@Serializable
private data class BangumiCalendarImagesDto(
    val large: String? = null,
    val common: String? = null,
)

@Serializable
private data class BangumiCalendarRatingDto(val score: Double = 0.0)

@Serializable
private data class BangumiCalendarCollectionDto(val doing: Int = 0)

@Serializable
internal data class BangumiDataItemDto(
    val broadcast: String? = null,
    val sites: List<BangumiDataSiteDto> = emptyList(),
) {
    fun toMetadata(): BangumiDataMetadata? {
        val subjectId = sites.firstOrNull { it.site == "bangumi" }?.id?.toLongOrNull()?.takeIf { it > 0L }
            ?: return null
        // TMDB 的 movie/tv ID 数字空间会重叠。旧实现只取最后一段，导致 "movie/123"
        // 被当成 TV 123 请求，从而把完全无关的海报、简介和剧照覆盖到正确 Bangumi 条目。
        // bangumi-data 的 TV 规范形态为 "tv/12345"；保留纯数字旧数据兼容，但明确拒绝其它媒体类型。
        val tmdbId = sites.firstOrNull { it.site == "tmdb" }?.id
            ?.let(::parseBangumiDataTmdbTvId)
        return BangumiDataMetadata(subjectId, tmdbId, broadcast)
    }
}

private fun parseBangumiDataTmdbTvId(raw: String): Long? {
    val normalized = raw.trim().trimStart('/')
    val numeric = when {
        normalized.all(Char::isDigit) -> normalized
        normalized.startsWith("tv/", ignoreCase = true) -> normalized.substring(3).takeIf { it.all(Char::isDigit) }
        else -> null
    }
    return numeric?.toLongOrNull()?.takeIf { it > 0L }
}

@Serializable
internal data class BangumiDataSiteDto(
    val site: String = "",
    val id: String = "",
)
