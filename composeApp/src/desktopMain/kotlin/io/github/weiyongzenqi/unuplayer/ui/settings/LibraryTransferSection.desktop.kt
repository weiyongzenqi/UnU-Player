package io.github.weiyongzenqi.unuplayer.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import io.github.weiyongzenqi.unuplayer.library.export.ConnectionCandidate
import io.github.weiyongzenqi.unuplayer.library.export.ConnectionEdit
import io.github.weiyongzenqi.unuplayer.library.export.DesktopLibraryImageService
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 媒体库数据管理(desktopMain): 导出/导入 WebDAV/SMB 刮削库。
 * 导出 = 选库 -> 配置开关 -> JFileChooser 保存 -> 打包 zip。
 * 导入 = 选 zip -> 预览(连接复用/新建 + 开关) -> 建库+全量写入 -> 图片还原。
 */
@Composable
fun LibraryTransferSection(
    scrapedRepo: ScrapedLibraryRepository,
    webDavRepo: WebDavConnectionRepository,
    smbRepo: SmbConnectionRepository?,
    scanCoordinator: PosterWallScanCoordinator?,
    settings: SettingsState,
) {
    val scope = rememberCoroutineScope()
    val exporter = remember(scrapedRepo, webDavRepo, smbRepo) {
        LibraryExporter(
            scrapedRepo, webDavRepo, smbRepo,
            PlaybackRecordRepositoryImpl.get(), DesktopLibraryImageService(),
        )
    }
    val importer = remember(scrapedRepo, webDavRepo, smbRepo) {
        LibraryImporter(
            scrapedRepo, webDavRepo, smbRepo,
            PlaybackRecordRepositoryImpl.get(), DesktopLibraryImageService(),
        ) { UUID.randomUUID().toString() }
    }

    var libraries by remember { mutableStateOf(emptyList<LibraryConfig>()) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    var showExportPicker by remember { mutableStateOf(false) }
    var exportLibrary by remember { mutableStateOf<LibraryConfig?>(null) }
    var exportOptions by remember { mutableStateOf(ExportOptions()) }
    var showExportOptions by remember { mutableStateOf(false) }

    var importPayload by remember { mutableStateOf<ZipPayload?>(null) }
    var importZipFile by remember { mutableStateOf<File?>(null) }
    var importCandidate by remember { mutableStateOf<ConnectionCandidate?>(null) }
    var showImportPreview by remember { mutableStateOf(false) }

    suspend fun doExport(library: LibraryConfig, options: ExportOptions, destPath: String) {
        busy = true
        busyText = "正在导出媒体库…"
        progress = null
        var partFile: File? = null
        try {
            runSuspendCatching {
                withContext(Dispatchers.IO) {
                    val destination = File(destPath).absoluteFile
                    val parent = requireNotNull(destination.parentFile) { "导出目标没有父目录" }
                    require(parent.isDirectory) { "导出目标目录不存在" }
                    val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.part")
                    partFile = temporary
                    val output = exporter.exportLibrary(library.id, options)
                    val zip = LibraryZipOutput(temporary.absolutePath)
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
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }.onSuccess {
                AppNotif.toast("媒体库已导出：${library.name}")
            }.onFailure { error ->
                AppNotif.toast("媒体库导出失败：${error.message ?: "未知错误"}")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { partFile?.delete() } }
            busy = false
            busyText = null
        }
    }

    suspend fun doPrepareImport(file: File) {
        busy = true
        busyText = "正在读取导入文件…"
        progress = null
        try {
            runSuspendCatching {
                val payload = importer.readZip(file.absolutePath)
                    ?: throw IllegalArgumentException("导入文件格式不支持")
                val candidate = importer.resolveConnectionCandidate(payload.data)
                libraries = scrapedRepo.listLibraries()
                importZipFile = file
                importPayload = payload
                importCandidate = candidate
            }.onFailure { error ->
                AppNotif.toast("导入文件读取失败：${error.message ?: "未知错误"}")
            }
        } finally {
            busy = false
            busyText = null
        }
        if (importPayload != null) showImportPreview = true
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
            importPayload = null
            importZipFile = null
            importCandidate = null
            showImportPreview = false
            busy = false
            busyText = null
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
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    dialogTitle = "选择要导入的媒体库文件"
                    fileFilter = FileNameExtensionFilter("UnU 媒体库导出 (*.zip)", "zip")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    scope.launch { doPrepareImport(file) }
                }
            }
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
                        }) { Text("${lib.name}（${lib.sourceKind.name}）") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExportPicker = false }) { Text("取消") } },
        )
    }

    if (showExportOptions && exportLibrary != null) {
        LibraryTransferDialog.ExportOptionsDialog(
            libraryName = exportLibrary!!.name,
            options = exportOptions,
            onOptionsChange = { exportOptions = it },
            onConfirm = {
                showExportOptions = false
                val library = exportLibrary!!
                val options = exportOptions
                exportOptions = ExportOptions()
                SwingUtilities.invokeLater {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "保存媒体库导出"
                        fileFilter = FileNameExtensionFilter("UnU 媒体库导出 (*.zip)", "zip")
                    }
                    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val file = chooser.selectedFile
                        val path = if (file.name.contains('.')) file.absolutePath else "${file.absolutePath}.zip"
                        scope.launch { doExport(library, options, path) }
                    }
                }
            },
            onDismiss = {
                showExportOptions = false
                exportOptions = ExportOptions()
            },
        )
    }

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
            onDismiss = {
                importPayload = null
                importZipFile = null
                importCandidate = null
                showImportPreview = false
            },
        )
    }

}
