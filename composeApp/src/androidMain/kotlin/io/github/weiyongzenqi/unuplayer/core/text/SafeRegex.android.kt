package io.github.weiyongzenqi.unuplayer.core.text

import com.google.re2j.Pattern

internal actual class SafeRegex actual constructor(pattern: String, ignoreCase: Boolean) {
    private val delegate = Pattern.compile(pattern, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)

    actual fun find(input: String): SafeRegexMatch? {
        val matcher = delegate.matcher(input)
        if (!matcher.find()) return null
        return SafeRegexMatch(
            groupValues = (0..matcher.groupCount()).map { index -> matcher.group(index).orEmpty() },
        )
    }
}
