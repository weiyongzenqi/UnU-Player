package io.github.weiyongzenqi.unuplayer.util

/**
 * 跨平台加密原语(通用工具, 原居 danmaku 包; 弹弹play 签名、海报缓存 key、
 * 字体缓存 key、SMB 凭据、媒体服务器历史键等均依赖, 属层次倒置故迁至 util)。
 *
 * commonMain 声明 expect, 各平台 actual 提供实现:
 * - Android/JVM: java.security.MessageDigest + android.util.Base64
 *
 * commonMain 无 SHA-256 / Base64 stdlib, 故走 expect/actual。
 */
expect object Crypto {
    /**
     * 计算 SHA-256 摘要并做标准 Base64 编码。
     *
     * 注意: 是对 SHA-256 的**原始字节**做 Base64, 不是对 hex 串做 Base64。
     * Base64 不加换行(NO_WRAP), 否则签名校验失败。
     */
    fun sha256Base64(data: String): String

    /**
     * MD5 摘要 -> 32 位小写 hex。
     * 用于弹弹play 文件哈希(前 16MB 的 MD5)。
     */
    fun md5Hex(bytes: ByteArray): String

    /** 创建流式 MD5 累加器: 分块 update + 末次 hexDigest, 结果与同字节序列 [md5Hex] 一致。 */
    fun md5Accumulator(): Md5Accumulator

    /** 创建流式 SHA-256 累加器: 分块 update + 末次 hexDigest, 结果与同字符串 [sha256Hex] 一致。 */
    fun sha256Accumulator(): Sha256Accumulator

    /**
     * SHA-256 摘要 -> 64 位小写 hex。
     * 用于海报缓存 key 等场景的哈希生成。
     */
    fun sha256Hex(value: String): String
}

/**
 * 流式 MD5 累加器: 分块 [update] 累积, [hexDigest] 输出 32 位小写 hex。
 * 用于无法整块载入内存的数据(远程 16MB 弹幕哈希走网络流分块), 峰值内存仅单块缓冲。
 * 单线程使用, 非线程安全; [hexDigest] 后实例不应再用。
 */
interface Md5Accumulator {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun hexDigest(): String
}

/**
 * 流式 SHA-256 累加器: 分块 [update] 累积, [hexDigest] 输出 64 位小写 hex。
 * 用于无法整块载入内存的数据(大字体文件哈希), 峰值内存仅单块缓冲。
 * 单线程使用, 非线程安全; [hexDigest] 后实例不应再用。
 */
interface Sha256Accumulator {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun hexDigest(): String
}
