package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSource

/** 抽帧位置配置(来自设置, 阶段 A 硬编码 Percent(10))。 */
sealed interface EpisodeThumbPosition {
    /** 按视频时长百分比定位(0..100, 默认 10)。短视频自动回落首帧附近。 */
    data class Percent(val value: Int) : EpisodeThumbPosition
    /** 按固定秒数定位。短视频(<该秒)回落百分比。 */
    data class Seconds(val value: Int) : EpisodeThumbPosition
}

/**
 * 集照抽帧位置模式(设置项持久化用)。
 *
 * 对应 [EpisodeThumbPosition] 的 Percent/Seconds; 设置层用枚举便于序列化,
 * 详情页据此 + 数值构造 [EpisodeThumbPosition] 传 [EpisodeThumbCoordinator]。
 */
enum class EpisodeThumbPositionMode { PERCENT, SECONDS }

/**
 * 抽帧位置 -> 绝对秒数换算(两平台生成器共用, 抽到 commonMain 便于单测)。
 *
 * @param duration 视频时长(秒); <=0 表示取不到时长(部分流不报 duration)。
 * - [EpisodeThumbPosition.Percent]: duration>0 取 duration*value/100(coerce 到 [0, duration]);
 *   duration<=0 回落 0(首帧), 避免盲算。
 * - [EpisodeThumbPosition.Seconds]: duration<=0 回落 0(避免盲 seek 超时长);
 *   短视频(value >= duration)回落 duration*0.1; 否则用 value。
 */
fun EpisodeThumbPosition.toSeconds(duration: Double): Double = when (this) {
    is EpisodeThumbPosition.Percent ->
        if (duration > 0) (duration * value / 100.0).coerceIn(0.0, duration) else 0.0
    is EpisodeThumbPosition.Seconds -> when {
        duration <= 0 -> 0.0
        value.toDouble() >= duration -> duration * 0.1
        else -> value.toDouble()
    }
}

/**
 * 集照生成器。commonMain 接口, 实现在 platformMain(Android=JNA libmpv sw render, Windows 后续)。
 *
 * 对无刮削集照([ScrapedEpisode.thumb_path] 为空)的剧集, 用 libmpv software render API
 * headless seek 抽一帧 -> JPEG 写入 PosterCache, 返回缓存绝对路径。内部复用 [MediaSource.resolvePlayMedia]
 * 取 url+headers + 系统 CA bundle 取 TLS, 与正式播放路径一致。
 *
 * commonMain 禁 JVM API(CR-016), JNA 不得进 commonMain, 故仅定义接口。
 */
interface EpisodeThumbGenerator {
    /**
     * 对 [episode] 的视频抽一帧, JPEG 写 PosterCache, 返回缓存绝对路径; null=失败。
     *
     * 内部: [source].resolvePlayMedia 取 url+headers + SystemCaBundle 取 TLS + libmpv sw render 抽帧
     * + 写 PosterCache/<showKey>/ep<id>.jpg。
     *
     * @param episode 目标剧集(取 video_name/video_path)
     * @param showKey PosterCache 子目录(番剧缓存 key, 同 ScrapedShow.cacheKey)
     * @param source 调用方经 [MediaSourceCache.withSource] 租用的来源
     * @param position 抽帧位置(百分比或秒数)
     * @return 生成的 JPEG 绝对路径; null 表示失败(已记日志, 调用方跳过)
     */
    suspend fun generate(
        episode: ScrapedEpisode,
        showKey: String,
        source: MediaSource,
        position: EpisodeThumbPosition,
    ): String?
}
