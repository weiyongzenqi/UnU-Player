package io.github.weiyongzenqi.unuplayer.core.security

/** 解码客户端内置的 XOR + hex 字符串。仅用于降低静态直读成本，不提供密码学保护。 */
/** 三处网关客户端(弹弹代理/TMDB/Bangumi)共用的内置 API key 密文; 轮换时只改这一处。 */
internal const val OBFUSCATED_GATEWAY_API_KEY_HEX =
    "455359564d405400420f01547550706f744343706604567b720462070755766f7a5973040558070e75726204727e7b59"

/** 网关客户端统一 XOR mask, 值为 0x37, 不以单一明文常量保存。 */
internal val GATEWAY_CLIENT_MASK: Int get() = (0x3 shl 4) or 0x7

internal fun decodeObfuscatedClientValue(hex: String, mask: Int): String {
    require(hex.length % 2 == 0) { "混淆字符串长度无效" }
    require(mask in 0..0xff) { "混淆掩码无效" }

    val bytes = ByteArray(hex.length / 2)
    var index = 0
    while (index < hex.length) {
        val high = hexValue(hex[index])
        val low = hexValue(hex[index + 1])
        bytes[index / 2] = (((high shl 4) or low) xor mask).toByte()
        index += 2
    }
    return bytes.decodeToString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> throw IllegalArgumentException("混淆字符串包含非法字符")
}
