package io.github.weiyongzenqi.unuplayer.domain

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.github.weiyongzenqi.unuplayer.core.security.AndroidCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.EncryptedSecretStorage
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage

/**
 * [SettingsRepositoryImpl] 的进程级单例入口(commonMain 类不能感知 android Context,
 * 故单例入口放 androidMain; 机制照抄 PlaybackRecordRepositoryImpl.get / AndroidAppLogger.get:
 * @Volatile 实例 + 双检锁, 首次用 [Context.getApplicationContext] 构造, 后续忽略 context)。
 *
 * 为何必须单例(P1): [SettingsRepositoryImpl.update] 从本实例的 _state 变换后经 saveSettings
 * 全量写穿 DataStore。若 MainActivity 与 PlayerActivity 各持一套实例, 任一界面的设置更新
 * 都会被另一实例的陈旧 state 全量写穿还原。底层 AndroidStorage 的 preferencesDataStore
 * 委托本就是进程级单例(存储已共享), 仓库实例也必须共享, 更新才跨界面可见。
 */
object SettingsRepositoryProvider {

    @Volatile private var instance: SettingsRepositoryImpl? = null

    /**
     * 进程级 scope: 仅供仓库 init 的异步首载(init 内唯一用法是 scope.launch 加载 Storage)。
     * 不能用 Activity 的 scope——Activity 销毁会取消加载/写入, 第二个 Activity 会拿到死 scope 的仓库。
     * 语义与原先 Activity 传入的 MainScope()(SupervisorJob + Dispatchers.Main)一致, 仅生命周期提到进程级。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 进程级单例。首次用 [context] 建存储与安全凭据仓库, 后续忽略 context。 */
    fun get(context: Context): SettingsRepositoryImpl =
        instance ?: synchronized(this) {
            instance ?: run {
                val storage = AndroidStorage(context.applicationContext)
                val secretStorage = EncryptedSecretStorage(storage, AndroidCredentialCipher())
                SettingsRepositoryImpl(storage, scope, secretStorage).also { instance = it }
            }
        }
}
