package io.github.weiyongzenqi.unuplayer.core.player

/**
 * mpv http-header-fields 选项的请求头序列化(双端引擎共用)。
 *
 * mpv 的 keyvalue list 解析器(options/m_option.c parse_keyvalue_list → read_subparam)
 * 不支持反斜杠转义; 值内分隔符只能靠 read_subparam 的 %len% 字面量形式表达
 * (%5%hello = 字面量 "hello", 长度按字节计), 可无损表达任意字节。
 *
 * 触发转义的字符: ','(列表分隔符)、':'(键值分隔)、前导 '"'/'['/'%'(read_subparam
 * 模式字符)。'=' 与值中间位置的 '"' 经真实 Jellyfin 头(MediaBrowser Token="...")
 * 实机验证不破坏解析, 不转义——保证现有输出逐字节不变。
 * 键由本应用代码生成(Authorization 等简单 token), 不做转义。
 */
fun serializeHttpHeaderFields(headers: Map<String, String>): String =
    headers.entries.joinToString(",") { (key, value) ->
        "$key: ${escapeMpvHeaderValue(value)}"
    }

internal fun escapeMpvHeaderValue(value: String): String {
    if (value.isEmpty()) return value
    val needsEscape = value.contains(',') || value.contains(':') ||
        value.startsWith('"') || value.startsWith('[') || value.startsWith('%')
    if (!needsEscape) return value
    return "%${value.encodeToByteArray().size}%$value"
}
