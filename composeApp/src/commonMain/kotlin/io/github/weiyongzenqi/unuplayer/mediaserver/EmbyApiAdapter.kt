package io.github.weiyongzenqi.unuplayer.mediaserver

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class EmbyApiAdapter(
    private val transport: MediaServerTransport = sharedMediaServerTransport,
) : MediaServerApi {
    override val vendor: MediaServerVendor = MediaServerVendor.EMBY

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun getPublicInfo(
        baseUrl: String,
        allowCleartext: Boolean,
    ): MediaServerPublicInfo {
        val apiBaseUrl = requireApiBaseUrl(baseUrl, allowCleartext)
        val request = MediaServerHttpRequest(
            operation = "emby.public-info",
            method = MediaServerHttpMethod.GET,
            url = buildMediaServerUrl(apiBaseUrl, listOf("System", "Info", "Public")),
            headers = jsonHeaders(),
        )
        val dto = executeAndDecode<EmbyPublicInfoDto>(request)
        val productName = dto.productName?.trim()?.takeIf { it.isNotEmpty() }
        if (productName != null && !productName.startsWith(EMBY_PRODUCT_PREFIX, ignoreCase = true)) {
            throw MediaServerProtocolException(request.operation)
        }
        return MediaServerPublicInfo(
            vendor = vendor,
            serverId = dto.id.required(request.operation),
            serverName = dto.serverName?.takeIf { it.isNotBlank() } ?: "Emby",
            version = dto.version.orEmpty(),
            productName = productName,
            apiBaseUrl = apiBaseUrl,
        )
    }

    override suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        client: MediaServerClientIdentity,
        allowCleartext: Boolean,
    ): MediaServerSession {
        require(username.isNotBlank()) { "用户名不能为空" }
        val publicInfo = getPublicInfo(baseUrl, allowCleartext)
        val request = MediaServerHttpRequest(
            operation = "emby.authenticate",
            method = MediaServerHttpMethod.POST,
            url = buildMediaServerUrl(publicInfo.apiBaseUrl, listOf("Users", "AuthenticateByName")),
            headers = jsonHeaders() + ("X-Emby-Authorization" to embyAuthorization(client)),
            body = json.encodeToString(EmbyAuthenticateRequestDto(username.trim(), password)),
        )
        val dto = executeAndDecode<EmbyAuthenticationResultDto>(request)
        val responseServerId = dto.serverId?.takeIf { it.isNotBlank() }
        if (responseServerId != null && responseServerId != publicInfo.serverId) {
            throw MediaServerProtocolException(request.operation)
        }
        return MediaServerSession(
            vendor = vendor,
            apiBaseUrl = publicInfo.apiBaseUrl,
            serverId = publicInfo.serverId,
            serverVersion = publicInfo.version,
            userId = dto.user?.id.required(request.operation),
            username = dto.user?.name?.takeIf { it.isNotBlank() } ?: username.trim(),
            accessToken = dto.accessToken.required(request.operation),
            client = client,
        )
    }

    override suspend fun listLibraries(session: MediaServerSession): List<MediaServerLibrary> {
        requireSessionVendor(session, vendor)
        val request = MediaServerHttpRequest(
            operation = "emby.list-libraries",
            method = MediaServerHttpMethod.GET,
            url = buildMediaServerUrl(
                session.apiBaseUrl,
                listOf("Users", session.userId, "Views"),
                mapOf("IncludeExternalContent" to "false"),
            ),
            headers = sessionHeaders(session),
        )
        return executeAndDecode<EmbyItemsResultDto>(request).items.mapNotNull { item ->
            val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MediaServerLibrary(
                id = id,
                name = item.name.orEmpty().ifBlank { id },
                collectionType = item.collectionType,
                primaryImageTag = item.imageTags["Primary"],
            )
        }
    }

    override suspend fun listItems(
        session: MediaServerSession,
        query: MediaServerItemsQuery,
    ): MediaServerPage<MediaServerItem> {
        requireSessionVendor(session, vendor)
        val request = MediaServerHttpRequest(
            operation = "emby.list-items",
            method = MediaServerHttpMethod.GET,
            url = buildMediaServerUrl(
                session.apiBaseUrl,
                listOf("Users", session.userId, "Items"),
                itemQuery(query),
            ),
            headers = sessionHeaders(session),
        )
        val dto = executeAndDecode<EmbyItemsResultDto>(request)
        return MediaServerPage(
            items = dto.items.mapNotNull(::mapItem),
            startIndex = dto.startIndex ?: query.startIndex,
            limit = query.limit,
            totalRecordCount = dto.totalRecordCount,
            returnedItemCount = dto.items.size,
        )
    }

    override suspend fun getItemDetail(
        session: MediaServerSession,
        itemId: String,
    ): MediaServerItemDetail {
        requireSessionVendor(session, vendor)
        require(itemId.isNotBlank()) { "媒体 item ID 不能为空" }
        // 与 listItems 同路径形态, 末段加 itemId(Emby/Jellyfin 一致)。
        val request = MediaServerHttpRequest(
            operation = "emby.item-detail",
            method = MediaServerHttpMethod.GET,
            url = buildMediaServerUrl(
                session.apiBaseUrl,
                listOf("Users", session.userId, "Items", itemId),
            ),
            headers = sessionHeaders(session),
        )
        val dto = executeAndDecode<EmbyItemDto>(request)
        val id = dto.id?.takeIf { it.isNotBlank() } ?: throw MediaServerProtocolException(request.operation)
        return mapDetail(id, dto)
    }

    override suspend fun getPlaybackInfo(
        session: MediaServerSession,
        request: MediaServerPlaybackRequest,
    ): MediaServerPlaybackInfo {
        requireSessionVendor(session, vendor)
        val wireRequest = EmbyPlaybackRequestDto(
            userId = session.userId,
            mediaSourceId = request.mediaSourceId,
            startTimeTicks = millisecondsToTicks(request.startPositionMs),
            audioStreamIndex = request.audioStreamIndex,
            subtitleStreamIndex = request.subtitleStreamIndex,
            maxStreamingBitrate = request.maxStreamingBitrate,
        )
        val httpRequest = MediaServerHttpRequest(
            operation = "emby.playback-info",
            method = MediaServerHttpMethod.POST,
            url = buildMediaServerUrl(
                session.apiBaseUrl,
                listOf("Items", request.itemId, "PlaybackInfo"),
            ),
            headers = sessionHeaders(session),
            body = json.encodeToString(wireRequest),
        )
        val dto = executeAndDecode<EmbyPlaybackInfoDto>(httpRequest)
        return MediaServerPlaybackInfo(
            playSessionId = dto.playSessionId,
            errorCode = dto.errorCode,
            mediaSources = dto.mediaSources.mapNotNull { it.toDomain(session.accessToken) },
        )
    }

    override suspend fun preparePlayback(
        session: MediaServerSession,
        request: MediaServerPlaybackRequest,
    ): MediaServerPlaybackPlan = buildDirectPlayPlan(
        session = session,
        request = request,
        playbackInfo = getPlaybackInfo(session, request),
        authenticationHeaders = authenticationHeaders(session),
    )

    override fun imageRequest(
        session: MediaServerSession,
        itemId: String,
        imageType: MediaServerImageType,
        imageIndex: Int?,
        imageTag: String?,
        maxWidth: Int?,
        maxHeight: Int?,
    ): MediaServerImageRequest {
        requireSessionVendor(session, vendor)
        return buildImageRequest(
            session = session,
            itemId = itemId,
            imageType = imageType,
            imageIndex = imageIndex,
            imageTag = imageTag,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            authenticationHeaders = authenticationHeaders(session),
        )
    }

    override suspend fun reportPlaybackStarted(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
    ) {
        executeWithoutResponse(
            session = session,
            operation = "emby.playing",
            pathSegments = listOf("Sessions", "Playing"),
            body = json.encodeToString(state.toEmbyProgressDto()),
        )
    }

    override suspend fun reportPlaybackProgress(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
    ) {
        executeWithoutResponse(
            session = session,
            operation = "emby.progress",
            pathSegments = listOf("Sessions", "Playing", "Progress"),
            body = json.encodeToString(state.toEmbyProgressDto()),
        )
    }

    override suspend fun reportPlaybackStopped(
        session: MediaServerSession,
        state: MediaServerPlaybackState,
        failed: Boolean,
    ) {
        executeWithoutResponse(
            session = session,
            operation = "emby.stopped",
            pathSegments = listOf("Sessions", "Playing", "Stopped"),
            body = json.encodeToString(
                EmbyPlaybackStoppedDto(
                    itemId = state.itemId,
                    mediaSourceId = state.mediaSourceId,
                    playSessionId = state.playSessionId,
                    positionTicks = millisecondsToTicks(state.positionMs),
                    failed = failed,
                ),
            ),
        )
    }

    override suspend fun logout(session: MediaServerSession) {
        executeWithoutResponse(
            session = session,
            operation = "emby.logout",
            pathSegments = listOf("Sessions", "Logout"),
            body = null,
        )
    }

    private fun requireApiBaseUrl(value: String, allowCleartext: Boolean): String {
        val validation = validateMediaServerBaseUrl(value, vendor)
        require(validation.isValid) { validation.errorMessage ?: "Emby 服务器地址无效" }
        require(!validation.requiresCleartextConfirmation || allowCleartext) {
            "HTTP Emby 必须经过用户明确授权"
        }
        return requireNotNull(validation.normalizedApiBaseUrl)
    }

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
    )

    private fun sessionHeaders(session: MediaServerSession): Map<String, String> =
        jsonHeaders() + authenticationHeaders(session)

    // 直放/字幕/图片认证收敛为单一 X-Emby-Token(值即 token, 无逗号):
    // X-Emby-Authorization 完整形态值含逗号, 会被 mpv http-header-fields(逗号分隔列表)拆成非法头行。
    private fun authenticationHeaders(session: MediaServerSession): Map<String, String> = mapOf(
        "X-Emby-Token" to session.accessToken,
    )

    private fun itemQuery(query: MediaServerItemsQuery): Map<String, String?> = mapOf(
        "ParentId" to query.parentId,
        "StartIndex" to query.startIndex.toString(),
        "Limit" to query.limit.toString(),
        "Recursive" to query.recursive.toString(),
        "IncludeItemTypes" to query.includeItemTypes.mapNotNull { it.wireName() }
            .takeIf { it.isNotEmpty() }?.joinToString(","),
        "SearchTerm" to query.searchTerm?.trim()?.takeIf { it.isNotEmpty() },
        "Fields" to BROWSE_FIELDS,
        "EnableUserData" to "true",
        "EnableImages" to "true",
        "ImageTypeLimit" to "1",
    )

    private fun mapItem(item: EmbyItemDto): MediaServerItem? {
        val id = item.id?.takeIf { it.isNotBlank() } ?: return null
        return MediaServerItem(
            id = id,
            name = item.name.orEmpty().ifBlank { id },
            kind = mediaServerItemKind(item.type, item.isFolder),
            isFolder = item.isFolder,
            mediaType = item.mediaType,
            container = item.container,
            runTimeMs = ticksToMilliseconds(item.runTimeTicks),
            overview = item.overview,
            productionYear = item.productionYear,
            seriesName = item.seriesName,
            indexNumber = item.indexNumber,
            parentIndexNumber = item.parentIndexNumber,
            primaryImageTag = item.imageTags["Primary"],
            userData = item.userData?.let {
                MediaServerUserData(
                    playbackPositionMs = ticksToMilliseconds(it.playbackPositionTicks) ?: 0,
                    played = it.played,
                    playedPercentage = it.playedPercentage,
                )
            },
        )
    }

    private fun mapDetail(id: String, dto: EmbyItemDto): MediaServerItemDetail = MediaServerItemDetail(
        id = id,
        kind = mediaServerItemKind(dto.type, dto.isFolder),
        providerIds = dto.providerIds,
        seriesId = dto.seriesId?.takeIf { it.isNotBlank() },
        seasonId = dto.seasonId?.takeIf { it.isNotBlank() },
        seriesName = dto.seriesName,
        indexNumber = dto.indexNumber,
        parentIndexNumber = dto.parentIndexNumber,
    )

    private fun EmbyMediaSourceDto.toDomain(accessToken: String): MediaServerMediaSource? {
        val sourceId = id?.takeIf { it.isNotBlank() } ?: return null
        return MediaServerMediaSource(
            id = sourceId,
            name = name,
            container = container,
            runTimeMs = ticksToMilliseconds(runTimeTicks),
            supportsDirectPlay = supportsDirectPlay,
            supportsDirectStream = supportsDirectStream,
            supportsTranscoding = supportsTranscoding,
            directStreamUrl = credentialFreeUrlOrNull(directStreamUrl, accessToken),
            transcodingUrl = credentialFreeUrlOrNull(transcodingUrl, accessToken),
            requiredHttpHeaders = requiredHttpHeaders,
            defaultAudioStreamIndex = defaultAudioStreamIndex,
            defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
            mediaStreams = mediaStreams.filter { it.index >= 0 }.map {
                MediaServerMediaStream(
                    index = it.index,
                    type = it.type,
                    codec = it.codec,
                    language = it.language,
                    displayTitle = it.displayTitle,
                    isExternal = it.isExternal,
                    deliveryMethod = it.deliveryMethod,
                    deliveryUrl = credentialFreeUrlOrNull(it.deliveryUrl, accessToken),
                    supportsExternalStream = it.supportsExternalStream,
                )
            },
        )
    }

    private fun MediaServerPlaybackState.toEmbyProgressDto(): EmbyPlaybackProgressDto =
        EmbyPlaybackProgressDto(
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            playSessionId = playSessionId,
            positionTicks = millisecondsToTicks(positionMs),
            audioStreamIndex = audioStreamIndex,
            subtitleStreamIndex = subtitleStreamIndex,
            playMethod = playMethod.wireName,
            isPaused = isPaused,
            isMuted = isMuted,
            canSeek = canSeek,
        )

    private suspend fun executeWithoutResponse(
        session: MediaServerSession,
        operation: String,
        pathSegments: List<String>,
        body: String?,
    ) {
        requireSessionVendor(session, vendor)
        val request = MediaServerHttpRequest(
            operation = operation,
            method = MediaServerHttpMethod.POST,
            url = buildMediaServerUrl(session.apiBaseUrl, pathSegments),
            headers = sessionHeaders(session),
            body = body,
        )
        requireSuccessfulResponse(request, transport.execute(request))
    }

    private suspend inline fun <reified T> executeAndDecode(request: MediaServerHttpRequest): T {
        val response = transport.execute(request)
        requireSuccessfulResponse(request, response)
        return try {
            json.decodeFromString(response.body)
        } catch (_: SerializationException) {
            throw MediaServerProtocolException(request.operation)
        }
    }

    private fun String?.required(operation: String): String =
        this?.takeIf { it.isNotBlank() } ?: throw MediaServerProtocolException(operation)

    private companion object {
        const val BROWSE_FIELDS = "Overview,PrimaryImageAspectRatio"
        const val EMBY_PRODUCT_PREFIX = "Emby"
    }
}
