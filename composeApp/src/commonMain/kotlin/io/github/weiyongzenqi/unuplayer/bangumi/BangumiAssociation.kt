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
    if (
        persisted?.state == BangumiLinkState.CONFIRMED &&
        persisted.source == BangumiLinkSource.MANUAL &&
        persisted.subjectId != null
    ) {
        return EffectiveBangumiLink(persisted.subjectId, EffectiveBangumiLinkSource.MANUAL)
    }
    if (scannedSubjectId != null) {
        return EffectiveBangumiLink(scannedSubjectId, EffectiveBangumiLinkSource.SCANNED)
    }
    if (persisted?.state == BangumiLinkState.CONFIRMED && persisted.subjectId != null) {
        return EffectiveBangumiLink(persisted.subjectId, EffectiveBangumiLinkSource.AUTO_VERIFIED)
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
