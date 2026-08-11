package io.github.weiyongzenqi.unuplayer.library

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.TmdbScrapeApi
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformFileLength
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayApi
import io.github.weiyongzenqi.unuplayer.danmaku.source.DandanplayProxyConfig
import io.github.weiyongzenqi.unuplayer.danmaku.source.calcDanmakuHash
import io.github.weiyongzenqi.unuplayer.danmaku.source.remoteHashForUrl
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 在线刮削工厂(commonMain): 从设置构造 [AnimeScraper] 与每季 hashProvider。
 *
 * 弹弹凭证策略同播放器: 默认走自建代理(签名/缓存/限流下沉服务端); 否则用户直连 appId/secret;
 * 弹弹凭证缺失时仅禁用弹弹文件名/hash路径；Bangumi 公开读免 key, 仍可独立执行刮削。
 */
object ScrapeFactory {

    fun createScraper(
        settings: SettingsState,
        repo: ScrapedLibraryRepository,
        downloader: RemoteImageDownloader,
    ): AnimeScraper {
        val dandanApi = dandanplayApiFor(settings)
        val bangumiEndpoints = settings.bangumiEndpoints()
        return AnimeScraper(
            dandanplay = dandanApi?.let(::DandanplayScrapeProvider),
            bangumi = BangumiScrapeProvider(BangumiScrapeApi(baseUrl = bangumiEndpoints.apiBaseUrl)),
            downloader = downloader,
            repo = repo,
            tmdb = tmdbApiFor(),
        )
    }

    /** TMDB 增强固定通过内置 Gateway，客户端不再持有或配置 TMDB 官方令牌。 */
    fun tmdbApiFor(): TmdbScrapeApi = TmdbScrapeApi()

    fun dandanplayApiFor(settings: SettingsState): DandanplayApi? = when {
        settings.dandanplayUseProxy ->
            DandanplayApi(baseUrl = DandanplayProxyConfig.proxyUrl(), proxyApiKey = DandanplayProxyConfig.apiKey())
        settings.dandanplayAppId.isNotBlank() ->
            DandanplayApi(settings.dandanplayAppId, settings.dandanplayAppSecret)
        else -> null
    }

    /**
     * 每季至多 1 文件前 16MB MD5 + size(见设计 §3 ②, 绝不遍历全集):
     * - LOCAL: 直接读文件(platformFileLength + calcDanmakuHash)
     * - WEBDAV: 经 [MediaSourceCache] 租用来源 resolvePlayMedia 取 URL + 认证头, Range GET 前 16MB
     * 媒体服务器(Jellyfin/Emby)/其他来源: null(跳过 hash, 回落文件名匹配)
     */
    fun buildHashProvider(
        library: LibraryConfig,
        mediaSourceCache: MediaSourceCache,
    ): (suspend (videoPath: String) -> Pair<Long, String>?)? = when (library.sourceKind) {
        MediaSourceKind.LOCAL -> { path ->
            withContext(Dispatchers.IO) {
                val size = platformFileLength(path)
                if (size > 0) Pair(size, calcDanmakuHash(path)) else null
            }
        }
        MediaSourceKind.WEBDAV -> { videoPath ->
            mediaSourceCache.withSource(library) { source ->
                val entry = MediaEntry(
                    name = videoPath.substringAfterLast('/').ifBlank { "video" },
                    path = videoPath,
                    isDirectory = false,
                )
                val media = source.resolvePlayMedia(entry)
                val auth = media.headers["Authorization"] ?: ""
                remoteHashForUrl(media.url, auth)
            }
        }
        else -> null
    }
}
