package io.github.weiyongzenqi.unuplayer.core.media

/**
 * 播放记录 media_key 生成(浏览层与 source 层共用同一公式, 保证查/写一致)。
 *
 * 以"本地导航位置"区分文件, 不用传给播放器的链接:
 * - WebDAV: 部分 WebDAV(云盘网关)播放时 302 跳转到带签名的直链, 签名会变更,
 *   用 url 作 key 会导致同一文件每次签名变就认成新记录。改用 连接id+浏览路径, 稳定。
 * - 本地: DocumentFile content uri 稳定(同一授权目录下同一文件 uri 不变)。
 */
object MediaKeys {
    /** WebDAV: 连接 id + 导航路径(entry.path = PROPFIND href, 即浏览位置)。 */
    fun webDav(connId: String, path: String): String = "webdav:$connId:$path"

    /** 本地: DocumentFile content uri。 */
    fun local(contentUri: String): String = "local:$contentUri"
}
