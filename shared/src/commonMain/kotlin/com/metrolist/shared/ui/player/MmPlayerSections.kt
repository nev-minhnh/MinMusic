/*
 * MinMusic full player sections that bind the shared Mm* components to the player state.
 * Used by PlayerScreen in the portrait phone layout of the default (modern) player type.
 */

package com.metrolist.shared.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.mm_player_eq_chip
import com.metrolist.shared.generated.resources.mm_player_eq_chip_profile
import com.metrolist.shared.generated.resources.mm_player_lyrics_peek_empty
import com.metrolist.shared.generated.resources.mm_player_lyrics_peek_hint
import com.metrolist.shared.generated.resources.mm_player_playing_from
import com.metrolist.shared.generated.resources.mm_player_this_device
import com.metrolist.shared.generated.resources.mute
import com.metrolist.shared.generated.resources.now_playing
import com.metrolist.shared.generated.resources.queue
import com.metrolist.shared.generated.resources.share
import com.metrolist.shared.generated.resources.switch_to_audio
import com.metrolist.shared.generated.resources.switch_to_video
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.generated.resources.unmute
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.playback.LocalPlayerSession
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.formatSleepTimerRemaining
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState
import com.metrolist.shared.ui.components.minmusic.MmPlayerChip
import com.metrolist.shared.ui.components.minmusic.MmPlayerFullArtworkRadius
import com.metrolist.shared.ui.components.minmusic.MmPlayerLyricsPeek
import com.metrolist.shared.ui.components.minmusic.MmPlayerTopBar
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.viewmodels.PlayerViewModel
import org.jetbrains.compose.resources.stringResource

/** Height of the chip row (output device, EQ, sleep timer, queue) at the bottom of the player. */
internal val MmPlayerChipRowHeight = 56.dp

/** Height of the lyrics peek card under the chip row. */
internal val MmPlayerLyricsPeekHeight = 96.dp

/** Track change: title and artist rise this far while fading in (design-system section 7). */
internal val MmPlayerTitleSlide = 8.dp

/** Full player artwork: 288 dp on a 360 dp phone, radius 20. */
internal val MmPlayerArtworkShape = RoundedCornerShape(MmPlayerFullArtworkRadius)

/** The portrait column has a 24 dp gutter; the top bar's icon buttons sit 4 dp from the screen edge instead. */
internal val MmPlayerTopBarBleed = 20.dp

/** Lets a child extend [amount] past both horizontal edges of its parent's padding. */
internal fun Modifier.mmPlayerBleed(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val extra = if (constraints.hasBoundedWidth) amount.roundToPx() * 2 else 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
        val placeable =
            if (constraints.hasBoundedWidth) {
                measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            } else {
                measurable.measure(constraints)
            }
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(layoutWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }

/** Height of the bottom area that the queue sheet uses as its collapsed header. */
internal fun mmPlayerBottomAreaHeight(showLyricsPeek: Boolean): Dp =
    if (showLyricsPeek) MmPlayerChipRowHeight + MmPlayerLyricsPeekHeight else MmPlayerChipRowHeight

@Composable
internal fun MmPlayerTopBarSection(
    queueTitle: String?,
    onCollapse: () -> Unit,
    onMore: () -> Unit,
    showVideoButton: Boolean,
    isVideoModeActive: Boolean,
    isVideoModeResolving: Boolean,
    onToggleVideoMode: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val playingFrom = queueTitle?.takeIf { it.isNotBlank() }
    MmPlayerTopBar(
        overline = stringResource(if (playingFrom != null) Res.string.mm_player_playing_from else Res.string.now_playing),
        title = playingFrom,
        onCollapse = onCollapse,
        onMore = onMore,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        if (showVideoButton) {
            IconButton(onClick = onToggleVideoMode, enabled = !isVideoModeResolving) {
                if (isVideoModeResolving && LocalPlayerContentActive.current) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector =
                            if (isVideoModeActive) MetrolistIcons.Filled.SmartDisplay else MetrolistIcons.Outlined.SmartDisplay,
                        contentDescription =
                            stringResource(if (isVideoModeActive) Res.string.switch_to_audio else Res.string.switch_to_video),
                        tint = if (isVideoModeActive) MaterialTheme.colorScheme.primary else contentColor,
                    )
                }
            }
        }
    }
}

/** Share and like next to the title, replacing the connected button group of the stock layout. */
@Composable
internal fun MmPlayerTitleActions(
    isLiked: Boolean,
    onLikeClick: () -> Unit,
    onShareClick: () -> Unit,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onShareClick) {
            Icon(
                imageVector = MetrolistIcons.Outlined.Share,
                contentDescription = stringResource(Res.string.share),
                tint = contentColor.copy(alpha = 0.76f),
            )
        }
        IconButton(onClick = onLikeClick) {
            Icon(
                imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                contentDescription = stringResource(if (isLiked) Res.string.unlike else Res.string.action_like),
                tint = contentColor,
            )
        }
    }
}

/**
 * The queue sheet's collapsed header in the MinMusic layout: output device and EQ chips, sleep timer and queue,
 * then the lyrics peek card (only while the lyrics are hidden).
 */
@Composable
internal fun MmPlayerBottomArea(
    viewModel: PlayerViewModel,
    lyrics: List<LyricsEntry>,
    songId: String?,
    lyricsOffsetMs: Int,
    isLyricsLoading: Boolean,
    isLyricsVisible: Boolean,
    onLyricsVisibleChange: (Boolean) -> Unit,
    isPlaying: Boolean,
    castState: CastState,
    onOpenCast: (() -> Unit)?,
    onOpenEqualizer: () -> Unit,
    sleepTimerState: SleepTimerState,
    onShowSleepTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    isListenTogetherGuest: Boolean,
    navigationBarBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberMetrolistHaptics()
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MmPlayerChipRowHeight)
                    .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val outputName =
                    castState.device?.name?.takeIf { castState.isConnected && it.isNotBlank() }
                        ?: EqualizerProfiles.outputDevice?.productName?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.mm_player_this_device)
                MmPlayerChip(
                    label = outputName,
                    icon = if (castState.isConnected) MetrolistIcons.Filled.Cast else MetrolistIcons.Outlined.Headphones,
                    onClick = {
                        if (onOpenCast != null) {
                            haptics.perform(HapticEvent.PRESS)
                            onOpenCast()
                        }
                    },
                    active = castState.isConnected,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val profileName =
                    EqualizerProfiles.activeProfile?.name?.takeIf { Settings.shared.equalizerEnabled && it.isNotBlank() }
                MmPlayerChip(
                    label =
                        if (profileName != null) {
                            stringResource(Res.string.mm_player_eq_chip_profile, profileName)
                        } else {
                            stringResource(Res.string.mm_player_eq_chip)
                        },
                    icon = MetrolistIcons.Outlined.Tune,
                    onClick = {
                        haptics.perform(HapticEvent.PRESS)
                        onOpenEqualizer()
                    },
                    showBrandDot = profileName != null,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (isLyricsVisible) {
                IconButton(
                    onClick = {
                        haptics.toggle(false)
                        onLyricsVisibleChange(false)
                    },
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Lyrics,
                        contentDescription = stringResource(Res.string.lyrics),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (isListenTogetherGuest) {
                MmPlayerGuestVolumeButton(contentColor = ink)
            } else if (sleepTimerState.isActive) {
                MmPlayerChip(
                    label =
                        sleepTimerState.remainingMs?.let(::formatSleepTimerRemaining)
                            ?: sleepTimerContentDescription(sleepTimerState),
                    icon = MetrolistIcons.Outlined.Bedtime,
                    onClick = {
                        haptics.perform(HapticEvent.PRESS)
                        onShowSleepTimer()
                    },
                    active = true,
                    contentDescription = sleepTimerContentDescription(sleepTimerState),
                )
            } else {
                IconButton(
                    onClick = {
                        haptics.perform(HapticEvent.PRESS)
                        onShowSleepTimer()
                    },
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Bedtime,
                        contentDescription = sleepTimerContentDescription(sleepTimerState),
                        tint = ink.copy(alpha = 0.76f),
                    )
                }
            }
            IconButton(
                onClick = {
                    haptics.perform(HapticEvent.PRESS)
                    onOpenQueue()
                },
            ) {
                Icon(
                    imageVector = MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                    contentDescription = stringResource(Res.string.queue),
                    tint = ink,
                )
            }
        }
        if (isLyricsVisible) {
            Spacer(Modifier.height(navigationBarBottomPadding))
        } else {
            MmPlayerLyricsPeekSection(
                viewModel = viewModel,
                lyrics = lyrics,
                songId = songId,
                timingOffsetMs = lyricsOffsetMs,
                isPlaying = isPlaying,
                isCasting = castState.isConnected,
                isBuffering = castState.isBuffering,
                isLoading = isLyricsLoading,
                onOpen = {
                    haptics.toggle(true)
                    onLyricsVisibleChange(true)
                },
                bottomInset = navigationBarBottomPadding,
                modifier = Modifier.padding(horizontal = 12.dp).height(MmPlayerLyricsPeekHeight + navigationBarBottomPadding),
            )
        }
    }
}

@Composable
private fun MmPlayerGuestVolumeButton(contentColor: Color) {
    val playerConnection = LocalPlayerSession.current
    val volume by playerConnection?.volume?.collectAsState() ?: remember { mutableFloatStateOf(1f) }
    val haptics = rememberMetrolistHaptics()
    val muted = volume <= 0.01f
    IconButton(
        onClick = {
            haptics.perform(HapticEvent.PRESS)
            playerConnection?.setVolume(if (muted) 1f else 0f)
        },
    ) {
        Icon(
            imageVector = if (muted) MetrolistIcons.AutoMirrored.Filled.VolumeOff else MetrolistIcons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(if (muted) Res.string.unmute else Res.string.mute),
            tint = contentColor,
        )
    }
}

/** Current and next lyric line for the peek card; only the line index is observed, not the playback clock. */
@Composable
private fun MmPlayerLyricsPeekSection(
    viewModel: PlayerViewModel,
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    isCasting: Boolean,
    isBuffering: Boolean,
    isLoading: Boolean,
    onOpen: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.lyrics)
    val hint = stringResource(Res.string.mm_player_lyrics_peek_hint)
    val placeholder = stringResource(if (isLoading) Res.string.loading else Res.string.mm_player_lyrics_peek_empty)
    val lines = remember(lyrics) { lyrics.filter { it.text.isNotBlank() } }
    if (lines.isEmpty() || !LyricsUtils.hasPlaybackTiming(lines)) {
        MmPlayerLyricsPeek(
            title = title,
            hint = hint,
            currentLine = lines.firstOrNull()?.text ?: placeholder,
            nextLine = lines.getOrNull(1)?.text,
            onOpen = onOpen,
            bottomInset = bottomInset,
            modifier = modifier,
        )
        return
    }
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val position =
        rememberCaptionLyricsPositionState(
            lyrics = lines,
            isPlaying = isPlaying,
            songId = songId,
            timingOffsetMs = timingOffsetMs,
            isCasting = isCasting,
            currentPositionFlow = viewModel.currentPosition,
            positionReader = viewModel::currentPositionMs,
            enabled = LocalPlayerPositionActive.current,
            isBuffering = isBuffering,
            finalLineDurationMs = LyricsUtils.FINAL_DYNAMIC_LINE_DURATION_MS,
            playbackSpeed = playbackSpeed,
        )
    val index by remember(lines, position) {
        derivedStateOf(structuralEqualityPolicy()) { LyricsUtils.findCurrentLineIndex(lines, position.value) }
    }
    MmPlayerLyricsPeek(
        title = title,
        hint = hint,
        currentLine = lines.getOrNull(index)?.text ?: lines.first().text,
        nextLine = lines.getOrNull(if (index < 0) 1 else index + 1)?.text,
        onOpen = onOpen,
        bottomInset = bottomInset,
        modifier = modifier,
    )
}
