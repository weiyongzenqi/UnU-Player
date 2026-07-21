package io.github.weiyongzenqi.unuplayer.core.player

/** Surface 引用状态本身不调用 native；调用方在同一生命周期锁内执行返回的 attach 动作。 */
internal class MpvSurfaceBindingState<T : Any> {
    @Volatile
    var current: T? = null
        private set

    /** 每次 Surface 到达或销毁递增，防止 HDR reinit 使用过期快照回绑旧 Surface。 */
    var generation: Long = 0L
        private set

    private var pending: T? = null

    /** native 已就绪时返回要立即 attach 的 Surface，否则缓存到 init 发布前。 */
    fun onAvailable(surface: T, nativeReady: Boolean): T? {
        current = surface
        generation++
        return if (nativeReady) {
            surface
        } else {
            pending = surface
            null
        }
    }

    fun pendingForInitialization(): T? = pending

    fun markAttached(surface: T) {
        if (pending === surface) pending = null
    }

    fun retainCurrentForRetry() {
        pending = current
    }

    /** HDR 重建跨过 destroy 后选择快照或期间到达的最新 Surface，不伪造一次外部 Surface 事件。 */
    fun retainForReinitialization(snapshot: T?, snapshotGeneration: Long) {
        pending = if (generation == snapshotGeneration) snapshot else current
    }

    fun clearPendingForDestroy() {
        pending = null
    }

    fun onDestroyed() {
        pending = null
        current = null
        generation++
    }
}
