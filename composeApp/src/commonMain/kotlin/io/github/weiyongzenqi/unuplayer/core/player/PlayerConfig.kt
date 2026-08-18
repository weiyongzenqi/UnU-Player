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
    /** HTTP 30x 策略。媒体服务器 token 头与 WebDAV Basic 头必须使用 DENY, 避免 FFmpeg 把
     *  自定义认证头(Authorization: Basic / MediaBrowser token)转发到跨源重定向地址。 */
    val httpRedirectPolicy: HttpRedirectPolicy = HttpRedirectPolicy.FOLLOW,
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
) {
    /** 生效的重定向策略: 携带重定向敏感认证头(WebDAV Basic / 媒体服务器 token)时无条件 DENY——
     *  FFmpeg http 协议重定向会原样重发自定义认证头且不做同源校验, FOLLOW 会把凭据转发给第三方。
     *  无敏感头时按调用方显式策略(默认 FOLLOW, 兼容无认证的普通媒体源)。 */
    internal val effectiveHttpRedirectPolicy: HttpRedirectPolicy
        get() = if (httpHeaders.hasRedirectSensitiveCredentials()) HttpRedirectPolicy.DENY else httpRedirectPolicy

    override fun toString(): String =
        "PlayerConfig(hwdec=$hwdec, audioOutput=$audioOutput, hdrMode=$hdrMode, cacheSize=$cacheSize, " +
            "cacheSecs=$cacheSecs, vo=$vo, httpHeaders=<redacted>, httpRedirectPolicy=$httpRedirectPolicy, " +
            "logLevel=$logLevel, allowTlsInsecure=$allowTlsInsecure)"
}

enum class HttpRedirectPolicy {
    FOLLOW,
    DENY,
}

/** mpv v0.41.0 通过 stream-lavf-o 把该选项传给 FFmpeg HTTP 协议。生效策略见 [PlayerConfig.effectiveHttpRedirectPolicy]。 */
internal fun PlayerConfig.streamLavfOptions(): String? = when (effectiveHttpRedirectPolicy) {
    HttpRedirectPolicy.FOLLOW -> null
    HttpRedirectPolicy.DENY -> "max_redirects=0"
}

/** 认证头是否属于"重定向敏感"(转发会给第三方泄密): WebDAV Basic 与媒体服务器 token 头。 */
private fun Map<String, String>.hasRedirectSensitiveCredentials(): Boolean = entries.any { (name, value) ->
    name.equals("X-Emby-Token", ignoreCase = true) ||
        name.equals("X-MediaBrowser-Token", ignoreCase = true) ||
        name.equals("X-Emby-Authorization", ignoreCase = true) ||
        name.equals("X-MediaBrowser-Authorization", ignoreCase = true) ||
        name.equals("Authorization", ignoreCase = true) && value.trimStart().let { authorization ->
            authorization.startsWith("MediaBrowser ", ignoreCase = true) ||
                authorization.startsWith("Emby ", ignoreCase = true) ||
                authorization.startsWith("Basic ", ignoreCase = true) // WebDAV 明文口令, 跨源重定向不得转发
        }
}

/** HDR 模式。见 DESIGN.md §11.3 */
enum class HdrMode {
    AUTO,            // 检测设备 HDR 能力, 有则直通, 无则 tone-map
    TONE_MAP_SDR,    // 强制 tone-mapping 到 SDR(最可靠)
    HDR_PASSTHROUGH, // 直通 PQ 到 Surface(实验性)
    OFF,             // 当 SDR 处理
}
