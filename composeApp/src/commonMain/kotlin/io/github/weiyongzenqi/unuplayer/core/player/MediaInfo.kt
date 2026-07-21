package io.github.weiyongzenqi.unuplayer.core.player

/** 媒体技术信息。FILE_LOADED 后拉一次快照(大部分字段不变), HDR/解码器实时 observe。 */
data class MediaInfo(
    val title: String? = null,
    val filePath: String? = null,
    val containerFormat: String? = null,       // mp4/mkv/...
    val videoCodec: String? = null,            // h264/hevc/av1/...
    val audioCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val videoBitrate: Int = 0,                 // bps
    val audioBitrate: Int = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val durationMs: Long = 0,
    val hdrInfo: HdrInfo? = null,
    val hwdecCurrent: String? = null,          // 实际解码器("no"=软解)
    val requestedHwdec: String? = null,        // 用户请求的 hwdec(区分"选了直出实际软解")
    val effectiveHwdec: String? = null,        // 桌面实际提交给 mpv 的 copy-back 模式
    val vo: String? = null,                    // 视频输出渲染器("gpu-next")
    val gpuApi: String? = null,                // 图形 API("opengl"/"vulkan"), 渲染器跑在其上
    val rotation: Int = 0,                     // 视频旋转 metadata(0/90/180/270, 顺时针)
)

/** HDR 信息。从 video-params/gamma + primaries 检测。 */
data class HdrInfo(
    val isHDR: Boolean,
    val gamma: String? = null,         // "pq"/"hlg"/"bt.1886"/...
    val primaries: String? = null,     // "bt.2020"/"bt.709"/...
    val maxCll: Double = 0.0,          // 内容峰值亮度 nits
)
