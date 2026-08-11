package io.github.weiyongzenqi.unuplayer.domain

import io.github.weiyongzenqi.unuplayer.core.platform.platformLocalDateString

/** 文件大小/时间格式化(commonMain, 不依赖平台 API)。 */
object FileFormatUtil {

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "--"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024.0 && i < units.lastIndex) { size /= 1024.0; i++ }
        return if (i == 0) "${bytes} B" else "%.1f %s".format(size, units[i])
    }

    /**
     * F-2-8: 本地时区 yyyy-MM-dd。
     * 原实现按 UTC 天界(epochMillis / 86400000)计算, 东八区 00:00-08:00 的记录显示前一天;
     * 现委托平台实现([platformLocalDateString])按系统时区取日期。
     */
    fun formatDate(epochMillis: Long): String = platformLocalDateString(epochMillis)
}
