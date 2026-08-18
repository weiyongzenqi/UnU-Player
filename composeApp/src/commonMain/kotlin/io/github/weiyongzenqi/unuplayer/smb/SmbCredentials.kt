package io.github.weiyongzenqi.unuplayer.smb

import io.github.weiyongzenqi.unuplayer.util.Crypto
import io.github.weiyongzenqi.unuplayer.domain.SmbConnection

/** 仅用于 MediaSourceCache 身份比较的凭据指纹，不返回明文。 */
fun smbCredentialsToken(connection: SmbConnection): String =
    Crypto.sha256Base64(
        connection.username + Char.MIN_VALUE + connection.domain + Char.MIN_VALUE + connection.password,
    )
