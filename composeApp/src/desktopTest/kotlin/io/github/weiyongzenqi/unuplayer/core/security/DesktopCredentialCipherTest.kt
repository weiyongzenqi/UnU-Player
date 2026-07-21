package io.github.weiyongzenqi.unuplayer.core.security

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DesktopCredentialCipherTest {
    private val cipher = DesktopCredentialCipher()

    @Test
    fun `Unicode 凭据可往返解密`() {
        val plaintext = "动漫库-密码🔐-пароль"
        val protectedValue = cipher.protect(PURPOSE, plaintext)

        assertEquals(plaintext, cipher.unprotect(PURPOSE, protectedValue))
    }

    @Test
    fun `相同明文每次生成不同密文`() {
        val first = cipher.protect(PURPOSE, "same-password")
        val second = cipher.protect(PURPOSE, "same-password")

        assertNotEquals(first, second)
    }

    @Test
    fun `不同 purpose 不能解密`() {
        val protectedValue = cipher.protect(PURPOSE, "password")

        assertFailsWith<CredentialProtectionException> {
            cipher.unprotect("settings:another-secret", protectedValue)
        }
    }

    @Test
    fun `损坏密文不能解密`() {
        val protectedValue = cipher.protect(PURPOSE, "password")
        val encoded = protectedValue.removePrefix(PROTECTED_CREDENTIAL_PREFIX)
        val damagedBytes = Base64.getDecoder().decode(encoded).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }
        val damagedValue = PROTECTED_CREDENTIAL_PREFIX +
            Base64.getEncoder().encodeToString(damagedBytes)

        assertFailsWith<CredentialProtectionException> {
            cipher.unprotect(PURPOSE, damagedValue)
        }
    }

    @Test
    fun `无版本前缀不能解密`() {
        assertFailsWith<CredentialProtectionException> {
            cipher.unprotect(PURPOSE, "not-a-protected-credential")
        }
    }

    private companion object {
        const val PURPOSE = "settings:dandanplayAppSecret"
    }
}
