package io.github.weiyongzenqi.unuplayer.core.text

import com.google.re2j.Pattern

internal actual class SafeRegex actual constructor(pattern: String) {
    private val delegate = Pattern.compile(pattern)

    actual fun find(input: String): SafeRegexMatch? {
        val matcher = delegate.matcher(input)
        if (!matcher.find()) return null
        return SafeRegexMatch(
            groupValues = (0..matcher.groupCount()).map { index -> matcher.group(index).orEmpty() },
        )
    }
}
