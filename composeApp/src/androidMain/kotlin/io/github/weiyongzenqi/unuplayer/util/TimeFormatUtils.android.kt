package io.github.weiyongzenqi.unuplayer.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

actual fun formatTimestamp(timestampMillis: Long): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))

actual fun formatLogDate(timestampMillis: Long): String =
    DATE_FORMAT.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()))
