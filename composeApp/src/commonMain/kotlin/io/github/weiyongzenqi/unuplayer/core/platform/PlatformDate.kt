package io.github.weiyongzenqi.unuplayer.core.platform

/**
 * F-2-8: 将 epoch 毫秒格式化为**本地时区**的 yyyy-MM-dd 日期字符串。
 *
 * FileFormatUtil.formatDate 原用 UTC 天界(epochMillis / 86400000), 东八区 00:00-08:00 的记录
 * 显示成前一天。commonMain 禁 JVM API, 故按 expect/actual 由平台实现(Android/桌面用 java.time)。
 */
expect fun platformLocalDateString(epochMillis: Long): String
