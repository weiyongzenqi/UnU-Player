package io.github.weiyongzenqi.unuplayer.core.player

/**
 * 播放器配置。init() 前通过 setOptionString 应用(不可运行时改的项)。
 * 运行时可改的项(hwdec/ao/speed/hdrMode)通过 PlayerEngine 的 setter 热切换。
 */
data class PlayerConfig(
    val hwdec: String = "auto-copy",        // 见 11.1, 默认安全拷回硬解
    val audioOutput: String = "audiotrack", // 见 11.2, 默认保留系统后处理
    val hdrMode: HdrMode = HdrMode.AUTO,    // 见 11.3
    val maxSpeed: Float = 16f,              // scaletempo2 max-speed
    val minSpeed: Float = 0.1f,             // scaletempo2 min-speed
    val cacheSize: Int = 32,                // MiB, demuxer-max-bytes(默认 32, 内存-only)
    val cacheSecs: Int = 20,
    val vo: String = "gpu-next",            // gpu-next(HDR/质量) 或 gpu
    /** HTTP 头(init 前设 http-header-fields)。WebDAV basic auth 用 Authorization 头,
     *  不再用 URL 内嵌 user:pass@host(mpv 对 percent-encoding 解码不可靠)。 */
    val httpHeaders: Map<String, String> = emptyMap(),
    /** mpv log-level(error/warn/info/v/debug/trace), 仅日志开启时生效。 */
    val logLevel: String = "info",
    /** 允许 TLS 降级: 系统 CA 不可用时是否回退 tls-verify=no。默认 false(宁可播放失败)。
     *  init-only, 改了需重进播放器。 */
    val allowTlsInsecure: Boolean = false,

    // === 字幕(init-only 默认) ===
    /** sub-auto: 自动加载与视频同名字幕。no/fuzzy/exact/all。 */
    val subAuto: String = "fuzzy",
    /** sub-codepage: 字幕编码自动检测。 */
    val subCodepage: String = "auto",
    /** 以下字幕样式不是 setOption init-only；Android native init 完成后、load 前应用，并由 HDR reinit 复用。 */
    val subtitleFont: String = "",
    val subtitleFontDir: String? = null,
    val subtitleScale: Float = 1.0f,
    val subtitleColor: String = "#FFFFFFFF",
    val subtitleBorderSize: Float = 2.0f,
    val subtitleBold: Boolean = false,
    val subtitleStyleOverride: String = "force",
)

/** HDR 模式。见 DESIGN.md §11.3 */
enum class HdrMode {
    AUTO,            // 检测设备 HDR 能力, 有则直通, 无则 tone-map
    TONE_MAP_SDR,    // 强制 tone-mapping 到 SDR(最可靠)
    HDR_PASSTHROUGH, // 直通 PQ 到 Surface(实验性)
    OFF,             // 当 SDR 处理
}
