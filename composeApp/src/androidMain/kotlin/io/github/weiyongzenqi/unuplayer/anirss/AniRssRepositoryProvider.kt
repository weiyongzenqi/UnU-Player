package io.github.weiyongzenqi.unuplayer.anirss

import android.content.Context
import io.github.weiyongzenqi.unuplayer.core.security.AndroidCredentialCipher
import io.github.weiyongzenqi.unuplayer.core.security.EncryptedSecretStorage
import io.github.weiyongzenqi.unuplayer.domain.SettingsRepository
import io.github.weiyongzenqi.unuplayer.platform.AndroidStorage

object AniRssRepositoryProvider {
    @Volatile private var instance: AniRssRepository? = null

    fun get(context: Context, settingsRepository: SettingsRepository): AniRssRepository =
        instance ?: synchronized(this) {
            instance ?: run {
                val storage = AndroidStorage(context.applicationContext)
                AniRssRepositoryImpl(
                    settingsRepository = settingsRepository,
                    secretStorage = EncryptedSecretStorage(storage, AndroidCredentialCipher()),
                ).also { instance = it }
            }
        }
}
