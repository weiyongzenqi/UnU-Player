package io.github.weiyongzenqi.unuplayer.core.security

/** 解码客户端内置的 XOR + hex 字符串。仅用于降低静态直读成本，不提供密码学保护。 */
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
