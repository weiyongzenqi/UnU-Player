package io.github.weiyongzenqi.unuplayer.playback.sync

import android.content.Context
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepositoryImpl
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepositoryProvider

/**
 * P2 同步触发器进程级单例提供者(Android)。
 *
 * MainActivity 的启动/手动同步与 PlayerActivity.onDestroy 的防抖推送必须共用同一实例，
 * 使同步互斥、待执行任务撤销和连接切换在同一所有权边界内完成。实例不随 Activity 销毁，
 * 使用双检锁单例对齐 WebDavConnectionRepositoryProvider。
 */
object PlaybackSyncTriggerProvider {
    @Volatile
    private var instance: PlaybackSyncTrigger? = null

    fun get(context: Context, appLogger: AndroidAppLogger): PlaybackSyncTrigger =
        instance ?: synchronized(this) {
            instance ?: PlaybackSyncTrigger(
                webDavRepository = WebDavConnectionRepositoryProvider.get(context.applicationContext),
                playbackRepository = PlaybackRecordRepositoryImpl.get(context.applicationContext),
                scheduleRepository = ScrapedLibraryRepositoryImpl.get(context.applicationContext),
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProviderImpl(AndroidStorage(context.applicationContext)),
                deviceName = "Android",
                logger = appLogger,
            ).also { instance = it }
        }
}
