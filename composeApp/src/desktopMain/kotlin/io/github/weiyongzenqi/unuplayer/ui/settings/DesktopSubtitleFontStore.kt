package io.github.weiyongzenqi.unuplayer.ui.settings

import io.github.weiyongzenqi.unuplayer.platform.DesktopAppDirectories
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.name

internal const val MAX_IMPORTED_FONT_BYTES: Long = 64L * 1024L * 1024L

private val SUPPORTED_FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
private val WINDOWS_RESERVED_FILE_NAMES = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")

internal data class DesktopFontFace(
    val family: String,
    val fullName: String,
    val fileName: String,
)

internal data class DesktopFontImportResult(
    val path: Path,
    val faces: List<DesktopFontFace>,
)

/** 桌面系统字体枚举;调用方应在后台线程首次访问。 */
internal object DesktopSystemFontCatalog {
    // CA-005: 改为 @Volatile var + double-check lock, 支持 refresh() 后重新枚举。
    // 原 by lazy 一次性枚举, 运行中装/卸系统字体不刷新, 需重启 app。
    @Volatile
    private var cachedNames: List<String>? = null

    fun names(): List<String> {
        cachedNames?.let { return it }
        synchronized(this) {
            cachedNames?.let { return it }
            val list = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ROOT)
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
            cachedNames = list
            return list
        }
    }

    /** 作废缓存, 下次 [names] 重新枚举系统字体。SubtitleFontsSlot 进入时调用以感知运行中装/卸字体。 */
    fun refresh() {
        synchronized(this) {
            cachedNames = null
        }
    }
}

/**
 * Windows 字幕字体私有存储。
 *
 * 导入流程固定为“校验源文件 -> 同目录唯一 .part -> Java 字体解析 -> 原子发布”，
 * mpv 因此不会扫描到尚未写完的字体文件。
 */
internal class DesktopSubtitleFontStore(
    root: Path = defaultDesktopSubtitleFontRoot(),
    private val maxBytes: Long = MAX_IMPORTED_FONT_BYTES,
    private val fontParser: (Path) -> List<DesktopFontFace> = ::parseDesktopFontFaces,
) {
    val directory: Path = root.toAbsolutePath().normalize()
    private val operationLock = Any()

    fun listImportedFonts(): List<DesktopFontFace> = synchronized(operationLock) {
        ensureDirectory()
        cleanupOrphanParts()
        val faces = mutableListOf<DesktopFontFace>()
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { path ->
                if (!isSafeOwnedFile(path) || path.extension.lowercase(Locale.ROOT) !in SUPPORTED_FONT_EXTENSIONS) {
                    return@forEach
                }
                runCatching { fontParser(path) }.getOrNull()?.let(faces::addAll)
            }
        }
        return faces
            .distinctBy { "${it.family.lowercase(Locale.ROOT)}\u0000${it.fileName.lowercase(Locale.ROOT)}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.family })
    }

    fun importFont(source: Path): DesktopFontImportResult = synchronized(operationLock) {
        ensureDirectory()
        val normalizedSource = validateSource(source)
        val extension = normalizedSource.extension.lowercase(Locale.ROOT)
        var part: Path? = null
        try {
            part = Files.createTempFile(directory, ".font-import-", ".part")
            checkContainedTarget(directory, part.name)

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileChannel.open(normalizedSource, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                Channels.newInputStream(channel).use { input ->
                    FileChannel.open(
                        part,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS,
                    ).use { outputChannel ->
                        val output = Channels.newOutputStream(outputChannel)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            require(copied <= maxBytes) { "字体文件超过 64 MiB 限制" }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                        outputChannel.force(true)
                    }
                }
            }
            require(copied > 0L) { "字体文件为空" }

            val parsedFaces = fontParser(part)
            require(parsedFaces.isNotEmpty()) { "文件中未找到可用字体" }
            val digestHex = digest.digest().joinToString("") { "%02x".format(it) }
            val safeStem = sanitizeFontFileStem(normalizedSource.fileName.toString().substringBeforeLast('.'))
            val targetName = "$safeStem-${digestHex.take(12)}.$extension"
            val target = checkContainedTarget(directory, targetName)

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                require(isSafeOwnedFile(target)) { "目标字体文件不安全，已拒绝覆盖" }
                require(sha256(target, maxBytes) == digestHex) { "目标字体文件冲突，已拒绝覆盖" }
                Files.deleteIfExists(part)
            } else {
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part, target)
                }
            }
            part = null

            val publishedFaces = parsedFaces.map { it.copy(fileName = target.fileName.toString()) }
            return DesktopFontImportResult(target, publishedFaces)
        } finally {
            part?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** 只清理私有目录的常规字体和遗留 .part；链接、目录及其他文件一律保留。 */
    fun clearImportedFonts(): Unit = synchronized(operationLock) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        ensureDirectory()
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { path ->
                if (!isSafeOwnedFile(path)) return@forEach
                val name = path.fileName.toString()
                val isFont = path.extension.lowercase(Locale.ROOT) in SUPPORTED_FONT_EXTENSIONS
                if (isFont || name.endsWith(".part", ignoreCase = true)) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun validateSource(source: Path): Path {
        val normalized = source.toAbsolutePath().normalize()
        require(normalized.extension.lowercase(Locale.ROOT) in SUPPORTED_FONT_EXTENSIONS) {
            "仅支持 .ttf、.otf、.ttc 字体文件"
        }
        require(!Files.isSymbolicLink(normalized)) { "不允许导入符号链接或重解析点" }
        val attributes = Files.readAttributes(
            normalized,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isOther) { "所选路径不是常规字体文件" }
        require(attributes.size() in 1..maxBytes) { "字体文件为空或超过 64 MiB 限制" }
        return normalized
    }

    private fun ensureDirectory() {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(directory)) { "字体目录不能是符号链接或重解析点" }
            val attributes = Files.readAttributes(
                directory,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            require(attributes.isDirectory && !attributes.isOther) { "字体存储路径不是安全目录" }
        } else {
            Files.createDirectories(directory)
        }
    }

    private fun cleanupOrphanParts() {
        Files.newDirectoryStream(directory, "*.part").use { entries ->
            entries.forEach { path ->
                if (isSafeOwnedFile(path)) Files.deleteIfExists(path)
            }
        }
    }

    private fun isSafeOwnedFile(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        if (normalized.parent != directory || Files.isSymbolicLink(normalized)) return false
        val attributes = runCatching {
            Files.readAttributes(normalized, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return false
        return attributes.isRegularFile && !attributes.isOther
    }
}

internal fun defaultDesktopSubtitleFontRoot(): Path = DesktopAppDirectories.fontsDirectory

internal fun checkContainedTarget(root: Path, fileName: String): Path {
    require(fileName.isNotBlank()) { "字体文件名不能为空" }
    require(Path.of(fileName).fileName.toString() == fileName) { "字体目标路径越界" }
    val normalizedRoot = root.toAbsolutePath().normalize()
    val target = normalizedRoot.resolve(fileName).normalize()
    require(target.parent == normalizedRoot && target.startsWith(normalizedRoot)) { "字体目标路径越界" }
    return target
}

private fun sanitizeFontFileStem(raw: String): String {
    var result = raw
        .replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_")
        .trim(' ', '.')
        .take(80)
        .ifBlank { "imported-font" }
    if (WINDOWS_RESERVED_FILE_NAMES.matches(result)) result = "_$result"
    return result
}

private fun parseDesktopFontFaces(path: Path): List<DesktopFontFace> {
    return Font.createFonts(path.toFile())
        .mapNotNull { font ->
            val family = font.getFamily(Locale.ROOT).trim()
            if (family.isEmpty()) return@mapNotNull null
            DesktopFontFace(
                family = family,
                fullName = font.getFontName(Locale.ROOT).trim().ifBlank { family },
                fileName = path.fileName.toString(),
            )
        }
        .distinctBy { it.family.lowercase(Locale.ROOT) }
}

private fun sha256(path: Path, byteLimit: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var readBytes = 0L
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        Channels.newInputStream(channel).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                readBytes += read
                require(readBytes <= byteLimit) { "目标字体文件大小异常" }
                digest.update(buffer, 0, read)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
