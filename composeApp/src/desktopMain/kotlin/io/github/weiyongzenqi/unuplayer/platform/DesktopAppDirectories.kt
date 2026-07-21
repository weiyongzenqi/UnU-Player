package io.github.weiyongzenqi.unuplayer.platform

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Windows 桌面运行时文件的统一目录。 */
object DesktopAppDirectories {
    val rootDirectory: Path = defaultDesktopAppRoot()

    private val legacyRootDirectory: Path = Path.of(
        System.getProperty("user.home", "."),
        ".unuplayer",
    ).toAbsolutePath().normalize()

    val dataDirectory: Path by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        migrateLegacyDesktopDirectory(
            sourceDirectory = legacyRootDirectory.resolve("data"),
            targetDirectory = rootDirectory.resolve("data"),
        )
    }

    val databaseFile: Path
        get() = dataDirectory.resolve("unu_playback.db").toAbsolutePath().normalize()

    val settingsFile: Path
        get() = dataDirectory.resolve("settings.properties").toAbsolutePath().normalize()

    val posterCacheDirectory: Path by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        migrateLegacyDesktopDirectory(
            sourceDirectory = legacyRootDirectory.resolve("postercache"),
            targetDirectory = rootDirectory.resolve("cache").resolve("posters"),
        )
    }

    val fontsDirectory: Path by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        migrateLegacyDesktopDirectory(
            sourceDirectory = legacyRootDirectory.resolve("fonts"),
            targetDirectory = rootDirectory.resolve("fonts"),
        )
    }

    val logsDirectory: Path = rootDirectory.resolve("logs").toAbsolutePath().normalize()
    val subtitleTempDirectory: Path = rootDirectory.resolve("temp")
        .resolve("subtitles")
        .toAbsolutePath()
        .normalize()
    val jnaTempDirectory: Path = rootDirectory.resolve("temp")
        .resolve("jna")
        .toAbsolutePath()
        .normalize()
    val sqliteTempDirectory: Path = rootDirectory.resolve("temp")
        .resolve("sqlite")
        .toAbsolutePath()
        .normalize()

    /**
     * 在 JNA 与 SQLite 首次初始化前固定其 native 临时目录。
     * 尊重用户显式设置，不修改全局 java.io.tmpdir。
     */
    fun configureNativeTempDirectories() = synchronized(this) {
        Files.createDirectories(jnaTempDirectory)
        Files.createDirectories(sqliteTempDirectory)
        clearOwnedNativeTempFiles(jnaTempDirectory)
        clearOwnedNativeTempFiles(sqliteTempDirectory)
        if (System.getProperty("jna.tmpdir") == null) {
            System.setProperty("jna.tmpdir", jnaTempDirectory.toString())
        }
        if (System.getProperty("org.sqlite.tmpdir") == null) {
            System.setProperty("org.sqlite.tmpdir", sqliteTempDirectory.toString())
        }
    }
}

internal fun defaultDesktopAppRoot(): Path = resolveDesktopAppRoot(
    dataDirectoryOverride = System.getProperty("unu.data.dir"),
    localAppData = System.getenv("LOCALAPPDATA"),
    xdgDataHome = System.getenv("XDG_DATA_HOME"),
    userHome = System.getProperty("user.home", "."),
)

/** 无全局状态读取的默认根目录计算，供不同平台输入和单元测试复用。 */
internal fun resolveDesktopAppRoot(
    dataDirectoryOverride: String?,
    localAppData: String?,
    xdgDataHome: String?,
    userHome: String,
): Path {
    val selected = dataDirectoryOverride?.takeIf(String::isNotBlank)?.let { Path.of(it) }
        ?: localAppData?.takeIf(String::isNotBlank)?.let { Path.of(it, "UnU-Player") }
        ?: xdgDataHome?.takeIf(String::isNotBlank)?.let { Path.of(it, "UnU-Player") }
        ?: Path.of(userHome, ".local", "share", "UnU-Player")
    return selected.toAbsolutePath().normalize()
}

/**
 * 将旧目录整体移动到新位置。目标已存在时绝不覆盖；两种移动都失败时继续返回旧目录。
 */
internal fun migrateLegacyDesktopDirectory(sourceDirectory: Path, targetDirectory: Path): Path {
    val source = sourceDirectory.toAbsolutePath().normalize()
    val target = targetDirectory.toAbsolutePath().normalize()
    if (source == target || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target
    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return target

    return try {
        target.parent?.let { Files.createDirectories(it) }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            target
        } catch (_: Exception) {
            try {
                Files.move(source, target)
                target
            } catch (_: Exception) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) target else source
            }
        }
    } catch (_: Exception) {
        source
    }
}

/** 只清理 native 临时目录的直接普通文件；占用中或删除失败的文件留待下次启动。 */
internal fun clearOwnedNativeTempFiles(directory: Path) {
    val normalized = directory.toAbsolutePath().normalize()
    if (
        !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(normalized)
    ) {
        return
    }
    runCatching {
        Files.newDirectoryStream(normalized).use { entries ->
            entries.forEach { path ->
                runCatching {
                    if (
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(path)
                    ) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }
}
