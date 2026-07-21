package io.github.weiyongzenqi.unuplayer.webdav

/**
 * Season 文件夹匹配(移植自 NipaPlay WebDAVQuickAccessProvider)。
 * 通配符: * 任意字符, ? 单个字符。大小写不敏感。
 */
object SeasonFolderMatcher {

    /** 通配符 [pattern] 是否匹配 [folderName]。空模式返回 false。 */
    fun matches(pattern: String, folderName: String): Boolean {
        if (pattern.isEmpty()) return false
        val regex = buildString {
            append('^')
            for (ch in pattern) when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> { append('\\'); append(ch) }
                else -> append(ch)
            }
            append('$')
        }
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(folderName) }.getOrDefault(false)
    }

    /**
     * 在 [folderNames] 中找匹配 [pattern] 的文件夹:
     * 单匹配直取; 多匹配按自然排序取第一个; 无匹配返回 null。
     */
    fun findMatch(pattern: String, folderNames: List<String>): String? {
        if (pattern.isEmpty()) return null
        val matched = folderNames.filter { matches(pattern, it) }
        return when {
            matched.isEmpty() -> null
            matched.size == 1 -> matched.first()
            else -> matched.sortedWith(Comparator { a, b -> WebDavFileSorter.naturalCompare(a, b) }).first()
        }
    }
}