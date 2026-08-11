package io.github.weiyongzenqi.unuplayer.smb

import java.util.Base64

/**
 * 无凭据 SMB 播放定位。
 *
 * URL 只携带连接 id 和相对路径的 URL-safe 编码，真正的连接配置由播放器进程从加密仓库读取。
 * 使用 base64url 是为了避免远程路径中的斜杠、问号和百分号被 Uri 重新解释。
 */
data class SmbPlaybackLocator(
    val connectionId: String,
    val path: String,
) {
    fun toUrl(): String = PREFIX + encode(connectionId) + "/" + encode(path)

    companion object {
        private const val PREFIX = "smbfd://"

        fun parse(url: String): SmbPlaybackLocator? {
            if (!url.startsWith(PREFIX, ignoreCase = true)) return null
            val rest = url.substring(PREFIX.length)
            val separator = rest.indexOf('/')
            if (separator <= 0 || separator == rest.lastIndex) return null
            val connectionId = decode(rest.substring(0, separator)) ?: return null
            val path = decode(rest.substring(separator + 1)) ?: return null
            return SmbPlaybackLocator(connectionId, path).takeIf {
                it.connectionId.isNotBlank() && it.path.isNotBlank()
            }
        }

        private fun encode(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun decode(value: String): String? = runCatching {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        }.getOrNull()
    }
}
