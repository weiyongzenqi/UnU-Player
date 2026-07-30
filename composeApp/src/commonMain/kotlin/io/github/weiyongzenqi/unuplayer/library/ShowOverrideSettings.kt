package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuConfig
import io.github.weiyongzenqi.unuplayer.danmaku.model.toSupportedDanmakuEngineType
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 节目专属设置覆盖(稀疏模型): 字段全可空, null=跟随全局, 非 null=本部自定义。
 * 序列化为 JSON 存 ShowSettingsOverride.overrides_json。新增可覆盖项(字幕/音轨, 三期)
 * 只需在此加可空字段, ignoreUnknownKeys 保证旧读者前向兼容。
 */
@Serializable
data class ShowOverrideSettings(
    // === 弹幕(对应 DanmakuConfig) ===
    val danmakuOpacity: Float? = null,
    val danmakuFontSize: Float? = null,
    val danmakuDisplayArea: Float? = null,
    val danmakuSpeedMultiplier: Float? = null,
    val danmakuStrokeWidth: Float? = null,
    val danmakuTimeOffsetSec: Double? = null,
    val danmakuMaxOnScreen: Int? = null,
    val danmakuHideScroll: Boolean? = null,
    val danmakuHideTop: Boolean? = null,
    val danmakuHideBottom: Boolean? = null,
    val danmakuEngine: String? = null,   // DanmakuEngineType.name; null=跟随全局
    // === 字幕(对应 SettingsState 字幕样式; null=跟随全局) ===
    val subtitleScale: Float? = null,
    val subtitleBorderSize: Float? = null,
    val subtitleBold: Boolean? = null,
    // === 轨道偏好(null=跟随全局) ===
    val defaultSubtitleTrackPattern: String? = null,
    val defaultAudioTrackPattern: String? = null,
) {
    /** 无任何自定义(全 null)。 */
    fun isEmpty(): Boolean = this == ShowOverrideSettings()
}

/**
 * 覆盖身份键。刮削番剧(有 tmdbId)跨库共用一份("tmdb:<id>", 对齐 listRecentlyPlayedShows 的
 * tmdb 跨库聚合); ANCHOR 无 tmdbId 回退单库("show:<libraryId>:<showPath>")。只构造/匹配, 不反解析。
 */
object ShowOverrideIdentity {
    fun tmdb(tmdbId: Long): String = "tmdb:$tmdbId"
    fun anchor(libraryId: Long, showPath: String): String = "show:$libraryId:$showPath"
    fun keyFor(tmdbId: Long?, libraryId: Long, showPath: String): String =
        if (tmdbId != null) tmdb(tmdbId) else anchor(libraryId, showPath)
}

/** 覆盖 JSON 编解码。encodeDefaults 默认 false -> null 字段省略(稀疏); ignoreUnknownKeys 前向兼容。 */
object ShowOverrideJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(override: ShowOverrideSettings): String = json.encodeToString(override)
    fun decode(raw: String): ShowOverrideSettings? =
        runCatching { json.decodeFromString<ShowOverrideSettings>(raw) }.getOrNull()
}

/** 把本部覆盖叠加到全局弹幕配置: 覆盖字段非 null 用覆盖, 否则用全局(self)。o=null 原样返回。 */
fun DanmakuConfig.withOverride(o: ShowOverrideSettings?): DanmakuConfig {
    if (o == null) return this
    return copy(
        opacity = o.danmakuOpacity ?: opacity,
        fontSize = o.danmakuFontSize ?: fontSize,
        displayArea = o.danmakuDisplayArea ?: displayArea,
        speedMultiplier = o.danmakuSpeedMultiplier ?: speedMultiplier,
        strokeWidth = o.danmakuStrokeWidth ?: strokeWidth,
        timeOffsetSec = o.danmakuTimeOffsetSec ?: timeOffsetSec,
        maxOnScreen = o.danmakuMaxOnScreen ?: maxOnScreen,
        hideScroll = o.danmakuHideScroll ?: hideScroll,
        hideTop = o.danmakuHideTop ?: hideTop,
        hideBottom = o.danmakuHideBottom ?: hideBottom,
        engineType = o.danmakuEngine?.toSupportedDanmakuEngineType() ?: engineType,
    )
}

/** 依据有效配置旧->新逐字段差异把变动字段写入覆盖(稀疏: 未变字段保持原值)。播放页调整自动写入用。 */
fun ShowOverrideSettings.diffUpdate(old: DanmakuConfig, new: DanmakuConfig): ShowOverrideSettings = copy(
    danmakuOpacity = if (old.opacity != new.opacity) new.opacity else danmakuOpacity,
    danmakuFontSize = if (old.fontSize != new.fontSize) new.fontSize else danmakuFontSize,
    danmakuDisplayArea = if (old.displayArea != new.displayArea) new.displayArea else danmakuDisplayArea,
    danmakuSpeedMultiplier = if (old.speedMultiplier != new.speedMultiplier) new.speedMultiplier else danmakuSpeedMultiplier,
    danmakuStrokeWidth = if (old.strokeWidth != new.strokeWidth) new.strokeWidth else danmakuStrokeWidth,
    danmakuTimeOffsetSec = if (old.timeOffsetSec != new.timeOffsetSec) new.timeOffsetSec else danmakuTimeOffsetSec,
    danmakuMaxOnScreen = if (old.maxOnScreen != new.maxOnScreen) new.maxOnScreen else danmakuMaxOnScreen,
    danmakuHideScroll = if (old.hideScroll != new.hideScroll) new.hideScroll else danmakuHideScroll,
    danmakuHideTop = if (old.hideTop != new.hideTop) new.hideTop else danmakuHideTop,
    danmakuHideBottom = if (old.hideBottom != new.hideBottom) new.hideBottom else danmakuHideBottom,
    danmakuEngine = if (old.engineType != new.engineType) new.engineType.name else danmakuEngine,
)
