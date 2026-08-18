package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.library.ScrapedSeason
import io.github.weiyongzenqi.unuplayer.library.ScrapedShow

enum class BangumiLinkState { CONFIRMED, DISABLED, CONFLICT }

enum class BangumiLinkSource {
    MANUAL,
    EXT_LINKER,
    AUTO,
}

data class BangumiSeasonLink(
    val identityKey: String,
    val subjectId: Long?,
    val state: BangumiLinkState,
    val source: BangumiLinkSource,
    val evidence: String?,
    val updatedAt: Long,
    val verifiedAt: Long?,
)

internal fun preferredBangumiSeasonLink(
    current: BangumiSeasonLink?,
    legacy: BangumiSeasonLink?,
): BangumiSeasonLink? = when {
    current == null -> legacy
    legacy == null -> current
    shouldReplaceBangumiSeasonLink(current, legacy) -> legacy
    else -> current
}

/**
 * 关联仲裁统一口径：禁用 > 手动 > 冲突/外部关联器 > 自动；同优先级只允许更晚快照替换。
 * 导入与历史 identity 迁移都使用该函数，避免旧包覆盖目标设备上更新的手动选择。
 */
internal fun shouldReplaceBangumiSeasonLink(
    current: BangumiSeasonLink?,
    candidate: BangumiSeasonLink,
): Boolean {
    if (current == null) return true
    val currentPriority = current.preferencePriority()
    val candidatePriority = candidate.preferencePriority()
    return candidatePriority > currentPriority ||
        (candidatePriority == currentPriority && candidate.updatedAt > current.updatedAt)
}

private fun BangumiSeasonLink.preferencePriority(): Int = when {
    state == BangumiLinkState.DISABLED -> 4
    source == BangumiLinkSource.MANUAL -> 3
    state == BangumiLinkState.CONFLICT || source == BangumiLinkSource.EXT_LINKER -> 2
    else -> 1
}

enum class BangumiCandidateSource {
    EXT_LINKER,
    TITLE_SEARCH,
    ID_LOOKUP,
}

data class BangumiCandidate(
    val subjectId: Long,
    val title: String,
    val originalTitle: String? = null,
    val date: String? = null,
    val type: Int? = null,
    val episodeCount: Int? = null,
    val sources: Set<BangumiCandidateSource>,
    val evidence: String? = null,
    val seasonExact: Boolean = false,
)

data class BangumiDiscovery(
    val candidates: List<BangumiCandidate>,
    val autoVerified: BangumiCandidate? = null,
    val conflict: Boolean = false,
    val hadNetworkFailure: Boolean = false,
)

data class EffectiveBangumiLink(
    val subjectId: Long,
    val source: EffectiveBangumiLinkSource,
)

enum class EffectiveBangumiLinkSource { MANUAL, SCANNED, AUTO_VERIFIED }

object BangumiSeasonIdentity {
    fun keyFor(show: ScrapedShow, season: ScrapedSeason): String = keyFor(
        tmdbId = show.tmdb_id,
        libraryId = show.library_id,
        showPath = show.show_path,
        seasonNumber = season.season_number,
    )

    fun keyFor(tmdbId: Long?, libraryId: Long, showPath: String, seasonNumber: Long): String =
        if (tmdbId != null) {
            "tmdb-tv:$tmdbId:season:$seasonNumber"
        } else {
            "show:$libraryId:$showPath:season:$seasonNumber"
        }
}

/** 用户禁用/手动选择最高；否则扫描得到的 bangumi.ini 高于自动关联。 */
fun resolveEffectiveBangumiLink(
    persisted: BangumiSeasonLink?,
    scannedSubjectId: Long?,
): EffectiveBangumiLink? {
    if (persisted?.state == BangumiLinkState.DISABLED) return null
    val persistedSubjectId = persisted?.subjectId?.takeIf { it > 0L }
    val validScannedSubjectId = scannedSubjectId?.takeIf { it > 0L }
    if (
        persisted?.state == BangumiLinkState.CONFIRMED &&
        persisted.source == BangumiLinkSource.MANUAL &&
        persistedSubjectId != null
    ) {
        return EffectiveBangumiLink(persistedSubjectId, EffectiveBangumiLinkSource.MANUAL)
    }
    if (validScannedSubjectId != null) {
        return EffectiveBangumiLink(validScannedSubjectId, EffectiveBangumiLinkSource.SCANNED)
    }
    if (persisted?.state == BangumiLinkState.CONFIRMED && persistedSubjectId != null) {
        return EffectiveBangumiLink(persistedSubjectId, EffectiveBangumiLinkSource.AUTO_VERIFIED)
    }
    return null
}

internal fun mergeBangumiCandidates(candidates: List<BangumiCandidate>): List<BangumiCandidate> =
    candidates.groupBy { it.subjectId }.values.map { sameSubject ->
        sameSubject.reduce { left, right ->
            BangumiCandidate(
                subjectId = left.subjectId,
                title = richerText(left.title, right.title),
                originalTitle = left.originalTitle ?: right.originalTitle,
                date = left.date ?: right.date,
                type = left.type ?: right.type,
                episodeCount = left.episodeCount ?: right.episodeCount,
                sources = left.sources + right.sources,
                evidence = listOfNotNull(left.evidence, right.evidence).distinct().joinToString(";").ifBlank { null },
                seasonExact = left.seasonExact || right.seasonExact,
            )
        }
    }.sortedWith(
        compareByDescending<BangumiCandidate> { it.seasonExact }
            .thenByDescending { it.sources.size },
    )

private fun richerText(left: String, right: String): String = when {
    left.isBlank() -> right
    right.isBlank() -> left
    left.startsWith("Q") && !right.startsWith("Q") -> right
    else -> left
}

internal fun sameReleaseMonth(localDate: String?, remoteDate: String?): Boolean {
    val local = localDate?.takeIf { it.length >= 7 }?.take(7) ?: return false
    val remote = remoteDate?.takeIf { it.length >= 7 }?.take(7) ?: return false
    return local == remote
}
