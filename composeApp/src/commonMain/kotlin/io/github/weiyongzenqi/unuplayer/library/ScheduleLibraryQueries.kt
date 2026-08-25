package io.github.weiyongzenqi.unuplayer.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleLibraryMatch
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleLibraryMatchSource
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleStatus
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatch
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatchDeletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 时间表相关查询的共享实现(androidMain/desktopMain 的 [ScrapedLibraryRepositoryImpl] 共用)。
 *
 * SQLDelight 生成类与 .sq 在 commonMain, 双端查询逻辑完全一致; 集中在此避免两平台实现各自
 * 复制后漂移(曾出现过 match_source 回落映射双端不一致的教训)。实现类仅剩一行委托。
 */
internal suspend fun ScrapedQueries.scheduleWatches(): List<ScheduleWatch> = withContext(Dispatchers.IO) {
    listScheduleWatches().executeAsList().map { it.toDomainScheduleWatch() }
}

internal fun ScrapedQueries.observeScheduleWatchRows(): Flow<List<ScheduleWatch>> =
    listScheduleWatches().asFlow().mapToList(Dispatchers.IO).map { rows ->
        rows.map { it.toDomainScheduleWatch() }
    }

internal suspend fun ScrapedQueries.upsertScheduleWatchRow(watch: ScheduleWatch): Unit = withContext(Dispatchers.IO) {
    val normalized = watch.validatedForLocalWrite()
    transaction {
        val currentVersion = getScheduleWatch(subject_id = normalized.subjectId).executeAsOneOrNull()?.sync_version ?: 0L
        val deletedVersion = getScheduleWatchTombstone(subject_id = normalized.subjectId)
            .executeAsOneOrNull()?.sync_version ?: 0L
        upsertScheduleWatchDomain(
            normalized.copy(syncVersion = nextScheduleWatchSyncVersion(maxOf(currentVersion, deletedVersion))),
        )
        deleteScheduleWatchTombstone(subject_id = normalized.subjectId)
    }
}

internal suspend fun ScrapedQueries.deleteScheduleWatchRow(subjectId: Long): Unit = withContext(Dispatchers.IO) {
    if (subjectId <= 0L) return@withContext
    transaction {
        val current = getScheduleWatch(subject_id = subjectId).executeAsOneOrNull() ?: return@transaction
        val deletedVersion = getScheduleWatchTombstone(subject_id = subjectId)
            .executeAsOneOrNull()?.sync_version ?: 0L
        deleteScheduleWatch(subject_id = subjectId)
        upsertScheduleWatchTombstone(
            subject_id = subjectId,
            deleted_at = platformTimeMillis().coerceAtLeast(current.watched_at).coerceAtLeast(0L),
            sync_version = nextScheduleWatchSyncVersion(maxOf(current.sync_version, deletedVersion)),
        )
    }
}

internal suspend fun ScrapedQueries.scheduleWatchDeletions(): List<ScheduleWatchDeletion> = withContext(Dispatchers.IO) {
    listScheduleWatchTombstones().executeAsList().map { it.toDomainScheduleWatchDeletion() }
}

private fun ScrapedQueries.upsertScheduleWatchDomain(watch: ScheduleWatch) {
    upsertScheduleWatch(
        subject_id = watch.subjectId,
        title = watch.title,
        air_weekday = watch.airWeekday.toLong(),
        anime_id = watch.animeId,
        tmdb_id = watch.tmdbId,
        watched_at = watch.watchedAt,
        status = watch.status.name,
        sync_version = watch.syncVersion,
    )
}

private fun ScheduleWatch.validatedForLocalWrite(): ScheduleWatch {
    require(subjectId > 0L) { "Bangumi subject id 必须为正数" }
    val normalizedTitle = title.trim().take(MAX_SCHEDULE_WATCH_TITLE_LENGTH)
    require(normalizedTitle.isNotEmpty()) { "标记番剧标题不能为空" }
    require(airWeekday in 0..7) { "放送星期必须为 0..7" }
    require(status != ScheduleStatus.NONE) { "未标记状态应通过删除接口保存" }
    require(animeId == null || animeId > 0L) { "弹弹 anime id 必须为正数" }
    require(tmdbId == null || tmdbId > 0L) { "TMDB id 必须为正数" }
    return copy(title = normalizedTitle, watchedAt = watchedAt.coerceAtLeast(0L))
}

private fun nextScheduleWatchSyncVersion(current: Long): Long =
    if (current in 0L until MAX_SCHEDULE_WATCH_SYNC_VERSION - 1L) current + 1L
    else REPAIRED_SCHEDULE_WATCH_SYNC_VERSION + 1L

internal const val MAX_SCHEDULE_WATCH_SYNC_VERSION = 1_000_000_000_000L
internal const val REPAIRED_SCHEDULE_WATCH_SYNC_VERSION = MAX_SCHEDULE_WATCH_SYNC_VERSION / 2L
internal const val MAX_SCHEDULE_WATCH_TITLE_LENGTH = 500

internal suspend fun ScrapedQueries.scheduleLibraryMatches(
    subjectIds: Set<Long>,
    tmdbIds: Set<Long>,
    animeIds: Set<Long>,
): List<ScheduleLibraryMatch> = withContext(Dispatchers.IO) {
    buildList {
        if (subjectIds.isNotEmpty()) addAll(findScheduleLibraryBySubjectIds(subjectIds).executeAsList().map {
            ScheduleLibraryMatch(
                subjectId = it.subject_id,
                tmdbId = it.tmdb_id,
                showId = it.show_id,
                libraryId = it.library_id,
                seasonNumber = it.season_number.toInt(),
                bangumiOffset = it.bangumi_offset,
                localTitle = it.local_title,
                source = runCatching { ScheduleLibraryMatchSource.valueOf(it.match_source) }
                    .getOrDefault(ScheduleLibraryMatchSource.SCANNED),
            )
        })
        if (tmdbIds.isNotEmpty()) addAll(findScheduleLibraryByTmdbIds(tmdbIds).executeAsList().map {
            ScheduleLibraryMatch(
                tmdbId = it.tmdb_id,
                showId = it.show_id,
                libraryId = it.library_id,
                seasonNumber = it.season_number.toInt(),
                bangumiOffset = it.bangumi_offset,
                localTitle = it.local_title,
                source = ScheduleLibraryMatchSource.TMDB,
            )
        })
        if (animeIds.isNotEmpty()) addAll(findScheduleLibraryByDandanIds(animeIds).executeAsList().map {
            ScheduleLibraryMatch(
                animeId = it.anime_id,
                tmdbId = it.tmdb_id,
                showId = it.show_id,
                libraryId = it.library_id,
                seasonNumber = it.season_number.toInt(),
                bangumiOffset = it.bangumi_offset,
                localTitle = it.local_title,
                source = ScheduleLibraryMatchSource.DANDANPLAY,
            )
        })
    }
}

internal suspend fun ScrapedQueries.visibleShowTitles(): List<LibraryShowTitle> = withContext(Dispatchers.IO) {
    listVisibleShowTitles { showId, libraryId, title ->
        LibraryShowTitle(showId, libraryId, title)
    }.executeAsList()
}
