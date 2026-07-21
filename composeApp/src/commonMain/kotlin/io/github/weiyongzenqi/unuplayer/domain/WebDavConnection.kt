package io.github.weiyongzenqi.unuplayer.domain

import kotlinx.serialization.Serializable
import io.github.weiyongzenqi.unuplayer.core.security.redactSensitiveText

/**
 * WebDAV 连接配置。持久化到统一 SQLDelight 用户数据库。
 * P1-8 连接管理用。
 */
@Serializable
data class WebDavConnection(
    val id: String,            // UUID
    val name: String,          // 用户起的别名
    val baseUrl: String,
    val username: String,
    val password: String,
    /** 平台密钥失效、密文损坏等情况下保留连接元数据，但禁止把它当匿名连接使用。 */
    val credentialUnavailable: Boolean = false,
) {
    /** data class 默认 toString 会展开密码；日志、异常和调试器文本输出统一只显示占位符。 */
    override fun toString(): String =
        "WebDavConnection(id=$id, name=$name, baseUrl=${redactSensitiveText(baseUrl)}, username=$username, " +
            "password=<redacted>, credentialUnavailable=$credentialUnavailable)"
}
