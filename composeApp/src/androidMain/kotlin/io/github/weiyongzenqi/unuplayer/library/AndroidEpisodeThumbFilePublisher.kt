package io.github.weiyongzenqi.unuplayer.library

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 仅在完整 part 已存在时执行同目录原子替换；失败时保留旧目标不动。 */
internal fun publishAtomicEpisodeThumbPart(part: File, destination: File): Boolean {
    if (!part.isFile || part.length() <= 0L) return false
    return runCatching {
        Files.move(
            part.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrDefault(false)
}
