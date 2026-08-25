package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatch as ScheduleWatchDomain
import io.github.weiyongzenqi.unuplayer.schedule.ScheduleWatchDeletion

internal fun ScheduleWatch.toDomainScheduleWatch() = ScheduleWatchDomain(
    subjectId = subject_id,
    title = title,
    airWeekday = air_weekday.toInt(),
    animeId = anime_id,
    tmdbId = tmdb_id,
    watchedAt = watched_at,
    status = runCatching { io.github.weiyongzenqi.unuplayer.schedule.ScheduleStatus.valueOf(status) }
        .getOrDefault(io.github.weiyongzenqi.unuplayer.schedule.ScheduleStatus.WANT),
    syncVersion = sync_version,
)

internal fun ScheduleWatchTombstone.toDomainScheduleWatchDeletion() = ScheduleWatchDeletion(
    subjectId = subject_id,
    deletedAt = deleted_at,
    syncVersion = sync_version,
)
