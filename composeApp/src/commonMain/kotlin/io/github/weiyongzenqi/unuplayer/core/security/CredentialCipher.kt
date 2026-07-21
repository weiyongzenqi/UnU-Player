package io.github.weiyongzenqi.unuplayer.core.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.platform.Storage

/** 版本化密文统一前缀；数据库/设置中只允许此前缀或空值，不把平台密钥材料写入应用文件。 */
const val PROTECTED_CREDENTIAL_PREFIX = "unu-sec:v1:"

/** 凭据保护失败只暴露安全的固定消息，cause 不进入 UI/日志字符串。 */
class CredentialProtectionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Android Keystore / Windows DPAPI 的平台抽象。purpose 用作 AAD/附加熵，防密文跨字段替换。 */
interface CredentialCipher {
    fun isProtected(value: String): Boolean = value.startsWith(PROTECTED_CREDENTIAL_PREFIX)
    fun protect(purpose: String, plaintext: String): String
    fun unprotect(purpose: String, protectedValue: String): String
}

/** 敏感设置独立于普通 Settings 快照的持久化接口。 */
interface SecretStorage {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String)
    suspend fun remove(key: String)
}

/**
 * 用平台 cipher 加密后仍复用现有原子 Storage；磁盘只出现 versioned envelope。
 * 若早期版本误把明文写到 credential.*，首次读取会先加密覆盖再返回。
 */
class EncryptedSecretStorage(
    private val storage: Storage,
    private val cipher: CredentialCipher,
) : SecretStorage {

    override suspend fun getString(key: String): String? {
        val storageKey = storageKey(key)
        val stored = storage.getString(storageKey, null) ?: return null
        if (!cipher.isProtected(stored)) {
            putString(key, stored)
            return stored
        }
        return try {
            withContext(Dispatchers.Default) {
                cipher.unprotect(purpose(key), stored)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw CredentialProtectionException("安全凭据无法解密，请重新输入", error)
        }
    }

    override suspend fun putString(key: String, value: String) {
        val protectedValue = try {
            withContext(Dispatchers.Default) {
                cipher.protect(purpose(key), value)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw CredentialProtectionException("安全凭据无法保存，请稍后重试", error)
        }
        // suspend 写入放在 catch 之外，确保 CancellationException 不会被包装成普通安全错误。
        storage.putString(storageKey(key), protectedValue)
    }

    override suspend fun remove(key: String) {
        storage.remove(storageKey(key))
    }

    private fun storageKey(key: String): String = "credential.$key"
    private fun purpose(key: String): String = "settings:$key"
}
