package io.github.weiyongzenqi.unuplayer.danmaku

/**
 * 弹弹play API 签名所需的跨平台加密原语。
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
 * 弹弹play X-Signature 签名。
 *
 * 算法: `base64(sha256(AppId + Timestamp + Path + AppSecret))`
 * 字符串按顺序直接拼接, 无分隔符。
 *
 * @param appId      应用 ID
 * @param timestamp  Unix 时间戳(秒, 整数)
 * @param path       API 路径, 以 `/` 开头, **不含协议/域名/query 参数**
 *                   (如访问 `https://api.dandanplay.net/api/v2/comment/123?withRelated=true`
 *                   时, path = `/api/v2/comment/123`)
 * @param appSecret  应用密钥
 */
fun dandanplaySignature(
    appId: String,
    timestamp: Long,
    path: String,
    appSecret: String,
): String = Crypto.sha256Base64(appId + timestamp + path + appSecret)
