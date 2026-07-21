package io.github.weiyongzenqi.unuplayer.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import unu_player.composeapp.generated.resources.Res

/** 项目公开源码仓库(README/关于页/GPLv3 源码要约共用)。 */
private const val SOURCE_REPO_URL = "https://github.com/weiyongzenqi/UnU-Player"

/**
 * 关于页: 项目简介 + 开源许可说明 + "依赖与致谢"大标题下平铺所有开源依赖
 * (名称/许可证/说明/地址)。整行点击跳浏览器; 单独点许可证文字弹该许可证全文对话框。
 * 纯静态内容, 不依赖 SettingsRepository。
 */
fun LazyListScope.aboutItems() {
    // 项目头部
    item {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("UnU-Player", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "面向二次元的视频播放器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 开源许可说明
    item { LicenseSection() }

    // 依赖与致谢(大标题, 所有开源依赖共用, 不再分组)
    item {
        Text(
            "依赖与致谢",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
    }

    // 开源依赖平铺
    ABOUT_LIBRARIES.forEach { lib -> item { OpenSourceLibraryRow(lib) } }
}

/** 开源许可区块: 本项目 GPLv3 + 版权 + 源码链接 + 依赖各循原证的说明。GPLv3 句可点查看全文。 */
@Composable
private fun LicenseSection() {
    val uriHandler = LocalUriHandler.current
    var showGpl3 by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("开源许可", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        // GPLv3 整句可点 -> 弹 gpl-3.0 全文(主题色提示可点击)
        Text(
            "本项目源代码以 GNU General Public License v3（GPLv3）发布。点击查看许可证全文。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) { showGpl3 = true },
        )
        Text(
            "Copyright (C) 2026 weiyongzenqi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            SOURCE_REPO_URL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(SOURCE_REPO_URL) },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "本软件（UnU-Player 原创源代码）按 GPLv3（或更高版本）发布，不提供任何担保。" +
                "二进制发行版包含基于 GPL 的组件（mpv / FFmpeg），组合作品整体受 GPLv3 约束，并随程序附带许可证副本。" +
                "各第三方库按其原始许可证（清单见下）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showGpl3) {
        LicenseTextDialog(
            title = "GNU General Public License v3（GPLv3）",
            paths = listOf(LICENSE_DIR + "gpl-3.0.txt"),
            onDismiss = { showGpl3 = false },
        )
    }
}

private class OpenSourceLibrary(
    val name: String,
    val license: String,
    val description: String,
    val url: String,
)

private val ABOUT_LIBRARIES = listOf(
    // 播放内核(C 层, GPL-2.0-or-later; GNU GPL 无 2.1 版, 2.1 仅属 LGPL)
    OpenSourceLibrary("mpv", "GPL-2.0-or-later", "媒体播放器内核(上游 C 库)", "https://github.com/mpv-player/mpv"),
    OpenSourceLibrary("FFmpeg", "LGPL-2.1+ / GPL-2.0+", "编解码(随 mpv 以 GPL 构建)", "https://ffmpeg.org/"),
    OpenSourceLibrary("OpenSSL", "Apache-2.0", "TLS(经 libmpv 引入, 3.x)", "https://www.openssl.org/"),
    // 弹幕数据源(非开源软件库, 免费 API 服务)
    OpenSourceLibrary("弹弹play", "免费 API 服务", "弹幕匹配数据来源(弹幕源)", "https://www.dandanplay.com/"),
    // mpv 各平台构建
    OpenSourceLibrary("libmpv-android", "MIT / GPL-2.0+", "mpv 的 Android 构建(构建仓库 MIT, 产物 mpv 为 GPL),本项目直接依赖", "https://github.com/jarnedemeulemeester/libmpv-android"),
    OpenSourceLibrary("mpv-android", "MIT / GPL-2.0+", "mpv 官方 Android 实现(应用代码 MIT, 含 mpv GPL)", "https://github.com/mpv-android/mpv-android"),
    OpenSourceLibrary("mpv-winbuild (zhongfly)", "MIT / GPL-2.0+", "桌面端随包 libmpv-2.dll 的 Windows 构建来源(构建脚本 MIT, 产物 mpv 为 GPL)", "https://github.com/zhongfly/mpv-winbuild"),
    // 桌面内核绑定
    OpenSourceLibrary("JNA", "LGPL-2.1 / Apache-2.0", "桌面端经 JNA 调用 libmpv-2.dll(桌面播放器内核绑定)", "https://github.com/java-native-access/jna"),
    // Kotlin / Compose 生态(Apache-2.0)
    OpenSourceLibrary("Kotlin", "Apache-2.0", "编程语言", "https://kotlinlang.org/"),
    OpenSourceLibrary("kotlinx.coroutines", "Apache-2.0", "协程", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceLibrary("kotlinx.serialization", "Apache-2.0", "序列化", "https://github.com/Kotlin/kotlinx.serialization"),
    OpenSourceLibrary("Compose Multiplatform", "Apache-2.0", "跨平台 UI 框架", "https://github.com/JetBrains/compose-multiplatform"),
    OpenSourceLibrary("Material 3", "Apache-2.0", "Material Design 3 组件", "https://m3.material.io/"),
    OpenSourceLibrary("Material Icons", "Apache-2.0", "图标集", "https://github.com/google/material-design-icons"),
    OpenSourceLibrary("Navigation Compose", "Apache-2.0", "屏幕导航(JetBrains 多平台移植)", "https://github.com/JetBrains/compose-multiplatform-core"),
    OpenSourceLibrary("Lifecycle", "Apache-2.0", "生命周期(JetBrains 多平台移植)", "https://github.com/JetBrains/compose-multiplatform-core"),
    OpenSourceLibrary("AndroidX Jetpack", "Apache-2.0", "Activity Compose / Core KTX / DataStore / DocumentFile", "https://developer.android.com/jetpack/androidx"),
    OpenSourceLibrary("Ktor", "Apache-2.0", "多平台 HTTP 客户端", "https://github.com/ktorio/ktor"),
    OpenSourceLibrary("OkHttp", "Apache-2.0", "Ktor 的 Android/JVM HTTP 引擎", "https://github.com/square/okhttp"),
    OpenSourceLibrary("SQLDelight", "Apache-2.0", "播放记录数据库(Android android-driver / 桌面 jdbc + sqlite-jdbc)", "https://github.com/cashapp/sqldelight"),
    OpenSourceLibrary("Coil", "Apache-2.0", "海报墙图片加载(Compose Multiplatform)", "https://github.com/coil-kt/coil"),
)

/**
 * 单个开源依赖行: 名称 + 许可证 + 说明 + 地址(主题色)。
 * 整行点击用 UriHandler 打开项目主页; 单独点许可证 label 弹该许可证全文对话框
 * (子级 clickable 消费事件, 不与整行点击冲突)。
 */
@Composable
private fun OpenSourceLibraryRow(lib: OpenSourceLibrary) {
    val uriHandler = LocalUriHandler.current
    var dialogSpec by remember { mutableStateOf<LicenseDialogSpec?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { uriHandler.openUri(lib.url) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(lib.name, style = MaterialTheme.typography.titleSmall)
            // 许可证 label 单独可点 -> 弹全文(子级 clickable 优先, 不触发整行的 openUri)
            Text(
                lib.license,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { dialogSpec = licenseDialogSpec(lib.license) },
            )
        }
        Text(
            lib.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            lib.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    dialogSpec?.let { spec ->
        LicenseTextDialog(title = spec.title, paths = spec.paths, note = spec.note, onDismiss = { dialogSpec = null })
    }
}

/** 许可证资源文件目录(Compose resources 内 files/licenses/)。 */
private const val LICENSE_DIR = "files/licenses/"

/** 许可证全文对话框规格: 标题 + 一个或多个资源文件路径。 */
private class LicenseDialogSpec(val title: String, val paths: List<String>, val note: String? = null)

/**
 * 许可证标注 -> 对话框规格映射(标题 + 随包资源文件)。
 * 多文件许可证(FFmpeg / JNA)在对话框内依次显示, 以小标题分隔。
 */
private fun licenseDialogSpec(license: String): LicenseDialogSpec = when (license) {
    "Apache-2.0" -> LicenseDialogSpec("Apache License 2.0", listOf(LICENSE_DIR + "apache-2.0.txt"))
    "GPL-2.0-or-later" -> LicenseDialogSpec(
        "GNU GPL v2（或更高版本）；本组合作品按 GPLv3 分发",
        listOf(LICENSE_DIR + "gpl-2.0.txt"),
    )
    "LGPL-2.1+ / GPL-2.0+" -> LicenseDialogSpec(
        "FFmpeg：GNU LGPL v2.1（或更高版本）/ GNU GPL v2（或更高版本）",
        listOf(LICENSE_DIR + "lgpl-2.1.txt", LICENSE_DIR + "gpl-2.0.txt"),
    )
    "LGPL-2.1 / Apache-2.0" -> LicenseDialogSpec(
        "JNA：GNU LGPL v2.1 / Apache License 2.0",
        listOf(LICENSE_DIR + "lgpl-2.1.txt", LICENSE_DIR + "apache-2.0.txt"),
    )
    "GPLv3" -> LicenseDialogSpec(
        "GNU General Public License v3（GPLv3）",
        listOf(LICENSE_DIR + "gpl-3.0.txt"),
    )
    "MIT / GPL-2.0+" -> LicenseDialogSpec(
        "MIT（构建仓库）/ GNU GPL v2（mpv 二进制，或更高版本）",
        listOf(LICENSE_DIR + "mit.txt", LICENSE_DIR + "gpl-2.0.txt"),
    )
    "免费 API 服务" -> LicenseDialogSpec(
        "弹弹play（弹幕数据来源）",
        emptyList(),
        note = "弹弹play 是弹幕匹配数据的服务提供方，并非开源软件库，故无开源许可证文本。\n感谢其提供的免费弹幕数据服务。",
    )
    // 兜底: 未知许可证标注不内置全文, 对话框显示提示
    else -> LicenseDialogSpec(license, emptyList(), note = "该条目未内置许可证全文，请查阅对应项目源代码仓库。")
}

/** 多文件许可证的分段小标题(按资源文件名区分)。 */
private fun licenseFileHeading(path: String): String = when {
    path.endsWith("apache-2.0.txt") -> "Apache License 2.0"
    path.endsWith("mit.txt") -> "MIT License"
    path.endsWith("gpl-2.0.txt") -> "GNU General Public License v2"
    path.endsWith("gpl-3.0.txt") -> "GNU General Public License v3"
    path.endsWith("lgpl-2.1.txt") -> "GNU Lesser General Public License v2.1"
    else -> path.substringAfterLast('/')
}

/**
 * 许可证全文对话框: 异步读取一个或多个许可证资源文件(composeResources files/licenses/)并滚动显示。
 * - 加载中: 进度指示器 + 提示
 * - 失败/无内置全文: 显示提示而非崩溃(错误兜底)
 * - 多文件: 以 "=== 小标题 ===" 分隔后依次拼接
 */
@Composable
private fun LicenseTextDialog(
    title: String,
    paths: List<String>,
    note: String? = null,
    onDismiss: () -> Unit,
) {
    var loadedText by remember(paths) { mutableStateOf<String?>(null) }
    var loadError by remember(paths) { mutableStateOf<String?>(null) }

    LaunchedEffect(paths, note) {
        loadedText = null
        loadError = null
        if (paths.isEmpty()) {
            // 无内置全文(如弹弹play 等服务): 显示说明文字(正文色, 非错误)
            loadedText = note ?: "该条目未内置许可证全文，请查阅对应项目源代码仓库。"
            return@LaunchedEffect
        }
        try {
            val builder = StringBuilder()
            if (note != null) builder.append(note).append("\n\n")
            paths.forEach { path ->
                if (paths.size > 1) {
                    builder.append("=== ").append(licenseFileHeading(path)).append(" ===\n\n")
                }
                builder.append(Res.readBytes(path).decodeToString()).append("\n\n")
            }
            loadedText = builder.toString().trimEnd()
        } catch (t: Throwable) {
            // 错误兜底: 资源缺失/读取失败只显示提示, 不抛出
            loadError = "无法加载许可证全文：" + (t.message ?: t.toString())
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
                HorizontalDivider()
                // 内容区: 全文很长, 必须可滚动
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    when {
                        loadError != null -> Text(
                            loadError!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        loadedText != null -> Text(
                            loadedText!!,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        )
                        else -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(
                                "正在加载许可证全文…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
