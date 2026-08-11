package io.github.weiyongzenqi.unuplayer.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.weiyongzenqi.unuplayer.core.coroutines.runSuspendCatching
import io.github.weiyongzenqi.unuplayer.core.media.MediaSourceKind
import io.github.weiyongzenqi.unuplayer.core.platform.AppNotif
import io.github.weiyongzenqi.unuplayer.domain.SettingsState
import io.github.weiyongzenqi.unuplayer.library.LibraryConfig
import io.github.weiyongzenqi.unuplayer.library.PosterWallScanCoordinator
import io.github.weiyongzenqi.unuplayer.library.ScrapedLibraryRepository
import io.github.weiyongzenqi.unuplayer.library.ScanMode
import io.github.weiyongzenqi.unuplayer.library.export.AndroidLibraryImageService
import io.github.weiyongzenqi.unuplayer.library.export.ConnectionCandidate
import io.github.weiyongzenqi.unuplayer.library.export.ConnectionEdit
import io.github.weiyongzenqi.unuplayer.library.export.ExportOptions
import io.github.weiyongzenqi.unuplayer.library.export.ImportOptions
import io.github.weiyongzenqi.unuplayer.library.export.LibraryExporter
import io.github.weiyongzenqi.unuplayer.library.export.LIBRARY_EXPORT_MAX_IMAGE_BYTES
import io.github.weiyongzenqi.unuplayer.library.export.LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES
import io.github.weiyongzenqi.unuplayer.library.export.LibraryImporter
import io.github.weiyongzenqi.unuplayer.library.export.LibraryZipOutput
import io.github.weiyongzenqi.unuplayer.library.export.ZipPayload
import io.github.weiyongzenqi.unuplayer.library.export.hasLibraryNameConflict
import io.github.weiyongzenqi.unuplayer.library.export.passwordValue
import io.github.weiyongzenqi.unuplayer.library.export.withPassword
import io.github.weiyongzenqi.unuplayer.playback.PlaybackRecordRepositoryImpl
import io.github.weiyongzenqi.unuplayer.smb.SmbConnectionRepository
import io.github.weiyongzenqi.unuplayer.webdav.WebDavConnectionRepository
import java.io.File
import java.util.UUID

/**
 * 媒体库数据管理(androidMain): 导出/导入 WebDAV/SMB 刮削库。
 * 导出 = 选库 -> 配置开关 -> SAF 选目录 -> 打包 zip(临时文件 -> SAF)。
 * 导入 = 选 zip -> 预览(连接复用/新建 + 开关) -> 建库+全量写入 -> 图片还原 -> 可选扫描验证。
 */
@Composable
fun LibraryTransferSection(
    scrapedRepo: ScrapedLibraryRepository,
    webDavRepo: WebDavConnectionRepository,
    smbRepo: SmbConnectionRepository?,
    scanCoordinator: PosterWallScanCoordinator?,
    settings: SettingsState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember(scrapedRepo, webDavRepo, smbRepo) {
        LibraryExporter(
            scrapedRepo, webDavRepo, smbRepo,
            PlaybackRecordRepositoryImpl.get(context), AndroidLibraryImageService(context),
        )
    }
    val importer = remember(scrapedRepo, webDavRepo, smbRepo) {
        LibraryImporter(
            scrapedRepo, webDavRepo, smbRepo,
            PlaybackRecordRepositoryImpl.get(context), AndroidLibraryImageService(context),
        ) { UUID.randomUUID().toString() }
    }

    var libraries by remember { mutableStateOf(emptyList<LibraryConfig>()) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    // === 导出状态 ===
    var showExportPicker by remember { mutableStateOf(false) }
    var exportLibrary by remember { mutableStateOf<LibraryConfig?>(null) }
    var exportOptions by remember { mutableStateOf(ExportOptions()) }
    var showExportOptions by remember { mutableStateOf(false) }

    // === 导入状态 ===
    var importPayload by remember { mutableStateOf<ZipPayload?>(null) }
    var importZipFile by remember { mutableStateOf<File?>(null) }
    var importCandidate by remember { mutableStateOf<ConnectionCandidate?>(null) }
    var showImportPreview by remember { mutableStateOf(false) }

    fun scheduleImportCacheCleanup(file: File?) {
        if (file == null) return
        scope.launch(NonCancellable + Dispatchers.IO) {
            deleteOwnedImportCacheFile(context.cacheDir, file)
        }
    }

    fun clearImportState(cleanupFile: Boolean = true) {
        val file = importZipFile
        importPayload = null
        importZipFile = null
        importCandidate = null
        showImportPreview = false
        if (cleanupFile) scheduleImportCacheCleanup(file)
    }

    LaunchedEffect(context.cacheDir) {
        withContext(Dispatchers.IO) { cleanupStaleImportCacheFiles(context.cacheDir) }
    }

    val retainedImportFile = importZipFile
    DisposableEffect(retainedImportFile) {
        onDispose { scheduleImportCacheCleanup(retainedImportFile) }
    }

    suspend fun doExport(dirUri: Uri, library: LibraryConfig, options: ExportOptions) {
        busy = true
        busyText = "正在导出媒体库…"
        progress = null
        val tempFile = File(context.cacheDir, "library-export-${System.currentTimeMillis()}.zip")
        var createdDocument: DocumentFile? = null
        var exportCompleted = false
        try {
            val result = runSuspendCatching {
                withContext(Dispatchers.IO) {
                    val output = exporter.exportLibrary(library.id, options)
                    val zip = LibraryZipOutput(tempFile.absolutePath)
                    try {
                        zip.putText("manifest.json", output.manifestJson)
                        zip.putText("data/library.json", output.dataJson)
                        for (image in output.imageFiles) {
                            zip.putFile(
                                image.zipEntryName,
                                image.sourceAbsolutePath,
                                LIBRARY_EXPORT_MAX_IMAGE_BYTES,
                                LIBRARY_EXPORT_MAX_TOTAL_IMAGE_BYTES,
                            )
                        }
                    } finally {
                        zip.finish()
                    }
                    val dir = DocumentFile.fromTreeUri(context, dirUri)
                        ?: throw IllegalStateException("无法访问所选目录")
                    val name = "UnU-Library-${sanitizeForFile(library.name)}-${System.currentTimeMillis() / 1000}.zip"
                    val document = dir.createFile("application/zip", name)
                        ?: throw IllegalStateException("无法创建导出文件")
                    createdDocument = document
                    context.contentResolver.openOutputStream(document.uri, "wt")?.use { out ->
                        tempFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                    } ?: throw IllegalStateException("无法写入导出文件")
                }
            }
            if (result.isSuccess) {
                exportCompleted = true
                AppNotif.toast("媒体库已导出：${library.name}")
            } else {
                val error = requireNotNull(result.exceptionOrNull())
                AppNotif.toast("媒体库导出失败：${error.message ?: "未知错误"}")
            }
        } finally {
            if (!exportCompleted) {
                val cleaned = withContext(NonCancellable + Dispatchers.IO) {
                    deleteCreatedExportDocument(context.contentResolver, createdDocument)
                }
                if (!cleaned) {
                    AppNotif.toast("导出失败，且残缺文件无法自动清理，请在所选目录中手动删除")
                }
            }
            withContext(NonCancellable + Dispatchers.IO) { runCatching { tempFile.delete() } }
            busy = false
            busyText = null
        }
    }

    suspend fun doPrepareImport(uri: Uri) {
        busy = true
        busyText = "正在读取导入文件…"
        progress = null
        val tempFile = File(context.cacheDir, "library-import-${System.currentTimeMillis()}.zip")
        var prepared = false
        try {
            runSuspendCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyToLimited(output, MAX_IMPORT_FILE_BYTES) }
                    } ?: throw IllegalStateException("无法读取所选文件")
                }
                val payload = importer.readZip(tempFile.absolutePath)
                    ?: throw IllegalArgumentException("导入文件格式不支持")
                val candidate = importer.resolveConnectionCandidate(payload.data)
                libraries = scrapedRepo.listLibraries()
                importZipFile = tempFile
                importPayload = payload
                importCandidate = candidate
                prepared = true
            }.onFailure { error ->
                AppNotif.toast("导入文件读取失败：${error.message ?: "未知错误"}")
            }
        } finally {
            if (!prepared) {
                withContext(NonCancellable + Dispatchers.IO) {
                    deleteOwnedImportCacheFile(context.cacheDir, tempFile)
                }
            }
            busy = false
            busyText = null
        }
        if (prepared) showImportPreview = true
    }

    suspend fun doImport(
        payload: ZipPayload,
        zipFile: File,
        candidate: ConnectionCandidate,
        targetName: String,
        edit: ConnectionEdit?,
        exportPassword: String?,
        options: ImportOptions,
    ) {
        busy = true
        busyText = "正在导入媒体库…"
        progress = null
        val data = payload.data
        var createdConnectionId: String? = null
        var createdLibraryId: Long? = null
        var importCompleted = false
        try {
            val result = runSuspendCatching {
                val normalizedTargetName = targetName.trim()
                require(normalizedTargetName.isNotEmpty()) { "媒体库名称不能为空" }
                require(!hasLibraryNameConflict(scrapedRepo.listLibraries().map { it.name }, normalizedTargetName)) {
                    "已存在同名媒体库，请换一个名称"
                }
                val connectionId = when (candidate) {
                    is ConnectionCandidate.Reuse -> candidate.connectionId
                    is ConnectionCandidate.Create -> {
                        val baseEdit = edit ?: candidate.edit
                        val resolvedEdit = if (candidate.passwordProtected && baseEdit.passwordValue.isBlank()) {
                            val password = withContext(Dispatchers.Default) {
                                importer.decryptExportedPassword(data, exportPassword)
                            }
                            baseEdit.withPassword(requireNotNull(password))
                        } else {
                            baseEdit
                        }
                        importer.createConnection(resolvedEdit).also { createdConnectionId = it }
                    }
                }
                val sourceKind = when (data.connection.type) {
                    "SMB" -> MediaSourceKind.SMB
                    "WEBDAV" -> MediaSourceKind.WEBDAV
                    else -> throw IllegalArgumentException("未知连接类型: ${data.connection.type}")
                }
                val newLibraryId = scrapedRepo.addLibrary(
                    name = normalizedTargetName, sourceKind = sourceKind, connectionId = connectionId, localUri = null,
                    rootPath = data.library.rootPath, scanDepth = data.library.scanDepth,
                    scanMode = runCatching { ScanMode.valueOf(data.library.scanMode) }.getOrDefault(ScanMode.NFO),
                    anchorFilenames = data.library.anchorFilenames,
                ).also { createdLibraryId = it }
                val imported = importer.importLibrary(data, newLibraryId, connectionId, options) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else null
                }
                Triple(newLibraryId, imported.summary, connectionId)
            }
            if (result.isSuccess) {
                val (newLibraryId, summary, connectionId) = result.getOrThrow()
                importCompleted = true
                val playbackImported = if (options.includePlayback) {
                    runSuspendCatching { importer.importPlayback(data, connectionId) }.isSuccess
                } else {
                    true
                }
                val imagesRestored = if (options.includeImages) {
                    busyText = "正在还原图片…"
                    runSuspendCatching {
                        importer.restoreImages(zipFile.absolutePath, newLibraryId, data, summary)
                    }.isSuccess
                } else {
                    true
                }
                val scanStarted = if (options.scanAfterImport && scanCoordinator != null) {
                    runSuspendCatching {
                        val library = requireNotNull(scrapedRepo.getLibrary(newLibraryId)) { "导入后的媒体库不存在" }
                        scanCoordinator.startScan(library, settings, force = true)
                    }.isSuccess
                } else {
                    true
                }
                AppNotif.toast(
                    libraryImportCompletionMessage(
                        data.shows.size,
                        playbackImported,
                        imagesRestored,
                        scanStarted,
                    ),
                )
            } else {
                val error = requireNotNull(result.exceptionOrNull())
                AppNotif.toast("媒体库导入失败：${error.message ?: "未知错误"}")
            }
        } finally {
            if (!importCompleted) {
                withContext(NonCancellable) {
                    var libraryRemoved = createdLibraryId == null
                    createdLibraryId?.let { id ->
                        libraryRemoved = runSuspendCatching { scrapedRepo.deleteLibrary(id) }.isSuccess
                    }
                    if (libraryRemoved) {
                        createdConnectionId?.let { id ->
                            runSuspendCatching {
                                if (data.connection.type == "SMB") smbRepo?.remove(id) else webDavRepo.remove(id)
                            }
                        }
                    }
                }
            }
            withContext(NonCancellable + Dispatchers.IO) {
                deleteOwnedImportCacheFile(context.cacheDir, zipFile)
            }
            clearImportState(cleanupFile = false)
            busy = false
            busyText = null
        }
    }


    val exportDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val library = exportLibrary
        val options = exportOptions
        exportOptions = ExportOptions()
        if (uri != null && library != null && !busy) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch { doExport(uri, library, options) }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null && !busy) {
            scope.launch { doPrepareImport(uri) }
        }
    }

    SubsectionTitle("媒体库数据管理")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(enabled = !busy, onClick = {
            scope.launch {
                libraries = scrapedRepo.listLibraries().filter {
                    it.sourceKind == MediaSourceKind.WEBDAV || it.sourceKind == MediaSourceKind.SMB
                }
                showExportPicker = true
            }
        }) { Text("导出媒体库") }
        Button(enabled = !busy, onClick = {
            importFileLauncher.launch(arrayOf("application/zip"))
        }) { Text("导入媒体库") }
    }

    if (busy && busyText != null) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(busyText!!, style = MaterialTheme.typography.bodySmall)
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(
                    progress = progress!!,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }

    // === 导出: 选库 ===
    if (showExportPicker) {
        AlertDialog(
            onDismissRequest = { showExportPicker = false },
            title = { Text("导出媒体库") },
            text = {
                Column {
                    if (libraries.isEmpty()) {
                        Text("没有可导出的 WebDAV/SMB 媒体库", style = MaterialTheme.typography.bodySmall)
                    }
                    libraries.forEach { lib ->
                        TextButton(onClick = {
                            showExportPicker = false
                            exportLibrary = lib
                            exportOptions = ExportOptions()
                            showExportOptions = true
                        }) { Text("${lib.name}（${sourceKindLabel(lib.sourceKind)}）") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExportPicker = false }) { Text("取消") } },
        )
    }

    // === 导出: 配置 ===
    if (showExportOptions && exportLibrary != null) {
        LibraryTransferDialog.ExportOptionsDialog(
            libraryName = exportLibrary!!.name,
            options = exportOptions,
            onOptionsChange = { exportOptions = it },
            onConfirm = {
                showExportOptions = false
                exportDirLauncher.launch(Uri.EMPTY)
            },
            onDismiss = {
                showExportOptions = false
                exportOptions = ExportOptions()
            },
        )
    }

    // === 导入: 预览 ===
    val payload = importPayload
    val candidate = importCandidate
    if (showImportPreview && payload != null && candidate != null && importZipFile != null) {
        LibraryTransferDialog.ImportPreviewDialog(
            payload = payload,
            candidate = candidate,
            existingLibraries = libraries,
            onConfirm = { targetName, edit, exportPassword, options ->
                showImportPreview = false
                scope.launch { doImport(payload, importZipFile!!, candidate, targetName, edit, exportPassword, options) }
            },
            onDismiss = { clearImportState() },
        )
    }

    // === 执行 ===
}

private fun sourceKindLabel(sourceKind: MediaSourceKind): String = when (sourceKind) {
    MediaSourceKind.WEBDAV -> "WebDAV"
    MediaSourceKind.SMB -> "SMB"
    else -> sourceKind.name
}

private fun sanitizeForFile(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)

internal fun deleteOwnedImportCacheFile(cacheDir: File, file: File?): Boolean {
    if (file == null) return true
    val owned = runCatching {
        file.name.startsWith(IMPORT_CACHE_PREFIX) &&
            file.name.endsWith(IMPORT_CACHE_SUFFIX) &&
            file.canonicalFile.parentFile == cacheDir.canonicalFile
    }.getOrDefault(false)
    if (!owned) return false
    return !file.exists() || file.delete()
}

internal fun cleanupStaleImportCacheFiles(cacheDir: File) {
    cacheDir.listFiles { file ->
        file.name.startsWith(IMPORT_CACHE_PREFIX) && file.name.endsWith(IMPORT_CACHE_SUFFIX)
    }.orEmpty().forEach { file -> deleteOwnedImportCacheFile(cacheDir, file) }
}

private fun deleteCreatedExportDocument(
    contentResolver: android.content.ContentResolver,
    document: DocumentFile?,
): Boolean {
    if (document == null) return true
    if (runCatching { document.delete() }.getOrDefault(false)) return true
    return runCatching {
        contentResolver.delete(document.uri, null, null) > 0 || !document.exists()
    }.getOrDefault(false)
}

private fun java.io.InputStream.copyToLimited(output: java.io.OutputStream, maxBytes: Long) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return
        total += count
        require(total <= maxBytes) { "导入文件超过大小上限" }
        output.write(buffer, 0, count)
    }
}

private const val MAX_IMPORT_FILE_BYTES = 512L * 1024L * 1024L
private const val IMPORT_CACHE_PREFIX = "library-import-"
private const val IMPORT_CACHE_SUFFIX = ".zip"
