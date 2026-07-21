package io.github.weiyongzenqi.unuplayer.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RFC 1123 日期格式(线程局部缓存: SimpleDateFormat 非线程安全, 每线程一份复用,
 * 避免每次 PROPFIND 解析 new 3 个对象)。兼容多种服务器写法。
 */
private val rfcDateFormats: ThreadLocal<Array<SimpleDateFormat>> = ThreadLocal.withInitial {
    arrayOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    )
}

/**
 * 使用 Android XmlPullParser 解析 WebDAV PROPFIND 响应的 actual 实现。
 *
 * 兼容多命名空间前缀（如 <response> / <d:response> / <D:response>），
 * 通过 getName() 获取不带前缀的 local name 进行比较。
 */
actual fun parsePropfindResponse(xml: String): List<MediaEntry> {
    val entries = mutableListOf<MediaEntry>()
    val factory = XmlPullParserFactory.newInstance()
    // 启用 namespace aware，使 getName() 返回不带前缀的 local name，
    // 从而兼容各种前缀写法（D: / d: / 无前缀）
    factory.isNamespaceAware = true
    val parser = factory.newPullParser()
    parser.setInput(StringReader(xml))

    // ---- 当前 response 块解析状态 ----
    var currentHref: String? = null
    var currentDisplayName: String? = null
    var currentContentLength: Long = 0
    var currentLastModified: Long = 0
    var isCollection = false

    // ---- 状态标记，用于判断当前处于 XML 树的哪一层 ----
    var insideResponse = false
    var insideProp = false
    var insideResourcetype = false

    // textBuf 累积 TEXT 事件内容（某些解析器可能分多次发出 TEXT）
    val textBuf = StringBuilder()

    fun collectedText(): String = textBuf.toString()
    fun resetText() { textBuf.clear() }

    /**
     * 将 RFC 1123 日期字符串（如 "Mon, 01 Jan 2026 00:00:00 GMT"）
     * 解析为 epoch 毫秒数。解析失败返回 0。
     *
     * 用线程局部缓存的格式数组(见文件顶层 rfcDateFormats): SimpleDateFormat 非线程安全,
     * 每线程一份复用, 避免每次解析 new 3 个对象。
     */
    fun parseRfcDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val trimmed = dateStr.trim()
        val formats = rfcDateFormats.get() ?: return 0L  // ThreadLocal.withInitial 保证非空, ?: 仅满足编译器 nullable 检查
        for (fmt in formats) {
            try {
                return fmt.parse(trimmed)?.time ?: 0L
            } catch (_: Exception) {
                // 继续尝试下一种格式
            }
        }
        return 0L
    }

    /**
     * 将当前收集到的 response 数据提交为 MediaEntry，然后重置状态。
     *
     * 注意：不跳过第一个 response。许多 WebDAV 服务器会将请求 URL 自身作为
     * 第一个 response 返回，但此处全部返回，由调用方根据业务逻辑决定是否过滤。
     */
    fun commitResponse() {
        val href = currentHref ?: return
        // name 优先用 displayname，没有则从 href 取最后一段作为后备
        val name = currentDisplayName
            ?: href.trimEnd('/').substringAfterLast('/')
                .ifEmpty { href }

        entries.add(
            MediaEntry(
                name = name,
                path = href, // path 直接使用 href 原始值
                isDirectory = isCollection,
                size = currentContentLength,
                lastModified = currentLastModified
            )
        )

        currentHref = null
        currentDisplayName = null
        currentContentLength = 0
        currentLastModified = 0
        isCollection = false
    }

    // ---- 主解析循环 ----
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                // getName() 返回 local name（不带命名空间前缀）
                when (parser.name) {
                    "response" -> {
                        insideResponse = true
                        resetText()
                    }
                    "href" -> resetText()
                    "propstat" -> { /* 仅标记结构入口，不特殊处理 */ }
                    "prop" -> {
                        if (insideResponse) insideProp = true
                    }
                    "displayname" -> resetText()
                    "getcontentlength" -> resetText()
                    "getlastmodified" -> resetText()
                    "resourcetype" -> {
                        insideResourcetype = true
                        isCollection = false
                    }
                    "collection" -> {
                        if (insideResourcetype) isCollection = true
                    }
                }
            }

            XmlPullParser.TEXT -> {
                // 累积文本值，应对解析器将文本拆分成多个 TEXT 事件的情况
                textBuf.append(parser.text)
            }

            XmlPullParser.END_TAG -> {
                when (parser.name) {
                    "href" -> {
                        if (insideResponse) currentHref = collectedText().trim()
                        resetText()
                    }
                    "displayname" -> {
                        if (insideProp) currentDisplayName = collectedText().trim()
                        resetText()
                    }
                    "getcontentlength" -> {
                        if (insideProp) {
                            currentContentLength = collectedText().trim().toLongOrNull() ?: 0
                        }
                        resetText()
                    }
                    "getlastmodified" -> {
                        if (insideProp) {
                            currentLastModified = parseRfcDate(collectedText())
                        }
                        resetText()
                    }
                    "resourcetype" -> insideResourcetype = false
                    "prop" -> insideProp = false
                    "response" -> {
                        commitResponse()
                        insideResponse = false
                    }
                }
            }
        }
        parser.next()
    }

    return entries
}
