package io.github.weiyongzenqi.unuplayer.app

import io.github.weiyongzenqi.unuplayer.core.media.PlaybackQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Activity 之间只传不透明 token；队列及媒体定位保持在应用进程内。 */
internal object PlaybackQueueRegistry {
    private val nextId = AtomicLong(0L)
    private val queues = ConcurrentHashMap<String, PlaybackQueue>()

    fun register(queue: PlaybackQueue): String {
        val token = "queue-${nextId.incrementAndGet()}"
        queues[token] = queue
        return token
    }

    fun get(token: String?): PlaybackQueue? = token?.let(queues::get)

    fun select(token: String, index: Int): PlaybackQueue? {
        return queues.computeIfPresent(token) { _, queue ->
            if (index in queue.items.indices) queue.copy(currentIndex = index) else queue
        }?.takeIf { it.currentIndex == index }
    }

    fun remove(token: String?) {
        if (token != null) queues.remove(token)
    }
}
