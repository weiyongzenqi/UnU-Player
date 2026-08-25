package io.github.weiyongzenqi.unuplayer.bangumi

import coil3.PlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/**
 * 构造带网关鉴权头的 coil 图片模型(Bangumi/Ani-RSS 图片加载统一入口)。
 *
 * 无鉴权头时直接返回 URL 字符串, 让 coil 走字符串短路(免一次 ImageRequest 构造);
 * 有鉴权头时返回带 httpHeaders 的 ImageRequest。全项目 URL 图片加载统一走这里,
 * 避免各调用点对"空头短路/恒构造"行为各自实现而漂移。
 */
fun bangumiImageModel(context: PlatformContext, url: String): Any {
    val headers = bangumiImageRequestHeaders(url)
    if (headers.isEmpty()) return url
    return ImageRequest.Builder(context)
        .data(url)
        .httpHeaders(
            NetworkHeaders.Builder().apply {
                headers.forEach { (name, value) -> set(name, value) }
            }.build(),
        )
        .build()
}
