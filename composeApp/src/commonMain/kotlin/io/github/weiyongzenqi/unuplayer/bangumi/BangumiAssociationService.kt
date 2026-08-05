package io.github.weiyongzenqi.unuplayer.bangumi

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class BangumiAssociationService(
    private val catalog: BangumiCatalog,
    private val tmdbBridges: List<BangumiTmdbBridge>,
) {
    suspend fun discover(
        tmdbId: Long?,
        seasonNumber: Long,
        title: String,
        originalTitle: String?,
        releaseDate: String?,
    ): BangumiDiscovery = coroutineScope {
        val bridgeTasks = tmdbBridges.map { bridge ->
            async {
                if (tmdbId == null) Result.success(emptyList())
                else runSuspendCatching { bridge.find(tmdbId, seasonNumber) }
            }
        }
        val searchTask = async {
            runSuspendCatching { searchByTitles(title, originalTitle) }
        }

        val bridgeResults = bridgeTasks.map { it.await() }
        val searchResult = searchTask.await()
        val bridgeCandidates = bridgeResults.flatMap { it.getOrDefault(emptyList()) }
        val merged = mergeBangumiCandidates(bridgeCandidates + searchResult.getOrDefault(emptyList()))
        val exactExternal = bridgeCandidates.filter { it.seasonExact }.distinctBy { it.subjectId }

        var validationFailed = false
        val autoVerified = exactExternal.singleOrNull()?.let { exact ->
            val official = runSuspendCatching { catalog.getSubject(exact.subjectId) }
                .onFailure { validationFailed = true }
                .getOrNull()
            official?.takeIf {
                it.type == BANGUMI_ANIME_TYPE && sameReleaseMonth(releaseDate, it.date)
            }?.let { mergeBangumiCandidates(listOf(exact, it)).single() }
        }

        BangumiDiscovery(
            candidates = merged,
            autoVerified = autoVerified,
            conflict = exactExternal.size > 1,
            hadNetworkFailure = bridgeResults.any { it.isFailure } || searchResult.isFailure || validationFailed,
        )
    }

    suspend fun search(query: String): List<BangumiCandidate> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return if (trimmed.all(Char::isDigit)) {
            trimmed.toLongOrNull()?.let { catalog.getSubject(it) }?.let(::listOf).orEmpty()
        } else {
            catalog.search(trimmed)
        }
    }

    suspend fun getSubject(subjectId: Long): BangumiCandidate? = catalog.getSubject(subjectId)

    private suspend fun searchByTitles(title: String, originalTitle: String?): List<BangumiCandidate> {
        val first = catalog.search(title)
        if (first.isNotEmpty() || originalTitle.isNullOrBlank() || originalTitle == title) return first
        return catalog.search(originalTitle)
    }

    private companion object {
        const val BANGUMI_ANIME_TYPE = 2
    }
}
