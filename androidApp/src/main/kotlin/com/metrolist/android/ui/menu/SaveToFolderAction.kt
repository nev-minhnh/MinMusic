package com.metrolist.android.ui.menu

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.UserAlbumExport
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.download_folder_access_failed
import com.metrolist.shared.generated.resources.error_unknown
import com.metrolist.shared.generated.resources.save_to_folder_failed_count
import com.metrolist.shared.generated.resources.save_to_folder_nothing
import com.metrolist.shared.generated.resources.saved_to_folder
import com.metrolist.shared.generated.resources.saving_to_folder
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import timber.log.Timber

/** Downloaded songs to copy into the export folder; a user album tags them as one album in list order. */
data class SaveToFolderBatch(
    val songIds: List<String>,
    val userAlbum: UserAlbumExport? = null,
)

/**
 * Returns an action that copies downloads into the folder chosen for exports.
 * Without a writable folder it asks for one first and remembers it as the custom download folder.
 */
@Composable
fun rememberSaveToFolder(): (suspend () -> SaveToFolderBatch) -> Unit {
    val context = LocalContext.current
    val audioCacheManager: AudioCacheManager = koinInject()
    val scope: CoroutineScope = koinInject()
    var pending by remember { mutableStateOf<(suspend () -> SaveToFolderBatch)?>(null) }

    fun save(
        load: suspend () -> SaveToFolderBatch,
        treeUri: String,
    ) {
        scope.launch {
            context.showToast(getString(Res.string.saving_to_folder))
            val message =
                try {
                    val batch = load()
                    val result = audioCacheManager.saveDownloadsToFolder(batch.songIds, treeUri, batch.userAlbum)
                    when {
                        result.failed > 0 -> getString(Res.string.save_to_folder_failed_count, result.saved, result.failed)
                        result.saved == 0 -> getString(Res.string.save_to_folder_nothing)
                        else -> getPluralString(Res.plurals.saved_to_folder, result.saved, result.saved)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.w(error, "Could not save downloads to folder")
                    getString(Res.string.error_unknown)
                }
            context.showToast(message)
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val load = pending
            pending = null
            if (uri == null || load == null) return@rememberLauncherForActivityResult
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (error: Exception) {
                Timber.w(error, "Failed to take persistable URI permission")
                scope.launch { context.showToast(getString(Res.string.download_folder_access_failed)) }
                return@rememberLauncherForActivityResult
            }
            Settings.shared.customDownloadPath = uri.toString()
            save(load, uri.toString())
        }

    return { load ->
        val treeUri = Settings.shared.customDownloadPath
        if (treeUri.isNotBlank() && context.hasPersistedWriteAccess(treeUri)) {
            save(load, treeUri)
        } else {
            pending = load
            picker.launch(null)
        }
    }
}

private fun Context.hasPersistedWriteAccess(treeUri: String): Boolean =
    contentResolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isWritePermission }

private suspend fun Context.showToast(message: String) =
    withContext(Dispatchers.Main) { Toast.makeText(this@showToast, message, Toast.LENGTH_SHORT).show() }
