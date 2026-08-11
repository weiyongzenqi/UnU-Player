package io.github.weiyongzenqi.unuplayer.core.text

/** 使用平台线性时间正则引擎编译不可信表达式。 */
internal expect class SafeRegex(pattern: String, ignoreCase: Boolean = false) {
    fun find(input: String): SafeRegexMatch?
}

internal data class SafeRegexMatch(
    val groupValues: List<String>,
)
