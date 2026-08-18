package io.github.weiyongzenqi.unuplayer.danmaku

import io.github.weiyongzenqi.unuplayer.util.Crypto
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import io.github.weiyongzenqi.unuplayer.core.network.hashPrefixMd5AndCancel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 流式 MD5 累加器 + 网络流前缀哈希测试(B-11): 验证分块流式结果与整块/已知向量一致。
 */
class Md5AccumulatorTest {

    @Test
    fun `流式逐字节分块结果等于 RFC 1321 已知向量`() {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72
        val bytes = "abc".encodeToByteArray()
        val accumulator = Crypto.md5Accumulator()
        // 逐字节分块, 覆盖 update(offset, length) 的边界组合
        for (i in bytes.indices) accumulator.update(bytes, i, 1)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", accumulator.hexDigest())
    }

    @Test
    fun `流式变长分块与整块 md5Hex 一致`() {
        val bytes = Random(42).nextBytes(300_000)
        val bulk = Crypto.md5Hex(bytes)
        val accumulator = Crypto.md5Accumulator()
        var offset = 0
        var chunk = 4096
        while (offset < bytes.size) {
            val length = minOf(chunk, bytes.size - offset)
            accumulator.update(bytes, offset, length)
            offset += length
            chunk = if (chunk == 4096) 7919 else 4096 // 变长分块, 覆盖非对齐边界
        }
        assertEquals(bulk, accumulator.hexDigest())
    }

    @Test
    fun `网络流前缀哈希流式结果等于整块 MD5`() = runBlocking {
        val bytes = Random(7).nextBytes(3_000_000) // 跨多个 1MB 分块
        val streamed = hashPrefixMd5AndCancel(ByteReadChannel(bytes), limit = 16 * 1024 * 1024)
        assertEquals(Crypto.md5Hex(bytes), streamed)
    }

    @Test
    fun `网络流前缀哈希只哈希 limit 前缀`() = runBlocking {
        val prefix = Random(11).nextBytes(1024)
        val full = prefix + Random(12).nextBytes(4096)
        val streamed = hashPrefixMd5AndCancel(ByteReadChannel(full), limit = 1024)
        assertEquals(Crypto.md5Hex(prefix), streamed)
    }
}
