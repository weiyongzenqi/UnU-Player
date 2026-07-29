package io.github.weiyongzenqi.unuplayer.playback.sync

import android.content.Context
import io.github.weiyongzenqi.unuplayer.platform.AndroidAppLogger
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepositoryProvider

/**
 * P2 同步触发器进程级单例提供者(Android)。
 *
 * PlayerActivity.onDestroy 触发防抖推送时需进程级 trigger(不随 Activity 销毁),
 * 每次 onDestroy new trigger 浪费, 故用双检锁单例(对齐 WebDavConnectionRepositoryProvider)。
 */
object PlaybackSyncTriggerProvider {
    @Volatile
    private var instance: PlaybackSyncTrigger? = null

    fun get(context: Context, appLogger: AndroidAppLogger): PlaybackSyncTrigger =
        instance ?: synchronized(this) {
            instance ?: PlaybackSyncTrigger(
                webDavRepository = WebDavConnectionRepositoryProvider.get(context.applicationContext),
                playbackRepository = PlaybackRecordRepositoryImpl.get(context.applicationContext),
                deviceIdentityProvider = PlaybackSyncDeviceIdentityProviderImpl(AndroidStorage(context.applicationContext)),
                deviceName = "Android",
                logger = appLogger,
            ).also { instance = it }
        }
}
