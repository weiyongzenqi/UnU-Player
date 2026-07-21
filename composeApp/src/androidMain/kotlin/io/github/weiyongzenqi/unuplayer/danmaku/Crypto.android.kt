package io.github.weiyongzenqi.unuplayer.danmaku

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
}
