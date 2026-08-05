package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.text.SafeRegex
import io.github.weiyongzenqi.unuplayer.domain.EpisodeNumberExtractor

/**
 * 匹配配置(从 [io.github.weiyongzenqi.unuplayer.domain.SettingsState] 映射)。
 *
 * @param tmdbIdQuickMatch tmdb 快速匹配开关(从 URL/文件名提取 tmdbId)
 * @param tmdbIdMatchPattern tmdbId 提取正则(第 1 捕获组为数字)
 * @param hashFallback 快速匹配失败时是否回落哈希匹配
 */
data class DanmakuMatchConfig(
    val tmdbIdQuickMatch: Boolean,
    val tmdbIdMatchPattern: String,
    val hashFallback: Boolean,
)

/**
 * 弹幕匹配协调器(参考 NipaPlay webdav_browser_page._playVideo + player_setup)。
 *
 * 策略(按序回落, 命中即返回, 结果带 [DanmakuMatchMethod] 供日志输出):
 * 1. **tmdb 快速匹配**(优先): 正则从 URL/文件名提取 tmdbId
 *    -> [DandanplayApi.searchEpisodesByTmdb](season 选 animeId)
 *    -> [DandanplayApi.bangumi] 拿剧集列表 -> 文件名集数([EpisodeNumberExtractor])匹配 episodeId
 * 2. **哈希匹配**(回落): 算文件前 16MB MD5 + fileSize
 *    -> [DandanplaySourceProvider.match](POST /api/v2/match, hashAndFileName)
 * 3. **文件名搜索回落**(最后手段, fd:// 等无法哈希的场景):
 *    -> [DandanplayApi.searchAnime](关键词=文件名) 取首结果
 *    -> [DandanplayApi.bangumi] -> 集数匹配 episodeId (参考 NipaPlay _tryMatchByFileNameFirstResult)
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
    ): DanmakuMatchResult? {
        matchByPriority(
            fileName = fileName,
            urlOrPath = urlOrPath,
            config = config,
            hashProvider = hashProvider,
            databaseTmdbId = databaseTmdbId,
            seasonHint = seasonHint,
            episodeHint = episodeHint,
        )?.let { return it }

        // 文件名搜索仍作为完整匹配 API 的最后回退；播放器按需只调用 matchByPriority，避免误命中。
        return matchByFileName(fileName)
    }

    /** 执行数据库 TMDB -> 路径 TMDB -> 哈希的播放器优先级，不包含文件名搜索。 */
    suspend fun matchByPriority(
        fileName: String,
        urlOrPath: String,
        config: DanmakuMatchConfig,
        hashProvider: (suspend () -> Pair<Long, String>?)? = null,
        databaseTmdbId: Long? = null,
        seasonHint: Int? = null,
        episodeHint: Int? = null,
    ): DanmakuMatchResult? {
        val season = seasonHint ?: EpisodeNumberExtractor.extractSeason(fileName)

        // 海报墙数据库提供的 ID 是结构化元数据，优先于路径中可能残留的旧 ID。
        databaseTmdbId?.takeIf { it > 0L }?.let { tmdbId ->
            matchByTmdb(
                tmdbId = tmdbId,
                fileName = fileName,
                season = season,
                episodeHint = episodeHint,
                matchMethod = DanmakuMatchMethod.TMDB_DATABASE,
            )?.let { return it }
        }

        if (config.tmdbIdQuickMatch) {
            val pathTmdbId = extractTmdbId(urlOrPath, config.tmdbIdMatchPattern)
            // 同一 ID 已经尝试过，避免重复请求；不同 ID 时数据库候选仍保持优先且不被覆盖。
            if (pathTmdbId != null && pathTmdbId != databaseTmdbId) {
                matchByTmdb(
                    tmdbId = pathTmdbId,
                    fileName = fileName,
                    season = season,
                    episodeHint = episodeHint,
                    matchMethod = DanmakuMatchMethod.TMDB_PATH,
                )?.let { return it }
            }
        }

        if (config.hashFallback && hashProvider != null) {
            hashProvider()?.let { (size, hash) ->
                sourceProvider.match(fileName, hash, size)?.let { return it }
            }
        }
        return null
    }

    /**
     * tmdb 快速匹配: search/episodes(season 选 animeId) -> bangumi -> 集数匹配 episodeId。
     *
     * @param episodeHint 集号权威提示(如媒体服务器 IndexNumber); 优先用它在 bangumi 剧集表里比对 episodeNumber,
     *    null 或未命中时回退 [EpisodeNumberExtractor.extractEpisode] 从 [fileName] 提取(现行行为, 兼容现有调用方)。
     */
    suspend fun matchByTmdb(
        tmdbId: Long,
        fileName: String,
        season: Int?,
        episodeHint: Int? = null,
        matchMethod: DanmakuMatchMethod = DanmakuMatchMethod.TMDB_QUICK,
    ): DanmakuMatchResult? = runSuspendCatching {
            val search = api.searchEpisodesByTmdb(tmdbId)
            // 多结果按 animeId 升序, 用 season 选第 N 个(NipaPlay: selectedIndex = season-1);
            // 无 season 或越界取第一个
            val animes = search.animes.sortedBy { it.animeId }
            val anime = if (season != null && season > 0) {
                animes.getOrNull(season - 1) ?: animes.firstOrNull()
            } else {
                animes.firstOrNull()
            } ?: return@runSuspendCatching null
            val ep = locateEpisode(anime.animeId, fileName, episodeHint) ?: return@runSuspendCatching null
            DanmakuMatchResult(
                episodeId = ep.episodeId,
                animeId = anime.animeId,
                animeTitle = anime.animeTitle,
                episodeTitle = ep.episodeTitle,
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
     */
    private suspend fun locateEpisode(
        animeId: Long,
        fileName: String,
        episodeHint: Int? = null,
    ): DandanplayEpisode? {
        val bangumi = api.bangumi(animeId)
        val episodes = bangumi.bangumi?.episodes ?: return null
        // 优先权威提示(季内序号, 与 bangumi episodeNumber 通常一致); 未命中再回退文件名提取。
        if (episodeHint != null) {
            episodes.firstOrNull { it.episodeNumber?.toIntOrNull() == episodeHint }?.let { return it }
        }
        val epNum = EpisodeNumberExtractor.extractEpisode(fileName) ?: return null
        // episodeNumber 是字符串(见 DandanplayEpisode), toIntOrNull 比较
        return episodes.firstOrNull { it.episodeNumber?.toIntOrNull() == epNum }
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
            var s = fileName.substringBeforeLast('.')                    // 去扩展名
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
    }
}
