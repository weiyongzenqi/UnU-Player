package io.github.weiyongzenqi.unuplayer.library.export

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual fun protectLibraryExportPassword(exportPassword: String, plaintext: String): String = try {
    val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
    val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
    val key = deriveKey(exportPassword, salt)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(LIBRARY_EXPORT_PASSWORD_PREFIX.toByteArray(Charsets.UTF_8))
    val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val payload = salt + iv + encrypted
    LIBRARY_EXPORT_PASSWORD_PREFIX + Base64.getEncoder().encodeToString(payload)
} catch (error: Exception) {
    throw IllegalArgumentException("导出包密码保护失败", error)
}

actual fun unprotectLibraryExportPassword(exportPassword: String, protectedValue: String): String = try {
    require(protectedValue.startsWith(LIBRARY_EXPORT_PASSWORD_PREFIX)) { "导出包密码格式无效" }
    val payload = Base64.getDecoder().decode(protectedValue.removePrefix(LIBRARY_EXPORT_PASSWORD_PREFIX))
    require(payload.size >= SALT_BYTES + IV_BYTES + TAG_BYTES) { "导出包密码载荷无效" }
    val salt = payload.copyOfRange(0, SALT_BYTES)
    val iv = payload.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
    val encrypted = payload.copyOfRange(SALT_BYTES + IV_BYTES, payload.size)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, deriveKey(exportPassword, salt), GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(LIBRARY_EXPORT_PASSWORD_PREFIX.toByteArray(Charsets.UTF_8))
    cipher.doFinal(encrypted).toString(Charsets.UTF_8)
} catch (error: Exception) {
    throw IllegalArgumentException("导出包密码错误或已损坏", error)
}

private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
    val spec = PBEKeySpec(password.toCharArray(), salt, LIBRARY_EXPORT_PASSWORD_ITERATIONS, KEY_BITS)
    return try {
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
    } finally {
        spec.clearPassword()
    }
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_BITS = 256
private const val TAG_BITS = 128
private const val SALT_BYTES = 16
private const val IV_BYTES = 12
private const val TAG_BYTES = TAG_BITS / 8
