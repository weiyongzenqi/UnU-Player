package io.github.weiyongzenqi.unuplayer.playback.sync

/**
 * P2 同步 gzip 压缩层(expect/actual)。
 * commonMain 禁 java.util.zip(CR-016 边界: java.io/java.net/java.util.concurrent 禁, java.util.zip 虽不在禁表
 * 但属 JVM 专有须 expect/actual)。两端 actual 用 GZIPOutputStream/GZIPInputStream。
 * 压缩率约 1/8~1/10, 8MiB 上限压缩后可存 15-25 万条记录。
 */

/** gzip 压缩 UTF-8 文本为字节数组。 */
expect fun gzipCompress(text: String): ByteArray

/** gzip 解压字节数组为 UTF-8 文本。失败抛异常(调用方 runCatching 包裹)。 */
expect fun gzipDecompress(bytes: ByteArray): String
