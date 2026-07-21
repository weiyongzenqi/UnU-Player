package io.github.weiyongzenqi.unuplayer.webdav

/**
 * 视频文件扩展名识别。参考 NipaPlay webdav_service.dart:380-414 的并集。
 *
 * 过滤掉以 URL 类顶级域(com/cn/org/...)结尾的伪扩展名, 避免 "xxx.com" 被误判为视频。
 */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "m4v",
    "mpg", "mpeg", "m2ts", "ts", "mts", "vob", "ogv",
    "3gp", "wmv", "rm", "rmvb",
)

/** URL 类顶级域, 作为伪扩展名过滤(NipaPlay trick)。 */
private val URL_TLDS = setOf("com", "cn", "org", "net", "me", "cc", "tv", "co", "xyz")

/** 判断文件名是否是视频文件(按扩展名)。 */
fun isVideoFile(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return false
    val ext = name.substring(dot + 1).lowercase()
    if (ext in URL_TLDS) return false
    return ext in VIDEO_EXTENSIONS
}
