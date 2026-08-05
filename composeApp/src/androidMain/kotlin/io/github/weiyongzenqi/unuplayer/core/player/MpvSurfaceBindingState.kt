package io.github.weiyongzenqi.unuplayer.core.player

internal data class MpvSurfaceSize(val width: Int, val height: Int)

/** Surface 引用状态本身不调用 native；调用方在同一生命周期锁内执行返回的 attach 动作。 */
internal class MpvSurfaceBindingState<T : Any> {
    @Volatile
    var current: T? = null
        private set

    /** 每次 Surface 到达或销毁递增，防止 HDR reinit 使用过期快照回绑旧 Surface。 */
    var generation: Long = 0L
        private set

    private var pending: T? = null
    private var attached: T? = null
    private var latestSize: MpvSurfaceSize? = null
    private var appliedSize: MpvSurfaceSize? = null

    /** native 已就绪时返回要立即 attach 的 Surface，否则缓存到 init 发布前。 */
    fun onAvailable(surface: T, nativeReady: Boolean): T? {
        current = surface
        generation++
        attached = null
        latestSize = null
        appliedSize = null
        return if (nativeReady) {
            pending = null
            surface
        } else {
            pending = surface
            null
        }
    }

    fun pendingForInitialization(): T? = pending

    fun markAttached(surface: T) {
        if (current === surface) {
            attached = surface
            if (pending === surface) pending = null
        }
    }

    /** 只记录有效尺寸；返回 true 表示该尺寸尚未应用到当前 native 实例。 */
    fun onSizeChanged(width: Int, height: Int): Boolean {
        if (current == null || width <= 0 || height <= 0) return false
        val size = MpvSurfaceSize(width, height)
        latestSize = size
        return appliedSize != size
    }

    /** 新 Surface 必须先完成 attach，尺寸更新不能越过 wid 绑定。 */
    fun pendingSurfaceSize(): MpvSurfaceSize? {
        if (current == null || attached !== current) return null
        return latestSize?.takeIf { it != appliedSize }
    }

    fun markSurfaceSizeApplied(surface: T, size: MpvSurfaceSize) {
        if (current === surface && attached === surface) appliedSize = size
    }

    fun retainCurrentForRetry() {
        pending = current
        attached = null
        appliedSize = null
    }

    /** HDR 重建跨过 destroy 后选择快照或期间到达的最新 Surface，不伪造一次外部 Surface 事件。 */
    fun retainForReinitialization(snapshot: T?, snapshotGeneration: Long) {
        pending = if (generation == snapshotGeneration) snapshot else current
        attached = null
        appliedSize = null
    }

    fun clearPendingForDestroy() {
        pending = null
        attached = null
        appliedSize = null
    }

    fun onDestroyed() {
        pending = null
        attached = null
        current = null
        latestSize = null
        appliedSize = null
        generation++
    }
}
