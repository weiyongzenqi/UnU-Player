package io.github.weiyongzenqi.unuplayer.smb

import android.content.Context
import io.github.weiyongzenqi.unuplayer.core.security.AndroidCredentialCipher
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider

/** SMB 连接仓库进程级单例，与首页和 PlayerActivity 共用同一把读改写锁。 */
object SmbConnectionRepositoryProvider {
    @Volatile private var instance: SmbConnectionRepository? = null

    fun get(context: Context): SmbConnectionRepository = instance ?: synchronized(this) {
        instance ?: SmbConnectionRepository(
            UnuDatabaseProvider.get(context.applicationContext),
            AndroidCredentialCipher(),
        ).also { instance = it }
    }
}
