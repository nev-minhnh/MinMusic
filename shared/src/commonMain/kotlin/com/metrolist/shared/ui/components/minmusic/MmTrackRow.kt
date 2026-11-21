@file:OptIn(ExperimentalFoundationApi::class)

package com.metrolist.shared.ui.components.minmusic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.NowPlayingBars
import com.metrolist.shared.ui.components.SourceQualityBadges
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.MinType
import com.metrolist.shared.utils.formatDuration
import org.jetbrains.compose.resources.stringResource

private val ArtworkShape = RoundedCornerShape(8.dp)

/**
 * One song row from the design system.
 * - With artwork (default when [index] is null): 64 dp, 48 dp art. Used in search, charts, downloads, playlists.
 * - Compact (when [index] is set and [showArtwork] is false): 56 dp, track number or now-playing bars, duration
 *   column. Used in album and user-album detail.
 * [rank] adds the large chart rank before the artwork. Badges ([SourceQualityBadges]) go before the artist text.
 * Press feedback is a ripple plus a scale on the artwork only, so text never relayouts.
 */
@Composable
fun MmTrackRow(
    song: SongItem,
    onClick: () -> Unit,
    onShowMenu: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    rank: Int? = null,
    showArtwork: Boolean = index == null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isSelected: Boolean = false,
    showSource: Boolean = true,
    showDuration: Boolean = !showArtwork,
    onLongClick: (() -> Unit)? = onShowMenu,
    enabled: Boolean = !song.isUnavailable,
    startPadding: Dp = 16.dp,
    endPadding: Dp = 4.dp,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val effectiveSong = SongMetadataState.apply(song)
    val interactionSource = remember { MutableInteractionSource() }
    val artists = remember(effectiveSong.artists) { effectiveSong.artists.joinToString(", ") { it.name } }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(if (showArtwork) 64.dp else 56.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .background(if (isSelected) colors.primaryContainer else Color.Transparent)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onClick = { if (enabled) onClick() },
                    onLongClick = onLongClick,
                ).padding(start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = MinType.TabularFigures),
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(24.dp),
            )
        }
        if (!showArtwork) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    NowPlayingBars(isPlaying = isPlaying, color = colors.primary, modifier = Modifier.size(20.dp))
                } else if (index != null) {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = MinType.TabularFigures),
                        color = colors.outline,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        } else {
            AsyncImage(
                url = song.previewThumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .mmPressScale(interactionSource)
                        .size(48.dp)
                        .mmArtworkEdge(ArtworkShape)
                        .background(colors.surfaceContainerHigh),
                artworkEdge = false,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = effectiveSong.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceQualityBadges(songId = song.id, showSource = showSource)
                if (artists.isNotEmpty()) {
                    Text(
                        text = artists,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (showDuration) {
            val duration = effectiveSong.duration?.takeIf { it > 0 }
            Text(
                text = duration?.let { formatDuration(it * 1000L) }.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = MinType.TabularFigures),
                color = colors.outline,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(40.dp),
            )
        }
        if (trailingContent != null) {
            trailingContent()
        } else {
            IconButton(onClick = onShowMenu) {
                Icon(
                    MetrolistIcons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.more_options),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
