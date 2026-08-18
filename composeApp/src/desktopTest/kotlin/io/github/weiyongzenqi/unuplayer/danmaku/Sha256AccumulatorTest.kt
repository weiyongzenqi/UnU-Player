package io.github.weiyongzenqi.unuplayer.danmaku

import io.github.weiyongzenqi.unuplayer.util.Crypto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 流式 SHA-256 累加器 + 单次 sha256Hex 测试: 验证分块流式结果与已知向量/整块一致。
 *
 * 海报缓存 key(PosterCache 双端)与桌面字体缓存 key(DesktopSubtitleFontStore)都依赖
 * [Crypto.sha256Accumulator]/[Crypto.sha256Hex], 此测试防止 hex 编码或算法回归导致缓存 key 静默漂移。
 */
class Sha256AccumulatorTest {

    @Test
    fun `流式逐字节分块结果等于 NIST 已知向量`() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val bytes = "abc".encodeToByteArray()
        val accumulator = Crypto.sha256Accumulator()
        // 逐字节分块, 覆盖 update(offset, length) 的边界组合
        for (i in bytes.indices) accumulator.update(bytes, i, 1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", accumulator.hexDigest())
    }

    @Test
    fun `流式变长分块与整块 sha256Hex 一致`() {
        // 修复前: 输入仅 ~1KB < 首个 chunk 4096, 循环一次即退出, 7919 交替分支是死代码,
        // 非对齐多块大分块路径无覆盖。改为 3×4096+517 字节, 实际执行 4096/7919/4096(截尾)三次 update。
        val text = "poster-cache-key-中文-测试-" + "x".repeat(4096 * 3 + 517)
        val bulk = Crypto.sha256Hex(text)
        val accumulator = Crypto.sha256Accumulator()
        val bytes = text.encodeToByteArray()
        var offset = 0
        var chunk = 4096
        var updateCalls = 0
        while (offset < bytes.size) {
            val length = minOf(chunk, bytes.size - offset)
            accumulator.update(bytes, offset, length)
            offset += length
            updateCalls++
            chunk = if (chunk == 4096) 7919 else 4096 // 变长分块, 覆盖非对齐边界
        }
        assertEquals(3, updateCalls, "应覆盖 4096/7919/截尾三次 update")
        assertEquals(bulk, accumulator.hexDigest())
    }

    @Test
    fun `空输入与单字符输入一致`() {
        assertEquals(Crypto.sha256Hex(""), Crypto.sha256Accumulator().hexDigest())
        assertEquals(Crypto.sha256Hex("a"), run {
            val accumulator = Crypto.sha256Accumulator()
            accumulator.update("a".encodeToByteArray(), 0, 1)
            accumulator.hexDigest()
        })
    }

    @Test
    fun `sha256Hex 与 md5Hex 输出长度固定且不互相污染`() {
        val text = "poster-cache-key"
        assertEquals(64, Crypto.sha256Hex(text).length)
        assertEquals(32, Crypto.md5Hex(text.encodeToByteArray()).length)
        // 两个累加器独立实例, 互不影响
        val sha = Crypto.sha256Accumulator()
        val md5 = Crypto.md5Accumulator()
        sha.update("a".encodeToByteArray(), 0, 1)
        md5.update("a".encodeToByteArray(), 0, 1)
        assertEquals(Crypto.sha256Hex("a"), sha.hexDigest())
        assertEquals(Crypto.md5Hex("a".encodeToByteArray()), md5.hexDigest())
    }
}
