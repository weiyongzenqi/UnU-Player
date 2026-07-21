package io.github.weiyongzenqi.unuplayer.domain

/**
 * 集数/季号提取(移植自 NipaPlay webdav_browser_page.dart _extractEpisodeNumber/_extractSeasonNumber)。
 * 为未来番剧识别铺路, 本次不接消费。
 */
object EpisodeNumberExtractor {

    private val sxxexx = Regex("(?i)S(\\d{1,2})\\s*E(\\d{1,3})")
    private val patterns = listOf(
        Regex("第\\s*(\\d{1,3})\\s*[话話集]"),
        // \b 词边界防止标题尾字母误命中(如 "Steins;Gate 0" 的 e+空格+0)
        Regex("(?i)\\bEP?\\s*(\\d{1,3})"),
        Regex("[\\[【]\\s*(\\d{1,3})\\s*[\\]】]"),
        // 尾锚放宽为负向先行: 数字后不跟字母/数字(串尾天然满足), 兼容 "Title - 03 [1080p]"/"Title - 03.mkv"
        Regex("[\\-_]\\s*(\\d{1,3})\\s*(?![A-Za-z\\d])"),
    )

    /** 提取集号; SxxExx 优先取集, 否则按 patterns 顺序取首个命中。无匹配返回 null。 */
    fun extractEpisode(name: String): Int? {
        sxxexx.find(name)?.let { return it.groupValues.getOrNull(2)?.toIntOrNull() }
        for (p in patterns) {
            p.find(name)?.let { return it.groupValues.getOrNull(1)?.toIntOrNull() }
        }
        return null
    }

    /** 提取季号(SxxExx 的 S 部分); 无匹配返回 null。 */
    fun extractSeason(name: String): Int? =
        sxxexx.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** 返回 "S1E12" 格式季集标识(季集原样取自文件名); 仅当含 SxxExx 时返回, 否则 null。用于列表 badge 展示。 */
    fun formatSxxExx(name: String): String? =
        sxxexx.find(name)?.let { "S${it.groupValues[1]}E${it.groupValues[2]}" }
}