package io.github.weiyongzenqi.unuplayer.library

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import io.github.weiyongzenqi.unuplayer.core.media.MediaSource
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.media.PlayableMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EpisodeThumbCoordinator] 集照懒加载协调器单测: 容错(单集失败不阻断)、CR-080(数据库写失败
 * 不触发 onUpdated)、回调正确性、Semaphore(2) 并发上限。
 *
 * 全部 fake, 不依赖网络/真机/图片文件(本地集照失效判定用必然不存在的绝对路径)。
 */
class EpisodeThumbCoordinatorTest {

    @Test
    fun `单集生成失败不阻断其余集`() = runBlocking {
        val repo = FakeScrapedRepo()
        val generator = FakeThumbGenerator(behavior = { ep ->
            if (ep.id == 2L) error("模拟抽帧失败") else "/cache/ep${ep.id}.jpg"
        })
        val updated = mutableListOf<Pair<Long, String>>()

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(episode(1), episode(2), episode(3)),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { id, path -> updated.add(id to path) }

        assertEquals(3, generator.generated.size)   // 三集都尝试了
        assertEquals(setOf(1L, 3L), repo.updated.map { it.first }.toSet())
        assertEquals(setOf(1L to "/cache/ep1.jpg", 3L to "/cache/ep3.jpg"), updated.toSet())
    }

    @Test
    fun `数据库写失败不触发 onUpdated 回调 CR-080`() = runBlocking {
        val repo = FakeScrapedRepo(failEpisodeIds = setOf(1L))
        val generator = FakeThumbGenerator(behavior = { ep -> "/cache/ep${ep.id}.jpg" })
        val updated = mutableListOf<Pair<Long, String>>()

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(episode(1), episode(2)),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { id, path -> updated.add(id to path) }

        // ep1 抽帧成功但 DB 写失败 -> 不回调(UI state 与 DB 必须一致); ep2 正常
        assertEquals(listOf(2L), updated.map { it.first })
        assertEquals(setOf(2L), repo.updated.map { it.first }.toSet())
        assertEquals(2, generator.generated.size)
    }

    @Test
    fun `生成返回 null 不写库不回调`() = runBlocking {
        val repo = FakeScrapedRepo()
        val generator = FakeThumbGenerator(behavior = { null })
        val updated = mutableListOf<Pair<Long, String>>()

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(episode(1), episode(2)),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { id, path -> updated.add(id to path) }

        assertTrue(repo.updated.isEmpty())
        assertTrue(updated.isEmpty())
        assertEquals(2, generator.generated.size)
    }

    @Test
    fun `有源端集照或本地集照有效的集跳过生成`() = runBlocking {
        val repo = FakeScrapedRepo()
        val generator = FakeThumbGenerator(behavior = { ep -> "/cache/ep${ep.id}.jpg" })

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(
                episode(1, thumbPath = "/server/ep1-thumb.jpg"),   // 源端有集照, 跳过
                episode(2, localThumbPath = "/definitely/not/exist/ep2.jpg"),   // 本地失效, 需重新生成
                episode(3),                                        // 什么都没有, 需生成
            ),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { _, _ -> }

        // 仅 ep2(本地文件不存在, platformFileExists=false)与 ep3 进入生成
        assertEquals(listOf(2L, 3L), generator.generated.sorted())
        assertEquals(setOf(2L, 3L), repo.updated.map { it.first }.toSet())
    }

    @Test
    fun `在线meta已有剧照的集跳过本地抽帧`() = runBlocking {
        val repo = FakeScrapedRepo()
        val generator = FakeThumbGenerator(behavior = { ep -> "/cache/ep${ep.id}.jpg" })

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(episode(1), episode(2)),
            onlineThumbEpisodeNumbers = setOf(1L),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { _, _ -> }

        assertEquals(listOf(2L), generator.generated)
        assertEquals(setOf(2L), repo.updated.map { it.first }.toSet())
    }

    @Test
    fun `批量生成回报初始和完成进度并统计数据库成功数`() = runBlocking {
        val repo = FakeScrapedRepo()
        val generator = FakeThumbGenerator(
            behavior = { episode -> if (episode.id == 2L) null else "/cache/ep${episode.id}.jpg" },
        )
        val progress = mutableListOf<EpisodeThumbCoordinator.Progress>()

        val result = EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(episode(1), episode(2), episode(3)),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
            onProgress = { progress += it },
        ) { _, _ -> }

        assertEquals(3, result.total)
        assertEquals(2, result.generated)
        assertEquals(0, progress.first().completed)
        assertEquals(3, progress.last().completed)
        assertEquals(2, progress.last().generated)
    }

    @Test
    fun `存量黑图文件过小视为无效需重生成 C-02`() = runBlocking {
        val repo = FakeScrapedRepo()
        val generator = FakeThumbGenerator(behavior = { ep -> "/cache/ep${ep.id}.jpg" })
        // 旧版"全黑仍写盘"遗留: 纯色 JPEG 理论 1198B, 文件存在但过小 -> 应判无效重生成
        val blackThumb = File.createTempFile("ep-thumb-black", ".jpg").apply {
            writeBytes(ByteArray(1198))
            deleteOnExit()
        }
        // 正常集照 ≥4KB -> 有效, 跳过
        val validThumb = File.createTempFile("ep-thumb-valid", ".jpg").apply {
            writeBytes(ByteArray(4096))
            deleteOnExit()
        }

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = listOf(
                episode(1, localThumbPath = blackThumb.absolutePath),   // 黑图固化, 需重生成
                episode(2, localThumbPath = validThumb.absolutePath),   // 有效本地集照, 跳过
            ),
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { _, _ -> }

        assertEquals(listOf(1L), generator.generated)
        assertEquals(setOf(1L), repo.updated.map { it.first }.toSet())
    }

    @Test
    fun `并发上限为 Semaphore 2`() = runBlocking {
        // holdMs 让每次生成挂起一会, 制造并发窗口; 4 集并发峰值必须恰好 2
        val generator = FakeThumbGenerator(
            behavior = { ep -> "/cache/ep${ep.id}.jpg" },
            holdMs = 80L,
        )
        val repo = FakeScrapedRepo()

        EpisodeThumbCoordinator.ensureThumbs(
            episodes = List(4) { episode((it + 1).toLong()) },
            showKey = "show",
            library = library(),
            mediaSourceCache = sourceCache(),
            generator = generator,
            position = EpisodeThumbPosition.Percent(10),
            scrapedRepo = repo,
        ) { _, _ -> }

        assertEquals(4, generator.generated.size)
        assertEquals(2, generator.peakConcurrency.get())
    }

    // === fake 与辅助 ===

    private fun episode(id: Long, thumbPath: String? = null, localThumbPath: String? = null) = ScrapedEpisode(
        id = id,
        season_id = 1L,
        show_id = 1L,
        episode_number = id,
        title = null,
        plot = null,
        aired = null,
        year = null,
        runtime = null,
        rating = null,
        video_path = "/media/ep$id.mkv",
        video_name = "ep$id.mkv",
        thumb_path = thumbPath,
        local_thumb_path = localThumbPath,
        media_key = null,
        file_size = null,
        scanned_at = 0L,
    )

    private fun library() = LibraryConfig(
        id = 7L,
        name = "测试库",
        sourceKind = MediaSourceKind.LOCAL,
        connectionId = null,
        localUri = "/media",
        rootPath = "/media",
        scanDepth = 3,
        lastScannedAt = null,
        createdAt = 1L,
    )

    private fun sourceCache() = MediaSourceCache(
        object : MediaSourceFactory {
            override suspend fun create(library: LibraryConfig): MediaSource = FakeMediaSource()
            override suspend fun credentialsToken(library: LibraryConfig): String? = null
        },
    )

    private class FakeMediaSource : MediaSource {
        override val kind: MediaSourceKind = MediaSourceKind.LOCAL
        override val displayName: String = "fake"
        override suspend fun listFolder(path: String): List<MediaEntry> = emptyList()
        override suspend fun resolvePlayMedia(entry: MediaEntry): PlayableMedia = error("未用于本测试")
        override suspend fun testConnection(): Boolean = true
        override fun close() {}
    }

    private class FakeThumbGenerator(
        private val behavior: (ScrapedEpisode) -> String?,
        private val holdMs: Long = 0L,
    ) : EpisodeThumbGenerator {
        val generated = mutableListOf<Long>()
        private val current = AtomicInteger(0)
        val peakConcurrency = AtomicInteger(0)

        override suspend fun generate(
            episode: ScrapedEpisode,
            showKey: String,
            source: MediaSource,
            position: EpisodeThumbPosition,
        ): String? {
            generated.add(episode.id)
            val now = current.incrementAndGet()
            peakConcurrency.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                if (holdMs > 0) delay(holdMs)
                return behavior(episode)
            } finally {
                current.decrementAndGet()
            }
        }
    }

    /** 仅 updateEpisodeLocalThumb 有意义实现; 其余方法本测试不调, 调到即失败。 */
    private class FakeScrapedRepo(
        private val failEpisodeIds: Set<Long> = emptySet(),
    ) : ScrapedLibraryRepository {
        val updated = mutableListOf<Pair<Long, String?>>()

        override suspend fun updateEpisodeLocalThumb(episodeId: Long, path: String?) {
            if (episodeId in failEpisodeIds) error("模拟数据库写失败")
            updated.add(episodeId to path)
        }

        override suspend fun listLibraries(): List<LibraryConfig> = TODO("未用于本测试")
        override suspend fun getLibrary(id: Long): LibraryConfig? = TODO("未用于本测试")
        override suspend fun addLibrary(
            name: String, sourceKind: MediaSourceKind,
            connectionId: String?, localUri: String?,
            rootPath: String, scanDepth: Int,
            scanMode: ScanMode, anchorFilenames: List<String>,
        ): Long = TODO("未用于本测试")
        override suspend fun updateLibraryRoot(id: Long, rootPath: String, scanDepth: Int) = TODO("未用于本测试")
        override suspend fun updateLibrary(id: Long, name: String, rootPath: String, scanDepth: Int) = TODO("未用于本测试")
        override suspend fun deleteLibrary(id: Long) = TODO("未用于本测试")
        override suspend fun setLibraryScanned(id: Long, timestampMs: Long) = TODO("未用于本测试")
        override suspend fun listShows(libraryId: Long, sortBy: PosterWallSort): List<ListShowsByLibrary> = TODO("未用于本测试")
        override suspend fun listHidden(libraryId: Long): List<ListShowsByLibrary> = TODO("未用于本测试")
        override suspend fun getShow(showId: Long): ScrapedShow? = TODO("未用于本测试")
        override suspend fun getShowByPath(libraryId: Long, showPath: String): ScrapedShow? = TODO("未用于本测试")
        override suspend fun showExists(libraryId: Long, showPath: String): Boolean = TODO("未用于本测试")
        override suspend fun listShowPaths(libraryId: Long): List<String> = TODO("未用于本测试")
        override suspend fun searchShows(keyword: String, libraryId: Long?): List<ListShowsByLibrary> = TODO("未用于本测试")
        override suspend fun listRecentlyPlayed(libraryId: Long?, limit: Int): List<RecentShow> = TODO("未用于本测试")
        override suspend fun listSeasons(showId: Long): List<ScrapedSeason> = TODO("未用于本测试")
        override suspend fun listSeasonsByTmdb(libraryId: Long, tmdbId: Long): List<ScrapedSeason> = TODO("未用于本测试")
        override suspend fun listEpisodes(seasonId: Long): List<ScrapedEpisode> = TODO("未用于本测试")
        override suspend fun getEpisodesByMediaKeys(mediaKeys: List<String>): Map<String, ScrapedEpisode> = TODO("未用于本测试")
        override suspend fun upsertShow(
            libraryId: Long, sourceKind: MediaSourceKind, tmdbId: Long?, folderName: String, showPath: String,
            title: String, originalTitle: String?, year: Int?, plot: String?, rating: Double?, releaseDate: String?,
            genres: List<String>, studios: List<String>,
            posterPath: String?, fanartPath: String?, clearlogoPath: String?, scannedAt: Long,
            seasons: List<SeasonScanData>,
            replaceAllSeasons: Boolean,
        ): Long = TODO("未用于本测试")
        override suspend fun deleteShow(showId: Long) = TODO("未用于本测试")
        override suspend fun setFavorite(showId: Long, favorite: Boolean) = TODO("未用于本测试")
        override suspend fun setHidden(showId: Long, hidden: Boolean) = TODO("未用于本测试")
        override suspend fun blockShow(showId: Long) = TODO("未用于本测试")
        override suspend fun unblock(blockedId: Long) = TODO("未用于本测试")
        override suspend fun listBlocked(libraryId: Long): List<ScrapedBlocked> = TODO("未用于本测试")
        override suspend fun isBlocked(libraryId: Long, showPath: String): Boolean = TODO("未用于本测试")
        override suspend fun getShowOverrideJson(identityKey: String): String? = TODO("未用于本测试")
        override suspend fun upsertShowOverride(identityKey: String, overridesJson: String, updatedAt: Long) = TODO("未用于本测试")
        override suspend fun clearShowOverride(identityKey: String) = TODO("未用于本测试")
        override suspend fun getBangumiSeasonLink(identityKey: String) = TODO("未用于本测试")
        override suspend fun upsertBangumiSeasonLink(
            link: io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink,
        ) = TODO("未用于本测试")
        override suspend fun clearBangumiSeasonLink(identityKey: String) = TODO("未用于本测试")
        override suspend fun upsertOnlineMeta(
            libraryId: Long, showPath: String, seasonNumber: Int,
            source: ScrapeSource, overwriteTitle: Boolean,
            dandanplayId: Long?, bangumiId: Long?,
            remotePosterUrl: String?, localPosterPath: String?,
            title: String?, originalTitle: String?, year: Int?, plot: String?, rating: Double?,
            releaseDate: String?, genres: List<String>, studios: List<String>,
            episodes: List<ScrapedOnlineEpisode>, scrapedAt: Long,
        ) = TODO("未用于本测试")
        override suspend fun getOnlineMeta(libraryId: Long, showPath: String, seasonNumber: Int): ScrapedOnlineMeta? = TODO("未用于本测试")
        override suspend fun listOnlineMeta(libraryId: Long, showPath: String): List<ScrapedOnlineMeta> = TODO("未用于本测试")
        override suspend fun recordAutoScrapeAttempt(libraryId: Long, showPath: String, attemptedAt: Long) = TODO("未用于本测试")
        override suspend fun markAutoScrapeRetryable(libraryId: Long, showPath: String) = TODO("未用于本测试")
        override suspend fun hasAutoScrapeRetryMarker(libraryId: Long, showPath: String): Boolean = TODO("未用于本测试")
        override suspend fun updateOnlineMetaFanart(
            libraryId: Long, showPath: String, remoteFanartUrl: String?, localFanartPath: String?,
        ) = TODO("未用于本测试")
        override suspend fun updateOnlineMetaEpisodes(
            libraryId: Long, showPath: String, seasonNumber: Int, episodes: List<ScrapedOnlineEpisode>,
        ) = TODO("未用于本测试")
        override suspend fun migrateBangumiSeasonLinksToTmdb(libraryId: Long, showPath: String, tmdbId: Long) = TODO()
        override suspend fun resetOnlineTmdbEnrichment(
            libraryId: Long, showPath: String, clearShowTmdbId: Boolean,
        ) = TODO()
        override suspend fun persistTmdbId(
            libraryId: Long, showPath: String, tmdbId: Long, source: ScrapeSource, scrapedAt: Long,
        ) = TODO("未用于本测试")
        override suspend fun lastOnlineScrapeAt(libraryId: Long, showPath: String): Long? = TODO("未用于本测试")
        override suspend fun recordTmdbAutoMatchFailure(libraryId: Long, showPath: String, failedAt: Long) =
            TODO("未用于本测试")
        override suspend fun getTmdbAutoMatchFailure(
            libraryId: Long,
            showPath: String,
        ): TmdbAutoMatchFailureState? = TODO("未用于本测试")
        override suspend fun suppressTmdbAutoMatchPrompt(libraryId: Long, showPath: String) = TODO("未用于本测试")
        override suspend fun clearTmdbAutoMatchFailure(libraryId: Long, showPath: String) = TODO("未用于本测试")
        override suspend fun listScrapePending(
            libraryId: Long?,
            anchorOnly: Boolean,
            requireTmdbIdentity: Boolean,
        ): List<ScrapePendingShow> = TODO("未用于本测试")
        override suspend fun deleteOnlineMetaByShow(libraryId: Long, showPath: String) = TODO("未用于本测试")
        override suspend fun reapplyOnlineMeta(libraryId: Long, showPath: String) = TODO("未用于本测试")
        override suspend fun deleteShowAndBlock(showId: Long): String? = TODO("未用于本测试")
        override suspend fun clearShowCache(showId: Long) = TODO("未用于本测试")
        override suspend fun restoreNfoState(showId: Long) = TODO("未用于本测试")
        override suspend fun deleteAllScrapedData() = TODO("未用于本测试")
        override suspend fun countShows(libraryId: Long): Int = TODO("未用于本测试")
        override suspend fun countEpisodes(libraryId: Long): Int = TODO("未用于本测试")
        override suspend fun checkpointTruncate() = TODO("未用于本测试")
    }
}
