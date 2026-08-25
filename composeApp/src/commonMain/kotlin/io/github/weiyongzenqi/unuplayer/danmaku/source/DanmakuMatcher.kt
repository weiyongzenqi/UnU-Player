package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.text.SafeRegex
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor

/**
 * 匹配配置(从 [io.github.weiyongzenqi.unuplayer.domain.SettingsState] 映射)。
 *
 * @param tmdbIdMatchPattern tmdbId 提取正则(第 1 捕获组为数字)
 * @param matchOrder 启用的匹配方式按优先级顺序(用户可在设置/本部专属设置自定义; 未列出=禁用)
 */
data class DanmakuMatchConfig(
    val tmdbIdMatchPattern: String,
    val matchOrder: List<DanmakuMatchMethod>,
)

/**
 * 存储的枚举名列表(全局设置 danmakuMatchPriority / 本部覆盖同名字段) -> 有序匹配方式。
 * 未知名忽略(前向兼容旧存值); 空结果是合法状态 = 用户显式全部禁用, 调用方不得回落默认。
 */
fun parseDanmakuMatchOrder(names: List<String>): List<DanmakuMatchMethod> =
    names.mapNotNull { name -> runCatching { DanmakuMatchMethod.valueOf(name) }.getOrNull() }

/**
 * 弹幕匹配协调器(参考 NipaPlay webdav_browser_page._playVideo + player_setup)。
 *
 * 策略(按 [DanmakuMatchConfig.matchOrder] 用户可配置顺序尝试, 命中即返回, 结果带
 * [DanmakuMatchMethod] 供日志输出; 播放器只走 [matchByPriority], 不包含文件名搜索):
 * - **TMDB_DATABASE**: 海报墙数据库/媒体服务器结构化 tmdbId
 *   -> [DandanplayApi.searchEpisodesByTmdb](season 选 animeId)
 *   -> [DandanplayApi.bangumi] 拿剧集列表 -> 文件名集数([EpisodeNumberExtractor])匹配 episodeId
 * - **TMDB_PATH**: 正则从 URL/文件名提取 tmdbId(与数据库 id 相同时跳过防重复请求), 同上走 tmdb 定位
 * - **HASH**: 算文件前 16MB MD5 + fileSize -> [DandanplaySourceProvider.match](POST /api/v2/match, hashAndFileName)
 * - **FILENAME_SEARCH**(仅 [match] 完整 API 的最后回退, 播放器禁用, 易误命中):
 *   -> [DandanplayApi.searchAnime](关键词=文件名) 取首结果
 *   -> [DandanplayApi.bangumi] -> 集数匹配 episodeId (参考 NipaPlay _tryMatchByFileNameFirstResult)
 *
 * 哈希计算由调用方注入 [hashProvider](本地 [calcDanmakuHash] / WebDAV Range GET),
 * 本类只做匹配逻辑 + API 调用(commonMain, 不碰文件 IO)。
 *
 * @param api 已注入凭证的弹弹play API
 * @param sourceProvider 弹弹play 源(复用其 match 实现)
 */
class DanmakuMatcher(
    private val api: DandanplayApi,
    private val sourceProvider: DandanplaySourceProvider = DandanplaySourceProvider(api),
) {

    /**
     * 匹配视频到 episodeId。
     *
     * @param fileName 文件名(含扩展名; 集数提取 + match fileName + 搜索关键词用)
     * @param urlOrPath 视频 URL 或本地路径(tmdbId 正则提取用)
     * @param config 匹配配置
     * @param hashProvider 悬空返回 (fileSize, fileHash); null=无法算哈希(跳过哈希回落)
     * @return 匹配结果(含 [DanmakuMatchResult.matchMethod]); null=三级都未匹配
     */
    suspend fun match(
        fileName: String,
        urlOrPath: String,
        config: DanmakuMatchConfig,
        hashProvider: (suspend () -> Pair<Long, String>?)? = null,
        databaseTmdbId: Long? = null,
        seasonHint: Int? = null,
        episodeHint: Int? = null,
        episodeOrdinalHint: Int? = null,
        bangumiEpisodeOffset: Long = 0L,
    ): DanmakuMatchResult? {
        matchByPriority(
            fileName = fileName,
            urlOrPath = urlOrPath,
            config = config,
            hashProvider = hashProvider,
            databaseTmdbId = databaseTmdbId,
            seasonHint = seasonHint,
            episodeHint = episodeHint,
            episodeOrdinalHint = episodeOrdinalHint,
            bangumiEpisodeOffset = bangumiEpisodeOffset,
        )?.let { return it }

        // 文件名搜索仍作为完整匹配 API 的最后回退；播放器按需只调用 matchByPriority，避免误命中。
        return matchByFileName(fileName)
    }

    /**
     * 按用户配置的 [DanmakuMatchConfig.matchOrder] 顺序执行匹配(命中即返回, 不包含文件名搜索)。
     * 可排序/可禁用的方式: TMDB_DATABASE(海报墙库/媒体服务器身份)、TMDB_PATH(路径正则提取)、HASH。
     */
    suspend fun matchByPriority(
        fileName: String,
        urlOrPath: String,
        config: DanmakuMatchConfig,
        hashProvider: (suspend () -> Pair<Long, String>?)? = null,
        databaseTmdbId: Long? = null,
        seasonHint: Int? = null,
        episodeHint: Int? = null,
        episodeOrdinalHint: Int? = null,
        bangumiEpisodeOffset: Long = 0L,
    ): DanmakuMatchResult? {
        val season = seasonHint ?: EpisodeNumberExtractor.extractSeason(fileName)
        for (method in config.matchOrder) {
            when (method) {
                DanmakuMatchMethod.TMDB_DATABASE -> {
                    // 海报墙数据库/媒体服务器提供的 ID 是结构化元数据。
                    databaseTmdbId?.takeIf { it > 0L }?.let { tmdbId ->
                        matchByTmdb(
                            tmdbId = tmdbId,
                            fileName = fileName,
                            season = season,
                            episodeHint = episodeHint,
                            episodeOrdinalHint = episodeOrdinalHint,
                            bangumiEpisodeOffset = bangumiEpisodeOffset,
                            matchMethod = DanmakuMatchMethod.TMDB_DATABASE,
                        )?.let { return it }
                    }
                }
                DanmakuMatchMethod.TMDB_PATH -> {
                    val pathTmdbId = extractTmdbId(urlOrPath, config.tmdbIdMatchPattern)
                    // 同一 ID 已由 TMDB_DATABASE 方式尝试过时才避免重复请求; 若用户把 DATABASE
                    // 移出/禁用, 路径标记是唯一剩余 TMDB 通道, 必须放行(否则该方式静默失效)。
                    val databaseWillTry = DanmakuMatchMethod.TMDB_DATABASE in config.matchOrder
                    if (pathTmdbId != null && (pathTmdbId != databaseTmdbId || !databaseWillTry)) {
                        matchByTmdb(
                            tmdbId = pathTmdbId,
                            fileName = fileName,
                            season = season,
                            episodeHint = episodeHint,
                            episodeOrdinalHint = episodeOrdinalHint,
                            bangumiEpisodeOffset = bangumiEpisodeOffset,
                            matchMethod = DanmakuMatchMethod.TMDB_PATH,
                        )?.let { return it }
                    }
                }
                DanmakuMatchMethod.HASH -> {
                    if (hashProvider != null) {
                        hashProvider()?.let { (size, hash) ->
                            sourceProvider.match(fileName, hash, size)?.let { return it }
                        }
                    }
                }
                else -> Unit // 其余方式不在播放器匹配管线内
            }
        }
        return null
    }

    /**
     * tmdb 快速匹配: search/episodes(season 选 animeId) -> bangumi -> 集数匹配 episodeId。
     *
     * @param episodeHint 集号权威提示(如媒体服务器 IndexNumber); 优先用它在 bangumi 剧集表里比对 episodeNumber,
     *    null 或未命中时回退 [EpisodeNumberExtractor.extractEpisode] 从 [fileName] 提取(现行行为, 兼容现有调用方)。
     * @param episodeOrdinalHint 本地季内顺序号; 分段番剧(Ani-RSS 拆多文件夹)的本地集号与弹弹条目的
     *    全系列连续编号(如第二季条目 episodeNumber=12..24)对不上时, 按条目内第 N 个正片定位。
     * @param bangumiEpisodeOffset bangumi.ini 声明的漂移(负值): 本地集号 - offset = 全系列集号,
     *    用于在弹弹连续编号条目里做值匹配; 仅在 [episodeHint] 与 [episodeOrdinalHint] 都未命中时尝试。
     */
    suspend fun matchByTmdb(
        tmdbId: Long,
        fileName: String,
        season: Int?,
        episodeHint: Int? = null,
        episodeOrdinalHint: Int? = null,
        bangumiEpisodeOffset: Long = 0L,
        matchMethod: DanmakuMatchMethod = DanmakuMatchMethod.TMDB_QUICK,
    ): DanmakuMatchResult? = runSuspendCatching {
            val search = api.searchEpisodesByTmdb(tmdbId)
            // animeId 不是季度序号。多候选只能接受标题明确标注的季度，禁止越界回退第一季。
            val anime = selectAnimeForSeason(search.animes, season)
            // 分段漂移时季号仲裁可能错选相邻分段条目, 主路径禁用文件名集数兜底
            // (文件名的季内集号恰好落在错条目里会假成功), 失败后交给覆盖校验兜底。
            val hasSeriesOffset = bangumiEpisodeOffset != 0L
            val located = anime?.let {
                locateEpisode(
                    animeId = it.animeId,
                    fileName = fileName,
                    episodeHint = episodeHint,
                    episodeOrdinalHint = episodeOrdinalHint,
                    bangumiEpisodeOffset = bangumiEpisodeOffset,
                    allowFileNameFallback = !hasSeriesOffset,
                )?.let { ep -> it to ep }
            } ?: locateEpisodeInSeriesCoveringCandidate(
                animes = search.animes,
                excludedAnimeId = anime?.animeId,
                episodeHint = episodeHint,
                bangumiEpisodeOffset = bangumiEpisodeOffset,
            )?.let { candidate ->
                // 兜底条目按全系列集号空间唯一性确认过, 集内定位允许反向换算(库全局号 -> 条目内号),
                // 但不再使用可能错系的顺序号。
                locateEpisode(
                    animeId = candidate.animeId,
                    fileName = fileName,
                    episodeHint = episodeHint,
                    bangumiEpisodeOffset = bangumiEpisodeOffset,
                    allowReverseOffset = true,
                )?.let { ep -> candidate to ep }
            }
            val target = located ?: return@runSuspendCatching null
            DanmakuMatchResult(
                episodeId = target.second.episodeId,
                animeId = target.first.animeId,
                animeTitle = target.first.animeTitle,
                episodeTitle = target.second.episodeTitle,
                shift = 0,
                matchMethod = matchMethod,
            )
        }.getOrNull()

    /**
     * 分段漂移兜底: 季号/顺序号都没能定位时, 用集号在全系列集号空间里找唯一覆盖的弹弹条目。
     * 库集号与条目编号存在三种形态组合: 原值直配(库全系列+条目全系列)、正向换算
     * (本地 E4 - offset = 全系列, 条目全系列编号)、反向换算(本地 E15 + offset = 条目内,
     * 条目分段编号)。三形态各自要求唯一命中; **所有唯一命中必须指向同一条目才采用**,
     * 出现分歧说明单点集号无法判定库形态, 安全失败交给手动匹配/哈希兜底,
     * 宁可不匹配也不错配相邻分段。
     */
    private suspend fun locateEpisodeInSeriesCoveringCandidate(
        animes: List<DandanplayAnime>,
        excludedAnimeId: Long?,
        episodeHint: Int?,
        bangumiEpisodeOffset: Long,
    ): DandanplayAnime? {
        if (episodeHint == null || bangumiEpisodeOffset == 0L) return null
        val offset = bangumiEpisodeOffset.toInt()
        val probes = listOf(episodeHint, episodeHint - offset, episodeHint + offset)
            .filter { it > 0 }
            .distinct()
        if (probes.isEmpty()) return null
        // 单遍历取每个候选的编号集, 同时累计三个探针值的命中者, 避免逐探针重复请求详情。
        val coveringByProbe = mutableMapOf<Int, DandanplayAnime>()
        val coveringCountByProbe = mutableMapOf<Int, Int>()
        for (candidate in animes) {
            if (candidate.animeId <= 0L || candidate.animeId == excludedAnimeId) continue
            val episodes = runSuspendCatching { api.bangumi(candidate.animeId).bangumi?.episodes }
                .getOrNull() ?: continue
            val numbers = episodes.mapNotNull { it.episodeNumber?.toIntOrNull()?.takeIf { n -> n > 0 } }.toSet()
            if (numbers.isEmpty()) continue
            probes.forEach { probe ->
                if (probe in numbers) {
                    if (coveringByProbe.putIfAbsent(probe, candidate) != null) {
                        coveringCountByProbe[probe] = (coveringCountByProbe[probe] ?: 1) + 1
                    }
                }
            }
        }
        val uniqueHits = probes.mapNotNull { probe ->
            if ((coveringCountByProbe[probe] ?: 1) == 1) coveringByProbe[probe] else null
        }
        val distinct = uniqueHits.distinctBy { it.animeId }
        return distinct.singleOrNull()
    }

    /**
     * 已知可信 Bangumi subject 时解析弹弹 animeId。
     *
     * 弹弹 API 的 `bangumiId` 字段实测为条目自身 animeId(非 bgm.tv subject id), 无法直接桥接;
     * 策略: 若个别条目确有真桥(bangumiId==subjectId)唯一则直接采用, 否则关键词搜索候选 ->
     * 按季号唯一仲裁([selectAnimeForSeason] 同规则)。同名番的其它季条目不会进入结果;
     * 季号缺失或无法唯一仲裁时返回 null(保守放弃)。
     */
    suspend fun resolveAnimeIdByBangumiSubject(
        subjectId: Long,
        keywords: List<String>,
        seasonHint: Int? = null,
    ): Long? = runSuspendCatching {
        if (subjectId <= 0L) return@runSuspendCatching null
        val candidates = linkedMapOf<Long, DandanplayAnimeSummary>()
        keywords.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_BANGUMI_SEARCH_KEYWORDS)
            .forEach { keyword ->
                api.searchAnime(keyword).animes
                    .asSequence()
                    .filter { it.animeId > 0L }
                    .forEach { anime -> candidates.putIfAbsent(anime.animeId, anime) }
            }
        if (candidates.isEmpty()) return@runSuspendCatching null
        // 真桥防御: 个别条目若确有 bangumiId==subjectId 的精确桥(唯一)则直接采用,
        // 不进入标题季标仲裁, 防止同名番误配。
        val bridged = candidates.values.filter { it.bangumiId?.toLongOrNull() == subjectId }
        if (bridged.size == 1) return@runSuspendCatching bridged.single().animeId
        val forArbitration = candidates.values.map { anime ->
            DandanplayAnime(animeId = anime.animeId, animeTitle = anime.animeTitle, type = anime.type)
        }
        selectAnimeForSeason(forArbitration, seasonHint)?.animeId
    }.getOrNull()

    /**
     * 已确认弹弹季度身份后的精确集定位，不再经过 TMDB 多候选推断。
     * [episodeOrdinalHint] 是当前季度内的第 N 集，优先于可能采用全系列连续标签的 [episodeHint]。
     */
    suspend fun matchByAnimeId(
        animeId: Long,
        fileName: String,
        episodeHint: Int? = null,
        episodeOrdinalHint: Int? = null,
        bangumiEpisodeOffset: Long = 0L,
        matchMethod: DanmakuMatchMethod = DanmakuMatchMethod.DANDANPLAY_DATABASE,
    ): DanmakuMatchResult? = runSuspendCatching {
        if (animeId <= 0L) return@runSuspendCatching null
        val bangumi = api.bangumi(animeId).bangumi ?: return@runSuspendCatching null
        if (bangumi.animeId > 0L && bangumi.animeId != animeId) return@runSuspendCatching null
        // 条目身份可能来自季号仲裁(存在错选相邻分段的窗口), 分段漂移时禁用文件名集数
        // 兜底(文件名的季内集号恰好落在错条目里会假成功), 返回 null 交由调用方回落完整链。
        val episode = locateEpisode(
            episodes = bangumi.episodes,
            fileName = fileName,
            episodeHint = episodeHint,
            episodeOrdinalHint = episodeOrdinalHint,
            bangumiEpisodeOffset = bangumiEpisodeOffset,
            allowFileNameFallback = bangumiEpisodeOffset == 0L,
        ) ?: return@runSuspendCatching null
        DanmakuMatchResult(
            episodeId = episode.episodeId,
            animeId = animeId,
            animeTitle = bangumi.animeTitle,
            episodeTitle = episode.episodeTitle,
            shift = 0,
            matchMethod = matchMethod,
        )
    }.getOrNull()

    /**
     * 文件名搜索回落: 清洗关键词 -> search/anime 取首结果 -> bangumi -> 集数匹配 episodeId。
     * 参考 NipaPlay _tryMatchByFileNameFirstResult。
     */
    private suspend fun matchByFileName(fileName: String): DanmakuMatchResult? = runSuspendCatching {
        val keyword = cleanSearchKeyword(fileName)
        if (keyword.isBlank()) return@runSuspendCatching null
        val search = api.searchAnime(keyword)
        val anime = search.animes.firstOrNull() ?: return@runSuspendCatching null
        val ep = locateEpisode(anime.animeId, fileName) ?: return@runSuspendCatching null
        DanmakuMatchResult(
            episodeId = ep.episodeId,
            animeId = anime.animeId,
            animeTitle = anime.animeTitle,
            episodeTitle = ep.episodeTitle,
            shift = 0,
            matchMethod = DanmakuMatchMethod.FILENAME_SEARCH,
        )
    }.getOrNull()

    /**
     * 取番剧剧集列表(bangumi), 用文件名集数([EpisodeNumberExtractor])或权威 [episodeHint] 定位 episodeId。
     * tmdb 快速匹配 / 文件名搜索回落共用。
     *
     * @param episodeHint 优先比对值(媒体服务器 IndexNumber 等); null 或未命中回退 [EpisodeNumberExtractor.extractEpisode]。
     * @param allowReverseOffset 允许"库集号 + offset"的反向换算; 只在兜底候选(已按集号覆盖唯一性
     *    确认)里开启 —— 主路径条目未经覆盖校验, 反向换算会在错选的相邻分段里假成功。
     * @param allowFileNameFallback 允许文件名集数兜底。分段漂移(非零 offset)时季号仲裁可能错选
     *    相邻分段条目, 文件名提取的季内集号恰好落在该条目里会假成功(如本地 E9 命中第一季第 9 话),
     *    因此主路径禁用, 只在覆盖校验后的兜底条目里使用。
     */
    private suspend fun locateEpisode(
        animeId: Long,
        fileName: String,
        episodeHint: Int? = null,
        episodeOrdinalHint: Int? = null,
        bangumiEpisodeOffset: Long = 0L,
        allowReverseOffset: Boolean = false,
        allowFileNameFallback: Boolean = true,
    ): DandanplayEpisode? {
        val bangumi = api.bangumi(animeId)
        val episodes = bangumi.bangumi?.episodes ?: return null
        return locateEpisode(
            episodes,
            fileName,
            episodeHint,
            episodeOrdinalHint,
            bangumiEpisodeOffset,
            allowReverseOffset,
            allowFileNameFallback,
        )
    }

    /**
     * 集内定位优先级: 值匹配(episodeHint, 对分段与全系列编号条目都先试原值) -> 正向 offset
     * 换算(本地分段号 - offset = 全系列) -> 条目内顺序号(编号连续时) -> 反向 offset 换算
     * (仅覆盖校验后的兜底条目) -> 文件名提取。顺序号不能先于值匹配: 库集号为全系列
     * 编号时顺序号语义错位(本地 E12 会取到条目内第 12 个而不是 ep 12)。
     */
    private fun locateEpisode(
        episodes: List<DandanplayEpisode>,
        fileName: String,
        episodeHint: Int? = null,
        episodeOrdinalHint: Int? = null,
        bangumiEpisodeOffset: Long = 0L,
        allowReverseOffset: Boolean = false,
        allowFileNameFallback: Boolean = true,
    ): DandanplayEpisode? {
        // 权威提示值(季内序号或全系列号, 与 bangumi episodeNumber 通常一致)。
        if (episodeHint != null) {
            episodes.firstOrNull { it.episodeNumber?.toIntOrNull() == episodeHint }?.let { return it }
        }
        // 分段番剧的 offset 通道: 本地分段集号 - offset = 全系列集号(本地 E4 - (-11) = 15),
        // 覆盖弹弹把条目 episodeNumber 标为全系列连续号(如 12..24)的数据形态。
        val offset = bangumiEpisodeOffset.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        if (offset != null && offset != 0 && episodeHint != null) {
            (episodeHint - offset).takeIf { it > 0 }?.let { seriesNumber ->
                episodes.firstOrNull { it.episodeNumber?.toIntOrNull() == seriesNumber }?.let { return it }
            }
        }
        // 条目内顺序号: 已确认 animeId 代表一个具体季度时, 本地 E 是该季度的顺序号。
        // 弹弹有些季度仍把 episodeNumber 标为全系列连续号（如第二季条目 12..24），按值比较
        // 会把本地 E12 错配到首集, 顺序号在编号连续时兜底。
        if (episodeOrdinalHint != null) {
            locateEpisodeByOrdinal(episodes, episodeOrdinalHint)?.let { return it }
        }
        if (offset != null && offset != 0 && episodeHint != null && allowReverseOffset) {
            // 反向: 库集号为全系列编号(本地 E15)+offset = 条目内集号(4), 仅限兜底路径使用。
            (episodeHint + offset).takeIf { it > 0 }?.let { withinNumber ->
                episodes.firstOrNull { it.episodeNumber?.toIntOrNull() == withinNumber }?.let { return it }
            }
        }
        if (!allowFileNameFallback) return null
        val epNum = EpisodeNumberExtractor.extractEpisode(fileName) ?: return null
        // episodeNumber 是字符串(见 DandanplayEpisode), toIntOrNull 比较
        return episodes.firstOrNull { it.episodeNumber?.toIntOrNull() == epNum }
    }

    /** 条目内顺序号定位: 仅当正片编号连续无洞时有效, 有洞(连载缺口/特殊编号)拒绝猜测返回 null。 */
    private fun locateEpisodeByOrdinal(episodes: List<DandanplayEpisode>, ordinal: Int): DandanplayEpisode? {
        if (ordinal <= 0) return null
        val numbered = episodes.mapNotNull { episode ->
            episode.episodeNumber?.toIntOrNull()?.takeIf { it > 0 }?.let { it to episode }
        }.sortedBy { it.first }
        if (numbered.zipWithNext().any { (first, second) ->
                second.first.toLong() != first.first.toLong() + 1L
            }
        ) {
            return null
        }
        return numbered.getOrNull(ordinal - 1)?.second
    }

    /** 从 URL/路径用正则提取 tmdbId。取最后一个非空捕获组(NipaPlay 用 lastGroup), 解析为 Long。 */
    fun extractTmdbId(urlOrPath: String, pattern: String): Long? = runCatching {
        // D-V04: 用户表达式只交给线性时间引擎；长度限制同时约束编译与匹配成本。
        if (pattern.length > TMDB_PATTERN_MAX_LENGTH) return@runCatching null
        val regex = SafeRegex(pattern)
        val match = regex.find(urlOrPath.take(TMDB_INPUT_MAX_LENGTH)) ?: return@runCatching null
        match.groupValues.drop(1).lastOrNull { it.isNotEmpty() }?.toLongOrNull()
    }.getOrNull()

    companion object {
        /** ID 匹配正则长度上限；与线性时间引擎共同约束不可信表达式的资源消耗。 */
        const val TMDB_PATTERN_MAX_LENGTH = 64

        /** tmdbId 提取的输入长度上限(D-V04 ReDoS 兜底): tmdbId 在名/路径首段, 截断无损功能。 */
        private const val TMDB_INPUT_MAX_LENGTH = 256

        private const val MAX_BANGUMI_SEARCH_KEYWORDS = 3

        internal fun selectAnimeForSeason(
            animes: List<DandanplayAnime>,
            season: Int?,
        ): DandanplayAnime? {
            val valid = animes.filter { it.animeId > 0L }
            if (valid.size == 1) {
                val only = valid.single()
                val targetSeason = season?.takeIf { it > 0 } ?: return only
                val explicitSeason = extractExplicitSeason(only.animeTitle)
                return when {
                    explicitSeason != null -> only.takeIf { explicitSeason == targetSeason }
                    targetSeason == 1 -> only
                    else -> null
                }
            }
            val targetSeason = season?.takeIf { it > 0 } ?: return null
            val exact = valid.filter { extractExplicitSeason(it.animeTitle) == targetSeason }
            if (exact.size == 1) return exact.single()
            if (exact.isNotEmpty() || targetSeason != 1) return null

            // 第一季标题常不带季度；只有唯一无季标候选，且其余候选全都明确是后续季时才可采用。
            val unmarked = valid.filter { extractExplicitSeason(it.animeTitle) == null }
            return unmarked.singleOrNull()?.takeIf { firstSeason ->
                valid.asSequence()
                    .filterNot { candidate -> candidate === firstSeason }
                    .mapNotNull { candidate -> extractExplicitSeason(candidate.animeTitle) }
                    .all { candidateSeason -> candidateSeason > 1 }
            }
        }

        /**
         * 从弹弹条目标题解析明确季标(阿拉伯/中文数字, Season/Nth/第N季期形态)。
         * 刮削层(mapDandanSeasons)与弹幕匹配共用同一实现, 防止两处规则漂移后
         * 对同一标题解析出不同季号。
         */
        fun extractExplicitSeason(title: String): Int? {
            val patterns = listOf(
                Regex("(?i)\\bseason\\s*[-_. ]*(\\d{1,2})\\b"),
                Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b"),
                Regex("(?i)(?:^|[\\s._-])s(\\d{1,2})(?:$|[\\s._-])"),
                Regex("第\\s*(\\d{1,2})\\s*[季期]"),
            )
            patterns.asSequence()
                .mapNotNull { pattern -> pattern.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                .firstOrNull { it > 0 }
                ?.let { return it }
            val chinese = Regex("第\\s*([一二三四五六七八九十两]{1,3})\\s*[季期]")
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
            chinese?.let(::parseChineseSeason)?.let { return it }
            // 未锚定 S2 兜底(刮削层历史模式): CJK 紧贴的"S2"(如"転生史S2")没有词边界,
            // 锚定模式会漏; 放在全部模式之后, 只在前面都未命中时生效。
            return Regex("(?i)\\bs\\s*(\\d{1,2})\\b").find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        }

        private fun parseChineseSeason(value: String): Int? {
            fun digit(char: Char): Int? = when (char) {
                '一' -> 1
                '二', '两' -> 2
                '三' -> 3
                '四' -> 4
                '五' -> 5
                '六' -> 6
                '七' -> 7
                '八' -> 8
                '九' -> 9
                else -> null
            }
            val tenIndex = value.indexOf('十')
            return if (tenIndex >= 0) {
                val tens = value.getOrNull(tenIndex - 1)?.let(::digit) ?: 1
                val ones = value.getOrNull(tenIndex + 1)?.let(::digit) ?: 0
                (tens * 10 + ones).takeIf { it in 1..99 }
            } else {
                value.singleOrNull()?.let(::digit)
            }
        }

        /**
         * 设置保存处用的 ID 正则校验(D-V04): 长度 ≤ [TMDB_PATTERN_MAX_LENGTH] 且可由线性时间引擎编译。
         * 无效时调用方不得落库, 并给用户可见提示。不打日志(pattern 为用户输入, 脱敏意识)。
         */
        fun isValidIdMatchPattern(pattern: String): Boolean {
            if (pattern.length > TMDB_PATTERN_MAX_LENGTH) return false
            return runCatching { SafeRegex(pattern) }.isSuccess
        }

        fun isValidTmdbMatchPattern(pattern: String): Boolean = isValidIdMatchPattern(pattern)

        /**
         * 清洗文件名 -> 搜索关键词(提高 search/anime 命中率)。
         * 去: 扩展名 / [发行组]【括号】 / SxxExx / EPxx / 第x话 / 分辨率(1080p 等) / 集数后的副标题。
         * 保留: 番剧标题主体。例: "[LoliHouse] 义妹生活 S01E03-反射与修正.mkv" -> "义妹生活"。
         *
         * 手动匹配对话框预填关键词也复用此函数(故提到 companion public)。
         */
        fun cleanSearchKeyword(fileName: String): String {
            // 仅当末段点号后是已知媒体扩展名时才去扩展名(本函数同时服务视频文件名与刮削文件夹名:
            // 文件夹名如 "Attack.on.Titan" 无扩展名, substringBeforeLast('.') 会把标题末段截掉)。
            var s = if (lastSegmentIsMediaExtension(fileName)) fileName.substringBeforeLast('.') else fileName
            s = Regex("\\[[^\\]]*\\]").replace(s, " ")                   // [LoliHouse] 等
            s = Regex("【[^】]*】").replace(s, " ")                        // 【】
            s = Regex("(?i)\\d{3,4}p|\\b4k\\b|\\b\\d{2,3}fps\\b").replace(s, " ")  // 1080p/4k/60fps
            // 取集数标记前的部分(标题主体); 标记: S01E03 / EP03 / 第3话 / - 03 / 空格03
            val epMarker = Regex("(?i)S\\d{1,2}\\s*E\\d{1,3}|(?i)\\bEP?\\s*\\d{1,3}\\b|第\\s*\\d{1,3}\\s*[话話集]|[-\\s]\\d{1,3}\\b")
            val cut = epMarker.find(s)?.range?.first ?: s.length
            s = s.substring(0, cut)
            s = s.replace(Regex("[-_·]"), " ").replace(Regex("\\s+"), " ").trim()
            return if (s.length > 40) s.take(40).trim() else s
        }

        private fun lastSegmentIsMediaExtension(name: String): Boolean {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return false
            return name.substring(dot + 1).lowercase() in MEDIA_EXTENSIONS
        }

        private val MEDIA_EXTENSIONS = setOf(
            "mkv", "mp4", "avi", "ts", "m2ts", "webm", "flv", "mov", "wmv", "rmvb", "rm", "m4v", "mpg", "mpeg", "3gp",
        )
    }
}
