@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.weiyongzenqi.unuplayer.library.export

import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonIdentity
import io.github.weiyongzenqi.unuplayer.bangumi.BangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.bangumi.shouldReplaceBangumiSeasonLink
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.platformTimeMillis
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection
import io.github.weiyongzenqi.unuplayer.domain.WebDavConnection
import io.github.weiyongzenqi.unuplayer.library.ImportSummary
import io.github.weiyongzenqi.unuplayer.library.ImportedSeasonResult
import io.github.weiyongzenqi.unuplayer.library.ImportedShowResult
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideRow
import io.github.weiyongzenqi.unuplayer.library.ShowOverrideIdentity
import io.github.weiyongzenqi.unuplayer.library.decodedEpisodes
import io.github.weiyongzenqi.unuplayer.library.onlineScrapeCacheKey
import io.github.weiyongzenqi.unuplayer.playback.EpisodeProgress
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecord
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepository
import io.github.weiyongzenqi.unuplayer.playback.sync.isPlaybackSyncVersionSafe
import io.github.weiyongzenqi.unuplayer.playback.sync.parseWebDavMediaKeyPath
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.resolveWebDavUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

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

    /** 端点相同但账号配置不同，必须由用户明确选择复用还是另建。 */
    data class Choose(
        val reuse: Reuse,
        val create: Create,
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
    private val nowMillis: () -> Long = ::platformTimeMillis,
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

    /** 连接判断：端点和账号配置均相同则复用；同端点不同账号交给用户选择；端点不存在则新建。 */
    suspend fun resolveConnectionCandidate(
        data: LibraryExportData,
        exportPassword: String? = null,
    ): ConnectionCandidate {
        val conn = data.connection
        val password = decryptExportedPasswordIfProvided(data, exportPassword)
        return when (conn.type) {
            "WEBDAV" -> {
                val baseUrl = normalizeBaseUrl(conn.baseUrl.orEmpty())
                val username = conn.username.orEmpty().trim()
                val create = ConnectionCandidate.Create(
                    "WEBDAV",
                    ConnectionEdit.WebDav(
                        name = conn.name.trim().ifBlank { baseUrl },
                        baseUrl = baseUrl,
                        username = username,
                        password = password.orEmpty(),
                    ),
                    passwordProtected = conn.passwordEnvelope != null,
                )
                if (baseUrl.isEmpty()) {
                    create
                } else {
                    val endpointMatches = webDavRepository.loadAll().filter {
                        normalizeBaseUrl(it.baseUrl) == baseUrl
                    }
                    val exactAccount = endpointMatches.firstOrNull {
                        !it.credentialUnavailable && it.username.trim() == username
                    }
                    val reusable = exactAccount ?: endpointMatches.firstOrNull { !it.credentialUnavailable }
                    when {
                        exactAccount != null ->
                            ConnectionCandidate.Reuse(exactAccount.id, exactAccount.name, "WEBDAV")
                        reusable == null -> create
                        else -> ConnectionCandidate.Choose(
                            reuse = ConnectionCandidate.Reuse(reusable.id, reusable.name, "WEBDAV"),
                            create = create,
                        )
                    }
                }
            }
            "SMB" -> {
                val repository = smbRepository ?: throw IllegalStateException("当前平台未提供 SMB 能力")
                val host = conn.host?.trim().orEmpty()
                val share = conn.share?.trim().orEmpty()
                val port = conn.port ?: 445
                val username = conn.username.orEmpty().trim()
                val domain = conn.domain.orEmpty().trim()
                val requireEncryption = conn.requireEncryption ?: false
                val create = ConnectionCandidate.Create(
                    "SMB",
                    ConnectionEdit.Smb(
                        name = conn.name.trim().ifBlank { "$host/$share" },
                        host = host,
                        port = port,
                        share = share,
                        username = username,
                        domain = domain,
                        requireEncryption = requireEncryption,
                        password = password.orEmpty(),
                    ),
                    passwordProtected = conn.passwordEnvelope != null,
                )
                if (host.isEmpty() || share.isEmpty()) {
                    create
                } else {
                    val endpointMatches = repository.loadAll().filter {
                        it.host.equals(host, ignoreCase = true) && it.port == port && it.share == share
                    }
                    val exactAccount = endpointMatches.firstOrNull {
                        !it.credentialUnavailable &&
                            it.username.trim().equals(username, ignoreCase = true) &&
                            it.domain.trim().equals(domain, ignoreCase = true) &&
                            it.requireEncryption == requireEncryption
                    }
                    val reusable = exactAccount ?: endpointMatches.firstOrNull { !it.credentialUnavailable }
                    when {
                        exactAccount != null -> ConnectionCandidate.Reuse(exactAccount.id, exactAccount.name, "SMB")
                        reusable == null -> create
                        else -> ConnectionCandidate.Choose(
                            reuse = ConnectionCandidate.Reuse(reusable.id, reusable.name, "SMB"),
                            create = create,
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
        val hasEpisodeMediaKeys = data.shows.any { show ->
            show.seasons.any { season -> season.episodes.any { it.mediaKey != null } }
        }
        require(!hasEpisodeMediaKeys || oldConnectionId != null) { "导入包媒体键连接不一致" }

        // identity 重映射(show:<旧库id>: -> show:<新库id>:; tmdb/tmdb-tv 前缀不变) + episodes media_key 重映射
        val shows = data.shows.map { show ->
            val remappedSeasons = show.seasons.map { season ->
                    season.copy(
                        episodes = season.episodes.map { episode ->
                            episode.copy(mediaKey = remapMediaKeyFor(oldConnectionId, connectionId, episode.mediaKey))
                        },
                    )
                }
            val allowedLinkIdentities = remappedSeasons.mapTo(hashSetOf()) { season ->
                BangumiSeasonIdentity.keyFor(
                    tmdbId = show.tmdbId,
                    libraryId = newLibraryId,
                    showPath = show.showPath,
                    seasonNumber = season.seasonNumber.toLong(),
                )
            }
            val safeLinksByIdentity = linkedMapOf<String, Pair<BangumiSeasonLink, BangumiLinkExport>>()
            show.bangumiLinks.forEach { link ->
                val remapped = link.copy(identityKey = remapIdentity(link.identityKey, oldLibraryId, newLibraryId))
                val parsed = remapped.toBangumiSeasonLinkOrNull() ?: return@forEach
                if (remapped.identityKey !in allowedLinkIdentities) return@forEach
                val current = safeLinksByIdentity[remapped.identityKey]
                if (current == null || shouldReplaceBangumiSeasonLink(current.first, parsed)) {
                    safeLinksByIdentity[remapped.identityKey] = parsed to remapped
                }
            }
            show.copy(
                seasons = remappedSeasons,
                bangumiLinks = safeLinksByIdentity.values.map { it.second },
                overrideJson = if (options.includeOverrides) show.overrideJson else null,
            )
        }
        val links = shows.flatMap { it.bangumiLinks }
        val overrides = if (options.includeOverrides) {
            shows.mapNotNull { show ->
                show.overrideJson?.let { json ->
                    ShowOverrideRow(
                        identityKey = ShowOverrideIdentity.keyFor(show.tmdbId, newLibraryId, show.showPath),
                        overridesJson = json,
                        updatedAt = show.overrideUpdatedAt?.coerceAtLeast(0L) ?: 0L,
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

    /**
     * 核心库成功后独立合并播放进度；取消或失败不回滚已经可用的媒体库。
     *
     * 版本保护(原子): 逐条经 [PlaybackRecordRepository.applyMergedRecordIfNewer] 在事务内
     * "读-判-写", 仅当导出包比目标端更新时写入(sync_version 逻辑时钟优先, 平手比 last_played_at),
     * 旧导出包不再覆盖更新进度, 也消除快照读+内存判断的并发窗口。
     * WebDAV 记录按"目标连接 baseUrl + path"重算 url、SMB 按新连接重算 smbfd:// url——
     * media_key 已重映射到新连接, 若 url 仍写旧 URL, 新建连接/同端点多账号会访问旧账号或播失败。
     */
    suspend fun importPlayback(data: LibraryExportData, connectionId: String) {
        val importNowMillis = nowMillis()
        val oldConnectionId = findOldConnectionId(data) ?: findOldPlaybackConnectionId(data)
        val allowedGraph = buildPlaybackImportGraph(data, oldConnectionId, connectionId)
        val webDavBaseUrl = webDavRepository.loadAll()
            .firstOrNull { it.id == connectionId }
            ?.baseUrl
            ?.trimEnd('/')
        data.playback.forEach { p ->
            val sourceKind = p.sourceKind.trim().uppercase()
            if (sourceKind != allowedGraph.sourceKind) return@forEach
            if (
                !isPlaybackSyncVersionSafe(p.syncVersion) ||
                !isPlaybackSyncVersionSafe(p.danmakuSyncVersion)
            ) {
                return@forEach
            }
            val remappedKey = remapMediaKeyFor(oldConnectionId, connectionId, p.mediaKey) ?: p.mediaKey
            if (remappedKey !in allowedGraph.mediaKeys) return@forEach
            // P2-10 ghost 记录防护: 规范导出包只含导出主连接(本库)的记录, 其 media_key 重映射后
            // 必落到目标连接前缀; 若重映射后仍不属于目标连接(篡改/损坏包夹带其他连接记录),
            // 整条跳过——否则会写入引用不存在连接 id 的 ghost 行(历史列表出现不可播放条目)。
            // 仅对可判定的 WEBDAV/SMB 记录过滤; 其它来源(旧格式/本地)保持原行为不误伤。
            val targetPrefix = when (sourceKind) {
                "WEBDAV" -> "webdav:$connectionId:"
                "SMB" -> "smb:$connectionId:"
                else -> null
            }
            if (targetPrefix != null && !remappedKey.startsWith(targetPrefix)) return@forEach
            val recordTriple = when {
                p.tmdbId == null && p.seasonNumber == null && p.episodeNumber == null -> null
                p.tmdbId != null && p.seasonNumber != null && p.episodeNumber != null ->
                    ImportedEpisodeTriple(p.tmdbId, p.seasonNumber, p.episodeNumber)
                else -> return@forEach
            }
            if (recordTriple != null && recordTriple !in allowedGraph.triplesByMediaKey[remappedKey].orEmpty()) {
                return@forEach
            }
            // 目标 media_key 已通过导入图谱白名单验证，URL 必须同样从目标连接/路径重算。
            // 不信任包内旧 URL：同一 connectionId 换端点、损坏 smbfd 或目标连接缺失时，
            // 回落旧 URL 会把历史记录指向旧服务器或不可播放地址，因此整条跳过。
            val resolvedUrl = when (sourceKind) {
                "WEBDAV" -> {
                    val baseUrl = webDavBaseUrl ?: return@forEach
                    val path = parseWebDavMediaKeyPath(remappedKey) ?: return@forEach
                    runCatching { resolveWebDavUrl(baseUrl, path) }.getOrNull() ?: return@forEach
                }
                "SMB" -> {
                    val path = parseSmbMediaKeyPath(remappedKey) ?: return@forEach
                    smbPlaybackUrl(connectionId, path)
                }
                else -> return@forEach
            }
            playbackRepository.applyMergedRecordIfNewer(
                PlaybackRecord(
                    id = 0,
                    media_key = remappedKey,
                    source_kind = sourceKind,
                    url = resolvedUrl,
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
                    danmaku_sync_version = p.danmakuSyncVersion,
                    danmaku_updated_at = p.danmakuUpdatedAt.coerceAtLeast(0L).coerceAtMost(importNowMillis),
                    last_played_at = p.lastPlayedAt.coerceAtLeast(0L).coerceAtMost(importNowMillis),
                    sync_status = p.syncStatus,
                    sync_version = p.syncVersion,
                ),
            )
        }
        data.episodeProgress.forEach { ep ->
            if (!isPlaybackSyncVersionSafe(ep.syncVersion)) return@forEach
            val remappedKey = remapMediaKeyFor(oldConnectionId, connectionId, ep.mediaKey) ?: return@forEach
            val triple = ImportedEpisodeTriple(ep.tmdbId, ep.seasonNumber, ep.episodeNumber)
            if (
                remappedKey !in allowedGraph.mediaKeys ||
                triple !in allowedGraph.triplesByMediaKey[remappedKey].orEmpty()
            ) {
                return@forEach
            }
            playbackRepository.applyMergedEpisodeProgressIfNewer(
                EpisodeProgress(
                    tmdb_id = ep.tmdbId,
                    season_number = ep.seasonNumber,
                    episode_number = ep.episodeNumber,
                    media_key = remappedKey,
                    position_ms = ep.positionMs,
                    duration_ms = ep.durationMs,
                    watch_progress = ep.watchProgress,
                    is_completed = ep.isCompleted,
                    last_played_at = ep.lastPlayedAt.coerceAtLeast(0L).coerceAtMost(importNowMillis),
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
        // (showPath, seasonNumber) -> (episodeNumber -> 写入结果): 集照先收集, 循环后按季批量回写一次。
        // created=false 表示复用既有文件，DB 失败时不得删除。
        val episodeStillUpdates = mutableMapOf<Pair<String, Int>, MutableMap<Int, ImageWriteResult>>()
        // 写入集照前再次以目标库的在线 meta 校验集号。导出快照只能证明来源存在该集，
        // 不能证明目标库仍有对应季度/集；缓存写入必须先通过这个索引，避免孤立文件与错误 restored 计数。
        val targetEpisodeNumbers = mutableMapOf<Pair<String, Int>, Set<Int>?>()
        suspend fun targetEpisodesFor(showPath: String, seasonNumber: Int): Set<Int>? {
            val key = showPath to seasonNumber
            if (!targetEpisodeNumbers.containsKey(key)) {
                targetEpisodeNumbers[key] = scrapedRepo.getOnlineMeta(newLibraryId, showPath, seasonNumber)
                    ?.decodedEpisodes
                    ?.mapTo(hashSetOf()) { it.episodeNumber }
            }
            return targetEpisodeNumbers[key]
        }
        fun skipCurrentImage() {
            restoredBytes += input.skipEntry(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
            require(restoredBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
        }
        suspend fun deleteIfCreated(showKey: String, write: ImageWriteResult) {
            if (write.created) {
                withContext(NonCancellable) {
                    imageService.deleteShowImage(showKey, write.absolutePath)
                }
            }
        }
        suspend fun flushEpisodeStillUpdates() {
            val pendingSeasons = episodeStillUpdates.entries.toList()
            for ((index, pending) in pendingSeasons.withIndex()) {
                val key = pending.key
                val updates = pending.value
                val showPath = key.first
                val seasonNumber = key.second
                val showKey = onlineScrapeCacheKey(newLibraryId, showPath)
                val applied = try {
                    scrapedRepo.mergeOnlineMetaEpisodeThumbs(
                        newLibraryId,
                        showPath,
                        seasonNumber,
                        updates.mapValues { it.value.absolutePath },
                    )
                } catch (error: Throwable) {
                    // 当前季及后续季都尚未提交；只撤销本轮新建文件，既有复用文件保持不动。
                    pendingSeasons.drop(index).forEach { remaining ->
                        val remainingShowKey = onlineScrapeCacheKey(newLibraryId, remaining.key.first)
                        remaining.value.values.forEach { write -> deleteIfCreated(remainingShowKey, write) }
                    }
                    throw error
                }
                restored += applied.size
                for ((episodeNumber, write) in updates) {
                    if (episodeNumber !in applied) {
                        deleteIfCreated(showKey, write)
                        skipped++
                    }
                }
            }
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
                    val episodeRole = parseOnlineEpisodeImageRole(entry.role)
                    if (episodeRole != null) {
                        // 集照: 先用导出数据做廉价校验，再查目标 meta 的真实季度/集号；
                        // 只有两者都通过才读取并写入最终缓存目录。
                        val seasonData = data.shows
                            .firstOrNull { it.exportOnlineCacheKey == entry.onlineCacheKey }
                            ?.seasons?.firstOrNull { it.seasonNumber == episodeRole.seasonNumber }
                        val validEpisode = seasonData?.onlineMeta?.episodes
                            ?.any { it.episodeNumber == episodeRole.episodeNumber } == true
                        val targetEpisodes = if (seasonData == null || !validEpisode) {
                            null
                        } else {
                            targetEpisodesFor(showPath, episodeRole.seasonNumber)
                        }
                        if (targetEpisodes == null || episodeRole.episodeNumber !in targetEpisodes) {
                            skipCurrentImage()
                            skipped++
                            continue
                        }
                        val bytes = input.readEntryBytes(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
                        restoredBytes += bytes.size
                        require(restoredBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
                        val newPath = imageService.writeShowImage(
                            newOnlineKey,
                            onlineImageRestoreBasename(entry),
                            bytes,
                        )
                        if (newPath != null) {
                            val updates = episodeStillUpdates.getOrPut(showPath to episodeRole.seasonNumber) { mutableMapOf() }
                            val replaced = updates.put(episodeRole.episodeNumber, newPath)
                            if (replaced != null) {
                                if (replaced.absolutePath == newPath.absolutePath) {
                                    updates[episodeRole.episodeNumber] = newPath.copy(
                                        created = replaced.created || newPath.created,
                                    )
                                } else {
                                    deleteIfCreated(newOnlineKey, replaced)
                                }
                                skipped++
                            }
                        } else {
                            skipped++
                        }
                        continue
                    }
                    val bytes = input.readEntryBytes(LIBRARY_EXPORT_MAX_IMAGE_BYTES)
                    restoredBytes += bytes.size
                    require(restoredBytes <= LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES) { "导入包图片总量超过上限" }
                    val newPath = imageService.writeShowImage(
                        newOnlineKey,
                        onlineImageRestoreBasename(entry),
                        bytes,
                    )
                    if (newPath != null) {
                        try {
                            if (entry.role == "fanart") {
                                scrapedRepo.updateOnlineMetaFanart(newLibraryId, showPath, null, newPath.absolutePath)
                            } else {
                                val seasonNumber = entry.role
                                    .removePrefix("season").removeSuffix("-poster").toIntOrNull() ?: 0
                                scrapedRepo.updateOnlineMetaLocalPoster(
                                    newLibraryId,
                                    showPath,
                                    seasonNumber,
                                    newPath.absolutePath,
                                )
                            }
                        } catch (error: Throwable) {
                            deleteIfCreated(newOnlineKey, newPath)
                            throw error
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
                    try {
                        scrapedRepo.updateEpisodeLocalThumb(episodeId, newPath.absolutePath)
                    } catch (error: Throwable) {
                        deleteIfCreated(showResult.showKey, newPath)
                        throw error
                    }
                    restored++
                } else {
                    skipped++
                }
            }
        } finally {
            try {
                input.close()
            } finally {
                // 文件写入成功后必须落下 DB 指针；取消/后续条目失败也完成已收集季度的有界批量提交。
                withContext(NonCancellable) {
                    try {
                        flushEpisodeStillUpdates()
                    } finally {
                        imageService.finishRestore()
                    }
                }
            }
        }
        ImageRestoreReport(restored, skipped)
    }

    // === 内部 ===

    private data class ImportedEpisodeTriple(
        val tmdbId: Long,
        val seasonNumber: Long,
        val episodeNumber: Long,
    )

    private data class PlaybackImportGraph(
        val sourceKind: String,
        val mediaKeys: Set<String>,
        val triplesByMediaKey: Map<String, Set<ImportedEpisodeTriple>>,
    )

    /** 从实际导入的 show/season/episode 建立白名单，播放记录与语义进度只能落入该图谱。 */
    private fun buildPlaybackImportGraph(
        data: LibraryExportData,
        oldConnectionId: String?,
        newConnectionId: String,
    ): PlaybackImportGraph {
        val sourceKind = data.connection.type.uppercase()
        val scheme = when (sourceKind) {
            "WEBDAV" -> "webdav"
            "SMB" -> "smb"
            else -> return PlaybackImportGraph(sourceKind, emptySet(), emptyMap())
        }
        val oldPrefix = oldConnectionId?.let { "$scheme:$it:" }
            ?: return PlaybackImportGraph(sourceKind, emptySet(), emptyMap())
        val targetPrefix = "$scheme:$newConnectionId:"
        val mediaKeys = hashSetOf<String>()
        val triplesByMediaKey = mutableMapOf<String, MutableSet<ImportedEpisodeTriple>>()
        for (show in data.shows) {
            for (season in show.seasons) {
                for (episode in season.episodes) {
                    val originalKey = episode.mediaKey ?: continue
                    if (!originalKey.startsWith(oldPrefix)) continue
                    val key = remapMediaKeyFor(oldConnectionId, newConnectionId, originalKey) ?: continue
                    if (!key.startsWith(targetPrefix)) continue
                    mediaKeys += key
                    val tmdbId = show.tmdbId ?: continue
                    if (episode.episodeNumber <= 0) continue
                    triplesByMediaKey.getOrPut(key) { hashSetOf() } += ImportedEpisodeTriple(
                        tmdbId = tmdbId,
                        seasonNumber = season.seasonNumber.toLong(),
                        episodeNumber = episode.episodeNumber.toLong(),
                    )
                }
            }
        }
        return PlaybackImportGraph(sourceKind, mediaKeys, triplesByMediaKey)
    }

    private fun remapIdentity(identityKey: String, oldLibraryId: Long?, newLibraryId: Long): String =
        if (oldLibraryId != null) {
            remapShowIdentity(identityKey, oldLibraryId, newLibraryId)
        } else {
            identityKey
        }

    /** 从全部番剧集 mediaKey 解析唯一旧连接 id；混合协议或连接时失败关闭。 */
    private fun findOldConnectionId(data: LibraryExportData): String? {
        val mediaKeys = buildList {
            for (show in data.shows) {
                for (season in show.seasons) {
                    for (episode in season.episodes) episode.mediaKey?.let(::add)
                }
            }
        }
        return uniqueExportConnectionId(data.connection.type, mediaKeys)
    }

    private fun uniqueExportConnectionId(connectionType: String, mediaKeys: List<String>): String? {
        if (mediaKeys.isEmpty()) return null
        val prefix = when (connectionType.uppercase()) {
            "WEBDAV" -> "webdav:"
            "SMB" -> "smb:"
            else -> return null
        }
        val ids = hashSetOf<String>()
        for (key in mediaKeys) {
            if (!key.startsWith(prefix)) return null
            val rest = key.removePrefix(prefix)
            val colon = rest.indexOf(':')
            if (colon <= 0 || colon == rest.lastIndex) return null
            ids += rest.substring(0, colon)
            if (ids.size > 1) return null
        }
        return ids.singleOrNull()
    }

    /** 空库(无番剧)导出仍有播放记录时, 从播放记录自身的 media_key 反推唯一旧连接 id。 */
    private fun findOldPlaybackConnectionId(data: LibraryExportData): String? =
        uniqueExportConnectionId(data.connection.type, data.playback.map { it.mediaKey })

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

/** smbfd:// URL 编码(与 Android SmbPlaybackLocator 的 java Base64 url-safe 无 padding 兼容)。 */
private val smbLocatorBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** 从已验证的 SMB media_key 提取共享内路径；只在连接 id 后第一个冒号处分割。 */
private fun parseSmbMediaKeyPath(mediaKey: String): String? {
    if (!mediaKey.startsWith("smb:")) return null
    val payload = mediaKey.removePrefix("smb:")
    val separator = payload.indexOf(':')
    if (separator <= 0 || separator == payload.lastIndex) return null
    return payload.substring(separator + 1).takeIf { it.isNotBlank() }
}

/** 用目标连接和已验证路径构造不含凭据的 Android SMB 播放定位 URL。 */
private fun smbPlaybackUrl(connectionId: String, path: String): String =
    "smbfd://" +
        smbLocatorBase64.encode(connectionId.encodeToByteArray()) + "/" +
        smbLocatorBase64.encode(path.encodeToByteArray())

/**
 * 重算 smbfd:// 播放 URL 的连接 id。SMB 播放 URL 携带连接 id(SmbPlaybackLocator 编码),
 * 导入到新连接后必须替换为新连接 id, 否则 Android 历史重播仍走旧连接(可能找不到/播失败)。
 * 非 SMB URL / 解析失败返回 null；导入生产路径改为从已验证 media_key 重建，不再回落旧 URL。
 */
internal fun remapSmbPlaybackUrl(url: String, newConnectionId: String): String? {
    val prefix = "smbfd://"
    if (!url.startsWith(prefix, ignoreCase = true)) return null
    val rest = url.substring(prefix.length)
    val separator = rest.indexOf('/')
    if (separator <= 0 || separator == rest.lastIndex) return null
    val path = runCatching {
        smbLocatorBase64.decode(rest.substring(separator + 1)).decodeToString()
    }.getOrNull() ?: return null
    if (path.isBlank()) return null
    return smbPlaybackUrl(newConnectionId, path)
}

fun hasLibraryNameConflict(existingNames: Iterable<String>, targetName: String): Boolean {
    val normalizedTarget = targetName.trim()
    return normalizedTarget.isNotEmpty() && existingNames.any {
        it.trim().equals(normalizedTarget, ignoreCase = true)
    }
}
