package com.metrolist.shared.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.minmusic.mmArtworkEdge
import org.jetbrains.compose.resources.painterResource

@Composable
fun PlaylistCoverImage(
    playlist: PlaylistItem,
    modifier: Modifier = Modifier,
) {
    PlaylistCoverImage(
        url = libraryArtwork(playlist.id, playlist.thumbnail, playlist.customThumbnail),
        modifier = modifier,
    )
}

@Composable
fun PlaylistCoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    previewUrl: String? = null,
    artworkEdge: Boolean = true,
) {
    val placeholder = painterResource(Res.drawable.logo)
    val coverModifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer)
    if (url.isAutoPlaylistIcon()) {
        AutoPlaylistThumbnail(url, coverModifier)
    } else if (!url.isNullOrBlank()) {
        // MinMusic: the soft edge wraps the backdrop too, so no backdrop rim shows around the faded edge.
        AsyncImage(
            url = url,
            modifier =
                if (artworkEdge) {
                    modifier.mmArtworkEdge().background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    coverModifier
                },
            contentScale = contentScale,
            error = placeholder,
            previewUrl = previewUrl,
            artworkEdge = false,
        )
    } else {
        Image(
            painter = placeholder,
            contentDescription = null,
            modifier = coverModifier,
            contentScale = contentScale,
        )
    }
}
