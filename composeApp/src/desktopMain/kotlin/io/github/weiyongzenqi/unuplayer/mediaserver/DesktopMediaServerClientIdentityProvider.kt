package io.github.weiyongzenqi.unuplayer.mediaserver

import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Windows 安装级媒体服务器设备身份；保存在应用设置文件中，卸载数据后自然失效。 */
class DesktopMediaServerClientIdentityProvider(
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
            deviceName = "Windows",
            deviceId = deviceId,
        )
    }

    private companion object {
        const val DEVICE_ID_KEY = "mediaServerDeviceId"
        val deviceIdMutex = Mutex()
    }
}
