package io.github.weiyongzenqi.unuplayer.library

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ScanProgress(
    val scannedDirs: Int,
    val foundShows: Int,
    val foundEpisodes: Int,
)

internal data class ScanMetricsSnapshot(
    val scannedDirs: Int,
    val foundShows: Int,
    val foundEpisodes: Int,
    val skippedShows: Int,
    val errors: Int,
    val firstErrorMessage: String?,
    val peakQueuedDirs: Int,
    val visitedDirs: Int,
    val directoryLimitReached: Boolean,
)

/** 扫描 worker 共享计数；用协程 Mutex 替代 commonMain 不可用的 JVM 原子类。 */
internal class ScanMetrics {
    private val mutex = Mutex()
    private var scannedDirs = 0
    private var foundShows = 0
    private var foundEpisodes = 0
    private var skippedShows = 0
    private var errors = 0
    private var firstErrorMessage: String? = null
    private var peakQueuedDirs = 0
    private var visitedDirs = 0
    private var directoryLimitReached = false

    suspend fun recordScanned(): ScanProgress = mutex.withLock {
        scannedDirs++
        progressLocked()
    }

    suspend fun recordShow(episodeCount: Int): ScanProgress = mutex.withLock {
        foundShows++
        foundEpisodes += episodeCount
        progressLocked()
    }

    suspend fun recordSkipped() {
        mutex.withLock { skippedShows++ }
    }

    suspend fun recordError(message: String) {
        mutex.withLock {
            errors++
            if (firstErrorMessage == null) firstErrorMessage = message
        }
    }

    suspend fun updatePeakQueued(value: Int) {
        mutex.withLock { peakQueuedDirs = maxOf(peakQueuedDirs, value) }
    }

    suspend fun setVisited(value: Int) {
        mutex.withLock { visitedDirs = value }
    }

    suspend fun markDirectoryLimitReached() {
        mutex.withLock { directoryLimitReached = true }
    }

    suspend fun snapshot(): ScanMetricsSnapshot = mutex.withLock {
        ScanMetricsSnapshot(
            scannedDirs = scannedDirs,
            foundShows = foundShows,
            foundEpisodes = foundEpisodes,
            skippedShows = skippedShows,
            errors = errors,
            firstErrorMessage = firstErrorMessage,
            peakQueuedDirs = peakQueuedDirs,
            visitedDirs = visitedDirs,
            directoryLimitReached = directoryLimitReached,
        )
    }

    private fun progressLocked() = ScanProgress(scannedDirs, foundShows, foundEpisodes)
}
