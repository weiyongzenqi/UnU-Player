package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.core.media.MediaSource

/**
 * 媒体来源工厂: 从刮削库配置([LibraryConfig])重建 [MediaSource]。
 *
 * 扫描器直接创建并在结束时关闭；海报墙 UI 通过 [MediaSourceCache] 统一持有和租用，
 * 避免图片下载、详情播放和跨库搜索各自维护生命周期。
 * androidMain 用 WebDavSource/LocalSource 实现(commonMain 不持有平台连接/SAF 细节)。
 */
interface MediaSourceFactory {
    /**
     * 按 library 配置创建所有权独立的新实例。连接/URI 失效返回 null；成功结果由调用方负责关闭。
     * 实现不得把同一个可关闭实例交给多个调用方。
     */
    suspend fun create(library: LibraryConfig): MediaSource?

    /**
     * 该配置对应来源的凭据指纹: 需要凭据的来源(WEBDAV)返回连接 "username:password" 的哈希,
     * 本地源等无凭据来源返回 null。
     *
     * 供 [MediaSourceCache] 纳入缓存身份: 指纹变化(如用户编辑连接密码)即判定缓存源凭据过期,
     * 命中失败改走 [create] 新建。**实现禁止返回凭据明文**(指纹会进入身份对象的 toString)。
     */
    suspend fun credentialsToken(library: LibraryConfig): String?
}
