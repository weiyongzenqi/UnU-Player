package io.github.weiyongzenqi.unuplayer.domain

import io.github.weiyongzenqi.unuplayer.core.security.redactSensitiveText

/** SMB2/3 连接配置。密码只在仓库解密后的短生命周期内暴露。 */
data class SmbConnection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
    /** 是否要求 SMB3 加密；签名始终按服务端要求协商，不允许应用静默降级。 */
    val requireEncryption: Boolean = false,
    val credentialUnavailable: Boolean = false,
) {
    override fun toString(): String =
        "SmbConnection(id=$id, name=$name, host=${redactSensitiveText(host)}, port=$port, " +
            "share=${redactSensitiveText(share)}, username=<redacted>, password=<redacted>, " +
            "domain=<redacted>, requireEncryption=$requireEncryption, credentialUnavailable=$credentialUnavailable)"
}
