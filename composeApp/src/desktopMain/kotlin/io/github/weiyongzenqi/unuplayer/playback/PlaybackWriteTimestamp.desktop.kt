package io.github.weiyongzenqi.unuplayer.playback

import java.util.concurrent.atomic.AtomicLong

private val lastPlaybackWriteTimestamp = AtomicLong(System.currentTimeMillis())

actual fun nextPlaybackWriteTimestamp(after: Long): Long {
    require(after != Long.MAX_VALUE) { "播放记录时间戳已达到 Long.MAX_VALUE" }
    while (true) {
        val current = lastPlaybackWriteTimestamp.get()
        check(current != Long.MAX_VALUE) { "进程内播放记录时间戳已达到 Long.MAX_VALUE" }
        val next = maxOf(System.currentTimeMillis(), current + 1, after + 1)
        if (lastPlaybackWriteTimestamp.compareAndSet(current, next)) return next
    }
}
