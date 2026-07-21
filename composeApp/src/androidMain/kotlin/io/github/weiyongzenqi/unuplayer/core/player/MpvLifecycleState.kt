package io.github.weiyongzenqi.unuplayer.core.player

internal enum class MpvDestroyDecision {
    NONE,
    DEFERRED,
    CAPTURE_READY,
}

/**
 * mpv 初始化/销毁事务状态。所有变更都由 MpvPlayerEngine.lifecycleLock 串行调用，
 * volatile 只服务于事件线程和 release() 的只读快照。
 */
internal class MpvLifecycleState {
    private enum class Phase {
        IDLE,
        INITIALIZING,
        READY,
        FAILED_CLEANUP,
    }

    @Volatile
    private var phase = Phase.IDLE
    private var destroyRequested = false

    @Volatile
    var callbacksEnabled: Boolean = false
        private set

    val isReady: Boolean
        get() = phase == Phase.READY

    fun beginInitialization() {
        check(phase == Phase.IDLE) { "MpvPlayerEngine 已 init 过或仍在清理" }
        phase = Phase.INITIALIZING
        destroyRequested = false
        callbacksEnabled = true
    }

    /** 返回 true 表示初始化期间收到过 destroy，发布后必须立刻 capture。 */
    fun publishReady(): Boolean {
        check(phase == Phase.INITIALIZING) { "mpv 初始化状态无效" }
        phase = Phase.READY
        return destroyRequested
    }

    /** 初始化尚未发布时因播放器退出而中止，不把主动退出误报成初始化失败。 */
    fun abortInitialization() {
        check(phase == Phase.INITIALIZING) { "mpv 只能在初始化阶段中止" }
        phase = Phase.IDLE
        destroyRequested = false
        callbacksEnabled = false
    }

    fun beginFailedCleanup() {
        check(phase == Phase.INITIALIZING || phase == Phase.IDLE) { "mpv 不能重复进入失败清理" }
        phase = Phase.FAILED_CLEANUP
        callbacksEnabled = false
    }

    fun finishFailedCleanup() {
        check(phase == Phase.FAILED_CLEANUP) { "mpv 失败清理状态无效" }
        phase = Phase.IDLE
        destroyRequested = false
    }

    fun requestDestroy(): MpvDestroyDecision = when (phase) {
        Phase.IDLE -> MpvDestroyDecision.NONE
        Phase.INITIALIZING, Phase.FAILED_CLEANUP -> {
            destroyRequested = true
            MpvDestroyDecision.DEFERRED
        }
        Phase.READY -> MpvDestroyDecision.CAPTURE_READY
    }

    fun markCaptured() {
        phase = Phase.IDLE
        destroyRequested = false
        callbacksEnabled = false
    }
}
