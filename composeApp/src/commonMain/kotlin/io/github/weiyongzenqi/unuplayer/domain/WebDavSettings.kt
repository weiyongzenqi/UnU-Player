package io.github.weiyongzenqi.unuplayer.domain

/** WebDAV 文件排序预设。移植自 NipaPlay WebDAVSortPreset。 */
enum class WebDavSortPreset(val value: String, val displayName: String, val description: String) {
    DEFAULT("default", "默认", "文件夹在前, 名称 A-Z"),
    NAME_ASC("name_asc", "名称 A-Z", "所有项目按名称升序"),
    NAME_DESC("name_desc", "名称 Z-A", "所有项目按名称降序"),
    MODIFIED_DESC("modified_desc", "最新修改", "最近修改的项目在前"),
    MODIFIED_ASC("modified_asc", "最旧修改", "最早修改的项目在前"),
    SIZE_DESC("size_desc", "最大文件", "文件大小从大到小"),
    SIZE_ASC("size_asc", "最小文件", "文件大小从小到大");

    companion object {
        fun fromValue(v: String?): WebDavSortPreset =
            entries.firstOrNull { it.value == v } ?: DEFAULT
    }
}

/** WebDAV 搜索范围。 */
enum class WebDavSearchScope(val value: String, val displayName: String, val description: String) {
    CURRENT_DIRECTORY("current_directory", "仅当前目录", "只搜索当前显示的目录"),
    CURRENT_WITH_DEPTH("current_with_depth", "当前目录 + 子目录", "向下遍历指定层级"),
    GLOBAL("global", "全局搜索", "从根目录开始搜索所有文件");

    companion object {
        fun fromValue(v: String?): WebDavSearchScope =
            entries.firstOrNull { it.value == v } ?: CURRENT_WITH_DEPTH
    }
}

/** WebDAV 搜索目标(可多选)。 */
enum class WebDavSearchTarget(val value: String, val displayName: String) {
    FOLDER("folder", "文件夹"),
    VIDEO("video", "视频文件");

    companion object {
        val DEFAULT: Set<WebDavSearchTarget> = setOf(FOLDER, VIDEO)
        fun fromValues(values: Collection<String>): Set<WebDavSearchTarget> =
            values.mapNotNull { v -> entries.firstOrNull { it.value == v } }.toSet().ifEmpty { DEFAULT }
    }
}

/** WebDAV 搜索超时。 */
enum class WebDavSearchTimeout(val seconds: Int, val displayName: String) {
    SECONDS_10(10, "10 秒"),
    SECONDS_30(30, "30 秒"),
    SECONDS_60(60, "60 秒"),
    UNLIMITED(0, "无限制");

    companion object {
        fun fromSeconds(s: Int?): WebDavSearchTimeout =
            entries.firstOrNull { it.seconds == s } ?: SECONDS_30
    }
}