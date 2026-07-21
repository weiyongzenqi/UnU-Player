package io.github.weiyongzenqi.unuplayer.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 使用仅属于当前应用的 Android Keystore 密钥保护凭据。 */
class AndroidCredentialCipher : CredentialCipher {

    override fun protect(purpose: String, plaintext: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))

        val iv = cipher.iv
        if (iv.size != IV_LENGTH_BYTES) {
            throw IllegalStateException("Android Keystore 返回了非预期长度的 IV")
        }
        val protectedBytes = iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        PROTECTED_CREDENTIAL_PREFIX + Base64.encodeToString(protectedBytes, Base64.NO_WRAP)
    } catch (error: Throwable) {
        throw CredentialProtectionException(PROTECT_ERROR_MESSAGE, error)
    }

    override fun unprotect(purpose: String, protectedValue: String): String {
        if (!isProtected(protectedValue)) {
            throw CredentialProtectionException(UNPROTECT_ERROR_MESSAGE)
        }

        return try {
            val encoded = protectedValue.removePrefix(PROTECTED_CREDENTIAL_PREFIX)
            val protectedBytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (protectedBytes.size < IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
                throw IllegalArgumentException("加密载荷过短")
            }

            val iv = protectedBytes.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = protectedBytes.copyOfRange(IV_LENGTH_BYTES, protectedBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (error: Throwable) {
            throw CredentialProtectionException(UNPROTECT_ERROR_MESSAGE, error)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null)
        if (existingKey != null) {
            return@synchronized existingKey as? SecretKey
                ?: throw IllegalStateException("Android Keystore 中的凭据密钥类型无效")
        }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "unu-player-credential-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        const val PROTECT_ERROR_MESSAGE = "凭据加密失败"
        const val UNPROTECT_ERROR_MESSAGE = "凭据解密失败"
        val KEY_LOCK = Any()
    }
}
