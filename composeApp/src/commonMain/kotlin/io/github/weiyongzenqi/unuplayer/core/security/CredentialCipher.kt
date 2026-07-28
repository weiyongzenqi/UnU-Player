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
            // D-V09: 明文 -> 密文迁移。写回失败(Keystore 锁定 / DPAPI 不可用 / 磁盘满)绝不能丢凭据:
            // 降级返回明文(迁移未发生, 下次读取重试)。凭据可用性优先于"立即加密"。
            // AppLogger 不可达说明: 此类在 commonMain 且仅依赖 Storage/CredentialCipher 纯接口(构造函数无 logger 参数,
            // 多个仓库经各自 Provider 组装), 无法注入进程级日志器; 故静默降级, 失败仅表现为"下次读仍为明文"(Keystore 恢复后自动再迁)。
            try {
                putString(key, stored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // 迁移失败不阻断: 明文凭据原样返回, 功能不受影响, 下轮重试迁移。
            }
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
