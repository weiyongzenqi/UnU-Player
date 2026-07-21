package io.github.weiyongzenqi.unuplayer.core.player

import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器引擎抽象。接口在 commonMain, 实现在 platformMain(Android: MpvPlayerEngine)。
 *
 * 设计原则(见 DESIGN.md §4.2/§6.1):
 * - 屏蔽内核细节, 未来桌面端可换 mpv 其他绑定/VLC
 * - 暴露通用控制 + 透传 mpv 原生属性(高级/技术信息面板用)
 * - 状态通过 StateFlow 暴露, 线程安全
 *
 * 实现约束(MpvPlayerEngine 必须遵守, 见 DESIGN.md §7.6):
 * - setOptionString 只在 init() 前有效
 * - destroy() 阻塞, 必须在 IO 协程调用
 * - 底层事件回调在非主线程, 更新 StateFlow 用 update {}
 *
 * 继承 AutoCloseable(Kotlin 跨平台类型): 与 MediaSource 一致, 未来 iOS/native target
 * 也能直接用，资源关闭由平台 engine 自行实现。
 */
interface PlayerEngine : AutoCloseable {

    /** 内核名, 如 "MPV" */
    val kernelName: String

    /** 播放状态(播放中/暂停/时长/缓冲/音量/倍速...)。
     *  注意: 当前播放位置(positionMs)不在此 state 内, 见 [position](高频更新单独走流, 避免整个 UI 重组)。 */
    val state: StateFlow<PlayerState>

    /** 当前播放位置(ms)。time-pos 高频更新(~每帧), 单独成流让 UI 只在进度条叶节点收集,
     *  避免 state 每帧变化驱动整个播放页重组。 */
    val position: StateFlow<Long>

    /** 当前媒体技术信息 */
    val mediaInfo: StateFlow<MediaInfo?>

    /** 轨道列表(视频/音频/字幕) */
    val tracks: StateFlow<TrackList>

    // === 生命周期 ===

    /** 初始化内核。config 中的选项在此时通过 setOptionString 应用。 */
    fun init(config: PlayerConfig)

    /** 销毁内核(阻塞, 在 IO 协程调用)。 */
    fun destroy()

    // === 播放控制 ===

    /** 加载 URL 播放。认证/自定义 HTTP 头在 init() 时通过 PlayerConfig.httpHeaders
     *  以 http-header-fields 注入(init-only option, 运行时不可改), 故此处不带 headers。 */
    fun load(url: String)

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Int)          // 0-100
    fun setRate(rate: Float)            // 倍速, scaletempo2 范围内
    fun setMuted(muted: Boolean)

    // === 轨道 ===

    fun setAudioTrack(id: Int)
    fun setSubtitleTrack(id: Int)
    fun setVideoTrack(id: Int)

    // === 字幕(外挂加载 + 样式) ===

    /** 加载外挂字幕文件。path 为本地文件绝对路径(mpv 直读), 可选 title。 */
    fun addExternalSubtitle(path: String, title: String? = null)

    /** 设置字幕样式相关 mpv 属性(字体/缩放/颜色/描边/粗体/ASS 覆盖)。运行时热切换。 */
    fun applySubtitleStyle(
        font: String,
        fontDir: String?,
        scale: Float,
        color: String,
        borderSize: Float,
        bold: Boolean,
        styleOverride: String,
    )

    // === 运行时配置(热切换) ===

    /** hwdec 值, 见 11.1。运行时切换会重init 解码器。 */
    fun setHardwareDecoding(mode: String)

    /** "audiotrack"/"opensles", 见 11.2。切换有 200-500ms 静音。 */
    fun setAudioOutput(ao: String)

    /** HDR 模式, 见 11.3。运行时热切换(target-colorspace-hint/tone-mapping/hdr-compute-peak)。 */
    fun setHdrMode(mode: HdrMode)

    // === mpv 原生属性透传(高级/技术信息面板) ===

    fun getPropertyString(name: String): String?
    fun getPropertyInt(name: String): Int?
    fun getPropertyDouble(name: String): Double?
    fun getPropertyBoolean(name: String): Boolean?
    fun setPropertyString(name: String, value: String)
    fun setOptionString(name: String, value: String)  // 仅 init() 前
    fun observeProperty(name: String, format: Int)
    fun command(args: Array<String>)                  // 注意是 Array<String>

    // === 事件观察 ===

    fun addObserver(observer: PlayerEventObserver)
    fun removeObserver(observer: PlayerEventObserver)
}

/**
 * 应用层事件观察者。由 PlayerEngine 派发翻译后的高层事件。
 *
 * 注意: 回调可能在与内核事件线程关联的上下文触发, 需要更新 UI 时 marshal 到主线程。
 */
interface PlayerEventObserver {
    fun onEvent(event: PlayerEvent)
    fun onPropertyChanged(name: String, value: Any?)
}
