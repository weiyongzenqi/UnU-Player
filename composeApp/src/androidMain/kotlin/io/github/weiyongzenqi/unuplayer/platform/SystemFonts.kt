package io.github.weiyongzenqi.unuplayer.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

internal const val MAX_IMPORTED_FONT_BYTES: Long = 64L * 1024L * 1024L

internal data class AndroidImportedFont(
    val family: String,
    val fullName: String,
    val fileName: String,
    val legacyName: String,
)

internal data class AndroidFontImportResult(
    val directoryPath: String,
    val faces: List<AndroidImportedFont>,
)

internal fun AndroidImportedFont.matchesStoredSetting(setting: String): Boolean {
    if (setting == family || setting == legacyName) return true
    return setting.replace(Regex("[^A-Za-z0-9._-]"), "_") == legacyName
}

/** Android 字幕字体发现和 SAF 导入。 */
object SystemFonts {

    private const val FONT_DIR_NAME = "subtitle_fonts"
    private const val MAX_SFNT_TABLES = 4096
    private const val MAX_NAME_RECORDS = 4096
    private const val MAX_NAME_BYTES = 4096
    private const val MAX_TTC_FACES = 64
    private val operationLock = Any()
    private val supportedExtensions = setOf("ttf", "otf", "ttc")

    fun fontDirPath(context: Context): String = File(context.filesDir, FONT_DIR_NAME).absolutePath

    fun fontDir(context: Context): File {
        val directory = File(fontDirPath(context))
        ensureFontDirectory(directory)
        return directory
    }

    /** 列出 Android 系统字体目录中的可读字体名。 */
    fun listSystemFontNames(): List<String> {
        return runCatching {
            val directory = File("/system/fonts")
            if (!directory.isDirectory) return emptyList()
            directory.listFiles { file ->
                file.extension.equals("ttf", true) || file.extension.equals("otf", true)
            }?.map { fileNameToReadable(it.nameWithoutExtension) }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 导入单个 SAF 字体，并返回文件中包含的全部字体 family。 */
    internal fun importFont(context: Context, uri: Uri): AndroidFontImportResult = synchronized(operationLock) {
        val directory = fontDir(context)
        cleanupOrphanParts(directory)
        val displayName = queryDisplayName(context, uri)
        val input = checkNotNull(context.contentResolver.openInputStream(uri)) {
            "无法打开字体文件"
        }
        input.use { source -> importFontStreamLocked(directory, displayName, source) }
    }

    internal fun importFontStream(
        directory: File,
        displayName: String,
        input: InputStream,
    ): AndroidFontImportResult = synchronized(operationLock) {
        ensureFontDirectory(directory)
        cleanupOrphanParts(directory)
        importFontStreamLocked(directory, displayName, input)
    }

    private fun importFontStreamLocked(
        directory: File,
        displayName: String,
        input: InputStream,
    ): AndroidFontImportResult {
        val extension = extensionOf(displayName)
        val sourceStem = sanitizeFontFileStem(displayName.substringBeforeLast('.'))
        val part = File.createTempFile(".font-import-", ".part", directory)
        return try {
            var copied = 0L
            input.use { source ->
                FileOutputStream(part).use { output ->
                    copied = copyFontStreamLimited(source, output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(copied > 0L) { "字体文件为空" }

            val parsedFaces = parseAndroidFontFaces(part)
            require(parsedFaces.isNotEmpty()) { "文件中未找到可用字体" }
            val digest = sha256(part)
            val target = File(directory, "$sourceStem-${digest.take(12)}.$extension")
            check(target.parentFile?.canonicalFile == directory.canonicalFile) {
                "字体目标路径越界"
            }

            if (target.exists()) {
                require(isSafeRegularFile(target)) { "目标字体文件不安全，已拒绝覆盖" }
                require(sha256(target) == digest) { "目标字体文件冲突，已拒绝覆盖" }
                check(part.delete() || !part.exists()) { "无法清理字体临时文件" }
            } else {
                publishAtomically(part, target)
            }

            val faces = parsedFaces.map { face ->
                AndroidImportedFont(
                    family = face.family,
                    fullName = face.fullName,
                    fileName = target.name,
                    legacyName = sourceStem,
                )
            }
            AndroidFontImportResult(directory.absolutePath, faces)
        } finally {
            runCatching { part.delete() }
        }
    }

    /** 列出解析成功的 family；非法文件和中断遗留文件不会进入 UI。 */
    internal fun listImportedFonts(context: Context): List<AndroidImportedFont> = synchronized(operationLock) {
        val directory = fontDir(context)
        cleanupOrphanParts(directory)
        directory.listFiles().orEmpty()
            .filter { file ->
                isSafeRegularFile(file) && file.extension.lowercase(Locale.ROOT) in supportedExtensions
            }
            .flatMap { file ->
                runCatching {
                    parseAndroidFontFaces(file).map { face ->
                        AndroidImportedFont(
                            family = face.family,
                            fullName = face.fullName,
                            fileName = file.name,
                            legacyName = file.nameWithoutExtension,
                        )
                    }
                }.getOrDefault(emptyList())
            }
            .distinctBy { "${it.family.lowercase(Locale.ROOT)}\u0000${it.fileName.lowercase(Locale.ROOT)}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.family })
    }

    fun clearFonts(context: Context) = synchronized(operationLock) {
        val failed = fontDir(context).listFiles().orEmpty().filter { file ->
            file.exists() && !file.delete()
        }
        check(failed.isEmpty()) {
            "无法删除字体文件: ${failed.joinToString { it.name }}"
        }
    }

    /** 最多复制 [MAX_IMPORTED_FONT_BYTES]，到达上限后额外探测一个字节。 */
    internal fun copyFontStreamLimited(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_IMPORTED_FONT_BYTES,
    ): Long {
        require(maxBytes >= 0L)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < maxBytes) {
            val remaining = (maxBytes - total).coerceAtMost(buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, remaining)
            if (read < 0) return total
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
        }
        if (input.read() >= 0) throw IllegalArgumentException("字体文件超过 64 MiB 上限")
        return total
    }

    internal fun parseAndroidFontFaces(file: File): List<ParsedAndroidFontFace> {
        RandomAccessFile(file, "r").use { randomAccess ->
            val fileLength = randomAccess.length()
            require(fileLength in 1L..MAX_IMPORTED_FONT_BYTES) { "字体文件大小异常" }
            val tag = readTag(randomAccess, 0L)
            val offsets = if (tag == "ttcf") {
                readTtcOffsets(randomAccess, fileLength)
            } else {
                listOf(0L)
            }
            return offsets.flatMap { offset -> parseSfnt(randomAccess, offset, fileLength) }
                .distinctBy { "${it.family.lowercase(Locale.ROOT)}\u0000${it.fullName.lowercase(Locale.ROOT)}" }
        }
    }

    internal data class ParsedAndroidFontFace(val family: String, val fullName: String)

    private fun parseSfnt(
        randomAccess: RandomAccessFile,
        offset: Long,
        fileLength: Long,
    ): List<ParsedAndroidFontFace> {
        requireRange(offset, 12L, fileLength)
        val scaler = readTag(randomAccess, offset)
        require(scaler == "\u0000\u0001\u0000\u0000" || scaler == "OTTO" || scaler == "true") {
            "不支持的字体格式"
        }
        randomAccess.seek(offset + 4L)
        val tableCount = randomAccess.readUnsignedShort()
        require(tableCount in 1..MAX_SFNT_TABLES) { "字体表数量异常" }
        val directoryLength = 12L + tableCount.toLong() * 16L
        requireRange(offset, directoryLength, fileLength)
        var nameOffset = -1L
        var nameLength = -1L
        repeat(tableCount) { index ->
            val record = offset + 12L + index * 16L
            val tableTag = readTag(randomAccess, record)
            randomAccess.seek(record + 8L)
            val tableOffset = readUnsignedInt(randomAccess)
            val tableLength = readUnsignedInt(randomAccess)
            requireRange(tableOffset, tableLength, fileLength)
            if (tableTag == "name") {
                nameOffset = tableOffset
                nameLength = tableLength
            }
        }
        require(nameOffset >= 0L && nameLength >= 0L) { "字体缺少 name 表" }
        return parseNameTable(randomAccess, nameOffset, nameLength, fileLength)
    }

    private fun parseNameTable(
        randomAccess: RandomAccessFile,
        tableOffset: Long,
        tableLength: Long,
        fileLength: Long,
    ): List<ParsedAndroidFontFace> {
        require(tableLength >= 6L)
        randomAccess.seek(tableOffset)
        randomAccess.readUnsignedShort()
        val recordCount = randomAccess.readUnsignedShort()
        val stringOffset = randomAccess.readUnsignedShort()
        require(recordCount in 1..MAX_NAME_RECORDS)
        require(6L + recordCount.toLong() * 12L <= tableLength)
        val candidates = mutableListOf<NameCandidate>()
        repeat(recordCount) { index ->
            val record = tableOffset + 6L + index * 12L
            randomAccess.seek(record)
            val platform = randomAccess.readUnsignedShort()
            randomAccess.readUnsignedShort()
            val language = randomAccess.readUnsignedShort()
            val nameId = randomAccess.readUnsignedShort()
            val length = randomAccess.readUnsignedShort()
            val relativeOffset = randomAccess.readUnsignedShort()
            if (nameId != 1 && nameId != 4 && nameId != 16) return@repeat
            if (length == 0 || length > MAX_NAME_BYTES) return@repeat
            val stringStart = tableOffset + stringOffset + relativeOffset
            requireRange(stringStart, length.toLong(), tableOffset + tableLength)
            requireRange(stringStart, length.toLong(), fileLength)
            val bytes = ByteArray(length)
            randomAccess.seek(stringStart)
            randomAccess.readFully(bytes)
            decodeName(platform, bytes)?.let { value ->
                candidates += NameCandidate(nameId, language, platform, value)
            }
        }
        val family = chooseName(candidates, 16) ?: chooseName(candidates, 1) ?: return emptyList()
        val fullName = chooseName(candidates, 4) ?: family
        return listOf(ParsedAndroidFontFace(family, fullName))
    }

    private fun readTtcOffsets(randomAccess: RandomAccessFile, fileLength: Long): List<Long> {
        requireRange(0L, 12L, fileLength)
        randomAccess.seek(8L)
        val count = readUnsignedInt(randomAccess)
        require(count in 1L..MAX_TTC_FACES.toLong())
        requireRange(12L, count * 4L, fileLength)
        return buildList(count.toInt()) {
            repeat(count.toInt()) {
                add(readUnsignedInt(randomAccess))
            }
        }
    }

    private data class NameCandidate(
        val nameId: Int,
        val language: Int,
        val platform: Int,
        val value: String,
    )

    private fun chooseName(candidates: List<NameCandidate>, nameId: Int): String? =
        candidates.asSequence()
            .filter { it.nameId == nameId }
            .sortedWith(
                compareBy<NameCandidate>(
                    { if (it.language == 0x0409) 0 else if (it.language == 0) 1 else 2 },
                    { if (it.platform == 3 || it.platform == 0) 0 else 1 },
                ),
            )
            .map { it.value }
            .firstOrNull()

    private fun decodeName(platform: Int, bytes: ByteArray): String? {
        val charset = when (platform) {
            0, 3 -> StandardCharsets.UTF_16BE
            1 -> runCatching { Charset.forName("x-MacRoman") }.getOrDefault(StandardCharsets.ISO_8859_1)
            else -> StandardCharsets.UTF_8
        }
        val value = runCatching { String(bytes, charset) }
            .getOrNull()
            ?.replace('\u0000', ' ')
            ?.trim()
            ?: return null
        return value.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "imported.ttf"
    }

    private fun extensionOf(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension.isEmpty() || extension in supportedExtensions) {
            "仅支持 .ttf、.otf、.ttc 字体文件"
        }
        return extension.ifEmpty { "ttf" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMPORTED_FONT_BYTES) { "字体文件大小异常" }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun publishAtomically(part: File, target: File) {
        try {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            check(part.renameTo(target)) { "无法原子发布字体文件" }
        }
    }

    private fun cleanupOrphanParts(directory: File) {
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(".font-import-") && it.name.endsWith(".part") }
            .forEach { file -> runCatching { file.delete() } }
    }

    private fun ensureFontDirectory(directory: File) {
        if (!directory.exists()) check(directory.mkdirs() || directory.isDirectory) {
            "无法创建字体目录"
        }
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "字体存储路径不是安全目录"
        }
    }

    private fun isSafeRegularFile(file: File): Boolean =
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun requireRange(offset: Long, length: Long, fileLength: Long) {
        require(offset >= 0L && length >= 0L && offset <= fileLength - length) {
            "字体表范围越界"
        }
    }

    private fun readUnsignedInt(randomAccess: RandomAccessFile): Long =
        Integer.toUnsignedLong(randomAccess.readInt())

    private fun readTag(randomAccess: RandomAccessFile, offset: Long): String {
        randomAccess.seek(offset)
        val bytes = ByteArray(4)
        randomAccess.readFully(bytes)
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    private fun sanitizeFontFileStem(raw: String): String = raw
        .replace(Regex("[<>:\"/\\\\|?*\\p{Cntrl}]"), "_")
        .trim(' ', '.')
        .take(80)
        .ifBlank { "imported-font" }

    private fun fileNameToReadable(name: String): String = name
        .replace("-", " ")
        .replace("_", " ")
        .trim()
}
