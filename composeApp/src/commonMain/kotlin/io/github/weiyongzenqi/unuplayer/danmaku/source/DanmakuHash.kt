package io.github.weiyongzenqi.unuplayer.danmaku.source

/**
 * 弹幕文件哈希(对齐弹弹play 文件识别算法)。
 *
 * 算法: 取文件前 16MB (16 * 1024 * 1024 = 16777216 字节)的 MD5, 输出 hex 小写。
 * 文件不足 16MB 时哈希整个文件。
 *
 * commonMain 不能用 java.io / java.security, 故走 expect/actual。
 *
 * @param filePath 文件绝对路径
 * @return 32 位小写 hex MD5 摘要
 */
expect fun calcDanmakuHash(filePath: String): String
