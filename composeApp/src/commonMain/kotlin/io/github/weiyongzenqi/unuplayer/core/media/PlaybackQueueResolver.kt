package io.github.weiyongzenqi.unuplayer.core.media

import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching

private val PLAYBACK_QUEUE_VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts", "mpg", "mpeg",
)

/** 按调用方已经确定的目录顺序创建队列；单个兄弟文件失效只跳过该项，当前项失败仍向上抛出。 */
suspend fun MediaSource.resolvePlayMediaWithQueue(
    current: MediaEntry,
    orderedEntries: List<MediaEntry>,
): PlayableMedia {
    val candidates = orderedEntries.asSequence()
        .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in PLAYBACK_QUEUE_VIDEO_EXTENSIONS }
        .distinctBy { it.path }
        .toList()
        .let { entries -> if (entries.any { it.path == current.path }) entries else entries + current }
    val resolved = mutableListOf<PlayableMedia>()
    var currentIndex = -1
    candidates.forEach { entry ->
        val media = if (entry.path == current.path) {
            resolvePlayMedia(entry)
        } else {
            runSuspendCatching { resolvePlayMedia(entry) }.getOrNull() ?: return@forEach
        }
        if (entry.path == current.path) currentIndex = resolved.size
        resolved += media
    }
    check(currentIndex >= 0) { "当前媒体未能加入播放队列" }
    return resolved[currentIndex].withPlaybackQueue(resolved, currentIndex)
}
