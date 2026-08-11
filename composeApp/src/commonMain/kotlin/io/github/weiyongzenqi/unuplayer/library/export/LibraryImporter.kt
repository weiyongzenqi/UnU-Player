package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.ImportSummary
import io.github.weiyongzenqi.unuplayer.library.ImportedSeasonResult
import io.github.weiyongzenqi.unuplayer.library.ImportedShowResult
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideRow
import io.github.weiyongzenqi.unuplayer.library.onlineScrapeCacheKey
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 导入选项。 */
data class ImportOptions(
    /** 还原本地缓存图(zip 含 images/ 时)。 */
    val includeImages: Boolean = true,
    /** 导入播放进度(media_key 重映射)。 */
    val includePlayback: Boolean = true,
    /** 导入本部专属设置覆盖(identity 重映射)。 */
    val includeOverrides: Boolean = true,
    /** 导入屏蔽列表。 */
    val includeBlocked: Boolean = true,
    /** 导入后立即扫描验证(调用方匹配现有扫描协调器执行)。 */
    val scanAfterImport: Boolean = false,
)

/** 连接处理候选(预览显示用)。 */
sealed interface ConnectionCandidate {
    /** 目标设备已有同源连接, 复用其 id。 */
    data class Reuse(val connectionId: String, val name: String, val type: String) : ConnectionCandidate
    /** 无匹配, 以导出连接信息为基础新建(用户可先编辑)。 */
    data class Create(
        val type: String,
        val edit: ConnectionEdit,
        val passwordProtected: Boolean = false,
    ) : ConnectionCandidate
}

/** 新建连接的编辑模型(预览时可改名称/账号/密码等)。 */
sealed interface ConnectionEdit {
    data class WebDav(
        val name: String,
        val baseUrl: String,
        val username: String,
        val password: String,
        val allowCleartext: Boolean = false,
    ) : ConnectionEdit {
        override fun toString(): String =
            "WebDav(name=$name, baseUrl=$baseUrl, username=$username, password=<redacted>, " +
                "allowCleartext=$allowCleartext)"
    }

    data class Smb(
        val name: String,
        val host: String,
        val port: Int,
        val share: String,
        val username: String,
        val domain: String,
        val requireEncryption: Boolean,
        val password: String,
    ) : ConnectionEdit {
        override fun toString(): String =
            "Smb(name=$name, host=$host, port=$port, share=$share, username=$username, domain=$domain, " +
                "requireEncryption=$requireEncryption, password=<redacted>)"
    }
}

val ConnectionEdit.passwordValue: String
    get() = when (this) {
        is ConnectionEdit.WebDav -> password
        is ConnectionEdit.Smb -> password
    }

fun ConnectionEdit.withPassword(password: String): ConnectionEdit = when (this) {
    is ConnectionEdit.WebDav -> copy(password = password)
    is ConnectionEdit.Smb -> copy(password = password)
}

/** 导入结果。 */
data class ImportResult(
    val libraryId: Long,
    val summary: ImportSummary,
)

/** 图片还原报告。 */
data class ImageRestoreReport(val restored: Int, val skipped: Int)

/** zip 内读取结果。 */
data class ZipPayload(val manifest: LibraryExportManifest, val data: LibraryExportData)

/**
 * 媒体库导入(commonMain 纯逻辑)。
 *
 * 职责: 读 zip(manifest/data) -> 连接判断 -> 建库+全量写入(裸插) -> 播放进度重映射
 * -> 图片还原([restoreImages] 独立调用)。
 */
class LibraryImporter(
    private val scrapedRepo: ScrapedLibraryRepository,
    private val webDavRepository: WebDavConnectionRepository,
    private val smbRepository: SmbConnectionRepository?,
    private val playbackRepository: PlaybackRecordRepository,
    private val imageService: LibraryImageService,
    private val newConnectionId: () -> String,
) {
    /** 读 zip 的 manifest.json + data/library.json。格式不兼容(null)时调用方报错。 */
    suspend fun readZip(zipPath: String): ZipPayload? = withContext(Dispatchers.IO) {
        val input = LibraryZipInput(zipPath)
        var manifest: LibraryExportManifest? = null
        var data: LibraryExportData? = null
        var entryCount = 0
        var imageEntryCount = 0
        var imageBytes = 0L
        try {
            while (true) {
                val name = input.nextEntry() ?: break
                require(++entryCount <= MAX_ZIP_ENTRIES) { "导入包条目数量超过上限" }
                when (name) {
                    "manifest.json" -> {
                        require(manifest == null) { "导入包包含重复 manifest" }
                        manifest = LibraryExportCodec.decodeManifest(
                            input.readEntryBytes(MAX_MANIFEST_BYTES).decodeToString(),
                        )
                    }
                    "data/library.json" -> {
                        require(data == null) { "导入包包含重复媒体库数据" }
                        data = LibraryExportCodec.decodeData(
                            input.readEntryBytes(MAX_DATA_BYTES).decodeToString(),
                        )
                    }
                    "data/" -> input.skipEntry(MAX_MANIFEST_BYTES)
                    else -> {
                        require(name.startsWith(ZIP_IMAGES_PREFIX)) { "导入包包含不支持的条目: $name" }
                        if (!name.endsWith('/')) {
                            require(++imageEntryCount <= MAX_IMPORTED_IMAGES) { "导入包图片数量超过上限" }
                        }
                        imageBytes += input.skipEntry(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
                        require(imageBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
                    }
                }
            }
        } finally {
            input.close()
        }
        val m = manifest ?: return@withContext null
        if (m.formatVersion !in 1..LIBRARY_EXPORT_FORMAT_VERSION) return@withContext null
        val payload = data ?: return@withContext null
        require(payload.connection.password == null) {
            "导入包包含未加密连接密码，请在来源设备重新导出"
        }
        require(payload.shows.size <= MAX_IMPORTED_SHOWS) { "导入包番剧数量超过上限" }
        require(payload.shows.sumOf { show -> show.seasons.sumOf { it.episodes.size } } <= MAX_IMPORTED_EPISODES) {
            "导入包剧集数量超过上限"
        }
        ZipPayload(m, payload)
    }

    /** 连接判断: 同 baseUrl(WebDAV) / host+port+share(SMB) 匹配现有 -> 复用; 否则新建候选。 */
    suspend fun resolveConnectionCandidate(
        data: LibraryExportData,
        exportPassword: String? = null,
    ): ConnectionCandidate {
        val conn = data.connection
        val password = decryptExportedPasswordIfProvided(data, exportPassword)
        return when (conn.type) {
            "WEBDAV" -> {
                val baseUrl = normalizeBaseUrl(conn.baseUrl.orEmpty())
                if (baseUrl.isEmpty()) {
                    ConnectionCandidate.Create(
                        "WEBDAV",
                        ConnectionEdit.WebDav("", "", conn.username.orEmpty(), password.orEmpty()),
                        passwordProtected = conn.passwordEnvelope != null,
                    )
                } else {
                    val existing = webDavRepository.loadAll().firstOrNull {
                        normalizeBaseUrl(it.baseUrl) == baseUrl
                    }
                    if (existing != null) {
                        ConnectionCandidate.Reuse(existing.id, existing.name, "WEBDAV")
                    } else {
                        ConnectionCandidate.Create(
                            "WEBDAV",
                            ConnectionEdit.WebDav(
                                name = conn.name.trim().ifBlank { baseUrl },
                                baseUrl = baseUrl,
                                username = conn.username.orEmpty(),
                                password = password.orEmpty(),
                            ),
                            passwordProtected = conn.passwordEnvelope != null,
                        )
                    }
                }
            }
            "SMB" -> {
                val repository = smbRepository ?: throw IllegalStateException("当前平台未提供 SMB 能力")
                val host = conn.host?.trim().orEmpty()
                val share = conn.share?.trim().orEmpty()
                val port = conn.port ?: 445
                if (host.isEmpty() || share.isEmpty()) {
                    ConnectionCandidate.Create(
                        "SMB",
                        ConnectionEdit.Smb(
                            conn.name, host, port, share, conn.username.orEmpty(),
                            conn.domain.orEmpty(), conn.requireEncryption ?: false, password.orEmpty(),
                        ),
                        passwordProtected = conn.passwordEnvelope != null,
                    )
                } else {
                    val existing = repository.loadAll().firstOrNull {
                        it.host.equals(host, ignoreCase = true) && it.port == port && it.share == share
                    }
                    if (existing != null) {
                        ConnectionCandidate.Reuse(existing.id, existing.name, "SMB")
                    } else {
                        ConnectionCandidate.Create(
                            "SMB",
                            ConnectionEdit.Smb(
                                name = conn.name.trim().ifBlank { "$host/$share" },
                                host = host, port = port, share = share,
                                username = conn.username.orEmpty(), domain = conn.domain.orEmpty(),
                                requireEncryption = conn.requireEncryption ?: false,
                                password = password.orEmpty(),
                            ),
                            passwordProtected = conn.passwordEnvelope != null,
                        )
                    }
                }
            }
            else -> throw IllegalArgumentException("未知连接类型: ${conn.type}")
        }
    }

    /** 解开迁移口令保护的密码；预览阶段未输入口令时保留为空。 */
    fun decryptExportedPassword(data: LibraryExportData, exportPassword: String?): String? {
        val password = decryptExportedPasswordIfProvided(data, exportPassword)
        require(data.connection.passwordEnvelope == null || password != null) { "请输入导出包密码" }
        return password
    }

    private fun decryptExportedPasswordIfProvided(
        data: LibraryExportData,
        exportPassword: String?,
    ): String? = data.connection.passwordEnvelope?.let { envelope ->
        if (exportPassword.isNullOrBlank()) null
        else unprotectLibraryExportPassword(exportPassword, envelope)
    }

    /** 按编辑信息新建连接, 返回新连接 id。密码经平台 CredentialCipher 加密存储。 */
    suspend fun createConnection(edit: ConnectionEdit): String = when (edit) {
        is ConnectionEdit.WebDav -> {
            val id = newConnectionId()
            webDavRepository.add(
                WebDavConnection(
                    id = id, name = edit.name, baseUrl = edit.baseUrl,
                    username = edit.username, password = edit.password,
                ),
                allowCleartext = edit.allowCleartext,
            )
            id
        }
        is ConnectionEdit.Smb -> {
            val id = newConnectionId()
            val repository = smbRepository ?: throw IllegalStateException("当前平台未提供 SMB 能力")
            repository.add(
                SmbConnection(
                    id = id, name = edit.name, host = edit.host, port = edit.port,
                    share = edit.share, username = edit.username, password = edit.password,
                    domain = edit.domain, requireEncryption = edit.requireEncryption,
                ),
            )
            id
        }
    }

    /**
     * 执行核心导入：写媒体库结构与设置。
     * 调用方负责 addLibrary(可改库名) 后传 newLibraryId 与最终连接 id。
     * 播放进度通过 [importPlayback] 在核心成功后独立导入，避免失败回滚库时留下悬空进度。
     */
    suspend fun importLibrary(
        data: LibraryExportData,
        newLibraryId: Long,
        connectionId: String,
        options: ImportOptions,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        val oldLibraryId = data.library.libraryId
        val oldConnectionId = findOldConnectionId(data)

        // identity 重映射(show:<旧库id>: -> show:<新库id>:; tmdb/tmdb-tv 前缀不变) + episodes media_key 重映射
        val shows = data.shows.map { show ->
            show.copy(
                seasons = show.seasons.map { season ->
                    season.copy(
                        episodes = season.episodes.map { episode ->
                            episode.copy(mediaKey = remapMediaKeyFor(oldConnectionId, connectionId, episode.mediaKey))
                        },
                    )
                },
                bangumiLinks = show.bangumiLinks.map { link ->
                    link.copy(identityKey = remapIdentity(link.identityKey, oldLibraryId, newLibraryId))
                },
                overrideJson = if (options.includeOverrides) show.overrideJson else null,
            )
        }
        val links = shows.flatMap { it.bangumiLinks }
        val overrides = if (options.includeOverrides) {
            shows.mapNotNull { show ->
                show.overrideJson?.let { json ->
                    ShowOverrideRow(
                        identityKey = remapIdentity(overrideIdentityKey(show, oldLibraryId), oldLibraryId, newLibraryId),
                        overridesJson = json,
                        updatedAt = 0L,
                    )
                }
            }
        } else {
            emptyList()
        }
        val blocked = if (options.includeBlocked) data.blocked else emptyList()

        val summary = scrapedRepo.importLibraryFull(
            libraryId = newLibraryId,
            shows = shows,
            blocked = blocked,
            links = links,
            overrides = overrides,
            onProgress = onProgress,
        )

        // 导入的是快照，不代表目标设备已经验证过连接；扫描成功后再写入 last_scanned_at。
        return ImportResult(newLibraryId, summary)
    }

    /** 核心库成功后独立合并播放进度；取消或失败不回滚已经可用的媒体库。 */
    suspend fun importPlayback(data: LibraryExportData, connectionId: String) {
        val oldConnectionId = findOldConnectionId(data)
        data.playback.forEach { p ->
            playbackRepository.applyMergedRecord(
                PlaybackRecord(
                    id = 0,
                    media_key = remapMediaKeyFor(oldConnectionId, connectionId, p.mediaKey) ?: p.mediaKey,
                    source_kind = p.sourceKind,
                    url = p.url,
                    content_uri = null,
                    title = p.title,
                    position_ms = p.positionMs,
                    duration_ms = p.durationMs,
                    watch_progress = p.watchProgress,
                    is_completed = p.isCompleted,
                    tmdb_id = p.tmdbId,
                    season_number = p.seasonNumber,
                    episode_number = p.episodeNumber,
                    danmaku_episode_id = p.danmakuEpisodeId,
                    danmaku_anime_id = p.danmakuAnimeId,
                    danmaku_anime_title = p.danmakuAnimeTitle,
                    danmaku_episode_title = p.danmakuEpisodeTitle,
                    danmaku_match_method = p.danmakuMatchMethod,
                    last_played_at = p.lastPlayedAt,
                    sync_status = p.syncStatus,
                    sync_version = p.syncVersion,
                ),
            )
        }
        data.episodeProgress.forEach { ep ->
            playbackRepository.applyMergedEpisodeProgress(
                EpisodeProgress(
                    tmdb_id = ep.tmdbId,
                    season_number = ep.seasonNumber,
                    episode_number = ep.episodeNumber,
                    media_key = remapMediaKeyFor(oldConnectionId, connectionId, ep.mediaKey),
                    position_ms = ep.positionMs,
                    duration_ms = ep.durationMs,
                    watch_progress = ep.watchProgress,
                    is_completed = ep.isCompleted,
                    last_played_at = ep.lastPlayedAt,
                    sync_status = ep.syncStatus,
                    sync_version = ep.syncVersion,
                ),
            )
        }
    }

    /**
     * 还原 zip 中的本地缓存图到 PosterCache, 并回写 DB 局部路径。
     * 依赖 [ImportSummary](importLibrary 返回值) 提供新行 id/缓存 key 映射。
     */
    suspend fun restoreImages(
        zipPath: String,
        newLibraryId: Long,
        data: LibraryExportData,
        summary: ImportSummary,
    ): ImageRestoreReport = withContext(Dispatchers.IO) {
        val showKeyByExport = mutableMapOf<String, String>()   // exportShowCacheKey -> 新 showKey
        val onlineKeyByExport = mutableMapOf<String, String>() // exportOnlineCacheKey -> 新 onlineKey
        val showPathByShowKey = mutableMapOf<String, String>() // exportShowCacheKey -> showPath
        for (show in data.shows) {
            val showResult = summary.shows[show.showPath] ?: continue
            show.exportShowCacheKey?.let {
                showKeyByExport[it] = showResult.showKey
                showPathByShowKey[it] = show.showPath
            }
            show.exportOnlineCacheKey?.let {
                onlineKeyByExport[it] = onlineScrapeCacheKey(newLibraryId, show.showPath)
            }
        }

        val input = LibraryZipInput(zipPath)
        var restored = 0
        var skipped = 0
        var imageEntries = 0
        var restoredBytes = 0L
        fun skipCurrentImage() {
            restoredBytes += input.skipEntry(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
            require(restoredBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
        }
        try {
            while (true) {
                val name = input.nextEntry() ?: break
                if (!name.startsWith(ZIP_IMAGES_PREFIX)) continue
                if (name.endsWith('/')) {
                    input.skipEntry(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
                    continue
                }
                require(++imageEntries <= MAX_IMPORTED_IMAGES) { "导入包图片数量超过上限" }
                val onlineEntry = parseOnlineImageEntry(name)
                if (onlineEntry != null) {
                    val entry = onlineEntry
                    val newOnlineKey = onlineKeyByExport[entry.onlineCacheKey]
                    val showPath = data.shows.firstOrNull { it.exportOnlineCacheKey == entry.onlineCacheKey }?.showPath
                    if (newOnlineKey == null || showPath == null) {
                        skipCurrentImage()
                        skipped++
                        continue
                    }
                    val bytes = input.readEntryBytes(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
                    restoredBytes += bytes.size
                    require(restoredBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
                    val newPath = imageService.writeShowImage(newOnlineKey, entry.basename, bytes)
                    if (newPath != null) {
                        if (entry.role == "fanart") {
                            scrapedRepo.updateOnlineMetaFanart(newLibraryId, showPath, null, newPath)
                        } else {
                            val seasonNumber = entry.role
                                .removePrefix("season").removeSuffix("-poster").toIntOrNull() ?: 0
                            scrapedRepo.updateOnlineMetaLocalPoster(newLibraryId, showPath, seasonNumber, newPath)
                        }
                        restored++
                    } else {
                        skipped++
                    }
                    continue
                }

                val episodeEntry = parseEpisodeImageEntry(name)
                if (episodeEntry == null) {
                    skipCurrentImage()
                    skipped++
                    continue
                }
                val showPath = showPathByShowKey[episodeEntry.showCacheKey]
                val showResult = showPath?.let(summary.shows::get)
                val seasonResult = showResult?.seasons?.get(episodeEntry.seasonNumber)
                val episodeId = seasonResult?.episodes?.get(episodeEntry.episodeNumber)
                if (showResult == null || episodeId == null) {
                    skipCurrentImage()
                    skipped++
                    continue
                }
                val bytes = input.readEntryBytes(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
                restoredBytes += bytes.size
                require(restoredBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
                val newPath = imageService.writeEpisodeThumb(showResult.showKey, episodeId, bytes)
                if (newPath != null) {
                    scrapedRepo.updateEpisodeLocalThumb(episodeId, newPath)
                    restored++
                } else {
                    skipped++
                }
            }
        } finally {
            input.close()
        }
        ImageRestoreReport(restored, skipped)
    }

    // === 内部 ===

    private fun remapIdentity(identityKey: String, oldLibraryId: Long?, newLibraryId: Long): String =
        if (oldLibraryId != null) {
            remapShowIdentity(identityKey, oldLibraryId, newLibraryId)
        } else {
            identityKey
        }

    /** 本部覆盖 identity key: tmdb:<id>(有 tmdb) 或 show:<旧库id>:<showPath>(ANCHOR)。 */
    private fun overrideIdentityKey(show: ShowExport, oldLibraryId: Long?): String =
        show.tmdbId?.let { "tmdb:$it" } ?: "show:${oldLibraryId ?: 0L}:${show.showPath}"

    /** 从任一番剧集 mediaKey 提取旧连接 id(同库同连接, 首个非空)。 */
    private fun findOldConnectionId(data: LibraryExportData): String? {
        for (show in data.shows) {
            for (season in show.seasons) {
                for (episode in season.episodes) {
                    val key = episode.mediaKey ?: continue
                    val prefix = when {
                        key.startsWith("webdav:") -> "webdav:"
                        key.startsWith("smb:") -> "smb:"
                        else -> continue
                    }
                    val rest = key.removePrefix(prefix)
                    val colon = rest.indexOf(':')
                    if (colon > 0) return rest.substring(0, colon)
                }
            }
        }
        return null
    }

    private fun remapMediaKeyFor(oldConnectionId: String?, newConnectionId: String, mediaKey: String?): String? =
        mediaKey?.let { key ->
            oldConnectionId?.let { remapMediaKey(key, it, newConnectionId) } ?: key
        }
}

/** 规范化 WebDAV baseUrl(去末尾斜杠/小写) 供连接复用比较。 */
private fun normalizeBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return trimmed
    val authorityStart = schemeEnd + 3
    val authorityEnd = sequenceOf(
        trimmed.indexOf('/', authorityStart),
        trimmed.indexOf('?', authorityStart),
        trimmed.indexOf('#', authorityStart),
    ).filter { it >= 0 }.minOrNull() ?: trimmed.length
    return trimmed.substring(0, schemeEnd).lowercase() + "://" +
        trimmed.substring(authorityStart, authorityEnd).lowercase() +
        trimmed.substring(authorityEnd)
}

private const val MAX_MANIFEST_BYTES = 64L * 1024L
private const val MAX_DATA_BYTES = 64L * 1024L * 1024L
private const val MAX_ZIP_ENTRIES = 200_000
private const val MAX_IMPORTED_IMAGES = 100_000
private const val MAX_IMPORTED_SHOWS = 100_000
private const val MAX_IMPORTED_EPISODES = 1_000_000

fun hasLibraryNameConflict(existingNames: Iterable<String>, targetName: String): Boolean {
    val normalizedTarget = targetName.trim()
    return normalizedTarget.isNotEmpty() && existingNames.any {
        it.trim().equals(normalizedTarget, ignoreCase = true)
    }
}
