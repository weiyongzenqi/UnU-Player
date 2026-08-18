package io.github.weiyongzenqi.unuplayer.util

/**
 * 时间格式化工具函数。
 *
 * - [formatTimeMs]: 毫秒 → mm:ss 或 h:mm:ss
 * - [formatTimestamp]: epoch 毫秒 → yyyy-MM-dd HH:mm:ss.SSS (平台实现)
 * - [formatLogDate]: epoch 毫秒 → yyyy-MM-dd (日志文件名日期, 平台实现)
 */

/**
 * 毫秒 → mm:ss 或 h:mm:ss。
 *
 * 负数自动视作0。小于1小时时不显示小时位。
 */
fun formatTimeMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 3600) "%02d:%02d".format(s / 60, s % 60)
    else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

/**
 * epoch 毫秒 → yyyy-MM-dd HH:mm:ss.SSS (日志时间戳格式)。
 *
 * 平台实现，使用系统默认时区。
 */
expect fun formatTimestamp(timestampMillis: Long): String

/**
 * epoch 毫秒 → yyyy-MM-dd (日期格式)。
 *
 * 平台实现，使用系统默认时区。
 */
expect fun formatLogDate(timestampMillis: Long): String
