package io.github.weiyongzenqi.unuplayer.core.player

/**
 * 播放状态。由 PlayerEngine 通过 StateFlow 暴露, UI 订阅。
 *
 * 当前播放位置(positionMs)不在本类内——它由 time-pos 高频更新(~每帧),
 * 单独走 [PlayerEngine.position] 流, 避免每帧变化驱动整个播放页重组。
 *
 * 所有字段都有默认值, 初始状态 = IDLE 空播放。
 * 由 MpvEventBridge 在 mpv 事件线程上用 update {} 更新(线程安全),
 * Compose 端在主线程 collectAsStateWithLifecycle 收集。
 */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val durationMs: Long = 0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val volume: Int = 100,
    val muted: Boolean = false,
    val rate: Float = 1.0f,
    val eof: Boolean = false,
    val error: String? = null,
)

/** 播放状态机 */
enum class PlaybackStatus {
    IDLE,       // 未加载
    LOADING,    // 加载中
    READY,      // 文件已加载, 可读属性
    PLAYING,    // 播放中
    PAUSED,     // 暂停
    ENDED,      // 播放结束(keep-open)
    ERROR,      // 错误
}
