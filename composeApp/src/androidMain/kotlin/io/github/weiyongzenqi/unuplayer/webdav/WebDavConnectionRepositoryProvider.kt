package io.github.weiyongzenqi.unuplayer.webdav

import android.content.Context
import io.github.weiyongzenqi.unuplayer.core.security.AndroidCredentialCipher
import io.github.weiyongzenqi.unuplayer.playback.UnuDatabaseProvider

/**
 * [WebDavConnectionRepository] 的进程级单例入口(commonMain 类不能感知 android Context,
 * 故单例入口放 androidMain; 机制照抄 PlaybackRecordRepositoryImpl.get:
 * @Volatile 实例 + 双检锁, 首次用 [context] 构造, 后续忽略 context)。
 *
 * 为何必须单例(B10): 仓库的 mutationMutex 是实例级, 且读路径 loadDecodedLocked 隐含
 * 密文迁移回写。若 MainActivity 与 PlayerActivity 各持一套实例, 升级首播的迁移写与
 * 首页连接编辑并发时会互相覆盖丢更新。底层数据库(UnuDatabaseProvider)已是进程级单例,
 * 仓库实例也必须共享, 实例锁才真正覆盖全部并发入口。
 */
object WebDavConnectionRepositoryProvider {

    @Volatile private var instance: WebDavConnectionRepository? = null

    /** 进程级单例。首次用 [context] 打开数据库并建凭据 cipher(无状态, Keystore 密钥按固定别名共享), 后续忽略 context。 */
    fun get(context: Context): WebDavConnectionRepository =
        instance ?: synchronized(this) {
            instance ?: WebDavConnectionRepository(
                UnuDatabaseProvider.get(context.applicationContext),
                AndroidCredentialCipher(),
            ).also { instance = it }
        }
}
