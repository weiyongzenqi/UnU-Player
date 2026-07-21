package io.github.weiyongzenqi.unuplayer.danmaku.source

/**
 * 弹弹play 代理缓存内置配置(代理端点 + API Key 均混淆存储)。
 *
 * 项目开源, 代理端点与 API Key 都不能以明文出现在源码里(防 grep / 搜索引擎索引 / 懒人直接拷走)。
 * 两者均用 XOR 混淆 + 十六进制存储: 明文逐字节 XOR([mask]) 后存为 hex 字符串, 运行时 [decode] 反解。
 * [mask] 由位运算得出而非明文 0x.. 常量, 密文也不含任何明文子串, 提高反编译门槛。
 *
 * 注: 客户端持有配置本质无法防泄露(反编译总可逆), 真正的滥用防护在服务端四维限流。
 * 本混淆仅用于"不暴露明文 + 不被简单 grep / 爬虫命中", 不是密码学意义的保护。
 *
 * 如需换 URL/Key: 用
 *   python3 -c 's=b"..."; mask=0x37; print(bytes(b^mask for b in s).hex())'
 * 生成新 hex 填入对应常量即可(保持 [mask] 表达式不变)。
 */
internal object DandanplayProxyConfig {
    /** 代理端点经 XOR 混淆后的十六进制密文。 */
    private const val OBFUSCATED_URL_HEX = "5f434347440d18185356595356595456545f521907000506070705194f4e4d"

    /** API Key 经 XOR 混淆后的十六进制密文。 */
    private const val OBFUSCATED_KEY_HEX = "71566663635a6f455e7847765a6d6f036d6575440f797b4547"

    /** XOR mask: 由位运算得出, 不写明文 0x.. 常量。值 = (0x3 shl 4) or 0x7。 */
    private val mask: Int get() = (0x3 shl 4) or 0x7

    /** 运行时解密出代理端点。 */
    fun proxyUrl(): String = decode(OBFUSCATED_URL_HEX)

    /** 运行时解密出 API Key。 */
    fun apiKey(): String = decode(OBFUSCATED_KEY_HEX)

    /** hex 密文 -> 明文: 每 2 个 hex 字符 -> 1 字节 -> xor mask -> char。 */
    private fun decode(hex: String): String {
        val m = mask
        val out = CharArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hexVal(hex[i])
            val lo = hexVal(hex[i + 1])
            out[i / 2] = (((hi shl 4) or lo) xor m).toChar()
            i += 2
        }
        return String(out)
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("bad hex char: $c")
    }
}
