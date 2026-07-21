package io.github.weiyongzenqi.unuplayer.platform

import android.content.Context
import java.io.File

/**
 * 字幕字体工具。
 *
 * 两个职责:
 * 1. 枚举系统已安装字体名(供字幕设置 UI 选择 sub-font 用)。
 * 2. 把用户 SAF 导入的 .ttf/.otf 拷到私有目录, 供 mpv sub-fonts-dir 加载。
 *
 * mpv 字幕字体:
 * - sub-font 设字体名(需字体已在系统或 sub-fonts-dir 指向的目录里)
 * - sub-fonts-dir 设自定义字体目录, mpv 会从中扫描字体文件
 *
 * 系统字体目录: /system/fonts。Android 中文字体(NotoSansCJK)名通常含 "Noto Sans CJK"。
 */
object SystemFonts {

    private const val FONT_DIR_NAME = "subtitle_fonts"

    /** 只计算路径，不创建目录；可安全用于 UI 选择状态。 */
    fun fontDirPath(context: Context): String = File(context.filesDir, FONT_DIR_NAME).absolutePath

    /** 字体目录绝对路径(私有, 重启可重建)。SAF 导入的字体拷到这里。 */
    fun fontDir(context: Context): File {
        return File(fontDirPath(context)).also { it.mkdirs() }
    }

    /**
     * 枚举系统字体名。扫描 /system/fonts 下的 .ttf/.otf, 从文件名提取可读名。
     * 失败(无权限读)返回空列表, 调用方用 mpv 默认字体。
     */
    fun listSystemFontNames(): List<String> {
        return runCatching {
            val dir = File("/system/fonts")
            if (!dir.isDirectory) return emptyList()
            dir.listFiles { f -> f.extension.equals("ttf", true) || f.extension.equals("otf", true) }
                ?.map { fileNameToReadable(it.nameWithoutExtension) }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 把指定字体文件(用户通过 SAF 选的)拷到字体目录, 返回目录绝对路径供 sub-fonts-dir。
     * 已存在同名文件则跳过。
     *
     * @param source 源字体文件(SAF 解析后的临时 File 或可读 File)
     * @param displayName 显示名/字体名(用于 sub-font, 取文件名)
     * @return 字体目录绝对路径
     */
    fun importFont(context: Context, source: File, displayName: String): String {
        val dir = fontDir(context)
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(dir, "$safeName.${source.extension.ifEmpty { "ttf" }}")
        if (!target.exists()) source.copyTo(target, overwrite = true)
        return dir.absolutePath
    }

    /** 清空字体目录；任一文件删除失败都抛错，调用方不得误报成功。 */
    fun clearFonts(context: Context) {
        val failed = fontDir(context).listFiles().orEmpty().filter { file ->
            file.exists() && !file.delete()
        }
        check(failed.isEmpty()) {
            "无法删除字体文件：${failed.joinToString { it.name }}"
        }
    }

    /** 列出已导入的自定义字体文件名。 */
    fun listImportedFonts(context: Context): List<String> {
        return runCatching {
            fontDir(context).listFiles()?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** /system/fonts 文件名 → 可读字体名。如 NotoSansCJK-Regular → Noto Sans CJK Regular。 */
    private fun fileNameToReadable(name: String): String {
        return name
            .replace("-", " ")
            .replace("_", " ")
            .trim()
    }
}
