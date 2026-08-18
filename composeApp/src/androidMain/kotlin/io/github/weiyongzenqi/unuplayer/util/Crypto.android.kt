package io.github.weiyongzenqi.unuplayer.util

import android.util.Base64
import java.security.MessageDigest

/**
 * Android 端 [Crypto] 实现: java.security.MessageDigest(SHA-256) + android.util.Base64。
 */
actual object Crypto {
    actual fun sha256Base64(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
        // NO_WRAP: 不加换行符, 否则签名校验失败
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    actual fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    actual fun md5Accumulator(): Md5Accumulator = object : Md5Accumulator {
        private val digest = MessageDigest.getInstance("MD5")
        override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
        override fun hexDigest(): String =
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    actual fun sha256Accumulator(): Sha256Accumulator = object : Sha256Accumulator {
        private val digest = MessageDigest.getInstance("SHA-256")
        override fun update(bytes: ByteArray, offset: Int, length: Int) = digest.update(bytes, offset, length)
        override fun hexDigest(): String =
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    actual fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
