package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class AndroidMediaServerClientIdentityProvider(
    private val storage: Storage,
    private val clientVersion: String,
) : MediaServerClientIdentityProvider {

    override suspend fun get(): MediaServerClientIdentity = deviceIdMutex.withLock {
        val stored = storage.getString(DEVICE_ID_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        val deviceId = stored ?: UUID.randomUUID().toString().also { generated ->
            storage.putString(DEVICE_ID_KEY, generated)
        }
        MediaServerClientIdentity(
            clientName = "UnU Player",
            clientVersion = clientVersion,
            deviceName = "Android",
            deviceId = deviceId,
        )
    }

    private companion object {
        const val DEVICE_ID_KEY = "mediaServerDeviceId"
        val deviceIdMutex = Mutex()
    }
}
