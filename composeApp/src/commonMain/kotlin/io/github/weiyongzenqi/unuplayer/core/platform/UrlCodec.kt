package io.github.weiyongzenqi.unuplayer.core.platform

/** 解码 URL 百分号编码但保留字面量 '+'；用于 WebDAV/SAF 路径显示。 */
fun decodeUrlComponentPreservingPlus(value: String): String {
    val result = StringBuilder(value.length)
    // 整个 decode 只分配一次全值长度缓冲, 供所有百分号段复用: 每段写入 count 字节后
    // decodeToString(0, count) 只读 [0, count), 前段残留字节不影响结果。消除原实现里
    // 每个百分号段都按"剩余串长"重复分配大缓冲的问题; 逐字节语义(+ 保留、非法序列)不变。
    val bytes = ByteArray(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            result.append(value[index++])
            continue
        }

        var count = 0
        var cursor = index
        while (cursor + 2 < value.length && value[cursor] == '%') {
            val high = hexDigit(value[cursor + 1])
            val low = hexDigit(value[cursor + 2])
            if (high < 0 || low < 0) break
            bytes[count++] = ((high shl 4) or low).toByte()
            cursor += 3
        }
        if (count == 0) {
            result.append('%')
            index++
        } else {
            result.append(bytes.decodeToString(0, count))
            index = cursor
        }
    }
    return result.toString()
}

private fun hexDigit(value: Char): Int = when (value) {
    in '0'..'9' -> value - '0'
    in 'a'..'f' -> value - 'a' + 10
    in 'A'..'F' -> value - 'A' + 10
    else -> -1
}
