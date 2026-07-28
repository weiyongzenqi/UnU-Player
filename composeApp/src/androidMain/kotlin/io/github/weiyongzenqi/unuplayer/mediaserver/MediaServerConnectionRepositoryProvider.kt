package io.github.weiyongzenqi.unuplayer.mediaserver

import android.content.Context
import io.github.weiyongzenqi.unuplayer.core.security.AndroidCredentialCipher
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider

/** 进程级共享连接仓库，确保读改写事务使用同一把 Mutex。 */
object MediaServerConnectionRepositoryProvider {

    @Volatile
    private var instance: MediaServerConnectionRepository? = null

    fun get(context: Context): MediaServerConnectionRepository =
        instance ?: synchronized(this) {
            instance ?: MediaServerConnectionRepository(
                UnuDatabaseProvider.get(context.applicationContext),
                AndroidCredentialCipher(),
            ).also { instance = it }
        }
}
