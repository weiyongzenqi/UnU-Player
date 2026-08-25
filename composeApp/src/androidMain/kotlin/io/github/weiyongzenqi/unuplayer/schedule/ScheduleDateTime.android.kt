package io.github.weiyongzenqi.unuplayer.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

actual fun currentScheduleLocalDateTime(): ScheduleLocalDateTime = ZonedDateTime.now().toScheduleDateTime()

actual fun utcIsoToScheduleLocalDateTime(value: String): ScheduleLocalDateTime? = runCatching {
    Instant.parse(value).atZone(ZoneId.systemDefault()).toScheduleDateTime()
}.getOrNull()

private fun ZonedDateTime.toScheduleDateTime() = ScheduleLocalDateTime(
    year = year,
    month = monthValue,
    day = dayOfMonth,
    weekday = dayOfWeek.value,
    hour = hour,
    minute = minute,
)
