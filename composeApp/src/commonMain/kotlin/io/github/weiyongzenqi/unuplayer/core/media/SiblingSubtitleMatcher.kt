package io.github.weiyongzenqi.unuplayer.core.media

/**
 * 同目录字幕的纯匹配与排序算法。
 *
 * 不负责目录访问或文件下载，Android/桌面平台只需把列目录结果交给本对象，
 * 即可保持严格同名、中文语言偏好与字幕后缀优先级一致。
 */
object SiblingSubtitleMatcher {
    /** 支持的字幕后缀，顺序同时表示同级候选的优先级。 */
    val extensions: List<String> = listOf("ass", "ssa", "srt", "sub", "vtt")

    private val scLangs = setOf("zc", "sc", "chs", "zh-hans", "zh-cn", "gb")
    private val tcLangs = setOf("tc", "cht", "zh-hant", "zh-tw", "big5", "zh")

    /**
     * 选出可自动加载的同名字幕并排序。
     *
     * preference=sc/tc 时：偏好语言 > 另一中文 > 严格同名；none/未知值只接受严格同名。
     * 同一语言级别内按 [extensions]，最后按小写文件名稳定排序。
     */
    fun automaticCandidates(
        entries: List<MediaEntry>,
        videoTitle: String,
        preference: String,
    ): List<MediaEntry> {
        val baseName = videoTitle.substringBeforeLast('.').trim()
        if (baseName.isEmpty()) return emptyList()
        val normalizedPreference = preference.lowercase().takeIf { it == "sc" || it == "tc" } ?: "none"

        return entries.asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { entry ->
                val language = matchLanguage(entry.name, baseName, normalizedPreference) ?: return@mapNotNull null
                RankedEntry(
                    entry = entry,
                    languageOrder = languageOrder(language, normalizedPreference),
                    extensionOrder = extensions.indexOf(entry.name.substringAfterLast('.', "").lowercase()),
                )
            }
            .sortedWith(
                compareBy<RankedEntry> { it.languageOrder }
                    .thenBy { it.extensionOrder }
                    .thenBy { it.entry.name.lowercase() },
            )
            .map { it.entry }
            .toList()
    }

    /** 列出全部字幕文件（不限同名），排除目录并按小写文件名稳定排序。 */
    fun allSubtitles(entries: List<MediaEntry>): List<MediaEntry> = entries.asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in extensions }
        .sortedBy { it.name.lowercase() }
        .toList()

    private fun matchLanguage(name: String, baseName: String, preference: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in extensions) return null
        val stem = name.substringBeforeLast('.').trim()
        if (stem.equals(baseName, ignoreCase = true)) return "none"
        if (preference == "none") return null

        val languageSegment = stem.substringAfterLast('.').lowercase()
        val candidateBaseName = stem.substringBeforeLast('.')
        if (!candidateBaseName.equals(baseName, ignoreCase = true)) return null
        return when (languageSegment) {
            in scLangs -> "sc"
            in tcLangs -> "tc"
            else -> null
        }
    }

    private fun languageOrder(language: String, preference: String): Int = when {
        preference == "none" -> 0
        language == preference -> 0
        language == "none" -> 2
        else -> 1
    }

    private data class RankedEntry(
        val entry: MediaEntry,
        val languageOrder: Int,
        val extensionOrder: Int,
    )
}
