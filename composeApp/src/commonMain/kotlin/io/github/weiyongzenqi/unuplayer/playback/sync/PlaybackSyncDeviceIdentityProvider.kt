package io.github.weiyongzenqi.unuplayer.playback.sync

import io.github.weiyongzenqi.unuplayer.core.platform.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * P2 同步设备身份提供者: 返回稳定(卸载即毁)的 deviceId, 用于同步文件名 <deviceId>.json。
 *
 * 照 AndroidMediaServerClientIdentityProvider 先例(Storage + Mutex.withLock + 独立 key),
 * 但 deviceId 生成用 kotlin.random.Random(commonMain 禁 java.util.UUID)。
 *
 * **禁用跨重装稳定 ID**(如 ANDROID_ID): 卸载重装后 deviceId 变化, 旧设备文件成孤儿但可被 pull 合并;
 * 若用跨重装稳定 ID, 重装空库先 push 会用同 ID 覆盖服务器旧文件丢数据。
 */
fun interface PlaybackSyncDeviceIdentityProvider {
    suspend fun get(): String
}

/**
 * 生成 32 字符 hex 设备 ID(照 randomMediaServerConnectionId: Random.nextBytes(16) + 手动 hex)。
 * commonMain 无 java.util.UUID, 用纯 Kotlin Random。
 */
internal fun randomSyncDeviceId(): String {
    val bytes = Random.nextBytes(16)
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append(alphabet[unsigned ushr 4])
            append(alphabet[unsigned and 0x0f])
        }
    }
}

/**
 * [PlaybackSyncDeviceIdentityProvider] 默认实现: Storage 持久化 + Mutex 防并发首生成。
 * 首次取时生成并存, 后续读存储值。卸载 app 清除 Storage 即毁 deviceId。
 */
class PlaybackSyncDeviceIdentityProviderImpl(
    private val storage: Storage,
) : PlaybackSyncDeviceIdentityProvider {
    override suspend fun get(): String = mutex.withLock {
        val stored = storage.getString(KEY)?.trim()?.takeIf { it.isNotEmpty() }
        stored ?: randomSyncDeviceId().also { generated -> storage.putString(KEY, generated) }
    }

    private companion object {
        const val KEY = "syncDeviceId"
        val mutex = Mutex()
    }
}