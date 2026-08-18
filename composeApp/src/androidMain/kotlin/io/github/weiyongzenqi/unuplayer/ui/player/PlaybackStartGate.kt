package io.github.weiyongzenqi.unuplayer.ui.player

internal data class PlaybackStartToken(
    val revision: Long,
    val loadGeneration: Int,
)

/** 把前台、用户播放意图和 load generation 绑定为一次可复核的 native start 许可。 */
internal class PlaybackStartGate(initialForeground: Boolean) {
    private var revision = 0L
    private var foreground = initialForeground
    private var playRequested = true

    @Synchronized
    fun capture(loadGeneration: Int): PlaybackStartToken = PlaybackStartToken(revision, loadGeneration)

    @Synchronized
    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        revision++
    }

    @Synchronized
    fun setPlayRequested(value: Boolean) {
        if (playRequested == value) return
        playRequested = value
        revision++
    }

    @Synchronized
    fun permits(token: PlaybackStartToken, currentLoadGeneration: Int): Boolean =
        foreground && playRequested && token.revision == revision && token.loadGeneration == currentLoadGeneration

    @Synchronized
    fun permitsCurrentPlayback(): Boolean = foreground && playRequested

    @Synchronized
    fun matchesLoad(token: PlaybackStartToken, currentLoadGeneration: Int): Boolean =
        token.loadGeneration == currentLoadGeneration
}
