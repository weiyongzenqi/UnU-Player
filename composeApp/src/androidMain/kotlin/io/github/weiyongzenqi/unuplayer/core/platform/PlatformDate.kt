package io.github.weiyongzenqi.unuplayer.core.platform

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** F-2-8: 本地时区日期(东八区凌晨记录不再显示前一天)。 */
actual fun platformLocalDateString(epochMillis: Long): String =
    if (epochMillis <= 0L) "" else LOCAL_DATE_FORMAT.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
    )

private val LOCAL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
