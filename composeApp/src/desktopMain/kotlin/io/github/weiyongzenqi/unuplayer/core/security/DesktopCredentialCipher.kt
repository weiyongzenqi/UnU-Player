package io.github.weiyongzenqi.unuplayer.core.security

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.util.Base64

/** 使用 Windows DPAPI CurrentUser 保护凭据，不在应用文件中保存自管密钥。 */
class DesktopCredentialCipher : CredentialCipher {

    override fun protect(purpose: String, plaintext: String): String = try {
        val protectedBytes = Crypt32Util.cryptProtectData(
            plaintext.toByteArray(Charsets.UTF_8),
            purpose.toByteArray(Charsets.UTF_8),
            WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
            DATA_DESCRIPTION,
            null,
        )
        PROTECTED_CREDENTIAL_PREFIX + Base64.getEncoder().encodeToString(protectedBytes)
    } catch (error: Throwable) {
        throw CredentialProtectionException(PROTECT_ERROR_MESSAGE, error)
    }

    override fun unprotect(purpose: String, protectedValue: String): String {
        if (!isProtected(protectedValue)) {
            throw CredentialProtectionException(UNPROTECT_ERROR_MESSAGE)
        }

        return try {
            val encoded = protectedValue.removePrefix(PROTECTED_CREDENTIAL_PREFIX)
            val protectedBytes = Base64.getDecoder().decode(encoded)
            if (protectedBytes.isEmpty()) {
                throw IllegalArgumentException("加密载荷为空")
            }
            Crypt32Util.cryptUnprotectData(
                protectedBytes,
                purpose.toByteArray(Charsets.UTF_8),
                WinCrypt.CRYPTPROTECT_UI_FORBIDDEN,
                null,
            ).toString(Charsets.UTF_8)
        } catch (error: Throwable) {
            throw CredentialProtectionException(UNPROTECT_ERROR_MESSAGE, error)
        }
    }

    private companion object {
        const val DATA_DESCRIPTION = "UnU-Player credential"
        const val PROTECT_ERROR_MESSAGE = "凭据加密失败"
        const val UNPROTECT_ERROR_MESSAGE = "凭据解密失败"
    }
}
