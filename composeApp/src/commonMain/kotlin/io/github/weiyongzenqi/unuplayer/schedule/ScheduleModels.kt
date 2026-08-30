package io.github.weiyongzenqi.unuplayer.schedule

data class ScheduleWatch(
    val subjectId: Long,
    val title: String,
    val airWeekday: Int,
    val animeId: Long?,
    val tmdbId: Long?,
    val watchedAt: Long,
    val status: ScheduleStatus = ScheduleStatus.WANT,
    /** 跨设备标记同步的逻辑版本；本地写入由仓库在事务内递增。 */
    val syncVersion: Long = 0L,
)

/** 取消标记墓碑；没有墓碑时旧设备快照会把已删除条目重新带回。 */
data class ScheduleWatchDeletion(
    val subjectId: Long,
    val deletedAt: Long,
    val syncVersion: Long,
)

internal fun newerScheduleWatch(current: ScheduleWatch?, candidate: ScheduleWatch): ScheduleWatch = when {
    current == null -> candidate
    candidate.syncVersion > current.syncVersion -> candidate
    candidate.syncVersion < current.syncVersion -> current
    candidate.watchedAt > current.watchedAt -> candidate
    candidate.watchedAt < current.watchedAt -> current
    scheduleWatchStableValue(candidate) > scheduleWatchStableValue(current) -> candidate
    else -> current
}

internal fun newerScheduleWatchDeletion(
    current: ScheduleWatchDeletion?,
    candidate: ScheduleWatchDeletion,
): ScheduleWatchDeletion = when {
    current == null -> candidate
    candidate.syncVersion > current.syncVersion -> candidate
    candidate.syncVersion < current.syncVersion -> current
    candidate.deletedAt > current.deletedAt -> candidate
    else -> current
}

private fun scheduleWatchStableValue(watch: ScheduleWatch): String = buildString {
    append(watch.status.name).append('\u0000').append(watch.title).append('\u0000')
    append(watch.airWeekday).append('\u0000').append(watch.animeId ?: 0L).append('\u0000')
    append(watch.tmdbId ?: 0L)
}

/** 时间表条目的本地观看状态；NONE 表示未加入用户列表。 */
enum class ScheduleStatus(val label: String) {
    NONE("未标记"),
    WANT("想看"),
    WATCHING("在看"),
    DROPPED("丢弃"),
}

/**
 * 把一次有效搜索收敛进最近记录。记录按最近使用优先、忽略大小写去重，并限制数量，
 * 避免设置快照随着长期使用无界增长。
 */
fun updateScheduleSearchHistory(
    history: List<String>,
    query: String,
    limit: Int = 12,
): List<String> {
    if (limit <= 0) return emptyList()
    val normalized = query.replace('\n', ' ').replace('\r', ' ').trim().take(120)
    if (normalized.isEmpty()) return history.map(String::trim).filter(String::isNotEmpty).take(limit)
    return buildList {
        add(normalized)
        history.asSequence()
            .map { it.replace('\n', ' ').replace('\r', ' ').trim().take(120) }
            .filter { it.isNotEmpty() && !it.equals(normalized, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(limit - 1)
            .forEach(::add)
    }
}

enum class ScheduleLibraryMatchSource { PERSISTED, SCANNED, TMDB, DANDANPLAY, TITLE_HINT }

data class ScheduleLibraryMatch(
    val subjectId: Long? = null,
    val animeId: Long? = null,
    val tmdbId: Long? = null,
    val showId: Long,
    val libraryId: Long,
    val seasonNumber: Int? = null,
    val bangumiOffset: Long = 0L,
    val localTitle: String,
    val source: ScheduleLibraryMatchSource,
) {
    val confirmed: Boolean get() = source != ScheduleLibraryMatchSource.TITLE_HINT
}

data class ScheduleEntry(
    val subjectId: Long,
    val title: String,
    val originalTitle: String?,
    val weekday: Int,
    val broadcastTime: String?,
    val airDate: String?,
    val posterUrl: String?,
    val rating: Double?,
    val rank: Int?,
    val watchingCount: Int?,
    val animeId: Long?,
    val tmdbId: Long?,
    val libraryMatch: ScheduleLibraryMatch?,
    val watched: Boolean,
    val status: ScheduleStatus = if (watched) ScheduleStatus.WANT else ScheduleStatus.NONE,
    /** 发行平台(历史季度条目填充: TV/WEB/OVA/剧场/其他), 供剧场版过滤与类型徽章; 周表条目为 null。 */
    val platform: String? = null,
) {
    /** Bangumi 动画大类里的剧场版电影(platform="剧场"), 时间表可按设置隐藏。 */
    val isTheatrical: Boolean get() = platform == SCHEDULE_THEATRICAL_PLATFORM
}

/** Bangumi 平台枚举中的剧场版标识(中文原值)。 */
const val SCHEDULE_THEATRICAL_PLATFORM = "剧场"

data class ScheduleSnapshot(
    val entries: List<ScheduleEntry>,
    val refreshedAt: Long,
    val partialWarnings: List<String> = emptyList(),
) {
    fun forWeekday(weekday: Int): List<ScheduleEntry> = entries.filter { it.weekday == weekday }
}

/**
 * 历史季度快照(网关 /sn 聚合): entries 已按 weekday/在看人数排序并关联库匹配与观看标记。
 * truncated = 网关侧某月数据被单月 200 条封顶截断(正常年份不触发); total 为网关聚合的原始条数。
 */
data class ScheduleSeasonSnapshot(
    val year: Int,
    val quarterMonth: Int,
    val entries: List<ScheduleEntry>,
    val total: Int,
    val truncated: Boolean,
    val refreshedAt: Long,
) {
    fun forWeekday(weekday: Int): List<ScheduleEntry> = entries.filter { it.weekday == weekday }
}

/**
 * ISO 日期(YYYY-MM-DD)按字面日历日推 Bangumi 星期约定(1..7 = 周一..周日); 非法输入返回 null。
 * Sakamoto 算法纯 common 实现, 不引平台日期依赖; 锚点由单测固定(2025-07-06=周日)。
 */
fun isoDateToScheduleWeekday(date: String): Int? {
    val match = SCHEDULE_ISO_DATE.find(date) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null
    val monthOffsets = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    val effectiveYear = if (month < 3) year - 1 else year
    val sundayBased = (effectiveYear + effectiveYear / 4 - effectiveYear / 100 + effectiveYear / 400 + monthOffsets[month - 1] + day) % 7
    return ((sundayBased + 6) % 7) + 1
}

private val SCHEDULE_ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

/**
 * 把 Bangumi 在线剧集序号反查为扫描库中的本地集号。
 *
 * bangumi.ini 的 offset 只用于本地集与跨分段连续 sort/TMDB 坐标：
 * `Bangumi sort = local episode number - offset`，因此反查本地播放集时必须相加。
 * 单集评论属于当前 Bangumi subject，另按 [scheduleBangumiSeasonEpisodeNumber] 解析季内 ep。
 * 半集、特别篇等非整数 sort 没有稳定的 Long 型本地集号，不做猜测映射。
 */
fun scheduleLocalEpisodeNumber(bangumiSort: Double, bangumiOffset: Long): Long? {
    // Long.MAX_VALUE 本身无法由 Double 精确表示，其 toDouble() 已经舍入为 2^63，必须一并拒绝。
    if (!bangumiSort.isFinite() || bangumiSort <= 0.0 || bangumiSort >= Long.MAX_VALUE.toDouble()) return null
    val bangumiEpisodeNumber = bangumiSort.toLong()
    if (bangumiEpisodeNumber.toDouble() != bangumiSort) return null
    if (bangumiOffset > 0L && bangumiEpisodeNumber > Long.MAX_VALUE - bangumiOffset) return null
    if (bangumiOffset < 0L && bangumiEpisodeNumber < Long.MIN_VALUE - bangumiOffset) return null
    return (bangumiEpisodeNumber + bangumiOffset).takeIf { it > 0L }
}

/** 当前 Bangumi subject 内的季内集号；评论定位不得使用 sort 或 Ani-RSS offset 回退。 */
fun scheduleBangumiSeasonEpisodeNumber(bangumiEpisode: Double?): Long? {
    val value = bangumiEpisode ?: return null
    if (!value.isFinite() || value <= 0.0 || value >= Long.MAX_VALUE.toDouble()) return null
    val episodeNumber = value.toLong()
    return episodeNumber.takeIf { it.toDouble() == value }
}

/** 纯在线条目没有本地 offset 时，只把 Bangumi 正整数正片序号映射到 TMDB 集号。 */
fun scheduleTmdbEpisodeNumber(bangumiSort: Double): Int? {
    if (!bangumiSort.isFinite() || bangumiSort <= 0.0 || bangumiSort > Int.MAX_VALUE.toDouble()) return null
    val episodeNumber = bangumiSort.toInt()
    return episodeNumber.takeIf { it.toDouble() == bangumiSort }
}

/**
 * 已确认本地季度存在独立 TMDB 集映射时，把 Bangumi 坐标先还原为本地集号，再映射到 TMDB。
 *
 * Bangumi 的 `sort` 可能是跨分段连续编号，`ep` 通常是当前 subject 内的季内编号，但部分响应会
 * 把 `ep` 也填成连续编号；两者都存在时，`ep` 必须等于还原后的本地集号或原始 `sort`，其它值拒绝。
 */
fun scheduleMappedTmdbEpisodeNumber(
    bangumiSort: Double,
    bangumiEpisode: Double?,
    bangumiOffset: Long,
    tmdbEpisodeOffset: Int,
): Int? {
    val localFromSort = scheduleLocalEpisodeNumber(bangumiSort, bangumiOffset)
    val episodeCoordinate = bangumiEpisode?.let(::scheduleTmdbEpisodeNumber)?.toLong()
    val sortCoordinate = scheduleTmdbEpisodeNumber(bangumiSort)?.toLong()
    val localEpisode = when {
        localFromSort != null && episodeCoordinate != null &&
            episodeCoordinate != localFromSort && episodeCoordinate != sortCoordinate -> return null
        localFromSort != null -> localFromSort
        episodeCoordinate != null -> episodeCoordinate
        else -> return null
    }
    val remoteEpisode = localEpisode - tmdbEpisodeOffset.toLong()
    return remoteEpisode.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
}

/**
 * 时间表补充源给出的 TMDB 身份在覆盖 Bangumi 海报/简介前必须通过最小交叉校验。
 * 已确认的本地库身份直接信任；纯在线身份接受同发行窗口，或中/日/英任一标题去掉季度标记后的明确包含关系。
 */
fun isScheduleTmdbIdentityCompatible(
    confirmedLocalIdentity: Boolean,
    bangumiTitle: String,
    bangumiOriginalTitle: String?,
    bangumiAirDate: String?,
    tmdbTitle: String,
    tmdbOriginalTitle: String?,
    tmdbFirstAirDate: String?,
): Boolean {
    if (confirmedLocalIdentity) return true
    if (datesShareReleaseWindow(bangumiAirDate, tmdbFirstAirDate)) return true
    val bangumiTitles = listOfNotNull(bangumiTitle, bangumiOriginalTitle)
        .map(::normalizeScheduleIdentityTitle)
        .filter { it.length >= MIN_SCHEDULE_IDENTITY_TITLE_LENGTH }
        .distinct()
    val tmdbTitles = listOfNotNull(tmdbTitle, tmdbOriginalTitle)
        .map(::normalizeScheduleIdentityTitle)
        .filter { it.length >= MIN_SCHEDULE_IDENTITY_TITLE_LENGTH }
        .distinct()
    val titleMatches = bangumiTitles.any { bangumi ->
        tmdbTitles.any { tmdb -> bangumi == tmdb || bangumi.contains(tmdb) || tmdb.contains(bangumi) }
    }
    if (!titleMatches) return false
    val monthDistance = releaseMonthDistance(bangumiAirDate, tmdbFirstAirDate)
    return monthDistance == null || monthDistance <= MAX_SCHEDULE_MERGED_SERIES_MONTH_DISTANCE
}

private fun normalizeScheduleIdentityTitle(value: String): String = value.lowercase()
    .replace(SEASON_CN_IDENTITY, " ")
    .replace(SEASON_EN_IDENTITY, " ")
    .filter(Char::isLetterOrDigit)

private val SEASON_CN_IDENTITY = Regex("第\\s*(?:\\d{1,2}|[一二三四五六七八九十两]{1,3})\\s*[季期部]")
private val SEASON_EN_IDENTITY = Regex(
    "(?:season|series)\\s*[-:_]?\\s*(?:\\d{1,2}|[ivx]{1,5})|(?:\\d{1,2})(?:st|nd|rd|th)\\s+season",
    RegexOption.IGNORE_CASE,
)
private const val MIN_SCHEDULE_IDENTITY_TITLE_LENGTH = 4
private const val MAX_SCHEDULE_MERGED_SERIES_MONTH_DISTANCE = 10 * 12

/**
 * 为在线详情推断可安全请求的 TMDB 季号。
 *
 * 已确认的本地关联始终优先；否则接受标题中的明确季号，或当 Bangumi/TMDB 首播日期处于
 * 同一发行窗口(±2 个月)时认定为独立 TMDB 条目的第 1 季。
 *
 * 窗口判断先于标题季号是有意的: 动画续作一般至少 3 个月一季, 能落入 ±2 个月窗口
 * 且标题仍含 N≥2 时, TMDB 几乎必然已为当前季另立独立条目(此时取第 1 季才是对的),
 * 季号续写反而是错配。既没有明确季号、又是早年开始播出的长篇 TMDB 条目时返回 null，
 * 避免为了显示图片把续作误配到第一季。
 */
fun inferScheduleTmdbSeasonNumber(
    confirmedSeasonNumber: Int?,
    title: String,
    originalTitle: String?,
    bangumiAirDate: String?,
    tmdbFirstAirDate: String?,
): Int? {
    confirmedSeasonNumber?.takeIf { it >= 0 }?.let { return it }
    val explicitNumbers = listOfNotNull(title.explicitSeasonNumber(), originalTitle?.explicitSeasonNumber()).distinct()
    val explicit = explicitNumbers.singleOrNull()
    val independentTmdbEntry = datesShareReleaseWindow(bangumiAirDate, tmdbFirstAirDate)
    return when {
        independentTmdbEntry -> 1
        explicit != null -> explicit
        else -> null
    }
}

private fun String.explicitSeasonNumber(): Int? {
    val normalized = trim()
    val decimalPatterns = listOf(SEASON_CN, SEASON_EN_NUMERIC, SEASON_EN_ORDINAL)
    decimalPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }
    }?.let { return it }
    val roman = SEASON_EN_ROMAN.find(normalized)?.groupValues?.getOrNull(1)
        ?.uppercase()
        ?: return null
    return romanToInt(roman)?.takeIf { it in 1..99 }
}

private val SEASON_CN = Regex("第\\s*(\\d{1,2})\\s*[季期部]", RegexOption.IGNORE_CASE)
private val SEASON_EN_NUMERIC = Regex("(?:season|series)\\s*[-:_]?\\s*(\\d{1,2})", RegexOption.IGNORE_CASE)
private val SEASON_EN_ORDINAL = Regex("(\\d{1,2})(?:st|nd|rd|th)\\s+season", RegexOption.IGNORE_CASE)
private val SEASON_EN_ROMAN = Regex("(?:season|series)\\s*[-:_]?\\s*([IVX]{1,5})(?:\\b|$)", RegexOption.IGNORE_CASE)

private fun romanToInt(value: String): Int? {
    val digits = value.map { char ->
        when (char) {
            'I' -> 1
            'V' -> 5
            'X' -> 10
            else -> return null
        }
    }
    var result = 0
    digits.forEachIndexed { index, number ->
        result += if (index < digits.lastIndex && number < digits[index + 1]) -number else number
    }
    return result
}

/** YYYY-MM 前缀解析(放送日期比较/时间表放送时刻缓存共用)。 */
internal val YYYY_MM_PREFIX = Regex("^(\\d{4})-(\\d{2})")

private fun datesShareReleaseWindow(left: String?, right: String?): Boolean {
    return releaseMonthDistance(left, right)?.let { it <= 2 } == true
}

private fun releaseMonthDistance(left: String?, right: String?): Int? {
    fun monthIndex(value: String?): Int? {
        val match = YYYY_MM_PREFIX.find(value.orEmpty()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        return year * 12 + month
    }
    val leftMonth = monthIndex(left) ?: return null
    val rightMonth = monthIndex(right) ?: return null
    return kotlin.math.abs(leftMonth - rightMonth)
}

data class ScheduleLocalDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val weekday: Int,
    val hour: Int,
    val minute: Int,
) {
    val isoDate: String get() = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    val shortTime: String get() = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

expect fun currentScheduleLocalDateTime(): ScheduleLocalDateTime
expect fun utcIsoToScheduleLocalDateTime(value: String): ScheduleLocalDateTime?
