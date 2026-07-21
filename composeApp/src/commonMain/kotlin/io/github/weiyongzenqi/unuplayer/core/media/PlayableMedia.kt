package io.github.weiyongzenqi.unuplayer.core.media

/**
 * 可播放媒体。播放器只认这个, 不关心来源是 WebDAV/SMB/Emby/本地/外部。
 *
 * 认证策略(见 DESIGN.md §6.4, 2026-06-25 更新):
 * - WebDAV basic auth 通过 Authorization 头传给 mpv(http-header-fields, init 前设),
 *   不再用 URL 内嵌 user:pass@host(mpv 对 percent-encoding 解码不可靠, 特殊字符密码会失败)。
 * - headers 保留为高级/兼容选项(当前播放路径用 init 时注入的 http-header-fields, 此字段预留)。
 */
data class PlayableMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    val sourceKind: MediaSourceKind,
    /** 原始 content://(本地 SAF 视频的 url 同样保持稳定 URI；Android 引擎每次 load 时转 fdclose://，哈希通过 ContentResolver 读取)。非 content 为 null。 */
    val contentUri: String? = null,
    /**
     * 播放记录稳定 key(WebDAV=webdav:{connId}:{path}; 本地=local:{contentUri}; 见 [io.github.weiyongzenqi.unuplayer.core.media.MediaKeys])。
     * 以"导航位置"区分文件, 不受 WebDAV 302 签名直链变更影响。source 层 fill;
     * 外部 Intent 拉起无导航上下文, 传 null, PlayerScreen fallback 用 url/contentUri 作 key。
     */
    val mediaKey: String? = null,
)
