package io.github.weiyongzenqi.unuplayer.schedule

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiGatewayEndpoint
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiScrapeApi
import io.github.weiyongzenqi.unuplayer.bangumi.gatewayEndpointOrNull
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.domain.bangumiEndpoints
import io.github.weiyongzenqi.unuplayer.library.ScrapeFactory
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 延迟读取已加载的设置后构造时间表数据源，避免 Activity 冷启动时抓到 SettingsState 默认值。
 * 设置变化后下一次请求会换用新的端点/弹弹凭据，同时保留相同配置下的时间表缓存。
 */
class SettingsAwareScheduleRepository(
    private val settingsRepository: SettingsRepository,
    private val scrapedRepository: ScrapedLibraryRepository,
) : ScheduleRepository {
    private val mutex = Mutex()
    private var delegate: ScheduleRepositoryImpl? = null
    private var configuration: Configuration? = null

    override suspend fun load(forceRefresh: Boolean): ScheduleSnapshot = current().load(forceRefresh)

    override suspend fun loadSeason(year: Int, quarterMonth: Int, forceRefresh: Boolean): ScheduleSeasonSnapshot =
        current().loadSeason(year, quarterMonth, forceRefresh)

    override suspend fun searchAnime(query: String, limit: Int): List<ScheduleEntry> =
        current().searchAnime(query, limit)

    override suspend fun resolveAnime(subjectId: Long): ScheduleEntry? = current().resolveAnime(subjectId)

    override suspend fun setWatched(entry: ScheduleEntry, watched: Boolean) {
        current().setWatched(entry, watched)
    }

    override suspend fun setStatus(entry: ScheduleEntry, status: ScheduleStatus) {
        current().setStatus(entry, status)
    }

    private suspend fun current(): ScheduleRepositoryImpl {
        settingsRepository.awaitLoaded()
        val settings = settingsRepository.state.value
        val nextConfiguration = Configuration(
            dandanplayUseProxy = settings.dandanplayUseProxy,
            dandanplayAppId = settings.dandanplayAppId,
            dandanplayAppSecret = settings.dandanplayAppSecret,
            bangumiApiBaseUrl = settings.bangumiEndpoints().apiBaseUrl,
            bangumiNextApiBaseUrl = settings.bangumiEndpoints().nextApiBaseUrl,
            bangumiImageBaseUrl = settings.bangumiEndpoints().imageBaseUrl,
        )
        return mutex.withLock {
            if (delegate == null || configuration != nextConfiguration) {
                val endpoints = settings.bangumiEndpoints()
                delegate = ScheduleRepositoryImpl(
                    scrapedRepository = scrapedRepository,
                    bangumiGateway = BangumiGatewayEndpoint(),
                    dandanplayApi = ScrapeFactory.scheduleDandanplayApiFor(settings),
                    bangumiEpisodes = BangumiScrapeApi(
                        baseUrl = endpoints.apiBaseUrl,
                        gateway = endpoints.gatewayEndpointOrNull(),
                    ),
                )
                configuration = nextConfiguration
            }
            requireNotNull(delegate)
        }
    }

    private data class Configuration(
        val dandanplayUseProxy: Boolean,
        val dandanplayAppId: String,
        val dandanplayAppSecret: String,
        val bangumiApiBaseUrl: String,
        val bangumiNextApiBaseUrl: String,
        val bangumiImageBaseUrl: String,
    )
}
