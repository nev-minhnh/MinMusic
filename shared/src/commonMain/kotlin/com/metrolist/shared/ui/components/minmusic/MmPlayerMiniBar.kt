/*
 * MinMusic mini player (design-system section 10, mockups.html "Mini player · trạng thái"):
 * 64 dp, inset 8 dp, radius 16, surface2, 48 dp artwork, title + artist, play/pause and next,
 * and a 2 dp brand-gradient progress line along the bottom edge.
 */

package com.metrolist.shared.ui.components.minmusic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.ui.components.PlayerSheetSurface
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.LocalBrand
import org.jetbrains.compose.resources.stringResource

val MmPlayerMiniBarHeight = 64.dp

/** Horizontal inset of the mini player from the screen edges. */
val MmPlayerMiniBarInset = 8.dp

private val MiniBarRadius = 16.dp
private val MiniArtworkSize = 48.dp

/**
 * [progress] is read only while drawing the progress line. [artwork] replaces the default artwork image,
 * e.g. with a music video surface. The surface takes part in the mini <-> full player morph.
 */
@Composable
fun MmPlayerMiniBar(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    progress: () -> Float,
    progressAvailable: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    canSkipNext: Boolean,
    modifier: Modifier = Modifier,
    playbackEnabled: Boolean = true,
    playbackActionIcon: ImageVector? = null,
    playbackActionLabel: String? = null,
    titleModifier: Modifier = Modifier,
    artwork: (@Composable () -> Unit)? = null,
    docked: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    val gradientColors = LocalBrand.current.gradientColors
    val lineColor = scheme.outlineVariant
    // outlineVariant is nearly invisible on surface2; the unplayed track uses ink at 16% instead.
    val trackColor = ink.copy(alpha = 0.16f)
    val currentProgress by rememberUpdatedState(progress)
    val playLabel = playbackActionLabel ?: stringResource(if (isPlaying) Res.string.pause else Res.string.play)
    val loadingLabel = stringResource(Res.string.loading)
    PlayerSheetSurface(
        modifier = modifier.fillMaxWidth().height(MmPlayerMiniBarHeight),
        // Docked (tablet default): a square surface1 bar across the window bottom instead of the floating card.
        color = if (docked) scheme.surfaceContainerLow else scheme.surfaceContainer,
        cornerRadius = if (docked) 0.dp else MiniBarRadius,
        elevation = 0.dp,
        onClick = onExpand,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(MiniArtworkSize)
                            .mmPlayerMiniArtworkSlot()
                            .clip(RoundedCornerShape(MmPlayerMiniArtworkRadius)),
                ) {
                    if (artwork != null) {
                        artwork()
                    } else {
                        AsyncImage(
                            url = artworkUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = ink,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = titleModifier,
                    )
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = ink.copy(alpha = 0.56f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlay, enabled = playbackEnabled) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).semantics { contentDescription = loadingLabel },
                            color = scheme.primary,
                            strokeWidth = 3.dp,
                            trackColor = lineColor,
                        )
                    } else {
                        Icon(
                            imageVector =
                                playbackActionIcon
                                    ?: if (isPlaying) MetrolistIcons.Rounded.Pause else MetrolistIcons.Rounded.PlayArrow,
                            contentDescription = playLabel,
                            tint = ink,
                        )
                    }
                }
                IconButton(onClick = onSkipNext, enabled = canSkipNext) {
                    Icon(
                        imageVector = MetrolistIcons.Rounded.SkipNext,
                        contentDescription = stringResource(Res.string.next),
                        tint = if (canSkipNext) ink else ink.copy(alpha = 0.38f),
                    )
                }
            }
            if (progressAvailable) {
                Spacer(
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .drawWithCache {
                                val brush = Brush.horizontalGradient(gradientColors, startX = 0f, endX = size.width)
                                val radius = CornerRadius(size.height / 2f)
                                onDrawBehind {
                                    drawRoundRect(trackColor, cornerRadius = radius)
                                    // Read progress only here, so playback ticks redraw the line without recomposing the bar.
                                    val width = size.width * currentProgress().coerceIn(0f, 1f)
                                    if (width > 0f) {
                                        val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                                        drawRoundRect(brush, Offset(left, 0f), Size(width, size.height), radius)
                                    }
                                }
                            },
                )
            }
        }
    }
}
