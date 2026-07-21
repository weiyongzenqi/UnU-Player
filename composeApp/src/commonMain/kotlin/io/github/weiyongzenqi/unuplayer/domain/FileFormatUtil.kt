package io.github.weiyongzenqi.unuplayer.domain

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

    fun formatDate(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val days = epochMillis / 86_400_000L
        val (y, m, d) = civilFromDays(days)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** Howard Hinnant civil_from_days: 天数 -> (年, 月, 日), UTC。 */
    private fun civilFromDays(zInput: Long): Triple<Int, Int, Int> {
        val z = zInput + 719468
        val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era.toInt() * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return Triple(year, m, d)
    }
}