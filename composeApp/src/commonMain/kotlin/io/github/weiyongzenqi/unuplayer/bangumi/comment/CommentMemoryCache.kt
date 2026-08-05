package io.github.weiyongzenqi.unuplayer.bangumi.comment

import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CommentMemoryCache<K : Any, V : Any>(
    private val ttlMillis: Long = DEFAULT_COMMENT_CACHE_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_COMMENT_CACHE_MAX_ENTRIES,
    private val nowMillis: () -> Long = ::platformTimeMillis,
) {
    private data class Entry<V>(val value: V, val expiresAt: Long, val accessOrder: Long)
    private data class Pending<V>(
        val deferred: CompletableDeferred<V>,
        val generation: Long,
        val leaderJob: Job?,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<K, Entry<V>>()
    private val inFlight = mutableMapOf<K, Pending<V>>()
    private var accessCounter = 0L
    private var generation = 0L

    init {
        require(ttlMillis > 0)
        require(maxEntries > 0)
    }

    suspend fun getOrLoad(key: K, refresh: Boolean = false, loader: suspend () -> V): V {
        while (true) {
            var leader = false
            val callerJob = currentCoroutineContext()[Job]
            val pending = mutex.withLock {
                val now = nowMillis()
                entries.entries.removeAll { it.value.expiresAt <= now }
                if (refresh) entries.remove(key)
                entries[key]?.let { cached ->
                    entries[key] = cached.copy(accessOrder = ++accessCounter)
                    return cached.value
                }
                inFlight[key] ?: Pending(CompletableDeferred<V>(), generation, callerJob).also {
                    inFlight[key] = it
                    leader = true
                }
            }
            if (!leader) {
                try {
                    return pending.deferred.await()
                } catch (_: LeaderJobCancelledForRetryException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            return try {
                val value = loader()
                mutex.withLock {
                    if (pending.generation == generation && inFlight[key] === pending) {
                        while (entries.size >= maxEntries) {
                            val oldest = entries.minByOrNull { it.value.accessOrder }?.key ?: break
                            entries.remove(oldest)
                        }
                        entries[key] = Entry(value, nowMillis() + ttlMillis, ++accessCounter)
                        inFlight.remove(key)
                        pending.deferred.complete(value)
                    }
                }
                value
            } catch (throwable: Throwable) {
                val leaderJobCancelled = currentCoroutineContext()[Job]?.isCancelled == true
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (inFlight[key] === pending) {
                            inFlight.remove(key)
                            pending.deferred.completeExceptionally(
                                if (leaderJobCancelled) LeaderJobCancelledForRetryException() else throwable,
                            )
                        }
                    }
                }
                throw throwable
            }
        }
    }

    suspend fun invalidate(key: K) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun clear() {
        val clearJob = currentCoroutineContext()[Job]
        val pending = mutex.withLock {
            generation++
            entries.clear()
            inFlight.values.toList().also { inFlight.clear() }
        }
        pending.forEach {
            it.deferred.cancel()
            if (it.leaderJob !== clearJob) it.leaderJob?.cancel()
        }
    }

    internal suspend fun size(): Int = mutex.withLock { entries.size }
}

private class LeaderJobCancelledForRetryException : Exception()

internal const val DEFAULT_COMMENT_CACHE_TTL_MILLIS = 5 * 60 * 1_000L
internal const val DEFAULT_COMMENT_CACHE_MAX_ENTRIES = 64
