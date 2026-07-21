package io.github.weiyongzenqi.unuplayer.danmaku.source

import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuEntry
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuMode
import io.github.weiyongzenqi.unuplayer.danmaku.model.DanmakuSource

/**
 * B站 XML 弹幕解析。
 *
 * 格式: `<i>...<d p="time,mode,fontsize,color,sendtime,pool,userhash,id">text</d>...</i>`
 *
 * p 属性 8 段逗号分隔, 本解析器只取前 4 段(time/mode/fontsize/color)。
 * 解析失败的条目跳过, 结果按 timeSec 升序排序。
 */
object BiliXmlSource {

    /** 匹配 `<d p="...">text</d>`, text 可能跨行故用 [\s\S] */
    private val danmuRegex = Regex("""<d p="([^"]*)">([\s\S]*?)</d>""")

    /**
     * 解析 B站 XML 弹幕字符串为 [DanmakuEntry] 列表。
     *
     * @param xml 完整 XML 文本
     * @return 按 timeSec 升序排序的弹幕列表(解析失败的条目已跳过)
     */
    fun parse(xml: String): List<DanmakuEntry> =
        danmuRegex.findAll(xml).mapNotNull { match ->
            try {
                val p = match.groupValues[1].split(",")
                if (p.size < 4) return@mapNotNull null

                val time = p[0].toDouble()
                val mode = p[1].toInt()
                val fontsize = p[2].toInt()
                val color = p[3].toInt()
                val text = unescapeXml(match.groupValues[2])

                DanmakuEntry(
                    timeSec = time,
                    mode = DanmakuMode.fromCode(mode),
                    color = color,
                    text = text,
                    source = DanmakuSource.BILI,
                    fontSize = fontsize,
                )
            } catch (_: NumberFormatException) {
                null
            }
        }.sortedBy { it.timeSec }.toList()

    /** 去除 XML 转义实体 */
    private fun unescapeXml(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
