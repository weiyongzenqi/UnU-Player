package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformFileExists
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.ListShowsByLibrary
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScrapedOnlineMeta
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.cacheKey
import io.github.weiyongzenqi.unuplayer.library.onlineScrapeCacheKey
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导出选项(核心数据不可关: 库配置/连接/番剧结构/在线关联)。 */
data class ExportOptions(
    /** 是否把连接密码带入导出包；开启后必须设置迁移口令，密码不会明文落盘。 */
    val includePassword: Boolean = false,
    /** 只在本次导出内存中使用的迁移口令，不写入导出包或应用存储。 */
    val exportPassword: String? = null,
    /** 是否打包本地缓存图(默认关; 集照导入后懒加载)。 */
    val includeImages: Boolean = false,
    /** 是否导出播放进度(默认带)。 */
    val includePlayback: Boolean = true,
    /** 是否导出本部专属设置覆盖(默认带)。 */
    val includeOverrides: Boolean = true,
    /** 是否导出屏蔽列表(默认带)。 */
    val includeBlocked: Boolean = true,
) {
    override fun toString(): String =
        "ExportOptions(includePassword=$includePassword, exportPassword=<redacted>, includeImages=$includeImages, " +
            "includePlayback=$includePlayback, includeOverrides=$includeOverrides, includeBlocked=$includeBlocked)"
}

/** 导出产物: 描述文件 + 数据文件 JSON + 待打包图片清单。 */
data class ExportOutput(
    val manifestJson: String,
    val dataJson: String,
    val imageFiles: List<ImageExportFile>,
)

/** 一张待打包图片(zip 条目名 -> 源绝对路径)。 */
data class ImageExportFile(val zipEntryName: String, val sourceAbsolutePath: String)

/**
 * 媒体库导出(commonMain 纯逻辑)。
 *
 * 读某 WebDAV/SMB 库全部数据 -> manifest/data JSON + 图片清单;
 * 落盘(zip 打包)由平台层完成(见 UI 接线)。
 */
class LibraryExporter(
    private val scrapedRepo: ScrapedLibraryRepository,
    private val webDavRepository: WebDavConnectionRepository,
    private val smbRepository: SmbConnectionRepository?,
    private val playbackRepository: PlaybackRecordRepository,
    private val imageService: LibraryImageService,
) {
    suspend fun exportLibrary(libraryId: Long, options: ExportOptions): ExportOutput = withContext(Dispatchers.IO) {
        if (options.includePassword) {
            require(options.exportPassword.orEmpty().length >= LIBRARY_EXPORT_MIN_PASSWORD_LENGTH) {
                "导出密码至少需要 $LIBRARY_EXPORT_MIN_PASSWORD_LENGTH 个字符"
            }
        }
        val library = scrapedRepo.getLibrary(libraryId)
            ?: throw IllegalArgumentException("媒体库不存在")
        val sourceKind = library.sourceKind
        require(sourceKind == MediaSourceKind.WEBDAV || sourceKind == MediaSourceKind.SMB) {
            "仅支持 WebDAV/SMB 媒体库导出"
        }
        val connectionExport = loadConnectionExport(library, options)

        val libraryExport = LibraryExport(
            libraryId = library.id,
            name = library.name,
            rootPath = library.rootPath,
            scanDepth = library.scanDepth,
            scanMode = library.scanMode.name,
            anchorFilenames = library.anchorFilenames,
            lastScannedAt = library.lastScannedAt,
        )

        val allShows = scrapedRepo.listShows(libraryId)
        val allLinks = scrapedRepo.listBangumiSeasonLinksByLibrary(libraryId)
        val allOverrides = scrapedRepo.listShowOverridesByLibrary(libraryId)
        val blocked = if (options.includeBlocked) scrapedRepo.listBlocked(libraryId) else emptyList()

        val showExports = mutableListOf<ShowExport>()
        val imageFiles = mutableListOf<ImageExportFile>()
        val seenEntries = mutableSetOf<String>()
        val episodeMediaKeys = mutableListOf<String>()
        var totalEpisodes = 0

        for (row in allShows) {
            val seasonExports = mutableListOf<SeasonExport>()
            val episodeIdToNumber = mutableMapOf<Long, Pair<Int, Int>>()
            val metas = scrapedRepo.listOnlineMeta(libraryId, row.show_path)
            val showMeta = metas.firstOrNull { it.season_number == 0L }?.toOnlineMetaExport()
            val metasBySeason = metas.filter { it.season_number > 0L }.associateBy { it.season_number.toInt() }

            for (season in scrapedRepo.listSeasons(row.id)) {
                val episodes = scrapedRepo.listEpisodes(season.id)
                for (episode in episodes) {
                    episodeIdToNumber[episode.id] = season.season_number.toInt() to episode.episode_number.toInt()
                    episode.media_key?.let { episodeMediaKeys.add(it) }
                    totalEpisodes++
                }
                seasonExports += season.toSeasonExport(
                    episodes.map { it.toEpisodeExport() },
                    metasBySeason[season.season_number.toInt()]?.toOnlineMetaExport(),
                )
            }

            val links = buildList {
                for (season in scrapedRepo.listSeasons(row.id)) {
                    val key = BangumiSeasonIdentity.keyFor(
                        tmdbId = row.tmdb_id, libraryId = libraryId,
                        showPath = row.show_path, seasonNumber = season.season_number,
                    )
                    allLinks.firstOrNull { it.identityKey == key }?.let { add(it.toLinkExport()) }
                }
            }
            val overrideKey = ShowOverrideIdentity.keyFor(row.tmdb_id, libraryId, row.show_path)
            val overrideJson = if (options.includeOverrides) {
                allOverrides.firstOrNull { it.identityKey == overrideKey }?.overridesJson
            } else {
                null
            }

            showExports += row.toShowExport(seasonExports, showMeta, links, overrideJson)

            if (options.includeImages) {
                collectImages(row, metas, episodeIdToNumber, imageFiles, seenEntries)
            }
        }

        val playbackExports = mutableListOf<PlaybackExport>()
        val progressExports = mutableListOf<EpisodeProgressExport>()
        if (options.includePlayback) {
            collectPlayback(library, allShows, episodeMediaKeys, playbackExports, progressExports)
        }

        val data = LibraryExportData(
            connection = connectionExport,
            library = libraryExport,
            shows = showExports,
            blocked = blocked.map { it.toBlockedExport() },
            playback = playbackExports,
            episodeProgress = progressExports,
        )

        val manifest = LibraryExportManifest(
            exportedAt = platformTimeMillis(),
            connection = ManifestConnection(connectionExport.type, connectionExport.name),
            library = ManifestLibrary(libraryExport.name, libraryExport.rootPath, libraryExport.scanMode),
            content = ManifestContent(
                shows = showExports.size,
                episodes = totalEpisodes,
                hasImages = options.includeImages && imageFiles.isNotEmpty(),
                hasPlayback = options.includePlayback && playbackExports.isNotEmpty(),
                hasOverrides = options.includeOverrides && showExports.any { it.overrideJson != null },
                hasBlocked = options.includeBlocked && blocked.isNotEmpty(),
                includePassword = options.includePassword,
            ),
        )
        ExportOutput(
            manifestJson = LibraryExportCodec.encodeManifest(manifest),
            dataJson = LibraryExportCodec.encodeData(data),
            imageFiles = imageFiles,
        )
    }

    private suspend fun loadConnectionExport(library: LibraryConfig, options: ExportOptions): ConnectionExport =
        when (library.sourceKind) {
            MediaSourceKind.WEBDAV -> {
                val conn = webDavRepository.loadAll().firstOrNull { it.id == library.connectionId }
                    ?: throw IllegalArgumentException("WebDAV 连接不存在")
                require(!options.includePassword || !conn.credentialUnavailable) { "WebDAV 凭据已失效，无法带入密码" }
                ConnectionExport(
                    type = "WEBDAV", name = conn.name, baseUrl = conn.baseUrl, username = conn.username,
                    passwordEnvelope = protectedPassword(conn.password, options),
                    includePassword = options.includePassword,
                )
            }
            MediaSourceKind.SMB -> {
                val repository = smbRepository ?: throw IllegalStateException("当前平台未提供 SMB 能力")
                val conn = repository.loadAll().firstOrNull { it.id == library.connectionId }
                    ?: throw IllegalArgumentException("SMB 连接不存在")
                require(!options.includePassword || !conn.credentialUnavailable) { "SMB 凭据已失效，无法带入密码" }
                ConnectionExport(
                    type = "SMB", name = conn.name, host = conn.host, port = conn.port, share = conn.share,
                    username = conn.username, domain = conn.domain, requireEncryption = conn.requireEncryption,
                    passwordEnvelope = protectedPassword(conn.password, options),
                    includePassword = options.includePassword,
                )
            }
            else -> error("unreachable")
        }

    private suspend fun protectedPassword(password: String, options: ExportOptions): String? =
        if (options.includePassword && password.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                protectLibraryExportPassword(requireNotNull(options.exportPassword), password)
            }
        } else {
            null
        }

    private suspend fun collectImages(
        row: ListShowsByLibrary,
        metas: List<ScrapedOnlineMeta>,
        episodeIdToNumber: Map<Long, Pair<Int, Int>>,
        out: MutableList<ImageExportFile>,
        seen: MutableSet<String>,
    ) {
        val showKey = row.cacheKey
        val onlineKey = onlineScrapeCacheKey(row.library_id, row.show_path)
        fun add(entryName: String, sourcePath: String) {
            if (seen.add(entryName)) out += ImageExportFile(entryName, sourcePath)
        }
        for (meta in metas) {
            val poster = meta.local_poster_path?.takeIf { platformFileExists(it) }
            if (poster != null) {
                val role = if (meta.season_number == 0L) "poster" else "season${meta.season_number}-poster"
                add(onlineImageEntryName(onlineKey, role, poster.basename()), poster)
            }
            if (meta.season_number == 0L) {
                val fanart = meta.local_fanart_path?.takeIf { platformFileExists(it) }
                if (fanart != null) add(onlineImageEntryName(onlineKey, "fanart", fanart.basename()), fanart)
            }
        }
        // 集照 ep<rowId>.jpg(导出时按 (season, episode) 语义命名)
        val files = imageService.listShowFiles(showKey)
        for (file in files) {
            val episodeId = file.basename.removePrefix("ep").removeSuffix(".jpg").toLongOrNull() ?: continue
            val (seasonNumber, episodeNumber) = episodeIdToNumber[episodeId] ?: continue
            add(episodeImageEntryName(showKey, seasonNumber, episodeNumber), file.absolutePath)
        }
    }

    private suspend fun collectPlayback(
        library: LibraryConfig,
        allShows: List<ListShowsByLibrary>,
        episodeMediaKeys: List<String>,
        out: MutableList<PlaybackExport>,
        progressOut: MutableList<EpisodeProgressExport>,
    ) {
        val connId = library.connectionId ?: return
        val prefix = when (library.sourceKind) {
            MediaSourceKind.WEBDAV -> "webdav:$connId:"
            MediaSourceKind.SMB -> "smb:$connId:"
            else -> return
        }
        val records = if (episodeMediaKeys.isEmpty()) emptyMap() else playbackRepository.getByMediaKeys(episodeMediaKeys)
        out += records.values.filter { it.media_key.startsWith(prefix) }.map { it.toPlaybackExport() }
        // EpisodeProgress 双表一致: 全表过滤本库媒体键前缀
        progressOut += playbackRepository.listAllEpisodeProgress()
            .filter { it.media_key?.startsWith(prefix) == true }
            .map { it.toEpisodeProgressExport() }
    }
}

/** 文件名(最后一段, 兼容 / 与 \)——zip 条目用 basename 记录。 */
private fun String.basename(): String = substringAfterLast('/').substringAfterLast('\\')
