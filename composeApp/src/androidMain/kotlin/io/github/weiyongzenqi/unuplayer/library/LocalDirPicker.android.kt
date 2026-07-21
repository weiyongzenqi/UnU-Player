package io.github.weiyongzenqi.unuplayer.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberLocalDirPicker(): LocalDirPickerState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        if (treeUri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val displayName = runCatching {
                        DocumentFile.fromTreeUri(context, treeUri)?.name
                    }.getOrNull() ?: treeUri.lastPathSegment
                    treeUri.toString() to displayName
                }
                uri = picked.first
                name = picked.second
            }
        }
    }
    return LocalDirPickerState(
        pick = { launcher.launch(null) },
        pickedUri = uri,
        pickedName = name,
        clear = { uri = null; name = null },
    )
}
