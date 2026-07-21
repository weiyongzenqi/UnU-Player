package io.github.weiyongzenqi.unuplayer.webdav

import io.github.weiyongzenqi.unuplayer.core.media.MediaEntry

/**
 * 解析 WebDAV PROPFIND multi-status XML 响应。
 *
 * commonMain 声明, 各平台 actual 提供解析器。
 * Android 用 XmlPullParser(底层 kxml2, 平台自带)。
 *
 * 兼容多命名空间前缀(d:/D:/无前缀), 见 DESIGN.md §8.3。
 */
expect fun parsePropfindResponse(xml: String): List<MediaEntry>
