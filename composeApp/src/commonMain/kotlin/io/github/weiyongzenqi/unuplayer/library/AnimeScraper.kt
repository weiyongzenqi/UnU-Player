package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkSource
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiLinkState
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbApiException
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbSeasonEpisode
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbSeasonImages
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbTvCandidate
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.danmaku.source.DanmakuMatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val tmdbSeasonMarkerPatterns = listOf(
    Regex("第\\s*[零〇一二三四五六七八九十百两\\d]+\\s*(?:季|期|部|篇|クール)"),
    Regex("(?i)\\bseason\\s*(?:\\d+|[ivxlcdm]+)\\b"),
    Regex("(?i)\\b\\d+(?:st|nd|rd|th)\\s+season\\b"),
    Regex("(?i)(?<![A-Za-z0-9])s\\s*0*\\d+(?![\\p{L}\\p{N}])"),
)
private val emptyTmdbSeasonWrapperPattern = Regex("\\(\\s*\\)|（\\s*）|【\\s*】|\\[\\s*]")
private val trailingTmdbSeasonSeparatorPattern = Regex("[\\s\\-_:：·/\\\\]+$")
private val standaloneTmdbYearPattern = Regex("(?<![\\p{L}\\p{N}])(19|20)\\d{2}(?![\\p{L}\\p{N}])")
/** 用户文件夹命名标记如 {tmdb=-} / {tmdb=123456}(DanmakuMatcher 只剥 []【】不剥花括号)。 */
private val bracedFolderMarkerPattern = Regex("\\{[^}]*\\}")
/**
 * 纯 ASCII 圆括号组(质量/来源标签如 (BD 1080P)); 含 CJK 文字的括号内容视为标题成分保留。
 * 排除区间覆盖汉字(含扩展A/兼容表意)/假名(含半角片假名)/谚文, 防止 (サイドストーリー) 这类
 * 副标题被当质量标签剥掉。
 */
private val asciiParenthesizedGroupPattern =
    Regex("[\\(（][^()（）\\u3040-\\u31ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uff61-\\uff9f\\uac00-\\ud7af]*[\\)）]")
private val wholeDecorativeTitlePatterns = listOf(
    Regex("^\\s*\\[([^\\[\\]]+)\\]\\s*$"),
    Regex("^\\s*【([^【】]+)】\\s*$"),
    Regex("^\\s*［([^［］]+)］\\s*$"),
)
private val leadingDecorativeTitlePatterns = listOf(
    Regex("^\\s*\\[([^\\[\\]]+)\\](.*)$"),
    Regex("^\\s*【([^【】]+)】(.*)$"),
    Regex("^\\s*［([^［］]+)］(.*)$"),
)

private fun unwrapWholeDecorativeTitle(raw: String): String =
    wholeDecorativeTitlePatterns.firstNotNullOfOrNull { pattern ->
        pattern.matchEntire(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty)
    } ?: raw

/** 只在括号后仅剩季度/文件夹标记时保留括号内标题；避免把普通字幕组标签误当作品名。 */
private fun unwrapDecorativeTitleWithMetadataSuffix(raw: String): String =
    leadingDecorativeTitlePatterns.firstNotNullOfOrNull { pattern ->
        val match = pattern.matchEntire(raw) ?: return@firstNotNullOfOrNull null
        val suffix = tmdbSeasonMarkerPatterns.fold(match.groupValues[2]) { value, seasonPattern ->
            seasonPattern.replace(value, " ")
        }.replace(emptyTmdbSeasonWrapperPattern, " ")
            .replace(standaloneTmdbYearPattern, " ")
            .replace(trailingTmdbSeasonSeparatorPattern, " ")
            .replace(bracedFolderMarkerPattern, " ")
            .trim()
        match.groupValues[1].trim().takeIf { suffix.isEmpty() && it.isNotEmpty() }
    } ?: raw

internal fun cleanTmdbSearchKeyword(raw: String): String {
    val withoutSeason = tmdbSeasonMarkerPatterns.fold(raw) { value, pattern ->
        pattern.replace(value, " ")
    }.replace(emptyTmdbSeasonWrapperPattern, " ")
        .replace(standaloneTmdbYearPattern, " ")
        .replace(trailingTmdbSeasonSeparatorPattern, " ")
        .replace(bracedFolderMarkerPattern, " ")
        .replace(asciiParenthesizedGroupPattern, " ")
    val titleSafe = unwrapWholeDecorativeTitle(withoutSeason)
    return DanmakuMatcher.cleanSearchKeyword(titleSafe)
        .ifBlank { DanmakuMatcher.cleanSearchKeyword(raw) }
}

internal fun cleanAnimeSearchKeyword(raw: String): String {
    val stripped = raw.replace(bracedFolderMarkerPattern, " ")
        .replace(asciiParenthesizedGroupPattern, " ")
    val titleSafe = unwrapDecorativeTitleWithMetadataSuffix(stripped)
    return DanmakuMatcher.cleanSearchKeyword(titleSafe)
        .ifBlank { DanmakuMatcher.cleanSearchKeyword(raw) }
}

/** 播放器手动搜弹幕优先使用结构化番剧名；播放标题只作为没有番剧上下文时的回退。 */
internal fun resolveManualDanmakuSearchKeyword(seriesTitle: String?, fallbackRaw: String): String {
    val structured = seriesTitle
        ?.takeIf(String::isNotBlank)
        ?.let(::cleanTmdbSearchKeyword)
        .orEmpty()
    return structured.ifBlank { DanmakuMatcher.cleanSearchKeyword(fallbackRaw) }
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
    /** 详情页初始并行搜索的单来源预算；后台批量不使用此预算。 */
    private val interactiveSearchTimeoutMs: Long = DEFAULT_INTERACTIVE_SEARCH_TIMEOUT_MS,
    /**
     * 唯一结果放宽(海报墙设置「唯一结果自动应用」, 默认开): 搜索结果唯一且年份不冲突时直接应用,
     * 即使标题与文件夹名不完全相等(如 {tmdb=} 残余、罗马音 vs 日文); 关闭则仅精确/包含匹配自动应用。
     */
    private val uniqueCandidateAutoApply: Boolean = true,
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

    /** 详情页自动任务的最小执行范围，避免图片回补误触发完整多源刮削。 */
    enum class AutoScrapeMode {
        NONE,
        IMAGES_ONLY,
        FULL,
    }

    private data class LocalSeason(
        val seasonNumber: Int,
        val posterPath: String?,
        val episodes: List<ScrapedEpisode>,
        val bangumiId: Long?,
        val bangumiOffset: Int,
    )

    private data class ResolvedTmdbSeason(
        val mapping: TmdbEpisodeMapping,
        val images: TmdbSeasonImages,
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
    ): AutoScrapeOutcome = scrapeAutoWithPriority(
        library = library,
        showPath = showPath,
        hashProvider = hashProvider,
        priority = ScrapeTaskPriority.INTERACTIVE,
        onProgress = onProgress,
    )

    private suspend fun scrapeAutoWithPriority(
        library: LibraryConfig,
        showPath: String,
        hashProvider: (suspend (videoPath: String) -> Pair<Long, String>?)?,
        priority: ScrapeTaskPriority,
        onProgress: suspend (String) -> Unit = {},
    ): AutoScrapeOutcome {
        val scrapeKey = scrapeKey(library.id, showPath)
        val lease = beginScrape(scrapeKey, priority) ?: return AutoScrapeOutcome.Skipped
        return try {
            lease.runPreemptible {
                scrapeAutoOwned(library, showPath, hashProvider, priority, onProgress)
            }
        } finally {
            lease.release()
        }
    }

    private suspend fun scrapeAutoOwned(
        library: LibraryConfig,
        showPath: String,
        hashProvider: (suspend (videoPath: String) -> Pair<Long, String>?)?,
        priority: ScrapeTaskPriority,
        onProgress: suspend (String) -> Unit,
    ): AutoScrapeOutcome {
        var shouldRecordAttempt = false
        try {
            val show = repo.getShowByPath(library.id, showPath) ?: return AutoScrapeOutcome.NoMatch
            val localSeasons = loadLocalSeasons(show.id)
            if (localSeasons.isEmpty()) return AutoScrapeOutcome.Skipped
            shouldRecordAttempt = true

            val titleHint = show.title.ifBlank { show.folder_name }.ifBlank { pathLeaf(showPath) }
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
                    priority = priority,
                    onProgress = onProgress,
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
            val searchSourceNames = buildList {
                add("Bangumi")
                if (dandanplay != null) add("弹弹play")
                if (tmdbSearchWasAttempted) add("TMDB")
            }
            val scannedBangumiCandidate = localSeasons.singleOrNull()
                ?.bangumiId
                ?.takeIf { it > 0L }
                ?.let { subjectId ->
                    ScrapeCandidate(
                        source = ScrapeSource.BANGUMI,
                        identityId = subjectId,
                        title = titleHint,
                        year = yearHint,
                        evidence = "bangumi.ini",
                        bgmSubjectId = subjectId,
                    )
                }
            onProgress("正在并行查询 ${searchSourceNames.joinToString("、")}...")
            var earlyBangumiResult: Result<AutoScrapeOutcome>? = null
            val (bangumiResult, dandanLookup, tmdbResult) = coroutineScope {
                val bangumiDeferred = async {
                    scannedBangumiCandidate?.let { Result.success(listOf(it)) }
                        ?: runInitialSearch(priority) { bangumi.search(cleanTitleHint) }
                }
                val dandanDeferred = async {
                    val provider = dandanplay ?: return@async DandanLookup(emptyList(), emptyList())
                    var hadFailure = false
                    if (show.tmdb_id != null) {
                        val byTmdbResult = runInitialSearch(priority) {
                            provider.searchByTmdb(show.tmdb_id)
                        }
                        hadFailure = byTmdbResult.isFailure
                        val byTmdb = byTmdbResult.getOrDefault(emptyList())
                        if (byTmdb.isNotEmpty()) return@async DandanLookup(byTmdb, byTmdb, hadFailure)
                    }
                    val searchResult = runInitialSearch(priority) { provider.search(cleanTitleHint) }
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
                        runInitialSearch(priority) {
                            tmdb.searchTv(cleanTmdbTitleHint, yearHint)
                        }
                    }
                }
                val bangumiSearch = bangumiDeferred.await()
                val earlyBangumiCandidates = bangumiSearch.getOrDefault(emptyList())

                // ANCHOR/无身份的单季番以 Bangumi 为首选。候选已足够时立即开始详情与图片阶段，
                // 不再被仅作回退的弹弹搜索拖住；仅完整命中才取消弹弹，模糊/部分结果仍等待回退。
                if (preferBangumi && localSeasons.size == 1 && earlyBangumiCandidates.isNotEmpty()) {
                    earlyBangumiResult = runSuspendCatching {
                        scrapeByBangumi(
                            library = library,
                            showPath = showPath,
                            show = show,
                            localSeasons = localSeasons,
                            titleHint = titleHint,
                            subjects = earlyBangumiCandidates,
                            tmdbCandidatesDeferred = tmdbDeferred,
                            priority = priority,
                            onProgress = onProgress,
                        )
                    }
                    if (earlyBangumiResult.getOrNull() is AutoScrapeOutcome.Done) {
                        dandanDeferred.cancelAndJoin()
                        return@coroutineScope Triple(
                            bangumiSearch,
                            DandanLookup(emptyList(), emptyList()),
                            tmdbDeferred.await(),
                        )
                    }
                }
                val tmdbSearch = tmdbDeferred.await()
                Triple(bangumiSearch, dandanDeferred.await(), tmdbSearch)
            }
            val bangumiCandidates = bangumiResult.getOrDefault(emptyList())
            val tmdbCandidates = tmdbResult.getOrDefault(emptyList())
            if (tmdbSearchWasAttempted && tmdbResult.isSuccess) {
                // TMDB 自动匹配失败状态只以"当前是否已确定身份"为准: 早发 Bangumi 路径可能已用
                // 规范标题重搜并成功确定身份(清除), 也可能未确定(记录)。初始搜索结果不直接决定成败,
                // 它由后续 enrichWithTmdb 真正应用, 应用成功会自行清除失败状态。
                if (repo.getShowByPath(library.id, showPath)?.tmdb_id == null) {
                    repo.recordTmdbAutoMatchFailure(library.id, showPath, platformTimeMillis())
                } else {
                    repo.clearTmdbAutoMatchFailure(library.id, showPath)
                }
            }
            val earlyBangumiOutcome = earlyBangumiResult?.getOrNull()
            if (earlyBangumiOutcome is AutoScrapeOutcome.Done) return earlyBangumiOutcome
            var hadRetryableFailure = initialTmdbEnrichmentFailed || bangumiResult.isFailure ||
                dandanLookup.hadFailure || tmdbResult.isFailure || earlyBangumiResult?.isFailure == true ||
                earlyBangumiOutcome is AutoScrapeOutcome.RetryableFailure
            var bangumiAttempted = earlyBangumiResult != null
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
                        // 初始搜索失败时不传扁平空列表(否则 enrich 短路且丢失失败状态), 让 enrich 重搜
                        tmdbCandidates = tmdbCandidates.takeIf { tmdbResult.isSuccess },
                        priority = priority,
                        onProgress = onProgress,
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
            var bangumiAutoAttempt: AutoScrapeOutcome? = earlyBangumiOutcome
                ?.takeUnless { it is AutoScrapeOutcome.RetryableFailure }
            if (!bangumiAttempted && preferBangumi && localSeasons.size == 1 && bangumiCandidates.isNotEmpty()) {
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
                        // 初始搜索失败时不让空列表短路定位: 传给 null 让 enrich 自行重搜并如实上报可重试失败
                        preloadedCandidates = tmdbCandidates.takeIf { tmdbResult.isSuccess },
                        priority = priority,
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
                val localSeason = localSeasons.first { it.seasonNumber == seasonNumber }
                val detail = dandanProvider.fetchDetail(candidate)
                    .alignEpisodeCoordinates(localSeason, candidate.source)
                val localPoster = detail.remotePosterUrl?.let { url ->
                    runSuspendCatching {
                        downloader.downloadSeasonPoster(library.id, showPath, seasonNumber, url)
                    }.getOrNull()
                }
                Triple(seasonNumber, candidate, detail to localPoster)
            }
            for ((seasonNumber, candidate, detailAndPoster) in seasonDetails) {
                val (detail, localPoster) = detailAndPoster
                val localSeason = localSeasons.first { it.seasonNumber == seasonNumber }
                if (!detail.complete) hadIncompleteSeasonDetail = true
                if (!hasUsableDetail(detail)) continue
                repo.upsertOnlineMeta(
                    libraryId = library.id, showPath = showPath, seasonNumber = seasonNumber,
                    source = ScrapeSource.DANDANPLAY, overwriteTitle = overwriteTitle,
                    dandanplayId = candidate.identityId,
                    bangumiId = localSeason.bangumiId ?: candidate.bgmSubjectId,
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
            val showData = buildShowData(
                dandanSeasons = dandanSeasons,
                seasonCandidates = seasonCandidates,
                localSeasons = localSeasons,
                titleHint = titleHint,
                yearHint = yearHint,
                bangumiCandidates = bangumiCandidates,
            )
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

            // TMDB 定位优先用已解析的规范标题(经 bgm 桥或弹弹候选), 与 Bangumi 路径同理。
            // 初始搜索失败时预载传 null: 不让空列表短路定位, 由 enrich 重搜并如实上报可重试失败,
            // 避免「TMDB 超时被当空结果、掩盖失败错报 Done」。
            val tmdbTitleHint = showData.title?.takeIf { it.isNotBlank() } ?: titleHint
            val preloadMatchesResolvedHint = cleanTmdbSearchKeyword(tmdbTitleHint) == cleanTmdbSearchKeyword(titleHint)
            val tmdbEnrichment = enrichWithTmdb(
                library = library,
                showPath = showPath,
                show = show,
                localSeasons = localSeasons,
                titleHint = tmdbTitleHint,
                source = ScrapeSource.DANDANPLAY,
                preloadedCandidates = if (tmdbResult.isSuccess) {
                    tmdbCandidates.takeIf { preloadMatchesResolvedHint }
                } else {
                    null
                },
                priority = priority,
                onProgress = onProgress,
            )
            val resolvedTmdbId = tmdbEnrichment.tmdbId

            // 高置信命中自动写 Bangumi 季度关联(评论区立即亮): hash 锚点命中季, 或唯一候选整部。
            // 使用刚持久化的 TMDB 身份构造 key，避免先写 show: key 后又切换为 tmdb: key 导致关联悬空。
            if (dandanSeasons.size == 1 || hashAnchors.isNotEmpty()) {
                for ((seasonNumber, candidate) in seasonCandidates) {
                    val highConfidence = seasonNumber in hashAnchors || dandanSeasons.size == 1
                    if (!highConfidence) continue
                    val localSeason = localSeasons.firstOrNull { it.seasonNumber == seasonNumber }
                    writeBangumiLinkIfHighConfidence(
                        show = show, tmdbId = resolvedTmdbId, seasonNumber = seasonNumber,
                        bangumiOffset = localSeason?.bangumiOffset ?: 0,
                        subjectId = localSeason?.bangumiId
                            ?: candidate.bgmSubjectId
                            ?: showData.bgmSubjectId.takeIf { localSeasons.size == 1 },
                        source = BangumiLinkSource.AUTO, evidence = "online-scrape:dandanplay",
                    )
                }
            }

            repo.reapplyOnlineMeta(library.id, showPath)
            // 注: 不把外层 hadRetryableFailure(含未参与本路径解析的来源失败, 如不可达的 Bangumi 兜底)
            // 直接并入终判——那会把「弹弹独立完成」误判为可重试; TMDB 失败已由上方 enrich 重搜
            // (preloaded=null)如实上报 tmdbEnrichment.hadRetryableFailure。
            if (hadIncompleteSeasonDetail || tmdbEnrichment.hadRetryableFailure) {
                shouldRecordAttempt = false
                repo.markAutoScrapeRetryable(library.id, showPath)
                return AutoScrapeOutcome.Partial(show.id, scrapedSeasons)
            }
            return AutoScrapeOutcome.Done(show.id, scrapedSeasons)
        } catch (error: CancellationException) {
            // 取消/抢占(ScrapePreemptedException 也是 CancellationException)不写重试标记也不记录尝试:
            // 任务被更高优先级接管或页面离开, 不是失败, 下次进入不应额外多一次不必要重扫。
            shouldRecordAttempt = false
            throw error
        } catch (error: Throwable) {
            shouldRecordAttempt = false
            withContext(NonCancellable) {
                runSuspendCatching { repo.markAutoScrapeRetryable(library.id, showPath) }
            }
            throw error
        } finally {
            if (shouldRecordAttempt) {
                runSuspendCatching {
                    repo.recordAutoScrapeAttempt(library.id, showPath, platformTimeMillis())
                }
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
        val scrapeLease = beginScrape(scrapeKey, ScrapeTaskPriority.MANUAL)
            ?: return AutoScrapeOutcome.Skipped
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
                priority = ScrapeTaskPriority.MANUAL,
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
                scrapeLease.release()
            }
        }
    }

    /**
     * 只恢复已经保存了远程地址的海报/头图，并按既有 TMDB 身份回补集照。
     * 该路径不会搜索 Bangumi 或弹弹，也不会重新匹配作品身份。
     */
    suspend fun restoreOnlineImages(
        library: LibraryConfig,
        showPath: String,
        onProgress: suspend (String) -> Unit = {},
    ): AutoScrapeOutcome {
        val scrapeKey = scrapeKey(library.id, showPath)
        val scrapeLease = beginScrape(scrapeKey, ScrapeTaskPriority.INTERACTIVE)
            ?: return AutoScrapeOutcome.Skipped
        return try {
            scrapeLease.runPreemptible {
                restoreOnlineImagesOwned(library, showPath, onProgress)
            }
        } finally {
            scrapeLease.release()
        }
    }

    /**
     * 海报墙在线封面轻量通道(批次C): 仅下载指定季的远程海报并回写 local 路径, 不搜索/不匹配身份。
     * 与 [restoreOnlineImages] 的 INTERACTIVE 租约互不干扰——这里不走 beginScrape, 只做 targeted
     * UPDATE([ScrapedLibraryRepository.updateOnlineMetaLocalPoster]), 与租约内的写操作无冲突面
     * (二者写的都是同一行的 local_poster_path, 但触发时机互斥: 租约任务跑完才会轮到海报墙补封,
     * 海报墙补到后 card_poster_path 已非空, 租约重放对其无动作)。
     * 任何失败返回 false 不抛(调用方按会话级一次性守卫处理, 失败也不重试)。
     */
    suspend fun tryDownloadOnlinePoster(
        libraryId: Long,
        showPath: String,
        seasonNumber: Int,
        remoteUrl: String,
    ): Boolean {
        val localPoster = runSuspendCatching {
            downloader.downloadSeasonPoster(libraryId, showPath, seasonNumber, remoteUrl)
        }.getOrNull() ?: return false
        return runSuspendCatching {
            repo.updateOnlineMetaLocalPoster(libraryId, showPath, seasonNumber, localPoster)
        }.isSuccess
    }

    /**
     * 该番是否仍有「已存远程 URL 但本地文件缺失(未下载或文件丢失)」的海报/头图可恢复。
     * 详情页封面重试条显示判据; 委托既有 [hasKnownOnlineImagesToRestore](含真实文件存在性复核)。
     * 卡片已有可见封面(NFO 部级/季级海报或任一季在线本地图已落地)时返回 false:
     * 避免「封面下载失败」条与卡片实际有封面的事实矛盾(多季番仅一季缺封不应误导用户)。
     */
    suspend fun needsPosterRestore(libraryId: Long, showPath: String): Boolean {
        val metas = runSuspendCatching { repo.listOnlineMeta(libraryId, showPath) }
            .getOrDefault(emptyList())
        if (!hasKnownOnlineImagesToRestore(metas)) return false
        val show = runSuspendCatching { repo.getShowByPath(libraryId, showPath) }.getOrNull() ?: return true
        if (!show.poster_path.isNullOrBlank()) return false
        val seasons = runSuspendCatching { repo.listSeasons(show.id) }.getOrDefault(emptyList())
        if (seasons.any { !it.season_poster_path.isNullOrBlank() }) return false
        if (metas.any { !it.local_poster_path.isNullOrBlank() }) return false
        return true
    }

    private suspend fun restoreOnlineImagesOwned(
        library: LibraryConfig,
        showPath: String,
        onProgress: suspend (String) -> Unit,
    ): AutoScrapeOutcome {
        var shouldRecordAttempt = false
        try {
            val show = repo.getShowByPath(library.id, showPath) ?: return AutoScrapeOutcome.NoMatch
            val localSeasons = loadLocalSeasons(show.id)
            if (localSeasons.isEmpty()) return AutoScrapeOutcome.Skipped
            shouldRecordAttempt = true
            var hadRetryableFailure = false
            val retryRequested = repo.hasAutoScrapeRetryMarker(library.id, showPath)
            val metas = repo.listOnlineMeta(library.id, showPath)

            for (meta in metas) {
                val seasonNumber = meta.season_number.toInt()
                val remotePoster = meta.remote_poster_url?.takeIf { it.isNotBlank() }
                if (remotePoster != null &&
                    (meta.local_poster_path.isNullOrBlank() || isMissingLocalFilePath(meta.local_poster_path))
                ) {
                    onProgress("正在恢复在线海报...")
                    val download = runSuspendCatching {
                        downloader.downloadSeasonPoster(library.id, showPath, seasonNumber, remotePoster)
                    }
                    val localPoster = download.getOrNull()
                    val write = if (localPoster != null) {
                        runSuspendCatching {
                            repo.updateOnlineMetaLocalPoster(library.id, showPath, seasonNumber, localPoster)
                        }
                    } else {
                        Result.failure(IllegalStateException("在线海报下载失败"))
                    }
                    if (download.isFailure || write.isFailure) hadRetryableFailure = true
                }

                val remoteFanart = meta.remote_fanart_url?.takeIf { it.isNotBlank() }
                if (seasonNumber == 0 && remoteFanart != null &&
                    (meta.local_fanart_path.isNullOrBlank() || isMissingLocalFilePath(meta.local_fanart_path))
                ) {
                    onProgress("正在恢复在线头图...")
                    val download = runSuspendCatching {
                        downloader.downloadImage(
                            library.id,
                            showPath,
                            "backdrop.jpg",
                            remoteFanart,
                        )
                    }
                    val localFanart = download.getOrNull()
                    val write = if (localFanart != null) {
                        runSuspendCatching {
                            repo.updateOnlineMetaFanart(library.id, showPath, remoteFanart, localFanart)
                        }
                    } else {
                        Result.failure(IllegalStateException("在线头图下载失败"))
                    }
                    if (download.isFailure || write.isFailure) hadRetryableFailure = true
                }
            }

            val refreshedMetas = repo.listOnlineMeta(library.id, showPath)
            val shouldRefreshTmdb = tmdb != null && show.tmdb_id != null && (
                retryRequested ||
                    hasInvalidTmdbImageCache(refreshedMetas) ||
                    hasMissingTmdbEpisodeImages(localSeasons, refreshedMetas)
                )
            if (shouldRefreshTmdb) {
                onProgress("正在按已有 TMDB 身份恢复图片...")
                val enrichment = enrichWithTmdb(
                    library = library,
                    showPath = showPath,
                    show = show,
                    localSeasons = localSeasons,
                    titleHint = show.title.ifBlank { show.folder_name.ifBlank { pathLeaf(showPath) } },
                    source = ScrapeSource.TMDB,
                    priority = ScrapeTaskPriority.INTERACTIVE,
                    onProgress = onProgress,
                )
                hadRetryableFailure = hadRetryableFailure || enrichment.hadRetryableFailure
            }

            repo.reapplyOnlineMeta(library.id, showPath)
            if (hadRetryableFailure) {
                shouldRecordAttempt = false
                repo.markAutoScrapeRetryable(library.id, showPath)
                return AutoScrapeOutcome.Partial(show.id, 0)
            }
            return AutoScrapeOutcome.Done(show.id, localSeasons.size)
        } catch (error: CancellationException) {
            shouldRecordAttempt = false
            throw error
        } catch (error: Throwable) {
            shouldRecordAttempt = false
            withContext(NonCancellable) {
                runSuspendCatching { repo.markAutoScrapeRetryable(library.id, showPath) }
            }
            throw error
        } finally {
            if (shouldRecordAttempt) {
                runSuspendCatching {
                    repo.recordAutoScrapeAttempt(library.id, showPath, platformTimeMillis())
                }
            }
        }
    }

    /**
     * 计算详情页自动任务范围：身份/目录字段缺失才完整刮削；已有远程图或 TMDB 身份时只回补图片。
     * 无 24 小时节流(2026-08-14 用户决策删除): 数据仍缺失的番剧每次进入详情页都重新完整刮削,
     * 网络恢复/线上数据库后补后无需等待即可再试。防打扰由详情页横幅「单次/永久关闭自动刮削」控制,
     * 永久关闭落 AutoScrapeSuppression 表且仅作用于本入口(手动刮削/候选弹窗/TMDB 提示不受影响)。
     */
    suspend fun autoScrapeMode(libraryId: Long, showPath: String): AutoScrapeMode {
        if (repo.isAutoScrapeSuppressed(libraryId, showPath)) return AutoScrapeMode.NONE
        val show = repo.getShowByPath(libraryId, showPath) ?: return AutoScrapeMode.NONE
        val localSeasons = loadLocalSeasons(show.id)
        if (localSeasons.isEmpty()) return AutoScrapeMode.NONE
        val retryRequested = repo.hasAutoScrapeRetryMarker(libraryId, showPath)
        val retryMarkedAt = repo.autoScrapeRetryMarkedAt(libraryId, showPath)
        if (
            retryRequested && retryMarkedAt != null && retryMarkedAt > 0L &&
            platformTimeMillis() - retryMarkedAt < AUTO_RETRYABLE_FAILURE_COOLDOWN_MS
        ) {
            return AutoScrapeMode.NONE
        }
        val metas = repo.listOnlineMeta(libraryId, showPath)
        val missingIdentity = tmdb != null && show.tmdb_id == null
        val invalidPosterWithoutRemote = hasInvalidOnlinePosterWithoutRemote(metas)
        val missingCatalog = hasMissingCatalogData(
            show = show,
            localSeasons = localSeasons,
            metas = metas,
            remotePosterCounts = true,
        )
        if (missingIdentity || invalidPosterWithoutRemote || missingCatalog) return AutoScrapeMode.FULL

        val canRepairTmdbImages = tmdb != null && show.tmdb_id != null
        val needsKnownImageRestore = hasKnownOnlineImagesToRestore(metas)
        val needsTmdbImageRestore = canRepairTmdbImages && (
            hasInvalidTmdbImageCache(metas) || hasMissingTmdbEpisodeImages(localSeasons, metas)
            )
        if (needsKnownImageRestore || needsTmdbImageRestore) return AutoScrapeMode.IMAGES_ONLY
        if (retryRequested) {
            return if (canRepairTmdbImages) AutoScrapeMode.IMAGES_ONLY else AutoScrapeMode.FULL
        }
        return AutoScrapeMode.NONE
    }

    /** 兼容既有调用：是否存在任何自动任务。 */
    suspend fun shouldAutoScrape(libraryId: Long, showPath: String): Boolean =
        autoScrapeMode(libraryId, showPath) != AutoScrapeMode.NONE

    /**
     * 批量刮削(海报墙"批量补刮" / 扫描后自动补): 列出"缺元数据"番剧(无部级 meta),
     * 按全局并发(1~4)逐部 [scrapeAuto]。单部失败不阻断其余; 可取消。
     *
     * @param cooldownMs > 0 时跳过"最近已尝试未命中"的番剧(扫描后自动补防重复重刮);
     *   手动批量传 0 不节流。
     * @return 成功刮削(部分命中)的部数; 未命中的部自动跳过(候选留待手动)。
     */
    suspend fun scrapePending(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        cooldownMs: Long = 0L,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit = { _, _, _ -> },
    ): Int = scrapePendingInternal(library, anchorOnly, concurrency, hashProvider, cooldownMs, onProgress)

    override suspend fun scrapePendingInCoordinator(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        cooldownMs: Long,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit,
    ): Int = scrapePendingInternal(library, anchorOnly, concurrency, hashProvider, cooldownMs, onProgress)

    private suspend fun scrapePendingInternal(
        library: LibraryConfig,
        anchorOnly: Boolean,
        concurrency: Int,
        hashProvider: (suspend (String) -> Pair<Long, String>?)?,
        cooldownMs: Long,
        onProgress: suspend (done: Int, total: Int, currentTitle: String) -> Unit,
    ): Int = coroutineScope {
        val pending = repo.listScrapePending(
            libraryId = library.id,
            anchorOnly = anchorOnly,
            requireTmdbIdentity = tmdb != null,
            cooldownMs = cooldownMs,
            nowMs = platformTimeMillis(),
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
                    // 图片 CDN 限流退避由 RemoteImageFetcher 按主机自管理(fetchImageDetailed 请求前
                    // 对退避中的主机等待, 只等被限流的 CDN): 这里不再做全局延迟, 避免 image.tmdb.org
                    // 的 429 拖慢网关 /i 等其它主机的图片下载(FP3-13)。
                    val outcome = try {
                        scrapeAutoWithPriority(
                            library = library,
                            showPath = item.showPath,
                            hashProvider = hashProvider,
                            priority = ScrapeTaskPriority.BACKGROUND,
                        )
                    } catch (_: ScrapePreemptedException) {
                        null
                    }
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
        val scrapeLease = beginScrape(scrapeKey, ScrapeTaskPriority.MANUAL) ?: return false
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
                priority = ScrapeTaskPriority.MANUAL,
                onProgress = onProgress,
                onIdentityApplied = onTmdbIdentityApplied,
            )
            if (tmdbEnrichment.hadRetryableFailure) {
                repo.markAutoScrapeRetryable(library.id, showPath)
            }
            val resolvedTmdbId = tmdbEnrichment.tmdbId

            for (seasonDetail in seasonDetails) {
                val localSeason = localSeasons.firstOrNull { it.seasonNumber == seasonDetail.seasonNumber }
                val manualSubjectId = if (candidate.source == ScrapeSource.BANGUMI) {
                    seasonDetail.candidate.identityId
                } else {
                    seasonDetail.candidate.bgmSubjectId
                }
                writeBangumiLinkIfHighConfidence(
                    show = show,
                    tmdbId = resolvedTmdbId,
                    seasonNumber = seasonDetail.seasonNumber,
                    bangumiOffset = localSeason?.bangumiOffset ?: 0,
                    subjectId = manualSubjectId,
                    source = BangumiLinkSource.MANUAL,
                    evidence = "online-scrape:manual",
                )
            }
            return true
        } finally {
            scrapeLease.release()
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
            priority = ScrapeTaskPriority.MANUAL,
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
                    bangumiId = season.bangumi_id,
                    bangumiOffset = season.bangumi_offset.toInt(),
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
        remotePosterCounts: Boolean = false,
    ): Boolean {
        if (show.plot.isNullOrBlank()) return true
        val metaBySeason = metas.associateBy { it.season_number.toInt() }
        val hasPoster = !show.poster_path.isNullOrBlank() ||
            localSeasons.any { season ->
                val online = metaBySeason[season.seasonNumber]
                !season.posterPath.isNullOrBlank() ||
                    !online?.local_poster_path.isNullOrBlank() ||
                    (remotePosterCounts && !online?.remote_poster_url.isNullOrBlank())
            } ||
            (remotePosterCounts && metas.any { !it.remote_poster_url.isNullOrBlank() })
        if (!hasPoster) return true
        return localSeasons.any { season ->
            val seasonMeta = metaBySeason[season.seasonNumber]
            val onlineEpisodes = seasonMeta
                ?.decodedEpisodes
                .orEmpty()
                .associateBy { it.episodeNumber.toLong() }
            val shiftedSourceNeedsVerification = season.bangumiOffset != 0 && season.bangumiId != null
            season.episodes.any { episode ->
                // 被忽略集(正漂移前 offset 集 = 先行篇)不参与文本缺失判定: 它在 TMDB/纠错源里
                // 永远拿不到"正确"文本, 判缺失会导致每次进详情页都重刮。
                if (isOffsetIgnoredEpisode(season.bangumiOffset.toLong(), episode.episode_number)) {
                    return@any false
                }
                val online = onlineEpisodes[episode.episode_number]
                if (shiftedSourceNeedsVerification) {
                    online == null ||
                        !isVerifiedShiftedEpisodeText(
                            scannedBangumiId = season.bangumiId,
                            bangumiOffset = season.bangumiOffset,
                            onlineBangumiId = seasonMeta?.bangumi_id,
                            source = seasonMeta?.source,
                            episode = online,
                            localEpisodeNumber = episode.episode_number,
                        ) ||
                        (online.title.isNullOrBlank() && online.aired.isNullOrBlank())
                } else {
                    (episode.title.isNullOrBlank() && online?.title.isNullOrBlank()) ||
                        (episode.aired.isNullOrBlank() && online?.aired.isNullOrBlank())
                }
            }
        }
    }

    private suspend fun hasKnownOnlineImagesToRestore(metas: List<ScrapedOnlineMeta>): Boolean {
        for (meta in metas) {
            val posterNeedsRestore = !meta.remote_poster_url.isNullOrBlank() &&
                (meta.local_poster_path.isNullOrBlank() || isMissingLocalFilePath(meta.local_poster_path))
            val fanartNeedsRestore = meta.season_number == 0L && !meta.remote_fanart_url.isNullOrBlank() &&
                (meta.local_fanart_path.isNullOrBlank() || isMissingLocalFilePath(meta.local_fanart_path))
            if (posterNeedsRestore || fanartNeedsRestore) return true
        }
        return false
    }

    private suspend fun hasInvalidOnlinePosterWithoutRemote(metas: List<ScrapedOnlineMeta>): Boolean {
        for (meta in metas) {
            if (meta.remote_poster_url.isNullOrBlank() && isMissingLocalFilePath(meta.local_poster_path)) return true
        }
        return false
    }

    private suspend fun hasInvalidTmdbImageCache(metas: List<ScrapedOnlineMeta>): Boolean {
        for (meta in metas) {
            if (meta.season_number == 0L && isMissingLocalFilePath(meta.local_fanart_path)) return true
            if (meta.decodedEpisodes.any { episode -> isMissingLocalFilePath(episode.thumbPath) }) return true
        }
        return false
    }

    private suspend fun hasMissingTmdbEpisodeImages(
        localSeasons: List<LocalSeason>,
        metas: List<ScrapedOnlineMeta>,
    ): Boolean {
        val metaBySeason = metas.associateBy { it.season_number.toInt() }
        for (season in localSeasons) {
            val seasonMeta = metaBySeason[season.seasonNumber]
            val mapping = seasonMeta?.validatedTmdbEpisodeMapping(
                localSeasonNumber = season.seasonNumber,
                localEpisodeNumbers = season.episodes.mapNotNull { it.episode_number.toPositiveIntOrNull() },
                bangumiId = season.bangumiId,
                bangumiOffset = season.bangumiOffset,
            )
            val shiftedCoordinatesRequireTmdb = mapping?.episodeOffset?.let { it != 0 }
                ?: (season.bangumiOffset != 0 && season.bangumiId != null)
            val onlineByEpisode = seasonMeta
                ?.decodedEpisodes
                .orEmpty()
                .associateBy { it.episodeNumber.toLong() }
            for (episode in season.episodes) {
                // 被忽略集(正漂移前 offset 集 = 先行篇)在 TMDB 无对应集, 永远等不到"正确图",
                // 只认同文件名 NFO 集照(如果有); 跳过缺图判定, 防止每次进详情页都触发重刮。
                if (isOffsetIgnoredEpisode(season.bangumiOffset.toLong(), episode.episode_number)) continue
                // 非零映射已经证明 NFO 的本地 E 不是 TMDB 的远端 E；此时 NFO 集照不能证明正确图片已存在。
                if (!shiftedCoordinatesRequireTmdb && !episode.thumb_path.isNullOrBlank()) continue
                // 本地已抽帧(EpisodeThumbCoordinator 写 local_thumb_path, 文件仍在)视为已有集照,
                // 不再冗余下载 TMDB 剧照(此前只认 NFO thumb 导致已抽帧番仍被判 IMAGES_ONLY)
                if (!shiftedCoordinatesRequireTmdb && !episode.local_thumb_path.isNullOrBlank() &&
                    !isMissingLocalFilePath(episode.local_thumb_path)
                ) {
                    continue
                }
                val onlineEpisode = onlineByEpisode[episode.episode_number]
                val coordinatesMatch = mapping?.let { onlineEpisode?.matchesTmdbStillCoordinates(it) == true }
                    ?: !shiftedCoordinatesRequireTmdb
                if (onlineEpisode?.tmdbStillAvailable == false && coordinatesMatch) {
                    // false 仅短时抑制: TMDB 连载番的剧照可能晚于刮削生成, 超 TTL 后重新探测
                    val metaScrapedAt = seasonMeta?.scraped_at ?: 0L
                    if (platformTimeMillis() - metaScrapedAt < TMDB_STILL_NEGATIVE_TTL_MS) continue
                }
                val onlinePath = onlineEpisode?.thumbPath
                if (!coordinatesMatch || onlinePath.isNullOrBlank() || isMissingLocalFilePath(onlinePath)) return true
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
            val localEpisodeNumbers = season.episodes
                .mapNotNullTo(linkedSetOf()) {
                    it.episode_number.takeIf { number -> number in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                }
            val mergedByNumber = existing?.decodedEpisodes.orEmpty()
                .filter { it.episodeNumber in localEpisodeNumbers }
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
            val localSeason = localSeasons.firstOrNull { it.seasonNumber == seasonNumber } ?: return null
            val detail = provider.fetchDetail(candidate)
                .alignEpisodeCoordinates(localSeason, candidate.source)
                .takeIf(::hasUsableDetail)
                ?: return null
            return listOf(ManualSeasonDetail(seasonNumber, candidate, detail))
        }
        if (localSeasons.size == 1) {
            val localSeason = localSeasons.single()
            val detail = provider.fetchDetail(candidate)
                .alignEpisodeCoordinates(localSeason, candidate.source)
                .takeIf(::hasUsableDetail)
                ?: return null
            return listOf(ManualSeasonDetail(localSeason.seasonNumber, candidate, detail))
        }
        if (candidate.source != ScrapeSource.DANDANPLAY || !hasContinuousSeasonNumbers(localSeasons)) return null
        val expanded = orderedDandanSeries(expandDandanList(candidate)) ?: return null
        if (expanded.size != localSeasons.size) return null
        val mappedCandidates = localSeasons.mapIndexed { index, localSeason ->
            localSeason to (expanded.getOrNull(index) ?: return null)
        }
        val details = mapConcurrently(mappedCandidates) { (localSeason, seasonCandidate) ->
            ManualSeasonDetail(
                seasonNumber = localSeason.seasonNumber,
                candidate = seasonCandidate,
                detail = provider.fetchDetail(seasonCandidate).alignEpisodeCoordinates(localSeason, seasonCandidate.source),
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
        if (dandanSeasons.isEmpty()) return emptyMap()
        if (dandanSeasons.size == 1) {
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
        // tmdbId 反查返回全部季度条目时(如 203737 -> 第一/二/三/四季各一条), 不再直接放弃:
        // 依次用"标题季标唯一命中"与"offset 全系列集号范围唯一覆盖"为每个本地季定位分段条目。
        // 候选条目编号集跨季共享缓存: 多季番的覆盖判定只对每个候选取一次详情。
        val detailNumbersCache = mutableMapOf<Long, Set<Int>>()
        val resolved = linkedMapOf<Int, ScrapeCandidate>()
        for (season in localSeasons) {
            val candidate = resolveDandanCandidateAmongMany(season, dandanSeasons, detailNumbersCache)
                ?: return emptyMap()
            resolved[season.seasonNumber] = candidate
        }
        // 跨季唯一性防护: 不同本地季不得解析到同一条目(如整季条目同时覆盖两个分段的
        // 集号范围), 否则两个季页签会写入同一份剧集列表 —— 单候选路径的 1:1 防御在此等价。
        val resolvedIds = resolved.values.mapNotNull { it.identityId }
        if (resolvedIds.distinct().size != resolvedIds.size) {
            return emptyMap()
        }
        return resolved
    }

    /**
     * 多候选时为一个本地季定位弹弹条目。两级证据:
     * ① 标题明确标注季号且唯一(如"我推的孩子 第二季" 对本地 Season 2);
     * ② bangumi.ini 负 offset 把本地集换算为全系列集号后, 唯一一个条目的正片编号完整覆盖
     *    (弹弹分段条目的 episodeNumber 可能用全系列连续号, 如第二季条目 12..24)。
     * 任一级出现歧义都返回 null(保守放弃, 维持旧语义: 不猜测)。
     */
    private suspend fun resolveDandanCandidateAmongMany(
        season: LocalSeason,
        candidates: List<ScrapeCandidate>,
        detailNumbersCache: MutableMap<Long, Set<Int>>,
    ): ScrapeCandidate? {
        val byTitle = candidates.filter { candidate ->
            extractSeasonNumberFromTitle(candidate.title) == season.seasonNumber
        }
        if (byTitle.size == 1) return byTitle.single()
        if (season.bangumiOffset >= 0 || season.bangumiId == null) return null
        val localNumbers = season.episodes
            .mapNotNull { it.episode_number.takeIf { number -> number in 1L..Int.MAX_VALUE.toLong() }?.toInt() }
            .distinct()
        if (localNumbers.isEmpty()) return null
        val offset = season.bangumiOffset.toInt()
        val seriesNumbers = localNumbers.map { it - offset }.toSet()
        val provider = dandanplay ?: return null
        var covering: ScrapeCandidate? = null
        var coveringCount = 0
        for (candidate in candidates) {
            val animeId = candidate.identityId?.takeIf { it > 0L } ?: continue
            val numbers = detailNumbersCache[animeId] ?: run {
                val detail = runSuspendCatching { provider.fetchDetail(candidate) }.getOrNull()
                    ?: return@run null
                val fetched = detail.episodes.map { it.episodeNumber }.toSet()
                if (fetched.isNotEmpty()) detailNumbersCache[animeId] = fetched
                fetched
            } ?: continue
            // 本地每集换算出的全系列集号都必须落在该条目内; 条目允许比本地多(连载中/他分段)。
            if (numbers.isNotEmpty() && seriesNumbers.all { it in numbers }) {
                covering = candidate
                coveringCount++
                if (coveringCount > 1) return null
            }
        }
        return covering.takeIf { coveringCount == 1 }
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

    private fun extractSeasonNumberFromTitle(title: String): Int? =
        DanmakuMatcher.extractExplicitSeason(title)

    /** Bangumi 应用路径：仅单季唯一候选自动落库；多季或多候选返回人工确认。 */
    private suspend fun scrapeByBangumi(
        library: LibraryConfig,
        showPath: String,
        show: ScrapedShow,
        localSeasons: List<LocalSeason>,
        titleHint: String,
        subjects: List<ScrapeCandidate>? = null,
        tmdbCandidates: List<TmdbTvCandidate>? = null,
        tmdbCandidatesDeferred: Deferred<Result<List<TmdbTvCandidate>>>? = null,
        priority: ScrapeTaskPriority,
        onProgress: suspend (String) -> Unit,
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
        onProgress("正在获取 Bangumi 详情与海报...")
        val seasonDetails = mapConcurrently(mapped.mapIndexed { index, subject ->
            val season = localSeasons.getOrNull(index) ?: return@mapIndexed null
            season to subject
        }.filterNotNull()) { (season, subject) ->
            val detail = bangumi.fetchDetail(subject).alignEpisodeCoordinates(season, ScrapeSource.BANGUMI)
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

        // TMDB 定位优先用 Bangumi 详情给出的规范标题(用户反馈: 文件夹名带 {tmdb=} 等标记时
        // 直接搜不到 TMDB, 手动应用 Bangumi 后能搜到——原因就是这里换了搜索词)。
        // 预载候选(初始并行搜索)只在搜索词一致时沿用, 否则按新词重新搜索。
        val tmdbTitleHint = primaryDetail.title?.takeIf { it.isNotBlank() }
            ?: primaryDetail.originalTitle?.takeIf { it.isNotBlank() }
            ?: titleHint
        val preloadMatchesResolvedHint = cleanTmdbSearchKeyword(tmdbTitleHint) == cleanTmdbSearchKeyword(titleHint)
        val tmdbEnrichment = enrichWithTmdb(
            library = library,
            showPath = showPath,
            show = show,
            localSeasons = localSeasons,
            titleHint = tmdbTitleHint,
            source = ScrapeSource.BANGUMI,
            preloadedCandidates = tmdbCandidates.takeIf { preloadMatchesResolvedHint },
            preloadedCandidatesDeferred = tmdbCandidatesDeferred.takeIf { preloadMatchesResolvedHint },
            priority = priority,
            onProgress = onProgress,
        )
        val resolvedTmdbId = tmdbEnrichment.tmdbId

        // Bangumi 命中即高置信(唯一候选): 写季度关联(评论区立即可用)
        if (candidates.size == localSeasons.size) {
            localSeasons.forEachIndexed { index, season ->
                val subject = mapped.getOrNull(index) ?: return@forEachIndexed
                writeBangumiLinkIfHighConfidence(
                    show = show, tmdbId = resolvedTmdbId, seasonNumber = season.seasonNumber,
                    bangumiOffset = season.bangumiOffset,
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
        localSeasons: List<LocalSeason>,
        titleHint: String,
        yearHint: Int?,
        bangumiCandidates: List<ScrapeCandidate>,
    ): ScrapedScrapeData {
        val bridgeBgmId = localSeasons.singleOrNull()?.bangumiId
            ?: seasonCandidates.values.firstNotNullOfOrNull { it.bgmSubjectId }
            ?: dandanSeasons.firstNotNullOfOrNull { it.bgmSubjectId }
        var resolvedBgmId = bridgeBgmId
        val bgmDetail: ScrapedScrapeData? = if (bridgeBgmId != null) {
            bangumi.fetchDetail(ScrapeCandidate(ScrapeSource.BANGUMI, bridgeBgmId, ""))
        } else {
            val searched = bangumiCandidates
            // 部级数据只认精确匹配: 唯一放宽仅用于"哪部作品", 不用于"部级数据从哪取"。
            val candidates = filterBangumiCandidates(searched, titleHint, yearHint, allowUniqueFallback = false)
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

    private fun cleanKeyword(raw: String): String {
        return cleanAnimeSearchKeyword(raw)
    }

    private fun filterDandanCandidates(
        candidates: List<ScrapeCandidate>,
        titleHint: String,
        year: Int?,
    ): List<ScrapeCandidate> {
        val queryTitle = comparableTitle(titleHint)
        if (queryTitle.isBlank()) return emptyList()
        val exact = candidates
            .filter { candidate ->
                val candidateTitle = comparableTitle(candidate.title)
                val yearMatches = year == null || candidate.year == null || candidate.year == year
                candidateTitle.isNotBlank() && yearMatches && candidateTitle == queryTitle
            }
            .distinctBy { it.identityId }
        if (exact.isNotEmpty() || !uniqueCandidateAutoApply) return exact
        val unique = candidates.distinctBy { it.identityId }.singleOrNull() ?: return emptyList()
        return if (yearCompatible(year, unique.year)) listOf(unique) else emptyList()
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
        allowUniqueFallback: Boolean = true,
    ): List<ScrapeCandidate> {
        val queryTitle = comparableTitle(titleHint)
        if (queryTitle.isBlank()) return emptyList()
        val exact = candidates
            .filter { candidate ->
                val candidateTitles = listOf(candidate.title, candidate.originalTitle.orEmpty())
                    .map(::comparableTitle)
                    .filter { it.isNotBlank() }
                val yearMatches = year == null || candidate.year == null || candidate.year == year
                yearMatches && candidateTitles.any { title -> title == queryTitle }
            }
            .distinctBy { it.identityId }
        if (exact.isNotEmpty() || !uniqueCandidateAutoApply || !allowUniqueFallback) return exact
        val unique = candidates.distinctBy { it.identityId }.singleOrNull() ?: return emptyList()
        return if (yearCompatible(year, unique.year)) listOf(unique) else emptyList()
    }

    /** 唯一结果放宽用年份兼容: 任一侧年份未知视为兼容, 双侧已知且不同才拒绝。 */
    private fun yearCompatible(yearHint: Int?, candidateYear: Int?): Boolean =
        yearHint == null || candidateYear == null || candidateYear == yearHint

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

    private suspend fun beginScrape(
        key: String,
        priority: ScrapeTaskPriority,
    ): ScrapeTaskArbiter.Lease? = scrapeTaskArbiter.acquire(key, priority)

    private suspend fun <T> runInitialSearch(
        priority: ScrapeTaskPriority,
        block: suspend () -> T,
    ): Result<T> {
        if (priority == ScrapeTaskPriority.BACKGROUND) return runSuspendCatching(block)
        return withTimeoutOrNull(interactiveSearchTimeoutMs.coerceAtLeast(1L)) {
            runSuspendCatching(block)
        } ?: Result.failure(InteractiveSearchTimeoutException())
    }

    private fun scrapeKey(libraryId: Long, showPath: String): String = "${libraryId}\u0000$showPath"

    /**
     * TMDB 增强（通过内置 Gateway，见设计 §12.6）：
     * ①宽幅头图 backdrop → 部级 meta fanart，详情页在 NFO fanart 后回退；
     * ②每季逐集剧照 still → 季级 meta episode_json[].thumbPath，在 NFO 集照后回退；
     * ③海报兜底: 无本地封面时季海报(无则 TV 海报) → 季级 meta 海报对(卡片封面在线来源)。
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
        preloadedCandidatesDeferred: Deferred<Result<List<TmdbTvCandidate>>>? = null,
        priority: ScrapeTaskPriority,
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
            } else if (preloadedCandidatesDeferred != null) {
                preloadedCandidatesDeferred.await()
            } else {
                // 规范标题重搜与初始并行搜索同受交互预算约束, 避免弱网下详情页被拖住。
                runInitialSearch(priority) { tmdbApi.searchTv(cleanTitle, year) }
            }
            if (candidatesResult.isFailure) return TmdbEnrichmentResult(null, hadRetryableFailure = true)
            tmdbId = pickTmdbCandidate(candidatesResult.getOrThrow(), cleanTitle, year)?.tmdbId
                ?: return TmdbEnrichmentResult(null)
            repo.persistTmdbId(library.id, showPath, tmdbId, source, platformTimeMillis())
            // 身份已成功确定: 清除"自动匹配未命中"状态(与 sq 注释"成功取得 TMDB 身份时删除"一致)。
            repo.clearTmdbAutoMatchFailure(library.id, showPath)
            onIdentityApplied()
        }
        val resolvedTmdbId = tmdbId
        ensureTmdbImageMetaRows(library.id, showPath, localSeasons)
        var hadRetryableFailure = false

        // ② 宽幅头图 backdrop(顺手取 TV 海报, 供步骤④兜底) → 部级 meta(remote URL + 本地绝对路径)
        onProgress("正在获取 TMDB 头图...")
        val tvImagesResult = runSuspendCatching { tmdbApi.fetchTvImagePaths(resolvedTmdbId) }
        if (tvImagesResult.isFailure) hadRetryableFailure = true
        val tvImages = tvImagesResult.getOrNull()
        val backdropPath = tvImages?.backdropPath
        val tvPosterPath = tvImages?.posterPath
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
            val fanartWrite = runSuspendCatching {
                repo.updateOnlineMetaFanart(
                    library.id,
                    showPath,
                    remoteFanartUrl = tmdbApi.imageUrl(backdropPath, "w1280"),
                    localFanartPath = usableFanartPath,
                )
            }
            if (fanartWrite.isFailure) hadRetryableFailure = true
        }

        // ③ 每季逐集剧照(整季一次拉取仍图+季海报, 跳过已有剧照的集) → 季级 meta episode_json[].thumbPath
        onProgress("正在获取 TMDB 集照...")
        val stillsBySeason = mapConcurrently(localSeasons) { season ->
            season to resolveTmdbSeason(
                libraryId = library.id,
                showPath = showPath,
                tmdbId = resolvedTmdbId,
                season = season,
                tmdbApi = tmdbApi,
            )
        }
        // 批量最多四部并行时每部只占一个 CDN 下载槽，避免 12 个后台请求塞满 OkHttp
        // 同主机队列；前台任务不受批量槽限制，可一次提交最多五张图，缩短队列积压。
        val imageSemaphore = Semaphore(
            if (priority == ScrapeTaskPriority.BACKGROUND) 1 else INTERACTIVE_IMAGE_CONCURRENCY,
        )
        for ((season, stillsResult) in stillsBySeason) {
            if (stillsResult.isFailure) {
                hadRetryableFailure = true
                continue
            }
            val resolvedSeason = stillsResult.getOrThrow()
            val stills = resolvedSeason.images.stillPaths
            val mapping = resolvedSeason.mapping
            val seasonMeta = runSuspendCatching {
                repo.getOnlineMeta(library.id, showPath, season.seasonNumber)
            }.getOrNull() ?: continue
            val episodes = seasonMeta.decodedEpisodes
            if (episodes.isEmpty()) continue
            val nfoThumbEpisodeNumbers = season.episodes
                .filter { !it.thumb_path.isNullOrBlank() }
                .mapTo(hashSetOf()) { it.episode_number.toInt() }
            val updated = coroutineScope {
                episodes.map { ep ->
                    async {
                        val remoteEpisodeNumber = mapping.remoteEpisodeNumber(ep.episodeNumber.toLong())
                            ?.takeIf { it <= Int.MAX_VALUE.toLong() }
                            ?.toInt()
                        when {
                            mapping.episodeOffset == 0 && ep.episodeNumber in nfoThumbEpisodeNumbers -> EpisodeImageUpdate(ep)
                            ep.thumbPath != null && ep.matchesTmdbStillCoordinates(mapping) &&
                                !isMissingLocalFilePath(ep.thumbPath) -> {
                                EpisodeImageUpdate(ep.copy(tmdbStillAvailable = true))
                            }
                            remoteEpisodeNumber?.let(stills::get) == null -> {
                                EpisodeImageUpdate(
                                    ep.copy(
                                        thumbPath = null,
                                        tmdbStillAvailable = false,
                                        tmdbCoordinates = remoteEpisodeNumber?.let { remoteEpisode ->
                                            TmdbEpisodeCoordinates(mapping.seasonNumber, remoteEpisode)
                                        },
                                    ),
                                )
                            }
                            else -> {
                                val resolvedRemoteEpisodeNumber = requireNotNull(remoteEpisodeNumber)
                                val still = stills.getValue(resolvedRemoteEpisodeNumber)
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
                                    episode = ep.copy(
                                        thumbPath = local,
                                        tmdbStillAvailable = true,
                                        tmdbCoordinates = TmdbEpisodeCoordinates(
                                            mapping.seasonNumber,
                                            resolvedRemoteEpisodeNumber,
                                        ),
                                    ),
                                    hadRetryableFailure = download.isFailure || local == null,
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            if (updated.any { it.hadRetryableFailure }) hadRetryableFailure = true
            val updatedEpisodes = updated.map { it.episode }
            // still 列表读取成功后，即使结果与旧三态相同也刷新检查时间，重新建立完整 7 天负缓存 TTL。
            val writeResult = runSuspendCatching {
                repo.updateOnlineMetaEpisodes(
                    library.id,
                    showPath,
                    season.seasonNumber,
                    updatedEpisodes,
                    scrapedAt = platformTimeMillis(),
                )
            }
            // DB 写失败: 本轮计算的三态/thumbPath/检查时间丢失, 必须标记可重试而非静默吞掉
            if (writeResult.isFailure) hadRetryableFailure = true
        }

        // ④ 海报兜底: ANCHOR 番无本地封面(show.poster_path 空)时, 卡片封面唯一在线来源是
        // 季级 meta 的海报对; Bangumi/弹弹未命中的季用 TMDB 季海报(无则 TV 海报)补空。
        // 封面优先级「本地锚点/NFO > Bangumi > 弹弹 > TMDB」: 已有在线海报对/Bangumi 季照
        // 不顶掉(upsert 侧同语义), NFO 本地季照(season.posterPath)存在时该季跳过。
        // 独立于集照循环: TMDB-only 场景季 meta 无 episode_json, 不能被 episodes.isEmpty() 短路。
        if (show.poster_path.isNullOrBlank()) {
            val seasonPosterPaths = stillsBySeason.mapNotNull { (season, imagesResult) ->
                imagesResult.getOrNull()?.images?.posterPath?.let { season.seasonNumber to it }
            }.toMap()
            for (season in localSeasons) {
                val seasonMeta = runSuspendCatching {
                    repo.getOnlineMeta(library.id, showPath, season.seasonNumber)
                }.getOrNull()
                // 已有在线海报对(任一字段非空)或 NFO 本地季照 → 不动
                if (!seasonMeta?.remote_poster_url.isNullOrBlank()) continue
                if (!seasonMeta?.local_poster_path.isNullOrBlank()) continue
                if (!season.posterPath.isNullOrBlank()) continue
                val posterPath = seasonPosterPaths[season.seasonNumber] ?: tvPosterPath ?: continue
                val posterUrl = tmdbApi.imageUrl(posterPath, "w500")
                val posterDownload = runSuspendCatching {
                    downloader.downloadSeasonPoster(library.id, showPath, season.seasonNumber, posterUrl)
                }
                val localPoster = posterDownload.getOrNull()
                if (posterDownload.isFailure || localPoster == null) {
                    // 拿到了海报路径但下载失败: 可重试; TMDB 无海报路径则上面已静默 continue(不算失败)
                    hadRetryableFailure = true
                    continue
                }
                val posterWrite = runSuspendCatching {
                    repo.upsertOnlineMeta(
                        libraryId = library.id, showPath = showPath, seasonNumber = season.seasonNumber,
                        source = ScrapeSource.TMDB, overwriteTitle = false,
                        dandanplayId = null, bangumiId = null,
                        remotePosterUrl = posterUrl, localPosterPath = localPoster,
                        title = null, originalTitle = null, year = null, plot = null, rating = null,
                        releaseDate = null, genres = emptyList(), studios = emptyList(),
                        episodes = emptyList(), scrapedAt = platformTimeMillis(),
                    )
                }
                if (posterWrite.isFailure) hadRetryableFailure = true
            }
        }

        if (runSuspendCatching { repo.reapplyOnlineMeta(library.id, showPath) }.isFailure) {
            hadRetryableFailure = true
        }
        return TmdbEnrichmentResult(resolvedTmdbId, hadRetryableFailure)
    }

    /**
     * 默认使用本地季集坐标。存在非零 offset 时，即使同号 TMDB 季存在，也会同时比较 Bangumi
     * 季内 ep 与跨分段 sort 两套证据：负 offset 防止把合并季下半部分的本地 E1 错当成 TMDB E1，
     * 正 offset 防止把 TMDB 缺失的先行篇/第0话(本地比 TMDB 多出的前置集)错位成 TMDB E1。
     * 只有本地季度被 TMDB 明确判定不存在时，才继续尝试把后续本地季映射到 TMDB 第 1 季。
     * 当前 Gateway 会把上游 404 包装为 502/UPSTREAM_REJECTED，因此仅兼容这一明确错误组合；
     * 普通 502 不会触发推断，错误状态本身也绝不作为建立映射的证据。
     */
    private suspend fun resolveTmdbSeason(
        libraryId: Long,
        showPath: String,
        tmdbId: Long,
        season: LocalSeason,
        tmdbApi: TmdbScrapeApi,
    ): Result<ResolvedTmdbSeason> {
        val existingMeta = repo.getOnlineMeta(libraryId, showPath, season.seasonNumber)
        val existingMapping = existingMeta?.tmdbEpisodeMapping
        val localEpisodeNumbers = season.episodes.mapNotNull { it.episode_number.toPositiveIntOrNull() }
        val existingMappingCompatible = existingMapping?.let {
            isTmdbEpisodeMappingCompatible(
                mapping = it,
                localSeasonNumber = season.seasonNumber,
                localEpisodeNumbers = localEpisodeNumbers,
                bangumiId = season.bangumiId,
                bangumiOffset = season.bangumiOffset,
                evidence = existingMeta.tmdbEpisodeMappingEvidence,
            )
        } == true
        if (existingMapping != null && !existingMappingCompatible) {
            // 仅坐标已明确变化时立即清除；网络失败不应因一次临时错误抹掉仍可能有效的映射。
            repo.updateOnlineMetaTmdbEpisodeMapping(libraryId, showPath, season.seasonNumber, mapping = null)
        }
        val reusableMapping = existingMapping?.takeIf { existingMappingCompatible }
        if (reusableMapping != null) {
            val existingImages = runSuspendCatching {
                tmdbApi.fetchSeasonImages(tmdbId, reusableMapping.seasonNumber)
            }
            val images = existingImages.getOrNull()
            if (images != null && reusableMapping.coversLocalEpisodes(localEpisodeNumbers, images)) {
                return Result.success(ResolvedTmdbSeason(reusableMapping, images))
            }
            if (existingImages.isSuccess) {
                // 响应成功但当前本地集号已无法落到该远端季，旧映射已失去证据，清除后重新定位。
                repo.updateOnlineMetaTmdbEpisodeMapping(libraryId, showPath, season.seasonNumber, mapping = null)
            } else {
                return existingImages.map { ResolvedTmdbSeason(reusableMapping, it) }
            }
        }

        val direct = runSuspendCatching { tmdbApi.fetchSeasonImages(tmdbId, season.seasonNumber) }
        direct.getOrNull()?.let { images ->
            if (season.bangumiOffset != 0 && season.bangumiId != null) {
                return runSuspendCatching {
                    val bangumiEvidence = bangumi.fetchEpisodeEvidence(season.bangumiId)
                    val mapping = selectTmdbMappingForAvailableSeason(
                        localSeasonNumber = season.seasonNumber,
                        localEpisodeNumbers = localEpisodeNumbers,
                        bangumiOffset = season.bangumiOffset,
                        bangumiEpisodes = bangumiEvidence,
                        tmdbEpisodes = images.episodes,
                    ) ?: error("Bangumi 与 TMDB 剧集证据不足，拒绝在同号季内猜测分段坐标")
                    repo.updateOnlineMetaTmdbEpisodeMapping(
                        libraryId = libraryId,
                        showPath = showPath,
                        seasonNumber = season.seasonNumber,
                        mapping = mapping,
                        evidence = tmdbMappingEvidence(season.bangumiId, season.bangumiOffset),
                    )
                    ResolvedTmdbSeason(mapping, images)
                }
            }
            return Result.success(ResolvedTmdbSeason(TmdbEpisodeMapping(season.seasonNumber, 0), images))
        }
        val directError = direct.exceptionOrNull() ?: IllegalStateException("TMDB 季度读取失败")
        if (!isTmdbMissingSeasonSignal(directError)) {
            return Result.failure(directError)
        }
        if (season.seasonNumber <= 1 || season.bangumiOffset == 0 || season.bangumiId == null) {
            return Result.failure(directError)
        }

        return runSuspendCatching {
            val bangumiEvidence = bangumi.fetchEpisodeEvidence(season.bangumiId)
            val mergedImages = tmdbApi.fetchSeasonImages(tmdbId, TMDB_MERGED_SEASON_NUMBER)
            val mapping = inferTmdbMergedSeasonMapping(
                localSeasonNumber = season.seasonNumber,
                localEpisodeNumbers = season.episodes.map { it.episode_number.toInt() },
                bangumiOffset = season.bangumiOffset,
                bangumiEpisodes = bangumiEvidence,
                tmdbSeasonNumber = TMDB_MERGED_SEASON_NUMBER,
                tmdbEpisodes = mergedImages.episodes,
            ) ?: error("Bangumi 与 TMDB 剧集证据不足，拒绝自动建立合并季映射")
            repo.updateOnlineMetaTmdbEpisodeMapping(
                libraryId = libraryId,
                showPath = showPath,
                seasonNumber = season.seasonNumber,
                mapping = mapping,
                evidence = tmdbMappingEvidence(season.bangumiId, season.bangumiOffset),
            )
            ResolvedTmdbSeason(mapping, mergedImages)
        }
    }

    private fun TmdbEpisodeMapping.coversLocalEpisodes(
        localEpisodeNumbers: List<Int>,
        images: TmdbSeasonImages,
    ): Boolean = localEpisodeNumbers.all { localEpisodeNumber ->
        // 映射内无对应集号(先行篇/第0话, 如本地 E1 - offset = 0)的集是 TMDB 之外的合法存在,
        // 复用校验只要求其余每集都仍落在远端季内。
        val remote = remoteEpisodeNumber(localEpisodeNumber.toLong())?.toInt()
        remote == null || images.episodes.any { it.episodeNumber == remote }
    }

    private fun ScrapedScrapeData.alignEpisodeCoordinates(
        season: LocalSeason,
        source: ScrapeSource,
    ): ScrapedScrapeData = copy(
        episodes = alignOnlineEpisodesToLocalSeason(
            episodes = episodes,
            localEpisodeNumbers = season.episodes.mapNotNull { episode ->
                episode.episode_number
                    .takeIf { it in 1L..Int.MAX_VALUE.toLong() }
                    ?.toInt()
            },
            bangumiOffset = season.bangumiOffset,
            preferShiftedOffset = source == ScrapeSource.DANDANPLAY || source == ScrapeSource.MANUAL_DANDANPLAY,
        ),
    )

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
        if (uniqueCandidateAutoApply && candidates.size == 1) {
            // 唯一结果放宽: 搜索仅返回一个候选且年份不冲突即接受, 即使标题书写不一致。
            val only = candidates.single()
            val candidateYear = only.firstAirDate?.take(4)?.toIntOrNull()
            return only.takeIf { yearCompatible(year, candidateYear) }
        }
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
        bangumiOffset: Int,
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
            bangumiOffset = bangumiOffset.toLong(),
        )
        val existing = runSuspendCatching {
            val localSeason = repo.listSeasons(show.id).firstOrNull { season ->
                season.season_number == seasonNumber.toLong() &&
                    season.bangumi_offset == bangumiOffset.toLong()
            }
            if (tmdbId != null && localSeason != null) {
                repo.getStoredBangumiSeasonLink(show.copy(tmdb_id = tmdbId), localSeason).link
            } else {
                repo.getBangumiSeasonLink(identityKey)
            }
        }.getOrNull()
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
        val scrapeTaskArbiter = ScrapeTaskArbiter()
        const val DEFAULT_INTERACTIVE_SEARCH_TIMEOUT_MS = 10_000L
        const val INTERACTIVE_IMAGE_CONCURRENCY = 5
        const val TMDB_MERGED_SEASON_NUMBER = 1
        /** 临时在线失败后的详情页自动重试冷却；手动在线刮削不受此限制。 */
        const val AUTO_RETRYABLE_FAILURE_COOLDOWN_MS = 15L * 60L * 1000L
        /** TMDB 集照"确认无"标记的抑制期: 连载番剧照可能晚于刮削生成, 超期后重新探测。 */
        const val TMDB_STILL_NEGATIVE_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

/** 只把明确缺季信号送入后续正向证据链；返回 true 本身不代表可以建立映射。 */
internal fun isTmdbMissingSeasonSignal(error: Throwable?): Boolean {
    val tmdbError = error as? TmdbApiException ?: return false
    return tmdbError.statusCode == 404 ||
        (tmdbError.statusCode == 502 && tmdbError.errorCode == "UPSTREAM_REJECTED")
}

/**
 * 在线源有的返回季内集号，有的返回跨季度连续 sort。Bangumi 条目内 ep 与本地分段编号同系,
 * 显式 offset 只在覆盖强于直连时生效(覆盖与有效信息完全相同时保守保留直连), 并裁掉不属于
 * 当前本地季的项。弹弹条目则无论季内还是全系列编号都满足 本地 = 条目集号 + 漂移
 * (实测 17236 季内/18086 全系列/15954 全系列三形态), 且先行篇形态下直连"全命中"是
 * 假象(弹弹第1话恰好压在本地 E1 的集号上, coverage 不看标题)——非零漂移且换算可命中时
 * 信任漂移声明([preferShiftedOffset]), coverage 仅作漂移不可用时的兜底。
 */
internal fun alignOnlineEpisodesToLocalSeason(
    episodes: List<ScrapedOnlineEpisode>,
    localEpisodeNumbers: List<Int>,
    bangumiOffset: Int,
    preferShiftedOffset: Boolean = false,
): List<ScrapedOnlineEpisode> {
    if (episodes.isEmpty()) return emptyList()
    val localNumbers = localEpisodeNumbers.filter { it > 0 }.toSet()
    if (localNumbers.isEmpty()) return emptyList()

    data class Coverage(val matched: Int, val informative: Int)

    fun coverage(offset: Int): Coverage {
        var matched = 0
        var informative = 0
        episodes.forEach { episode ->
            val mapped = episode.episodeNumber.toLong() + offset.toLong()
            if (mapped !in 1L..Int.MAX_VALUE.toLong() || mapped.toInt() !in localNumbers) return@forEach
            matched++
            if (!episode.title.isNullOrBlank() || !episode.aired.isNullOrBlank() || !episode.plot.isNullOrBlank()) {
                informative++
            }
        }
        return Coverage(matched, informative)
    }

    val direct = coverage(0)
    val shifted = coverage(bangumiOffset)
    val effectiveOffset = when {
        bangumiOffset != 0 && preferShiftedOffset && shifted.matched > 0 -> bangumiOffset
        bangumiOffset != 0 && shifted.matched > 0 &&
            (shifted.matched > direct.matched ||
                (shifted.matched == direct.matched && shifted.informative > direct.informative)) -> bangumiOffset
        else -> 0
    }

    return episodes.mapNotNull { episode ->
        val mapped = episode.episodeNumber.toLong() + effectiveOffset.toLong()
        val localNumber = mapped.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: return@mapNotNull null
        episode.copy(episodeNumber = localNumber).takeIf { localNumber in localNumbers }
    }.distinctBy { it.episodeNumber }
        .sortedBy { it.episodeNumber }
}

/**
 * 合并季纯函数护栏：Ani-RSS offset 必须把本地集映射到正的全系列集号，且当前已有的
 * 每个本地集都被 Bangumi sort 与 TMDB 目标季覆盖；至少两集的播出日或规范标题一致才返回映射。
 * 本地文件允许稀疏，RSS 漏集或用户删除中间集不应否定其余剧集的正向证据。
 */
internal fun inferTmdbMergedSeasonMapping(
    localSeasonNumber: Int,
    localEpisodeNumbers: List<Int>,
    bangumiOffset: Int,
    bangumiEpisodes: List<BangumiEpisodeEvidence>,
    tmdbSeasonNumber: Int,
    tmdbEpisodes: List<TmdbSeasonEpisode>,
): TmdbEpisodeMapping? {
    if (localSeasonNumber <= 1 || tmdbSeasonNumber != 1 || bangumiOffset == 0) return null
    val local = localEpisodeNumbers.distinct().sorted()
    if (local.isEmpty() || local.any { it <= 0 }) return null
    val mapping = TmdbEpisodeMapping(tmdbSeasonNumber, bangumiOffset)
    val score = tmdbMappingEvidenceScore(
        mapping = mapping,
        localEpisodeNumbers = local,
        bangumiEpisodes = bangumiEpisodes,
        tmdbEpisodes = tmdbEpisodes,
        bangumiCoordinate = BangumiEvidenceCoordinate.SORT,
    ) ?: return null
    return mapping.takeIf { score >= requiredTmdbMappingEvidence(local.size) }
}

/**
 * TMDB 同号季存在时同时比较两套坐标：本地季内编号与 Ani-RSS 连续 sort 漂移。
 * 只有一方达到正向证据阈值且不弱于另一方才采用；同分保守保留同号季内坐标。
 */
internal fun selectTmdbMappingForAvailableSeason(
    localSeasonNumber: Int,
    localEpisodeNumbers: List<Int>,
    bangumiOffset: Int,
    bangumiEpisodes: List<BangumiEpisodeEvidence>,
    tmdbEpisodes: List<TmdbSeasonEpisode>,
): TmdbEpisodeMapping? {
    if (localSeasonNumber <= 0 || bangumiOffset == 0) return null
    val local = localEpisodeNumbers.distinct().sorted()
    if (local.isEmpty() || local.any { it <= 0 }) return null
    val required = requiredTmdbMappingEvidence(local.size)
    val direct = TmdbEpisodeMapping(localSeasonNumber, 0)
    val shifted = TmdbEpisodeMapping(localSeasonNumber, bangumiOffset)
    val directScore = tmdbMappingEvidenceScore(
        mapping = direct,
        localEpisodeNumbers = local,
        bangumiEpisodes = bangumiEpisodes,
        tmdbEpisodes = tmdbEpisodes,
        bangumiCoordinate = BangumiEvidenceCoordinate.EPISODE,
    )
    val shiftedScore = tmdbMappingEvidenceScore(
        mapping = shifted,
        localEpisodeNumbers = local,
        bangumiEpisodes = bangumiEpisodes,
        tmdbEpisodes = tmdbEpisodes,
        bangumiCoordinate = BangumiEvidenceCoordinate.SORT,
    )
    return when {
        shiftedScore != null && shiftedScore >= required && shiftedScore > (directScore ?: -1) -> shifted
        directScore != null && directScore >= required -> direct
        else -> null
    }
}

private enum class BangumiEvidenceCoordinate { EPISODE, SORT }

private fun tmdbMappingEvidenceScore(
    mapping: TmdbEpisodeMapping,
    localEpisodeNumbers: List<Int>,
    bangumiEpisodes: List<BangumiEpisodeEvidence>,
    tmdbEpisodes: List<TmdbSeasonEpisode>,
    bangumiCoordinate: BangumiEvidenceCoordinate,
): Int? {
    val bangumiByNumber = when (bangumiCoordinate) {
        BangumiEvidenceCoordinate.EPISODE -> bangumiEpisodes.mapNotNull { evidence ->
            evidence.episodeNumber?.let { it to evidence }
        }.toMap()
        BangumiEvidenceCoordinate.SORT -> bangumiEpisodes.associateBy { it.sort }
    }
    val tmdbByEpisode = tmdbEpisodes.associateBy { it.episodeNumber }
    var matchedEvidence = 0
    localEpisodeNumbers.forEach { localEpisode ->
        val remote = mapping.remoteEpisodeNumber(localEpisode.toLong())
            ?.takeIf { it <= Int.MAX_VALUE.toLong() }
            ?.toInt()
        val bangumiNumber = when (bangumiCoordinate) {
            BangumiEvidenceCoordinate.EPISODE -> localEpisode
            BangumiEvidenceCoordinate.SORT -> remote
        }
        val bangumi = bangumiNumber?.let(bangumiByNumber::get)
        val tmdb = remote?.let(tmdbByEpisode::get)
        // 换算越界或任一边缺该集(先行篇/第0话在 TMDB 无对应、Bangumi 缺该 sort): 无从互证,
        // 跳过不否决, 由其余集的正向证据与阈值把关; 双边都有而标题/日期冲突仍在下方否决。
        if (remote == null || bangumi == null || tmdb == null) return@forEach
        val bangumiDate = bangumi.aired?.trim()?.takeIf(String::isNotEmpty)
        val tmdbDate = tmdb.airDate?.trim()?.takeIf(String::isNotEmpty)
        if (bangumiDate != null && tmdbDate != null) {
            if (!episodeEvidenceDatesMatch(bangumiDate, tmdbDate)) return null
            matchedEvidence++
        } else {
            val bangumiTitle = normalizeEpisodeEvidenceTitle(bangumi.title)
            val tmdbTitle = normalizeEpisodeEvidenceTitle(tmdb.name)
            if (bangumiTitle.isNotEmpty() && tmdbTitle.isNotEmpty() && bangumiTitle != tmdbTitle) return null
            if (bangumiTitle.isNotEmpty() && bangumiTitle == tmdbTitle) matchedEvidence++
        }
    }
    return matchedEvidence
}

private fun requiredTmdbMappingEvidence(episodeCount: Int): Int = if (episodeCount == 1) 1 else 2

/** 日本午夜档在数据源的时区归属可能相差一个自然日；只放宽这一日，更多偏差仍视为冲突。 */
private fun episodeEvidenceDatesMatch(first: String, second: String): Boolean {
    if (first == second) return true
    val firstDay = isoDateEpochDay(first) ?: return false
    val secondDay = isoDateEpochDay(second) ?: return false
    return kotlin.math.abs(firstDay - secondDay) <= 1L
}

/** 公历 YYYY-MM-DD 转相对天数；commonMain 不依赖 java.time。 */
private fun isoDateEpochDay(value: String): Long? {
    val match = ISO_DATE_PATTERN.matchEntire(value) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (month !in 1..12) return null
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val daysInMonth = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    if (day !in 1..daysInMonth) return null
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = if (adjustedYear >= 0) adjustedYear / 400 else (adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146097L + dayOfEra.toLong()
}

private val ISO_DATE_PATTERN = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

/**
 * 已落库映射只能在当前本地坐标仍满足建立映射时的前提下复用。
 * offset=0 的映射代表同号 TMDB 季；非零 offset 的映射代表漂移季(负=TMDB 合并季拆段,
 * 正=本地比 TMDB 多出先行篇/第0话等前置集)，必须保留后续本地季、非零 offset 与
 * Bangumi subject 这些结构条件。已验证映射允许本地文件稀疏；RSS 漏集或用户删除
 * 中间集时，由远端覆盖检查继续验证现存每一集。
 */
internal fun isTmdbEpisodeMappingCompatible(
    mapping: TmdbEpisodeMapping,
    localSeasonNumber: Int,
    localEpisodeNumbers: List<Int>,
    bangumiId: Long?,
    bangumiOffset: Int,
    evidence: TmdbEpisodeMappingEvidence? = null,
): Boolean {
    val local = localEpisodeNumbers.distinct().sorted()
    if (local.isEmpty() || local.any { it <= 0 }) return false
    val structurallyCompatible = if (mapping.episodeOffset == 0) {
        mapping.seasonNumber == localSeasonNumber
    } else {
            mapping.episodeOffset == bangumiOffset &&
            (mapping.seasonNumber == localSeasonNumber ||
                (localSeasonNumber > 1 && mapping.seasonNumber == 1)) &&
            bangumiOffset != 0 &&
            bangumiId != null
    }
    if (!structurallyCompatible) return false
    val requiresEvidence = mapping.episodeOffset != 0 || (bangumiId != null && bangumiOffset != 0)
    if (!requiresEvidence) return true
    val currentEvidence = evidence ?: return false
    return currentEvidence.bangumiSubjectId == bangumiId && currentEvidence.bangumiOffset == bangumiOffset
}

private fun tmdbMappingEvidence(
    bangumiId: Long?,
    bangumiOffset: Int,
): TmdbEpisodeMappingEvidence? {
    val subjectId = bangumiId?.takeIf { it > 0L } ?: return null
    return TmdbEpisodeMappingEvidence(
        bangumiSubjectId = subjectId,
        bangumiOffset = bangumiOffset,
    )
}

private fun Long.toPositiveIntOrNull(): Int? =
    takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()

private fun normalizeEpisodeEvidenceTitle(value: String?): String = value.orEmpty()
    .lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]"), "")

private class InteractiveSearchTimeoutException : RuntimeException("交互式在线来源搜索超时")

/** 懒触发/批量自动补刮重刮间隔: 24h(部级 meta 存在且在此间隔内不重复刮)。 */
internal const val SCRAPE_RETRY_INTERVAL_MS = 24L * 60L * 60L * 1000L
