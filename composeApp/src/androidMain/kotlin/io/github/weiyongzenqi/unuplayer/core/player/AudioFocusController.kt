package io.github.weiyongzenqi.unuplayer.core.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import io.github.weiyongzenqi.unuplayer.platform.AppLogger
import io.github.weiyongzenqi.unuplayer.platform.LogLevel

/**
 * 音频焦点控制器(B-03)。
 *
 * 背景: 全仓此前零音频焦点处理 —— 与其他应用音乐混音, 且不响应焦点抢占。
 * minSdk=26, 直接用 [AudioFocusRequest](API 26+), 无 legacy requestAudioFocus 分支。
 *
 * 线程模型: 不带 handler 的 [AudioManager.requestAudioFocus] 变体在主 Looper 派发回调;
 * 回调只发播放指令(engine.pause/play 内部入队), 绝不做 IO。成组状态由同步状态机维护。
 *
 * 焦点语义(保守):
 * - AUDIOFOCUS_LOSS → 暂停并释放请求，不等待 GAIN；用户手动恢复时重新申请焦点。
 * - AUDIOFOCUS_LOSS_TRANSIENT → 暂停并保留请求，GAIN 后自动恢复。
 * - AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK → 不处理(不做降音量, 简单起见)。
 * - AUDIOFOCUS_GAIN → 仅当"上次是因焦点丢失被暂停"([pausedByAudioFocusLoss])时自动恢复;
 *   用户手动暂停过的(pausedByAudioFocusLoss=false, 且 UI 层已在手动暂停时放弃焦点)不恢复。
 *
 * 与播放状态的联动由调用方(PlayerScreen)驱动:
 * - 进入播放态 → [requestForPlayback]
 * - 用户暂停/播完/出错/退出 → [abandonForPlayback](焦点丢失导致的暂停除外, 需保留请求等 GAIN)
 */
internal class AudioFocusController(
    context: Context,
    private val logger: AppLogger?,
    private val onRequestPause: () -> Unit,
    private val onRequestResume: () -> Unit,
) : AudioManager.OnAudioFocusChangeListener {

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val focusRequest: AudioFocusRequest? = audioManager?.let {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    // 动漫视频: 电影/音乐内容类型; MOVIE 优先, 系统据此做焦点仲裁。
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(this)
            .build()
    }

    private val focusState = AudioFocusState()

    val pausedByAudioFocusLoss: Boolean get() = focusState.waitingForGain

    /** 进入播放态: 请求焦点(幂等)。清焦点丢失标志(已恢复活跃播放, 不应再因旧 GAIN 误恢复)。 */
    fun requestForPlayback() {
        val manager = audioManager
        val request = focusRequest
        if (manager == null || request == null) return
        if (!focusState.beginPlaybackRequest()) return
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusState.completePlaybackRequest(granted)
        if (!granted) {
            // 未获焦点不拦播放(其他应用不配合焦点时仍可播), 只记日志。
            logger?.appEvent("player", "音频焦点请求未获授予, 继续播放", LogLevel.WARN)
        }
    }

    /**
     * 结束/用户暂停/出错/退出: 放弃焦点(幂等)。
     * 清焦点丢失标志: 主动放弃后不再期待 GAIN 自动恢复(用户手动暂停不应被恢复)。
     */
    fun abandonForPlayback() {
        val manager = audioManager
        val request = focusRequest
        val shouldAbandon = focusState.abandon()
        if (manager == null || request == null || !shouldAbandon) return
        runCatching { manager.abandonAudioFocusRequest(request) }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (focusState.gain()) {
                    logger?.appEvent("player", "音频焦点恢复, 自动继续播放", LogLevel.INFO)
                    onRequestResume()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                val manager = audioManager
                val request = focusRequest
                val shouldAbandon = focusState.permanentLoss()
                if (!shouldAbandon) return
                logger?.appEvent("player", "音频焦点永久丢失, 暂停并释放请求", LogLevel.INFO)
                if (manager != null && request != null && shouldAbandon) {
                    runCatching { manager.abandonAudioFocusRequest(request) }
                }
                onRequestPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!focusState.transientLoss()) return
                logger?.appEvent("player", "音频焦点瞬时丢失, 暂停并等待恢复", LogLevel.INFO)
                onRequestPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit  // 不降音量, 简单起见
        }
    }
}

/** 音频焦点请求状态机；成对状态在同一监视器内更新，避免永久 LOSS 留下陈旧请求。 */
internal class AudioFocusState {
    private var requestActive = false
    private var resumeOnGain = false

    val waitingForGain: Boolean
        @Synchronized get() = resumeOnGain

    /** 返回 true 表示调用方需要向系统发起请求。 */
    @Synchronized
    fun beginPlaybackRequest(): Boolean {
        resumeOnGain = false
        return !requestActive
    }

    @Synchronized
    fun completePlaybackRequest(granted: Boolean) {
        requestActive = granted
    }

    /** 返回 true 表示此前有活动请求，需要调用系统 abandon。 */
    @Synchronized
    fun abandon(): Boolean {
        resumeOnGain = false
        return requestActive.also { requestActive = false }
    }

    @Synchronized
    fun transientLoss(): Boolean {
        if (!requestActive || resumeOnGain) return false
        resumeOnGain = true
        return true
    }

    @Synchronized
    fun permanentLoss(): Boolean = abandon()

    /** 返回 true 表示应自动恢复播放。 */
    @Synchronized
    fun gain(): Boolean {
        val shouldResume = resumeOnGain
        if (shouldResume) requestActive = true
        resumeOnGain = false
        return shouldResume
    }
}
