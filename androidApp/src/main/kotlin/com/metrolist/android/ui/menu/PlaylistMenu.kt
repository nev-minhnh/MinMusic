package com.metrolist.android.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.metrolist.android.LocalSaveToFolder
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.save_all_to_folder
import com.metrolist.shared.generated.resources.save_to_folder_desc
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.localizedBuiltinPlaylist
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.MediaDownloadState
import com.metrolist.shared.ui.menu.MediaMenuSheetContent
import com.metrolist.shared.ui.menu.loadMediaItemSongs
import com.metrolist.shared.ui.menu.playlistMediaMenuModel
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlaylistMenu(
    playlist: PlaylistItem,
    onDismiss: () -> Unit,
    isFavorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    fallbackIcon: ImageVector? = null,
    loadSongs: suspend () -> List<SongItem> = { loadMediaItemSongs(playlist) },
    showSpeedDialAction: Boolean = true,
    hideFromLibraryLabel: String? = null,
) {
    val playlist = localizedBuiltinPlaylist(playlist)
    val download = rememberMediaDownloadAction(playlist, onDismiss, onDownload, loadSongs)
    val saveToFolder = LocalSaveToFolder.current
    val saveAllToFolder =
        if (download.isDownloaded) {
            PlayerMenuAction(
                MetrolistIcons.Outlined.Storage,
                stringResource(Res.string.save_all_to_folder),
                stringResource(Res.string.save_to_folder_desc),
            ) {
                saveToFolder { SaveToFolderBatch(loadSongs().map { it.id }) }
                onDismiss()
            }
        } else {
            null
        }
    val pin = rememberSpeedDialToggle(playlist)
    val model =
        playlistMediaMenuModel(
            playlist = playlist,
            isFavorite = isFavorite,
            onFavorite = onFavorite,
            onDismiss = { if (!download.isConfirming()) onDismiss() },
            fallbackIcon = fallbackIcon,
            isPinned = pin.isPinned,
            showPinAction = showSpeedDialAction,
            downloadState = if (download.isDownloaded) MediaDownloadState.DOWNLOADED else MediaDownloadState.NOT_DOWNLOADED,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onShare = onShare.takeUnless { playlist.isLocal || playlist.id.startsWith("auto:") },
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onTogglePin = pin.toggle,
            onDownload = download.invoke,
            onHideFromLibrary = onHideFromLibrary,
            hideFromLibraryLabel = hideFromLibraryLabel,
            onExport = onExport,
            additionalActions = listOfNotNull(saveAllToFolder),
            onRename = onRename,
            onDelete = onDelete,
        )
    MediaMenuBottomSheet(model = model, onDismissRequest = onDismiss, canDismiss = { !download.isConfirming() }) {
        MediaMenuSheetContent(model)
    }
    download.confirmationDialog(onDismiss)
}
