package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbTvCandidate
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val tmdbSeasonMarkerPatterns = listOf(
    Regex("第\\s*[零〇一二三四五六七八九十百两\\d]+\\s*(?:季|期|部|篇|クール)"),
    Regex("(?i)\\bseason\\s*(?:\\d+|[ivxlcdm]+)\\b"),
    Regex("(?i)\\b\\d+(?:st|nd|rd|th)\\s+season\\b"),
    Regex("(?i)(?<![A-Za-z0-9])s\\s*0*\\d+(?![\\p{L}\\p{N}])"),
)
private val emptyTmdbSeasonWrapperPattern = Regex("\\(\\s*\\)|（\\s*）|【\\s*】|\\[\\s*]")
private val trailingTmdbSeasonSeparatorPattern = Regex("[\\s\\-_:：·/\\\\]+$")
private val standaloneTmdbYearPattern = Regex("(?<![\\p{L}\\p{N}])(19|20)\\d{2}(?![\\p{L}\\p{N}])")

internal fun cleanTmdbSearchKeyword(raw: String): String {
    val withoutSeason = tmdbSeasonMarkerPatterns.fold(raw) { value, pattern ->
        pattern.replace(value, " ")
    }.replace(emptyTmdbSeasonWrapperPattern, " ")
        .replace(standaloneTmdbYearPattern, " ")
        .replace(trailingTmdbSeasonSeparatorPattern, " ")
    return DanmakuMatcher.cleanSearchKeyword(withoutSeason)
        .ifBlank { DanmakuMatcher.cleanSearchKeyword(raw) }
}

/**
 * 在线刮削管线(commonMain, 见 .claude/plans/online-scraping-2026-08-06.md §3)。
 *
 * 识别策略: **无 NFO/无 TMDB 身份先 Bangumi → 弹弹文件名/hash 精确回退**；
 * NFO 已有 tmdbId 时仍优先使用弹弹 search/episodes 快查，再用 Bangumi 补充元数据。
 * - hash: 每季**至多挑 1 个代表文件**(最小集号主集, 排 SP/OAD), 只对它算前 16MB MD5 + match,
 *   命中即锁死该季 animeId; 未命中该季**停止 hash** 回落文件名候选, **绝不遍历全集**
 * - 季映射: hash 锚点优先; 无锚点时仅当候选季数可信(≤ 本地季数且唯一候选)才顺序映射,
 *   否则降级每季独立 hash / 手动(Bangumi 侧按年份排序对齐)
 * - 落库: 部级(season_number=0)+ 每季 meta 行写 [ScrapedOnlineMeta]；文本/身份 reapply，图片 UI 直读;
 *   ANCHOR 占位标题(show.tmdb_id 空)overwrite_title=1 可被真实标题覆盖, NFO 真标题保留
 *
 * 手动路径: [searchCandidates] 搜索候选, [applyCandidate] 应用候选(写 MANUAL_* 覆盖语义)。
 * 网络纪律: 区分真实空结果与可重试失败；批量/懒触发并发由调用方控制(全局 1~4)。
 */
class AnimeScraper(
    private val dandanplay: DandanplayScrapeProvider?,
    private val bangumi: BangumiScrapeProvider,
    private val downloader: RemoteImageDownloader,
    private val repo: ScrapedLibraryRepository,
    /** TMDB 增强（宽幅头图 backdrop + 剧集剧照 still）；生产工厂固定注入 Gateway 客户端。 */
    private val tmdb: TmdbScrapeApi? = null,
) : BatchScraper {

    /** TMDB 补全通道已注入。 */
    val hasTmdb: Boolean get() = tmdb != null

    /** 自动刮削结果。 */
    sealed interface AutoScrapeOutcome {
        /** 无本地季可刮(不动)。 */
        data object Skipped : AutoScrapeOutcome
        /** 全部未命中(需手动)。 */
        data object NoMatch : AutoScrapeOutcome
        /** 在线请求临时失败，不写 24 小时节流占位。 */
        data object RetryableFailure : AutoScrapeOutcome
        /** 候选模糊(多个可能作品/季数不一致), 需用户确认。 */
        data class NeedsConfirmation(val candidates: List<ScrapeCandidate>) : AutoScrapeOutcome
        /** 已写入部分在线数据，其余缺项保持可立即重试。 */
        data class Partial(val showId: Long, val seasonsScraped: Int) : AutoScrapeOutcome
        /** 完成(showId, 成功刮到的季数)。 */
        data class Done(val showId: Long, val seasonsScraped: Int) : AutoScrapeOutcome
    }

    private data class LocalSeason(
        val seasonNumber: Int,
        val posterPath: String?,
        val episodes: List<ScrapedEpisode>,
    )

    private data class ManualSeasonDetail(
        val seasonNumber: Int,
        val candidate: ScrapeCandidate,
        val detail: ScrapedScrapeData,
    )

    private data class DandanLookup(
        val seasons: List<ScrapeCandidate>,
        val confirmationCandidates: List<ScrapeCandidate>,
        val hadFailure: Boolean = false,
    )

    private data class TmdbEnrichmentResult(
        val tmdbId: Long?,
        val hadRetryableFailure: Boolean = false,
    )

    private data class EpisodeImageUpdate(
        val episode: ScrapedOnlineEpisode,
        val hadRetryableFailure: Boolean = false,
    )

    /**
     * 单部番剧自动刮削。
     *
     * @param hashProvider 悬空返回 (fileSize, fileHash) 或 null(无法哈希/关闭哈希回落)。
     *        WEBDAV=Range GET 前 16MB, LOCAL=calcDanmakuHash, 由调用方按 sourceKind 注入。
     */
    suspend fun scrapeAuto(
        library: LibraryConfig,
        showPath: String,
        hashProvider: (suspend (videoPath: String) -> Pair<Long, String>?)? = null,
        onProgress: suspend (String) -> Unit = {},
    ): AutoScrapeOutcome {
        val scrapeKey = scrapeKey(library.id, showPath)
        if (!beginScrape(scrapeKey)) return AutoScrapeOutcome.Skipped
        var shouldRecordAttempt = false
        try {
            val show = repo.getShowByPath(library.id, showPath) ?: return AutoScrapeOutcome.NoMatch
            val localSeasons = loadLocalSeasons(show.id)
            if (localSeasons.isEmpty()) return AutoScrapeOutcome.Skipped
            shouldRecordAttempt = true

            val titleHint = show.folder_name.ifBlank { show.title }.ifBlank { pathLeaf(showPath) }
            val cleanTitleHint = cleanKeyword(titleHint)
            val cleanTmdbTitleHint = cleanTmdbSearchKeyword(titleHint)
            val yearHint = show.year?.toInt()
                ?: Regex("(19|20)\\d{2}").find(titleHint)?.value?.toIntOrNull()

            // NFO 已给出 TMDB 身份时无需再依赖 Bangumi/弹弹定位，先直接补齐在线集照。
            // NFO 其余数据已完整时，身份匹配已经可靠；still 缺失交给详情页的图片回退闸门处理。
            val initialMetas = repo.listOnlineMeta(library.id, showPath)
            var initialTmdbEnrichmentFailed = false
            if (tmdb != null && show.tmdb_id != null && hasMissingTmdbEpisodeImages(localSeasons, initialMetas)) {
                ensureTmdbImageMetaRows(library.id, showPath, localSeasons)
                val enrichment = enrichWithTmdb(
                    library = library,
                    showPath = showPath,
                    show = show,
                    localSeasons = localSeasons,
                    titleHint = titleHint,
                    source = ScrapeSource.NFO,
                )
                initialTmdbEnrichmentFailed = enrichment.hadRetryableFailure
                val refreshedMetas = repo.listOnlineMeta(library.id, showPath)
                if (!hasMissingCatalogData(show, localSeasons, refreshedMetas)) {
                    repo.reapplyOnlineMeta(library.id, showPath)
                    if (enrichment.hadRetryableFailure) {
                        shouldRecordAttempt = false
                        repo.markAutoScrapeRetryable(library.id, showPath)
                        return AutoScrapeOutcome.Partial(show.id, 0)
                    }
                    return AutoScrapeOutcome.Done(show.id, localSeasons.size)
                }
            }

            val preferBangumi = library.scanMode == ScanMode.ANCHOR || show.tmdb_id == null
            val tmdbSearchWasAttempted = tmdb != null && show.tmdb_id == null && cleanTmdbTitleHint.isNotBlank()
            onProgress("正在并行查询 Bangumi、弹弹play 和 TMDB...")
            val (bangumiResult, dandanLookup, tmdbResult) = coroutineScope {
                val bangumiDeferred = async {
                    runSuspendCatching { bangumi.search(cleanTitleHint) }
                }
                val dandanDeferred = async {
                    val provider = dandanplay ?: return@async DandanLookup(emptyList(), emptyList())
                    var hadFailure = false
                    if (show.tmdb_id != null) {
                        val byTmdbResult = runSuspendCatching {
                            provider.searchByTmdb(show.tmdb_id)
                        }
                        hadFailure = byTmdbResult.isFailure
                        val byTmdb = byTmdbResult.getOrDefault(emptyList())
                        if (byTmdb.isNotEmpty()) return@async DandanLookup(byTmdb, byTmdb, hadFailure)
                    }
                    val searchResult = runSuspendCatching { provider.search(cleanTitleHint) }
                    hadFailure = hadFailure || searchResult.isFailure
                    val searched = searchResult.getOrDefault(emptyList())
                    DandanLookup(
                        seasons = filterDandanCandidates(searched, titleHint, yearHint),
                        confirmationCandidates = filterDandanConfirmationCandidates(
                            searched,
                            titleHint = titleHint,
                            year = yearHint,
                        ),
                        hadFailure = hadFailure,
                    )
                }
                val tmdbDeferred = async {
                    if (!tmdbSearchWasAttempted) {
                        Result.success(emptyList())
                    } else {
                        runSuspendCatching {
                            tmdb.searchTv(cleanTmdbTitleHint, yearHint)
                        }
                    }
                }
                Triple(
                    bangumiDeferred.await(),
                    dandanDeferred.await(),
                    tmdbDeferred.await(),
                )
            }
            val bangumiCandidates = bangumiResult.getOrDefault(emptyList())
            val tmdbCandidates = tmdbResult.getOrDefault(emptyList())
            if (tmdbSearchWasAttempted && tmdbResult.isSuccess) {
                val automaticCandidate = pickTmdbCandidate(tmdbCandidates, cleanTmdbTitleHint, yearHint)
                if (automaticCandidate == null) {
                    repo.recordTmdbAutoMatchFailure(library.id, showPath, platformTimeMillis())
                } else {
                    repo.clearTmdbAutoMatchFailure(library.id, showPath)
                }
            }
            var hadRetryableFailure = initialTmdbEnrichmentFailed || bangumiResult.isFailure ||
                dandanLookup.hadFailure || tmdbResult.isFailure
            var bangumiAttempted = false
            suspend fun attemptBangumi(): AutoScrapeOutcome? {
                bangumiAttempted = true
                val result = runSuspendCatching {
                    scrapeByBangumi(
                        library = library,
                        showPath = showPath,
                        show = show,
                        localSeasons = localSeasons,
                        titleHint = titleHint,
                        subjects = bangumiCandidates,
                        tmdbCandidates = tmdbCandidates,
                    )
                }
                if (result.isFailure) {
                    hadRetryableFailure = true
                    return null
                }
                return when (val outcome = result.getOrThrow()) {
                    AutoScrapeOutcome.RetryableFailure -> {
                        hadRetryableFailure = true
                        null
                    }
                    else -> outcome
                }
            }
            var bangumiAutoAttempt: AutoScrapeOutcome? = null
            if (preferBangumi && localSeasons.size == 1 && bangumiCandidates.isNotEmpty()) {
                val outcome = attemptBangumi()
                if (outcome is AutoScrapeOutcome.Done) return outcome
                bangumiAutoAttempt = outcome
            }

            // ① 弹弹文件名/系列级匹配：Bangumi 不可唯一确认时作为精确回退
            val dandanSeasons = dandanLookup.seasons
            val dandanCandidatesForConfirmation = dandanLookup.confirmationCandidates

            // ② 每季 1 文件 hash(季级锚点, 兜底+精确)
            val hashAnchors = mutableMapOf<Int, ScrapeCandidate>()
            if (hashProvider != null && dandanplay != null) {
                for (season in localSeasons) {
                    if (season.episodes.isEmpty()) continue
                    val repFile = pickHashFile(season)
                    val hash = runSuspendCatching { hashProvider(repFile.video_path) }.getOrNull() ?: continue
                    val matchResult = runSuspendCatching {
                        dandanplay.matchSeason(repFile.video_name, hash.second, hash.first)
                    }
                    if (matchResult.isFailure) {
                        hadRetryableFailure = true
                        continue
                    }
                    val match = matchResult.getOrNull() ?: continue
                    hashAnchors[season.seasonNumber] = dandanSeasons.firstOrNull { it.identityId == match.animeId }
                        ?: ScrapeCandidate(
                            ScrapeSource.DANDANPLAY,
                            match.animeId,
                            match.animeTitle.ifBlank { titleHint },
                        )
                }
            }

            // ③ 季映射: 为每个本地季确定弹弹候选
            val seasonCandidatesResult = runSuspendCatching {
                mapDandanSeasons(localSeasons, dandanSeasons, hashAnchors)
            }
            if (seasonCandidatesResult.isFailure) hadRetryableFailure = true
            val seasonCandidates = seasonCandidatesResult.getOrDefault(emptyMap())

            // 弹弹完全未命中：先保留候选，继续尝试 TMDB 最终补全，再暴露人工确认。
            if (seasonCandidates.isEmpty()) {
                val ambiguousCandidates = (dandanCandidatesForConfirmation + hashAnchors.values)
                    .distinctBy { "${it.source}:${it.identityId}:${it.title}" }
                val bangumiOutcome = if (bangumiAttempted) {
                    bangumiAutoAttempt
                } else if (bangumiCandidates.isNotEmpty()) {
                    attemptBangumi()
                } else {
                    null
                }
                if (bangumiOutcome is AutoScrapeOutcome.Done) return bangumiOutcome
                val confirmationOutcome = bangumiOutcome as? AutoScrapeOutcome.NeedsConfirmation
                val partialOutcome = bangumiOutcome as? AutoScrapeOutcome.Partial
                if (tmdb != null) {
                    onProgress("正在补全 TMDB 图片与身份...")
                    val enrichment = enrichWithTmdb(
                        library = library,
                        showPath = showPath,
                        show = show,
                        localSeasons = localSeasons,
                        titleHint = show.title.ifBlank { titleHint },
                        source = ScrapeSource.TMDB,
                        preloadedCandidates = tmdbCandidates,
                        onProgress = onProgress,
                    )
                    if (enrichment.tmdbId != null) {
                        repo.reapplyOnlineMeta(library.id, showPath)
                        if (enrichment.hadRetryableFailure) {
                            shouldRecordAttempt = false
                            repo.markAutoScrapeRetryable(library.id, showPath)
                            return AutoScrapeOutcome.Partial(show.id, 0)
                        }
                        return AutoScrapeOutcome.Done(show.id, localSeasons.size)
                    }
                    hadRetryableFailure = hadRetryableFailure || enrichment.hadRetryableFailure
                }
                if (confirmationOutcome != null) return confirmationOutcome
                if (ambiguousCandidates.isNotEmpty()) return AutoScrapeOutcome.NeedsConfirmation(ambiguousCandidates)
                if (partialOutcome != null) {
                    shouldRecordAttempt = false
                    repo.markAutoScrapeRetryable(library.id, showPath)
                    return partialOutcome
                }
                if (hadRetryableFailure) {
                    shouldRecordAttempt = false
                    repo.markAutoScrapeRetryable(library.id, showPath)
                    return AutoScrapeOutcome.RetryableFailure
                }
                return AutoScrapeOutcome.NoMatch
            }

            // ④ 逐季 fetch detail + 下载季照 + 写季级 meta
            var scrapedSeasons = 0
            var hadIncompleteSeasonDetail = false
            val now = platformTimeMillis()
            val overwriteTitle = show.tmdb_id == null // ANCHOR 占位标题可被真实标题覆盖
            val dandanProvider = dandanplay ?: return AutoScrapeOutcome.NoMatch
            val seasonDetails = mapConcurrently(seasonCandidates.entries.toList()) { entry ->
                val seasonNumber = entry.key
                val candidate = entry.value
                val detail = dandanProvider.fetchDetail(candidate)
                val localPoster = detail.remotePosterUrl?.let { url ->
                    runSuspendCatching {
                        downloader.downloadSeasonPoster(library.id, showPath, seasonNumber, url)
                    }.getOrNull()
                }
                Triple(seasonNumber, candidate, detail to localPoster)
            }
            for ((seasonNumber, candidate, detailAndPoster) in seasonDetails) {
                val (detail, localPoster) = detailAndPoster
                if (!detail.complete) hadIncompleteSeasonDetail = true
                if (!hasUsableDetail(detail)) continue
                repo.upsertOnlineMeta(
                    libraryId = library.id, showPath = showPath, seasonNumber = seasonNumber,
                    source = ScrapeSource.DANDANPLAY, overwriteTitle = overwriteTitle,
                    dandanplayId = candidate.identityId, bangumiId = candidate.bgmSubjectId,
                    remotePosterUrl = detail.remotePosterUrl, localPosterPath = localPoster,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = detail.episodes, scrapedAt = now,
                )
                scrapedSeasons++
            }

            if (scrapedSeasons == 0) {
                if (hadIncompleteSeasonDetail) {
                    shouldRecordAttempt = false
                    repo.markAutoScrapeRetryable(library.id, showPath)
                    return AutoScrapeOutcome.RetryableFailure
                }
                return AutoScrapeOutcome.NoMatch
            }
            if (scrapedSeasons < seasonCandidates.size) {
                repo.reapplyOnlineMeta(library.id, showPath)
                shouldRecordAttempt = false
                repo.markAutoScrapeRetryable(library.id, showPath)
                return AutoScrapeOutcome.Partial(show.id, scrapedSeasons)
            }

            // 部级元数据: 优先 bgm(subject 详情: 简介/评分/标签/制作/原名), 弹弹候选兜底 title/year
            val showData = buildShowData(dandanSeasons, seasonCandidates, titleHint, yearHint, bangumiCandidates)
            repo.upsertOnlineMeta(
                libraryId = library.id, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.DANDANPLAY, overwriteTitle = overwriteTitle,
                dandanplayId = seasonCandidates.values.firstNotNullOfOrNull { it.identityId },
                bangumiId = showData.bgmSubjectId,
                remotePosterUrl = null, localPosterPath = null,
                title = showData.title, originalTitle = showData.originalTitle, year = showData.year,
                plot = showData.plot, rating = showData.rating, releaseDate = showData.releaseDate,
                genres = showData.genres, studios = showData.studios,
                episodes = emptyList(), scrapedAt = now,
            )

            val tmdbEnrichment = enrichWithTmdb(
                library = library,
                showPath = showPath,
                show = show,
                localSeasons = localSeasons,
                titleHint = titleHint,
                source = ScrapeSource.DANDANPLAY,
                preloadedCandidates = tmdbCandidates,
            )
            val resolvedTmdbId = tmdbEnrichment.tmdbId

            // 高置信命中自动写 Bangumi 季度关联(评论区立即亮): hash 锚点命中季, 或唯一候选整部。
            // 使用刚持久化的 TMDB 身份构造 key，避免先写 show: key 后又切换为 tmdb: key 导致关联悬空。
            if (dandanSeasons.size == 1 || hashAnchors.isNotEmpty()) {
                for ((seasonNumber, candidate) in seasonCandidates) {
                    val highConfidence = seasonNumber in hashAnchors || dandanSeasons.size == 1
                    if (!highConfidence) continue
                    writeBangumiLinkIfHighConfidence(
                        show = show, tmdbId = resolvedTmdbId, seasonNumber = seasonNumber,
                        subjectId = candidate.bgmSubjectId
                            ?: showData.bgmSubjectId.takeIf { localSeasons.size == 1 },
                        source = BangumiLinkSource.AUTO, evidence = "online-scrape:dandanplay",
                    )
                }
            }

            repo.reapplyOnlineMeta(library.id, showPath)
            if (hadIncompleteSeasonDetail || tmdbEnrichment.hadRetryableFailure) {
                shouldRecordAttempt = false
                repo.markAutoScrapeRetryable(library.id, showPath)
                return AutoScrapeOutcome.Partial(show.id, scrapedSeasons)
            }
            return AutoScrapeOutcome.Done(show.id, scrapedSeasons)
        } catch (error: Throwable) {
            shouldRecordAttempt = false
            withContext(NonCancellable) {
                runSuspendCatching { repo.markAutoScrapeRetryable(library.id, showPath) }
            }
            throw error
        } finally {
            try {
                if (shouldRecordAttempt) {
                    runSuspendCatching {
                        repo.recordAutoScrapeAttempt(library.id, showPath, platformTimeMillis())
                    }
                }
            } finally {
                endScrape(scrapeKey)
            }
        }
    }

    /** 手动匹配候选搜索(预填文件夹名/清洗后关键词)。 */
    suspend fun searchCandidates(keyword: String, source: ScrapeSource): List<ScrapeCandidate> =
        when (source) {
            ScrapeSource.DANDANPLAY -> dandanplay?.search(cleanKeyword(keyword)).orEmpty()
            ScrapeSource.BANGUMI -> bangumi.search(cleanKeyword(keyword))
            ScrapeSource.TMDB -> tmdb?.searchTv(cleanTmdbSearchKeyword(keyword)).orEmpty().map { candidate ->
                ScrapeCandidate(
                    source = ScrapeSource.TMDB,
                    identityId = candidate.tmdbId,
                    title = candidate.name,
                    originalTitle = candidate.originalName,
                    year = candidate.firstAirDate?.take(4)?.toIntOrNull(),
                    date = candidate.firstAirDate,
                    evidence = "TMDB",
                )
            }
            else -> emptyList()
        }

    /** 仅执行 TMDB 身份与图片补全，不重新请求 Bangumi/弹弹。 */
    suspend fun enrichTmdb(
        library: LibraryConfig,
        showPath: String,
        onProgress: suspend (String) -> Unit = {},
    ): AutoScrapeOutcome {
        if (tmdb == null) return AutoScrapeOutcome.NoMatch
        val scrapeKey = scrapeKey(library.id, showPath)
        if (!beginScrape(scrapeKey)) return AutoScrapeOutcome.Skipped
        var shouldRecordAttempt = false
        try {
            val show = repo.getShowByPath(library.id, showPath) ?: return AutoScrapeOutcome.NoMatch
            val localSeasons = loadLocalSeasons(show.id)
            if (localSeasons.isEmpty()) return AutoScrapeOutcome.NoMatch
            shouldRecordAttempt = true
            onProgress("正在匹配 TMDB 图片与身份...")
            val enrichment = enrichWithTmdb(
                library = library,
                showPath = showPath,
                show = show,
                localSeasons = localSeasons,
                titleHint = show.title.ifBlank { show.folder_name.ifBlank { pathLeaf(showPath) } },
                source = ScrapeSource.TMDB,
                onProgress = onProgress,
            )
            if (enrichment.hadRetryableFailure) {
                shouldRecordAttempt = false
                repo.markAutoScrapeRetryable(library.id, showPath)
                return if (enrichment.tmdbId != null) {
                    AutoScrapeOutcome.Partial(show.id, 0)
                } else {
                    AutoScrapeOutcome.RetryableFailure
                }
            }
            if (enrichment.tmdbId == null) return AutoScrapeOutcome.NoMatch
            repo.reapplyOnlineMeta(library.id, showPath)
            return AutoScrapeOutcome.Done(show.id, localSeasons.size)
        } catch (error: Throwable) {
            shouldRecordAttempt = false
            throw error
        } finally {
            try {
                if (shouldRecordAttempt) {
                    runSuspendCatching {
                        repo.recordAutoScrapeAttempt(library.id, showPath, platformTimeMillis())
                    }
                }
            } finally {
                endScrape(scrapeKey)
            }
        }
    }

    /** 懒触发节流：仅在仍有缺项或在线缓存失效时重试；完整番剧超过 24h 也不重复请求。 */
    suspend fun shouldAutoScrape(libraryId: Long, showPath: String): Boolean {
        val show = repo.getShowByPath(libraryId, showPath) ?: return false
        val localSeasons = loadLocalSeasons(show.id)
        if (localSeasons.isEmpty()) return false
        if (repo.hasAutoScrapeRetryMarker(libraryId, showPath)) return true
        val metas = repo.listOnlineMeta(libraryId, showPath)
        if (hasInvalidOnlineImageCache(metas)) return true
        if (!hasMissingScrapeData(show, localSeasons, metas)) return false
        val last = repo.lastOnlineScrapeAt(libraryId, showPath) ?: return true
        return platformTimeMillis() - last >= SCRAPE_RETRY_INTERVAL_MS
    }

    /**
     * 批量刮削(海报墙"批量补刮" / 扫描后自动补): 列出"缺元数据"番剧(无部级 meta),
     * 按全局并发(1~4)逐部 [scrapeAuto]。单部失败不阻断其余; 可取消。
     *
     * @return 成功刮削(部分命中)的部数; 未命中的部自动跳过(候选留待手动)。
     */
    suspend fun scrapePending(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> },
    ): Int = scrapePendingInternal(library, anchorOnly, concurrency, hashProvider, onProgress)

    override suspend fun scrapePendingInCoordinator(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit,
    ): Int = scrapePendingInternal(library, anchorOnly, concurrency, hashProvider, onProgress)

    private suspend fun scrapePendingInternal(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit,
    ): Int = coroutineScope {
        val pending = repo.listScrapePending(
            libraryId = library.id,
            anchorOnly = anchorOnly,
            requireTmdbIdentity = tmdb != null,
        )
        onProgress(0, pending.size, pending.firstOrNull()?.title.orEmpty())
        if (pending.isEmpty()) return@coroutineScope 0
        val semaphore = Semaphore(concurrency.coerceIn(1, 4))
        var completed = 0
        var successful = 0
        val mutex = kotlinx.coroutines.sync.Mutex()
        pending.forEach { item ->
            launch {
                semaphore.withPermit {
                    val outcome = runSuspendCatching { scrapeAuto(library, item.showPath, hashProvider) }.getOrNull()
                    val completedSnapshot = mutex.withLock {
                        completed++
                        if (outcome is AutoScrapeOutcome.Done || outcome is AutoScrapeOutcome.Partial) successful++
                        completed
                    }
                    onProgress(completedSnapshot, pending.size, item.title)
                }
            }
        }
        successful
    }

    /**
     * 应用候选(手动纠错/手动换源): 弹弹/Bangumi 写 MANUAL_* 文本覆盖；TMDB 只指定身份和图片。
     *
     * @param seasonNumber 非 null 仅该季; null 部级 + (单季番)同季级。
     * @param manual 手动(true=MANUAL_* 覆盖写) 或 auto(false=fill-if-null)
     * @param onTmdbIdentityApplied TMDB 身份已持久化、图片补全尚可继续时回调。
     */
    suspend fun applyCandidate(
        library: LibraryConfig,
        showPath: String,
        seasonNumber: Int?,
        candidate: ScrapeCandidate,
        manual: Boolean,
        onProgress: suspend (String) -> Unit = {},
        onTmdbIdentityApplied: suspend () -> Unit = {},
    ): Boolean {
        val scrapeKey = scrapeKey(library.id, showPath)
        if (!beginScrape(scrapeKey)) return false
        try {
            val show = repo.getShowByPath(library.id, showPath) ?: return false
            val localSeasons = loadLocalSeasons(show.id)
            if (localSeasons.isEmpty()) return false
            if (seasonNumber != null && localSeasons.none { it.seasonNumber == seasonNumber }) return false
            if (candidate.source == ScrapeSource.TMDB) {
                return applyTmdbCandidate(
                    library = library,
                    showPath = showPath,
                    show = show,
                    localSeasons = localSeasons,
                    seasonNumber = seasonNumber,
                    candidate = candidate,
                    onProgress = onProgress,
                    onIdentityApplied = onTmdbIdentityApplied,
                )
            }
            val provider: ScrapeProvider = when (candidate.source) {
                ScrapeSource.DANDANPLAY -> dandanplay ?: return false
                ScrapeSource.BANGUMI -> bangumi
                else -> return false
            }

            val seasonDetails = resolveManualSeasonDetails(
                provider = provider,
                candidate = candidate,
                localSeasons = localSeasons,
                seasonNumber = seasonNumber,
            ) ?: return false
            val primary = seasonDetails.minByOrNull { it.seasonNumber } ?: return false
            val effectiveSource = when {
                manual && candidate.source == ScrapeSource.DANDANPLAY -> ScrapeSource.MANUAL_DANDANPLAY
                manual && candidate.source == ScrapeSource.BANGUMI -> ScrapeSource.MANUAL_BANGUMI
                else -> candidate.source
            }
            val overwriteTitle = manual || library.scanMode == ScanMode.ANCHOR
            val now = platformTimeMillis()

            if (manual && seasonNumber == null) {
                repo.resetOnlineTmdbEnrichment(
                    libraryId = library.id,
                    showPath = showPath,
                    clearShowTmdbId = library.scanMode == ScanMode.ANCHOR,
                )
            }

            if (seasonNumber == null) {
                repo.upsertOnlineMeta(
                    libraryId = library.id, showPath = showPath, seasonNumber = 0,
                    source = effectiveSource, overwriteTitle = overwriteTitle,
                    dandanplayId = primary.candidate.identityId.takeIf { candidate.source == ScrapeSource.DANDANPLAY },
                    bangumiId = primary.candidate.bgmSubjectId
                        ?: primary.candidate.identityId.takeIf { candidate.source == ScrapeSource.BANGUMI },
                    remotePosterUrl = null, localPosterPath = null,
                    title = primary.detail.title, originalTitle = primary.detail.originalTitle,
                    year = primary.detail.year, plot = primary.detail.plot, rating = primary.detail.rating,
                    releaseDate = primary.detail.releaseDate, genres = primary.detail.genres,
                    studios = primary.detail.studios, episodes = emptyList(), scrapedAt = now,
                )
            }

            for (seasonDetail in seasonDetails) {
                val detail = seasonDetail.detail
                val localPoster = detail.remotePosterUrl?.let { url ->
                    runSuspendCatching {
                        downloader.downloadSeasonPoster(library.id, showPath, seasonDetail.seasonNumber, url)
                    }.getOrNull()
                }
                repo.upsertOnlineMeta(
                    libraryId = library.id, showPath = showPath, seasonNumber = seasonDetail.seasonNumber,
                    source = effectiveSource, overwriteTitle = overwriteTitle,
                    dandanplayId = seasonDetail.candidate.identityId.takeIf { candidate.source == ScrapeSource.DANDANPLAY },
                    bangumiId = seasonDetail.candidate.bgmSubjectId
                        ?: seasonDetail.candidate.identityId.takeIf { candidate.source == ScrapeSource.BANGUMI },
                    remotePosterUrl = detail.remotePosterUrl, localPosterPath = localPoster,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = detail.episodes, scrapedAt = now,
                )
            }

            repo.reapplyOnlineMeta(library.id, showPath)

            val refreshedShow = repo.getShowByPath(library.id, showPath) ?: show
            val tmdbEnrichment = enrichWithTmdb(
                library = library, showPath = showPath, show = refreshedShow,
                localSeasons = localSeasons,
                titleHint = primary.detail.title?.takeIf { it.isNotBlank() } ?: show.folder_name,
                source = effectiveSource,
                onProgress = onProgress,
                onIdentityApplied = onTmdbIdentityApplied,
            )
            if (tmdbEnrichment.hadRetryableFailure) {
                repo.markAutoScrapeRetryable(library.id, showPath)
            }
            val resolvedTmdbId = tmdbEnrichment.tmdbId

            for (seasonDetail in seasonDetails) {
                val manualSubjectId = if (candidate.source == ScrapeSource.BANGUMI) {
                    seasonDetail.candidate.identityId
                } else {
                    seasonDetail.candidate.bgmSubjectId
                }
                writeBangumiLinkIfHighConfidence(
                    show = show,
                    tmdbId = resolvedTmdbId,
                    seasonNumber = seasonDetail.seasonNumber,
                    subjectId = manualSubjectId,
                    source = BangumiLinkSource.MANUAL,
                    evidence = "online-scrape:manual",
                )
            }
            return true
        } finally {
            endScrape(scrapeKey)
        }
    }

    private suspend fun applyTmdbCandidate(
        library: LibraryConfig,
        showPath: String,
        show: ScrapedShow,
        localSeasons: List<LocalSeason>,
        seasonNumber: Int?,
        candidate: ScrapeCandidate,
        onProgress: suspend (String) -> Unit,
        onIdentityApplied: suspend () -> Unit,
    ): Boolean {
        if (tmdb == null || seasonNumber != null) return false
        val tmdbId = candidate.identityId?.takeIf { it > 0L } ?: return false
        if (show.tmdb_id != tmdbId) {
            repo.resetOnlineTmdbEnrichment(
                libraryId = library.id,
                showPath = showPath,
                clearShowTmdbId = show.tmdb_id != null,
            )
            repo.persistTmdbId(
                libraryId = library.id,
                showPath = showPath,
                tmdbId = tmdbId,
                source = ScrapeSource.MANUAL_TMDB,
                scrapedAt = platformTimeMillis(),
            )
        }
        val refreshedShow = repo.getShowByPath(library.id, showPath) ?: return false
        val enrichment = enrichWithTmdb(
            library = library,
            showPath = showPath,
            show = refreshedShow,
            localSeasons = localSeasons,
            titleHint = candidate.title,
            source = ScrapeSource.TMDB,
            onProgress = onProgress,
            onIdentityApplied = onIdentityApplied,
        )
        if (enrichment.hadRetryableFailure) {
            repo.markAutoScrapeRetryable(library.id, showPath)
        }
        repo.reapplyOnlineMeta(library.id, showPath)
        return enrichment.tmdbId == tmdbId
    }

    // === 内部 ===

    private suspend fun loadLocalSeasons(showId: Long): List<LocalSeason> =
        runSuspendCatching {
            repo.listSeasons(showId).map { season ->
                LocalSeason(
                    seasonNumber = season.season_number.toInt(),
                    posterPath = season.season_poster_path,
                    episodes = repo.listEpisodes(season.id).sortedBy { it.episode_number },
                )
            }.sortedBy { it.seasonNumber }
        }.getOrDefault(emptyList())

    private suspend fun hasMissingScrapeData(
        show: ScrapedShow,
        localSeasons: List<LocalSeason>,
        metas: List<ScrapedOnlineMeta>,
    ): Boolean {
        if (tmdb != null && show.tmdb_id == null) return true
        if (hasMissingCatalogData(show, localSeasons, metas)) return true
        return tmdb != null && hasMissingTmdbEpisodeImages(localSeasons, metas)
    }

    private fun hasMissingCatalogData(
        show: ScrapedShow,
        localSeasons: List<LocalSeason>,
        metas: List<ScrapedOnlineMeta>,
    ): Boolean {
        if (show.plot.isNullOrBlank()) return true
        val metaBySeason = metas.associateBy { it.season_number.toInt() }
        val hasPoster = !show.poster_path.isNullOrBlank() || localSeasons.any { season ->
            !season.posterPath.isNullOrBlank() || !metaBySeason[season.seasonNumber]?.local_poster_path.isNullOrBlank()
        }
        if (!hasPoster) return true
        return localSeasons.any { season ->
            val onlineEpisodes = metaBySeason[season.seasonNumber]
                ?.decodedEpisodes
                .orEmpty()
                .associateBy { it.episodeNumber.toLong() }
            season.episodes.any { episode ->
                val online = onlineEpisodes[episode.episode_number]
                (episode.title.isNullOrBlank() && online?.title.isNullOrBlank()) ||
                (episode.aired.isNullOrBlank() && online?.aired.isNullOrBlank())
            }
        }
    }

    private suspend fun hasMissingTmdbEpisodeImages(
        localSeasons: List<LocalSeason>,
        metas: List<ScrapedOnlineMeta>,
    ): Boolean {
        val metaBySeason = metas.associateBy { it.season_number.toInt() }
        for (season in localSeasons) {
            val onlineByEpisode = metaBySeason[season.seasonNumber]
                ?.decodedEpisodes
                .orEmpty()
                .associateBy { it.episodeNumber.toLong() }
            for (episode in season.episodes) {
                if (!episode.thumb_path.isNullOrBlank()) continue
                val onlinePath = onlineByEpisode[episode.episode_number]?.thumbPath
                if (onlinePath.isNullOrBlank() || isMissingLocalFilePath(onlinePath)) return true
            }
        }
        return false
    }

    private suspend fun ensureTmdbImageMetaRows(
        libraryId: Long,
        showPath: String,
        localSeasons: List<LocalSeason>,
    ) {
        if (repo.getOnlineMeta(libraryId, showPath, 0) == null) {
            repo.upsertOnlineMeta(
                libraryId = libraryId, showPath = showPath, seasonNumber = 0,
                source = ScrapeSource.NFO, overwriteTitle = false,
                dandanplayId = null, bangumiId = null,
                remotePosterUrl = null, localPosterPath = null,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = emptyList(), scrapedAt = platformTimeMillis(),
            )
        }
        for (season in localSeasons) {
            val existing = repo.getOnlineMeta(libraryId, showPath, season.seasonNumber)
            val mergedByNumber = existing?.decodedEpisodes.orEmpty()
                .associateByTo(linkedMapOf()) { it.episodeNumber }
            for (episode in season.episodes) {
                val episodeNumber = episode.episode_number.toInt()
                if (episodeNumber !in mergedByNumber) {
                    mergedByNumber[episodeNumber] = ScrapedOnlineEpisode(episodeNumber)
                }
            }
            val merged = mergedByNumber.values.sortedBy { it.episodeNumber }
            if (existing == null) {
                repo.upsertOnlineMeta(
                    libraryId = libraryId, showPath = showPath, seasonNumber = season.seasonNumber,
                    source = ScrapeSource.NFO, overwriteTitle = false,
                    dandanplayId = null, bangumiId = null,
                    remotePosterUrl = null, localPosterPath = null,
                    title = null, originalTitle = null, year = null, plot = null, rating = null,
                    releaseDate = null, genres = emptyList(), studios = emptyList(),
                    episodes = merged, scrapedAt = platformTimeMillis(),
                )
            } else if (merged != existing.decodedEpisodes) {
                repo.updateOnlineMetaEpisodes(libraryId, showPath, season.seasonNumber, merged)
            }
        }
    }

    private suspend fun resolveManualSeasonDetails(
        provider: ScrapeProvider,
        candidate: ScrapeCandidate,
        localSeasons: List<LocalSeason>,
        seasonNumber: Int?,
    ): List<ManualSeasonDetail>? {
        if (seasonNumber != null) {
            val detail = provider.fetchDetail(candidate).takeIf(::hasUsableDetail) ?: return null
            return listOf(ManualSeasonDetail(seasonNumber, candidate, detail))
        }
        if (localSeasons.size == 1) {
            val detail = provider.fetchDetail(candidate).takeIf(::hasUsableDetail) ?: return null
            return listOf(ManualSeasonDetail(localSeasons.single().seasonNumber, candidate, detail))
        }
        if (candidate.source != ScrapeSource.DANDANPLAY || !hasContinuousSeasonNumbers(localSeasons)) return null
        val expanded = orderedDandanSeries(expandDandanList(candidate)) ?: return null
        if (expanded.size != localSeasons.size) return null
            val mappedCandidates = localSeasons.mapIndexed { index, localSeason ->
                localSeason.seasonNumber to (expanded.getOrNull(index) ?: return null)
            }
            val details = mapConcurrently(mappedCandidates) { (seasonNumber, seasonCandidate) ->
                ManualSeasonDetail(
                    seasonNumber = seasonNumber,
                    candidate = seasonCandidate,
                    detail = provider.fetchDetail(seasonCandidate),
                )
            }
            return details.takeIf { it.all { detail -> hasUsableDetail(detail.detail) } }
    }

    /** 每季 1 个代表文件: 最小正集号主集(排 SP/OAD), 提取不到取文件名单词序第一个。 */
    private fun pickHashFile(season: LocalSeason): ScrapedEpisode =
        season.episodes.filter { it.episode_number > 0L }.minByOrNull { it.episode_number }
            ?: season.episodes.minByOrNull { it.video_name.lowercase() }
            ?: season.episodes.first()

    /**
     * 季映射(弹弹): hash 锚点优先; 无锚点时仅当候选季数可信才顺序映射。
     * 候选展开: 主候选 + 其 relateds(同作品其他季), 按首播日期排序。
     * 数量不一致(本地 > 候选)或候选模糊(多作品)返回空 → 返回 Bangumi 候选或进入手动。
     */
    private suspend fun mapDandanSeasons(
        localSeasons: List<LocalSeason>,
        dandanSeasons: List<ScrapeCandidate>,
        hashAnchors: Map<Int, ScrapeCandidate>,
    ): Map<Int, ScrapeCandidate> {
        if (hashAnchors.isNotEmpty()) {
            // hash 锚点优先: 只有取得可信远程序列且所有锚点相对偏移一致时，才补齐未命中季度。
            val result = hashAnchors.toMutableMap()
            if (localSeasons.all { it.seasonNumber in result }) return result
            if (!hasContinuousSeasonNumbers(localSeasons)) return emptyMap()
            val primary = result.values.firstOrNull() ?: return emptyMap()
            val expanded = orderedDandanSeries(expandDandanList(primary)) ?: return emptyMap()
            val remoteIndexById = expanded.mapIndexedNotNull { index, candidate ->
                candidate.identityId?.let { it to index }
            }.toMap()
            val offsetList = hashAnchors.map { (seasonNumber, candidate) ->
                val localIndex = localSeasons.indexOfFirst { it.seasonNumber == seasonNumber }.takeIf { it >= 0 }
                    ?: return emptyMap()
                val remoteIndex = candidate.identityId?.let(remoteIndexById::get) ?: return emptyMap()
                remoteIndex - localIndex
            }
            val offsets = offsetList.toSet()
            if (offsets.size != 1) return emptyMap()
            val offset = offsets.single()
            localSeasons.forEachIndexed { localIndex, season ->
                val target = expanded.getOrNull(localIndex + offset) ?: return emptyMap()
                val anchored = hashAnchors[season.seasonNumber]
                if (anchored != null && anchored.identityId != target.identityId) return emptyMap()
                result[season.seasonNumber] = target
            }
            return result
        }
        if (dandanSeasons.size != 1) return emptyMap()  // 多候选/空 -> 模糊, 走 hash 或降级/手动
        if (!hasContinuousSeasonNumbers(localSeasons)) return emptyMap()
        val expanded = orderedDandanSeries(expandDandanList(dandanSeasons.single())) ?: return emptyMap()
        if (expanded.size != localSeasons.size) return emptyMap()  // 无锚点只接受一一对应, 防前传/特别篇错位
        val result = linkedMapOf<Int, ScrapeCandidate>()
        localSeasons.forEachIndexed { index, season ->
            val target = expanded.getOrNull(index) ?: return@forEachIndexed
            result[season.seasonNumber] = target
        }
        return result
    }

    /** 主候选 + relateds(同作品其他季) 展开；排序由 [orderedDandanSeries] 在证据充分时完成。 */
    private suspend fun expandDandanList(primary: ScrapeCandidate): List<ScrapeCandidate> {
        val provider = dandanplay ?: return listOf(primary)
        val related = provider.fetchRelated(primary.identityId ?: return listOf(primary))
        return (listOf(primary) + related)
            .filter { it.identityId != null }
            .distinctBy { it.identityId }
    }

    private fun orderedDandanSeries(candidates: List<ScrapeCandidate>): List<ScrapeCandidate>? {
        if (candidates.isEmpty()) return null
        val explicitSeasonNumbers = candidates.map { extractSeasonNumberFromTitle(it.title) }
        if (explicitSeasonNumbers.all { it != null } && explicitSeasonNumbers.filterNotNull().distinct().size == candidates.size) {
            return candidates.zip(explicitSeasonNumbers.filterNotNull()).sortedBy { it.second }.map { it.first }
        }
        val dates = candidates.map { it.date?.takeIf(String::isNotBlank) }
        if (dates.all { it != null } && dates.filterNotNull().distinct().size == candidates.size) {
            return candidates.sortedBy { it.date }
        }
        return null
    }

    private fun extractSeasonNumberFromTitle(title: String): Int? {
        val patterns = listOf(
            Regex("(?i)\\bseason\\s*(\\d+)\\b"),
            Regex("(?i)\\bs\\s*(\\d+)\\b"),
            Regex("第\\s*(\\d+)\\s*季"),
        )
        return patterns.firstNotNullOfOrNull { regex -> regex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    /** Bangumi 应用路径：仅单季唯一候选自动落库；多季或多候选返回人工确认。 */
    private suspend fun scrapeByBangumi(
        library: LibraryConfig,
        showPath: String,
        show: ScrapedShow,
        localSeasons: List<LocalSeason>,
        titleHint: String,
        subjects: List<ScrapeCandidate>? = null,
        tmdbCandidates: List<TmdbTvCandidate>? = null,
    ): AutoScrapeOutcome {
        val searchedCandidates = subjects ?: bangumi.search(cleanKeyword(titleHint))
        if (searchedCandidates.isEmpty()) return AutoScrapeOutcome.NoMatch
        val yearHint = show.year?.toInt()
            ?: Regex("(19|20)\\d{2}").find(titleHint)?.value?.toIntOrNull()
        val candidates = filterBangumiCandidates(searchedCandidates, titleHint, yearHint)
        if (localSeasons.size != 1 || candidates.size != 1) {
            return AutoScrapeOutcome.NeedsConfirmation(candidates.ifEmpty { searchedCandidates })
        }
        val mapped = candidates

        var scrapedSeasons = 0
        var hadIncompleteDetail = false
        val now = platformTimeMillis()
        val overwriteTitle = show.tmdb_id == null
        val seasonDetails = mapConcurrently(mapped.mapIndexed { index, subject ->
            val season = localSeasons.getOrNull(index) ?: return@mapIndexed null
            season to subject
        }.filterNotNull()) { (season, subject) ->
            val detail = bangumi.fetchDetail(subject)
            val localPoster = detail.remotePosterUrl?.let { url ->
                runSuspendCatching {
                    downloader.downloadSeasonPoster(library.id, showPath, season.seasonNumber, url)
                }.getOrNull()
            }
            Triple(season, subject, detail to localPoster)
        }
        for ((season, subject, detailAndPoster) in seasonDetails) {
            val (detail, localPoster) = detailAndPoster
            if (!detail.complete) hadIncompleteDetail = true
            if (!hasUsableDetail(detail)) continue
            repo.upsertOnlineMeta(
                libraryId = library.id, showPath = showPath, seasonNumber = season.seasonNumber,
                source = ScrapeSource.BANGUMI, overwriteTitle = overwriteTitle,
                dandanplayId = null, bangumiId = subject.identityId,
                remotePosterUrl = detail.remotePosterUrl, localPosterPath = localPoster,
                title = null, originalTitle = null, year = null, plot = null, rating = null,
                releaseDate = null, genres = emptyList(), studios = emptyList(),
                episodes = detail.episodes, scrapedAt = now,
            )
            scrapedSeasons++
        }
        if (scrapedSeasons == 0) {
            return if (hadIncompleteDetail) AutoScrapeOutcome.RetryableFailure else AutoScrapeOutcome.NoMatch
        }
        if (scrapedSeasons < mapped.size) {
            repo.reapplyOnlineMeta(library.id, showPath)
            return AutoScrapeOutcome.Partial(show.id, scrapedSeasons)
        }
        // 部级: 主 subject(最早年份)的元数据
        val primary = mapped.first()
        val primaryDetail = seasonDetails.firstOrNull { it.second == primary }?.third?.first
            ?: return AutoScrapeOutcome.NoMatch
        repo.upsertOnlineMeta(
            libraryId = library.id, showPath = showPath, seasonNumber = 0,
            source = ScrapeSource.BANGUMI, overwriteTitle = overwriteTitle,
            dandanplayId = null, bangumiId = primary.identityId,
            remotePosterUrl = null, localPosterPath = null,
            title = primaryDetail.title, originalTitle = primaryDetail.originalTitle,
            year = primaryDetail.year, plot = primaryDetail.plot, rating = primaryDetail.rating,
            releaseDate = primaryDetail.releaseDate, genres = primaryDetail.genres, studios = primaryDetail.studios,
            episodes = emptyList(), scrapedAt = now,
        )

        val tmdbEnrichment = enrichWithTmdb(
            library = library,
            showPath = showPath,
            show = show,
            localSeasons = localSeasons,
            titleHint = titleHint,
            source = ScrapeSource.BANGUMI,
            preloadedCandidates = tmdbCandidates,
        )
        val resolvedTmdbId = tmdbEnrichment.tmdbId

        // Bangumi 命中即高置信(唯一候选): 写季度关联(评论区立即可用)
        if (candidates.size == localSeasons.size) {
            localSeasons.forEachIndexed { index, season ->
                val subject = mapped.getOrNull(index) ?: return@forEachIndexed
                writeBangumiLinkIfHighConfidence(
                    show = show, tmdbId = resolvedTmdbId, seasonNumber = season.seasonNumber,
                    subjectId = subject.identityId,
                    source = BangumiLinkSource.AUTO, evidence = "online-scrape:bangumi",
                )
            }
        }

        repo.reapplyOnlineMeta(library.id, showPath)
        return if (hadIncompleteDetail || tmdbEnrichment.hadRetryableFailure) {
            AutoScrapeOutcome.Partial(show.id, scrapedSeasons)
        } else {
            AutoScrapeOutcome.Done(show.id, scrapedSeasons)
        }
    }

    /** 部级元数据(合并): bgm subject(经弹弹 bangumiId 桥 或 标题搜索)优先, 弹弹候选兜底 title/year。 */
    private suspend fun buildShowData(
        dandanSeasons: List<ScrapeCandidate>,
        seasonCandidates: Map<Int, ScrapeCandidate>,
        titleHint: String,
        yearHint: Int?,
        bangumiCandidates: List<ScrapeCandidate>,
    ): ScrapedScrapeData {
        val bridgeBgmId = seasonCandidates.values.firstNotNullOfOrNull { it.bgmSubjectId }
            ?: dandanSeasons.firstNotNullOfOrNull { it.bgmSubjectId }
        var resolvedBgmId = bridgeBgmId
        val bgmDetail: ScrapedScrapeData? = if (bridgeBgmId != null) {
            bangumi.fetchDetail(ScrapeCandidate(ScrapeSource.BANGUMI, bridgeBgmId, ""))
        } else {
            val searched = bangumiCandidates
            val candidates = filterBangumiCandidates(searched, titleHint, yearHint)
            candidates.singleOrNull()?.let { candidate ->
                resolvedBgmId = candidate.identityId
                bangumi.fetchDetail(candidate)
            }
        }
        val dandanPrimary = dandanSeasons.firstOrNull()
        return ScrapedScrapeData(
            title = bgmDetail?.title ?: dandanPrimary?.title,
            originalTitle = bgmDetail?.originalTitle,
            year = bgmDetail?.year ?: dandanPrimary?.year,
            plot = bgmDetail?.plot,
            rating = bgmDetail?.rating,
            releaseDate = bgmDetail?.releaseDate,
            genres = bgmDetail?.genres ?: emptyList(),
            studios = bgmDetail?.studios ?: emptyList(),
            bgmSubjectId = resolvedBgmId,
        )
    }

    private fun pathLeaf(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    private suspend fun <T, R> mapConcurrently(
        items: List<T>,
        maxConcurrency: Int = 3,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceIn(1, 4))
        items.map { item ->
            async { semaphore.withPermit { block(item) } }
        }.awaitAll()
    }

    private fun cleanKeyword(raw: String): String = DanmakuMatcher.cleanSearchKeyword(raw)

    private fun filterDandanCandidates(
        candidates: List<ScrapeCandidate>,
        titleHint: String,
        year: Int?,
    ): List<ScrapeCandidate> {
        val queryTitle = comparableTitle(titleHint)
        if (queryTitle.isBlank()) return emptyList()
        return candidates
            .filter { candidate ->
                val candidateTitle = comparableTitle(candidate.title)
                val yearMatches = year == null || candidate.year == null || candidate.year == year
                candidateTitle.isNotBlank() && yearMatches && candidateTitle == queryTitle
            }
            .distinctBy { it.identityId }
    }

    private fun filterDandanConfirmationCandidates(
        candidates: List<ScrapeCandidate>,
        titleHint: String,
        year: Int?,
    ): List<ScrapeCandidate> {
        val queryTitle = comparableTitle(titleHint)
        if (queryTitle.isBlank()) return emptyList()
        return candidates
            .filter { candidate ->
                val candidateTitle = comparableTitle(candidate.title)
                val yearMatches = year == null || candidate.year == null || candidate.year == year
                candidateTitle.isNotBlank() && yearMatches &&
                    (candidateTitle.contains(queryTitle) || queryTitle.contains(candidateTitle))
            }
            .distinctBy { it.identityId }
    }

    private fun filterBangumiCandidates(
        candidates: List<ScrapeCandidate>,
        titleHint: String,
        year: Int?,
    ): List<ScrapeCandidate> {
        val queryTitle = comparableTitle(titleHint)
        if (queryTitle.isBlank()) return emptyList()
        return candidates
            .filter { candidate ->
                val candidateTitles = listOf(candidate.title, candidate.originalTitle.orEmpty())
                    .map(::comparableTitle)
                    .filter { it.isNotBlank() }
                val yearMatches = year == null || candidate.year == null || candidate.year == year
                yearMatches && candidateTitles.any { title -> title == queryTitle }
            }
            .distinctBy { it.identityId }
    }

    private fun comparableTitle(raw: String): String = cleanKeyword(raw)
        .replace(Regex("(?i)\\bseason\\s*\\d+\\b"), " ")
        .replace(Regex("(?i)\\bs\\s*\\d+\\b"), " ")
        .replace(Regex("第\\s*\\d+\\s*季"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
        .lowercase()

    private fun hasContinuousSeasonNumbers(localSeasons: List<LocalSeason>): Boolean =
        localSeasons.map { it.seasonNumber } == (1..localSeasons.size).toList()

    private fun hasUsableDetail(detail: ScrapedScrapeData): Boolean =
        detail.title?.isNotBlank() == true ||
            detail.originalTitle?.isNotBlank() == true ||
            detail.episodes.isNotEmpty() ||
            detail.remotePosterUrl?.isNotBlank() == true ||
            detail.plot?.isNotBlank() == true

    private suspend fun beginScrape(key: String): Boolean = activeScrapesMutex.withLock {
        activeScrapes.add(key)
    }

    private suspend fun endScrape(key: String) {
        withContext(NonCancellable) {
            activeScrapesMutex.withLock { activeScrapes.remove(key) }
        }
    }

    private fun scrapeKey(libraryId: Long, showPath: String): String = "${libraryId}\u0000$showPath"

    /**
     * TMDB 增强（通过内置 Gateway，见设计 §12.6）：
     * ①宽幅头图 backdrop → 部级 meta fanart，详情页在 NFO fanart 后回退；
     * ②每季逐集剧照 still → 季级 meta episode_json[].thumbPath，在 NFO 集照后回退。
     *
     * 定位: NFO 已有 tmdb_id 直接用; ANCHOR 按标题+年份搜 TMDB, **高置信(年份+名称双向命中)才用**,
     * 否则整段跳过(不错配头图/剧照)。只填空不覆盖(fill-if-null)；请求失败保留已写数据并返回可重试状态。
     * 独立写入(updateOnlineMetaFanart/updateOnlineMetaEpisodes), 不参与主 upsert 的冲突覆盖。
     */
    private suspend fun enrichWithTmdb(
        library: LibraryConfig,
        showPath: String,
        show: ScrapedShow,
        localSeasons: List<LocalSeason>,
        titleHint: String,
        source: ScrapeSource,
        preloadedCandidates: List<TmdbTvCandidate>? = null,
        onProgress: suspend (String) -> Unit = {},
        onIdentityApplied: suspend () -> Unit = {},
    ): TmdbEnrichmentResult {
        var tmdbId = show.tmdb_id
        if (tmdbId != null) {
            repo.migrateBangumiSeasonLinksToTmdb(library.id, showPath, tmdbId)
        }
        val tmdbApi = tmdb ?: return TmdbEnrichmentResult(tmdbId)
        if (tmdbId != null) {
            onIdentityApplied()
        }
        val cleanTitle = cleanTmdbSearchKeyword(titleHint)
        if (tmdbId == null && cleanTitle.isBlank()) return TmdbEnrichmentResult(null)

        // ① 定位 tmdb_id(NFO 直接可用; ANCHOR 高置信搜索)
        if (tmdbId == null) {
            val year = show.year?.toInt()
                ?: runSuspendCatching { repo.getOnlineMeta(library.id, showPath, 0)?.year?.toInt() }.getOrNull()
                ?: Regex("(19|20)\\d{2}").find(titleHint)?.value?.toIntOrNull()
            onProgress("正在搜索 TMDB 番剧...")
            val candidatesResult = if (preloadedCandidates != null) {
                Result.success(preloadedCandidates)
            } else {
                runSuspendCatching { tmdbApi.searchTv(cleanTitle, year) }
            }
            if (candidatesResult.isFailure) return TmdbEnrichmentResult(null, hadRetryableFailure = true)
            tmdbId = pickTmdbCandidate(candidatesResult.getOrThrow(), cleanTitle, year)?.tmdbId
                ?: return TmdbEnrichmentResult(null)
            repo.persistTmdbId(library.id, showPath, tmdbId, source, platformTimeMillis())
            onIdentityApplied()
        }
        val resolvedTmdbId = tmdbId
        ensureTmdbImageMetaRows(library.id, showPath, localSeasons)
        var hadRetryableFailure = false

        // ② 宽幅头图 backdrop → 部级 meta(remote URL + 本地绝对路径)
        onProgress("正在获取 TMDB 头图...")
        val backdropResult = runSuspendCatching { tmdbApi.fetchBackdropPath(resolvedTmdbId) }
        if (backdropResult.isFailure) hadRetryableFailure = true
        val backdropPath = backdropResult.getOrNull()
        if (backdropPath != null) {
            val existingFanartPath = runSuspendCatching {
                repo.getOnlineMeta(library.id, showPath, 0)?.local_fanart_path
            }.getOrNull()
            val fanartDownload = runSuspendCatching {
                downloader.downloadImage(library.id, showPath, "backdrop.jpg", tmdbApi.imageUrl(backdropPath, "w1280"))
            }
            val localFanart = fanartDownload.getOrNull()
            val usableFanartPath = localFanart ?: existingFanartPath?.takeUnless { isMissingLocalFilePath(it) }
            if (fanartDownload.isFailure || usableFanartPath == null) hadRetryableFailure = true
            runSuspendCatching {
                repo.updateOnlineMetaFanart(
                    library.id,
                    showPath,
                    remoteFanartUrl = tmdbApi.imageUrl(backdropPath, "w1280"),
                    localFanartPath = usableFanartPath,
                )
            }
        }

        // ③ 每季逐集剧照 → 季级 meta episode_json[].thumbPath(整季一次拉取仍图, 跳过已有剧照的集)
        onProgress("正在获取 TMDB 集照...")
        val stillsBySeason = mapConcurrently(localSeasons) { season ->
            season to runSuspendCatching {
                tmdbApi.fetchSeasonStillPaths(resolvedTmdbId, season.seasonNumber)
            }
        }
        val imageSemaphore = Semaphore(3)
        for ((season, stillsResult) in stillsBySeason) {
            if (stillsResult.isFailure) {
                hadRetryableFailure = true
                continue
            }
            val stills = stillsResult.getOrThrow()
            val seasonMeta = runSuspendCatching {
                repo.getOnlineMeta(library.id, showPath, season.seasonNumber)
            }.getOrNull() ?: continue
            val episodes = seasonMeta.decodedEpisodes
            if (episodes.isEmpty()) continue
            val nfoThumbEpisodeNumbers = season.episodes
                .filter { !it.thumb_path.isNullOrBlank() }
                .mapTo(hashSetOf()) { it.episode_number.toInt() }
            if (stills.isEmpty()) continue
            var changed = false
            val updated = coroutineScope {
                episodes.map { ep ->
                    async {
                        when {
                            ep.episodeNumber in nfoThumbEpisodeNumbers -> EpisodeImageUpdate(ep)
                            ep.thumbPath != null && !isMissingLocalFilePath(ep.thumbPath) -> EpisodeImageUpdate(ep)
                            stills[ep.episodeNumber] == null -> EpisodeImageUpdate(ep.copy(thumbPath = null))
                            else -> {
                                val still = stills.getValue(ep.episodeNumber)
                                val download = imageSemaphore.withPermit {
                                    runSuspendCatching {
                                        downloader.downloadImage(
                                            library.id,
                                            showPath,
                                            "s${season.seasonNumber}e${ep.episodeNumber}.jpg",
                                            tmdbApi.imageUrl(still, "w500"),
                                        )
                                    }
                                }
                                val local = download.getOrNull()
                                EpisodeImageUpdate(
                                    episode = ep.copy(thumbPath = local),
                                    hadRetryableFailure = download.isFailure || local == null,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            if (updated.any { it.hadRetryableFailure }) hadRetryableFailure = true
            val updatedEpisodes = updated.map { it.episode }
            changed = updatedEpisodes != episodes
            if (changed) {
                runSuspendCatching {
                    repo.updateOnlineMetaEpisodes(library.id, showPath, season.seasonNumber, updatedEpisodes)
                }
            }
        }

        runSuspendCatching { repo.reapplyOnlineMeta(library.id, showPath) }
        return TmdbEnrichmentResult(resolvedTmdbId, hadRetryableFailure)
    }

    /**
      * TMDB 候选评分: 标题/原名精确命中优先，年份命中加权，TMDB 返回顺序作为稳定兜底。
      * 多个结果不再直接失败；年份明确但全部不符时拒绝，否则始终选择最高分候选，方便用户后续手动纠正。
     */
    private fun pickTmdbCandidate(
        candidates: List<TmdbTvCandidate>,
        query: String,
        year: Int?,
    ): TmdbTvCandidate? {
        if (candidates.isEmpty()) return null
        val q = comparableTitle(query)
        if (q.isEmpty()) return null
        val scored = candidates.mapIndexedNotNull { index, candidate ->
            val title = comparableTitle(candidate.name)
            val original = comparableTitle(candidate.originalName.orEmpty())
            val titleScore = when {
                title == q -> 100
                original == q -> 95
                title.contains(q) || q.contains(title) -> 55
                original.isNotEmpty() && (original.contains(q) || q.contains(original)) -> 50
                else -> 0
            }
            if (titleScore == 0) return@mapIndexedNotNull null
            val candidateYear = candidate.firstAirDate?.take(4)?.toIntOrNull()
            val yearScore = when {
                year == null || candidateYear == null -> 0
                candidateYear == year -> 30
                else -> -80
            }
            Triple(candidate, titleScore + yearScore, index)
        }
        val compatible = scored.filter { triple ->
            val candidateYear = triple.first.firstAirDate?.take(4)?.toIntOrNull()
            year == null || candidateYear == null || candidateYear == year
        }
        val best = compatible
            .sortedWith(
                compareByDescending<Triple<TmdbTvCandidate, Int, Int>> { it.second }
                    .thenBy { it.third },
            )
            .firstOrNull() ?: return null
        return best.first
    }

    /**
     * 高置信命中后自动写 Bangumi 季度关联(评论区立即可用), 见设计 §5.2.1。
     *
     * 纪律(照用户决策放宽 P1-5): 自动命中(每季 hash 锚点 / 唯一候选 / Bangumi 唯一源)即写
     * source=AUTO 的 BangumiSeasonLinkEntity(CONFIRMED); 手动纠正写 MANUAL。
     * 不覆盖既有手动/禁用/已确认的选择: resolveEffectiveBangumiLink 的手动优先语义不受影响,
     * 只在无关联时才落地(评论区立即亮, 用户仍可经 BangumiLinkDialog 改/禁用)。
     */
    private suspend fun writeBangumiLinkIfHighConfidence(
        show: ScrapedShow,
        tmdbId: Long?,
        seasonNumber: Int,
        subjectId: Long?,
        source: BangumiLinkSource,
        evidence: String,
    ) {
        if (subjectId == null || subjectId <= 0) return
        val identityKey = BangumiSeasonIdentity.keyFor(
            tmdbId = tmdbId,
            libraryId = show.library_id,
            showPath = show.show_path,
            seasonNumber = seasonNumber.toLong(),
        )
        val existing = runSuspendCatching { repo.getBangumiSeasonLink(identityKey) }.getOrNull()
        // AUTO 不覆盖用户禁用或非 AUTO 关联；MANUAL 是用户显式纠正，可重新确认被禁用的关联。
        val canWrite = when {
            source == BangumiLinkSource.MANUAL -> true
            existing == null -> true
            existing.state == BangumiLinkState.DISABLED -> false
            else -> existing.source == BangumiLinkSource.AUTO
        }
        if (!canWrite) return
        val now = platformTimeMillis()
        repo.upsertBangumiSeasonLink(
            BangumiSeasonLink(
                identityKey = identityKey,
                subjectId = subjectId,
                state = BangumiLinkState.CONFIRMED,
                source = source,
                evidence = evidence,
                updatedAt = now,
                verifiedAt = now,
            ),
        )
    }

    private companion object {
        val activeScrapes = mutableSetOf<String>()
        val activeScrapesMutex = kotlinx.coroutines.sync.Mutex()
        /** 懒触发重刮间隔: 24h(部级 meta 存在且在此间隔内不重复刮)。 */
        const val SCRAPE_RETRY_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
