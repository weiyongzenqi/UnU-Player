package io.github.weiyongzenqi.unuplayer.danmaku

import java.security.MessageDigest
import java.util.Base64

/**
 * 桌面(JVM/Linux) [Crypto] 实现, 对应 androidMain 的 Crypto.android.kt。
 *
 * 用 java.security.MessageDigest(SHA-256/MD5) + java.util.Base64 替代 android.util.Base64。
 * java.util.Base64.getEncoder() 默认带 padding 无换行, 等价 android Base64.NO_WRAP。
 */
actual object Crypto {

    /**
     * 计算 SHA-256 摘要并做标准 Base64 编码。
     * 注意: 是对 SHA-256 的**原始字节**做 Base64, 不是对 hex 串做 Base64。
     * java.util.Base64.getEncoder() 默认带 padding 无换行, 等价 android NO_WRAP。
     */
    actual fun sha256Base64(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
        // getEncoder() 默认带 padding 无换行, 与 android Base64.NO_WRAP 行为一致
        return Base64.getEncoder().encodeToString(digest)
    }

    /**
     * MD5 摘要 -> 32 位小写 hex。
     * 用于弹弹play 文件哈希(前 16MB 的 MD5)。
     */
    actual fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
