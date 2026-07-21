package io.github.weiyongzenqi.unuplayer.core.player

/** 播放器事件。由 PlayerEngine 把底层内核事件翻译成应用层事件后派发给 observer。 */
sealed class PlayerEvent {
    /** 文件加载完成, 可读媒体属性(FILE_LOADED)。 */
    object FileLoaded : PlayerEvent()

    /** 播放结束(END_FILE)。 */
    data class EndFile(val reason: EndReason) : PlayerEvent()

    /** 视频参数变化(VIDEO_RECONFIG), 分辨率/HDR 等可能变。 */
    object VideoReconfig : PlayerEvent()

    /** seek 开始(SEEK)。 */
    object Seek : PlayerEvent()

    /** seek 完成/播放恢复(PLAYBACK_RESTART)。 */
    object PlaybackRestart : PlayerEvent()

    /** 内核关闭(SHUTDOWN)。 */
    object Shutdown : PlayerEvent()
}

/** 播放结束原因。 */
enum class EndReason {
    EOF,        // 自然播完
    STOP,       // 被停止(加载新文件/destroy)
    QUIT,       // 退出
    ERROR,      // 出错
    REDIRECT,   // 重定向
}
