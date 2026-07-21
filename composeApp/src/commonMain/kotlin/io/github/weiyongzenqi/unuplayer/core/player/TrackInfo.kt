package io.github.weiyongzenqi.unuplayer.core.player

/** 轨道列表(视频/音频/字幕)。track-list/count 变化时重建。 */
data class TrackList(
    val video: List<TrackInfo> = emptyList(),
    val audio: List<TrackInfo> = emptyList(),
    val subtitle: List<TrackInfo> = emptyList(),
)

data class TrackInfo(
    val id: Int,
    val type: TrackType,
    val title: String? = null,
    val lang: String? = null,
    val codec: String? = null,
    val external: Boolean = false,
    val selected: Boolean = false,
)

enum class TrackType { VIDEO, AUDIO, SUBTITLE }
