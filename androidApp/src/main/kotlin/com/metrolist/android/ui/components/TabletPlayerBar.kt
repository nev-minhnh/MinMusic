/*
 * MinMusic tablet player bar (tablet-layout-spec 5.5, mockup `pbarT`).
 * Full layout (window width >= 840): left song zone | centre controls + seek row (<= 480) | right EQ chip,
 * lyrics, queue, expand. Compact layout (600-839 or short heights): song zone, prev, play, next, queue,
 * expand, with a 2 dp gradient progress line on the top edge.
 */

package com.metrolist.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.metrolist.android.LocalListenTogetherManager
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.ui.player.MusicVideoSurface
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.cd_playback_position
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.mm_player_eq_chip
import com.metrolist.shared.generated.resources.mm_player_eq_chip_profile
import com.metrolist.shared.generated.resources.mm_player_queue_open
import com.metrolist.shared.generated.resources.mute
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.repeat_mode_all
import com.metrolist.shared.generated.resources.repeat_mode_off
import com.metrolist.shared.generated.resources.repeat_mode_one
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.generated.resources.tablet_bar_open_player
import com.metrolist.shared.generated.resources.unlike
import com.metrolist.shared.generated.resources.unmute
import com.metrolist.shared.generated.resources.volume
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.model.previewThumbnailUrl
import com.metrolist.shared.playback.PendingSeekPosition
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.SliderStyle
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.ui.components.BottomSheetState
import com.metrolist.shared.ui.components.LiveBadge
import com.metrolist.shared.ui.components.PlayerSheetSurface
import com.metrolist.shared.ui.components.SourceQualityBadges
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.minmusic.MmPlayerChip
import com.metrolist.shared.ui.components.minmusic.MmPlayerMiniArtworkRadius
import com.metrolist.shared.ui.components.minmusic.MmPlayerProgressTrack
import com.metrolist.shared.ui.components.minmusic.mmPlayerMiniArtworkSlot
import com.metrolist.shared.ui.components.rememberTrackMarquee
import com.metrolist.shared.ui.components.sliders.PlaybackProgressSlider
import com.metrolist.shared.ui.components.sliders.rememberPendingSeekPosition
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.player.shouldShowMusicVideoSurface
import com.metrolist.shared.ui.theme.LocalBrand
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.utils.formatDuration
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Inset of the floating variant from the content column edges. */
private val FloatingInset = 8.dp
private val FloatingRadius = 20.dp
private val BarIconButtonSize = 44.dp
private val FullIconSize = 20.dp
private val PlayButtonSize = 48.dp
private val PlayButtonRadius = 16.dp
private val FullPlayButtonRadius = 14.dp
private val ArtworkSize = TabletBarArtworkSize
private val CentreMaxWidth = 480.dp
private val CentreMinWidth = 280.dp
private val SideMinWidth = 220.dp
private val SideGap = 16.dp

/** Right zone width from which the volume slider (as on the desktop bar) fits next to the other actions. */
private val VolumeMinSideWidth = 360.dp

/**
 * Height of the tablet player bar for the current window, slider style/thickness, progress setting and font scale.
 * MetrolistApp reserves exactly this height, and the bar lays its rows out with the same numbers, so nothing clips.
 */
@Composable
fun rememberTabletPlayerBarHeight(
    fullLayout: Boolean,
    shortHeight: Boolean,
): Dp {
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val sliderStyle = Settings.shared.sliderStyle
    val thickness = Settings.shared.sliderTrackThicknessDp
    val showSeekRow = Settings.shared.showExpressiveMiniPlayerProgress
    return remember(fullLayout, shortHeight, density, typography, sliderStyle, thickness, showSeekRow) {
        with(density) {
            val seekRow =
                if (fullLayout && showSeekRow) {
                    tabletSeekRowHeight(tabletSeekTrackHeight(sliderStyle, thickness), lineHeightDp(typography.labelSmall))
                } else {
                    0.dp
                }
            tabletPlayerBarHeight(
                fullLayout = fullLayout,
                shortHeight = shortHeight,
                seekRowHeight = seekRow,
                songZoneHeight = tabletSongZoneHeight(lineHeightDp(typography.titleSmall), lineHeightDp(typography.labelSmall)),
            )
        }
    }
}

/** One text line in dp at the current (possibly non-linear) font scale. */
private fun Density.lineHeightDp(style: TextStyle): Dp {
    val line = if (style.lineHeight.isSp) style.lineHeight else style.fontSize * 1.4f
    return if (line.isSp) line.toDp() else 20.dp
}

/**
 * The tablet player bar. [fullLayout] picks the Expanded/Large layout; otherwise the compact one.
 * [docked] draws a square surface1 bar edge to edge; otherwise the floating rounded bar (r20, inset 8).
 */
@UnstableApi
@Composable
fun TabletPlayerBar(
    fullLayout: Boolean,
    docked: Boolean,
    height: Dp,
    pureBlack: Boolean,
    onExpand: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    bottomSheetState: BottomSheetState? = null,
    cardFocusRequester: FocusRequester? = null,
) {
    val player = LocalPlayerConnection.current ?: return
    val currentSong by player.currentSong.collectAsState(initial = null)
    val isPlaying by player.isPlaying.collectAsState(initial = false)
    val isPlaybackLoading by player.isPlaybackLoading.collectAsState(initial = false)
    val volume by player.volume.collectAsState(initial = 1f)
    val playbackResource by player.currentPlaybackResource.collectAsState(initial = null)
    val canSkipNext by player.canSkipNext.collectAsState(initial = false)
    val canSkipPrevious by player.canSkipPrevious.collectAsState(initial = false)
    val isVideoModeActive by player.isVideoModeActive.collectAsState()
    val isVideoViewerVisible by player.isVideoViewerVisible.collectAsState()
    val listenTogetherManager = LocalListenTogetherManager.current
    val listenTogetherRole by listenTogetherManager?.role?.collectAsState()
        ?: remember { mutableStateOf(ListenTogetherRoomRole.NONE) }
    val isInListenTogetherRoom by listenTogetherManager?.isInRoom?.collectAsState()
        ?: remember { mutableStateOf(false) }
    val isGuest = isInListenTogetherRoom && listenTogetherRole == ListenTogetherRoomRole.GUEST
    val isMuted = volume <= 0.01f
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = bottomSheetState?.isCollapsed != false && lifecycleState == Lifecycle.State.RESUMED

    if (currentSong == null) return
    val metadataRevision by SongMetadataState.revision.collectAsState()
    val song = remember(currentSong, metadataRevision) { SongMetadataState.apply(currentSong!!) }
    val isLive = song.isLive || playbackResource?.isLive == true

    val hapticManager = rememberMetrolistHaptics()
    val coroutineScope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current

    // Horizontal swipe on the song zone = next / previous (phone mini player behaviour).
    val dragOffset = remember { mutableStateOf(Offset.Zero) }
    var offsetResetJob by remember { mutableStateOf<Job?>(null) }
    val resetSpec = remember { spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow) }

    fun resetDragOffset() {
        offsetResetJob?.cancel()
        val startOffset = dragOffset.value
        offsetResetJob =
            coroutineScope.launch {
                Animatable(0f).animateTo(1f, resetSpec) {
                    dragOffset.value = startOffset * (1f - value)
                }
                dragOffset.value = Offset.Zero
            }
    }
    val sensitivityFactor = (173f - Settings.shared.swipeSensitivity) / 100f
    val swipeThreshold = with(LocalDensity.current) { 100.dp.toPx() } * sensitivityFactor
    val songZoneGestures =
        Modifier.miniPlayerGestures(
            layoutDirection = layoutDirection,
            hapticManager = hapticManager,
            dragOffset = dragOffset,
            onDragStart = {
                offsetResetJob?.cancel()
                dragOffset.value = Offset.Zero
            },
            onResetDragOffset = ::resetDragOffset,
            swipeThreshold = swipeThreshold,
            canSkipNext = canSkipNext && !isGuest,
            canSkipPrevious = canSkipPrevious && !isGuest,
            onSkipNext = { player.skipToNext() },
            onSkipPrevious = { player.skipToPrevious() },
        )

    val expand = {
        hapticManager.perform(HapticEvent.PRESS)
        onExpand()
    }
    val togglePlay = {
        hapticManager.toggle(if (isGuest) isMuted else !isPlaying)
        if (isGuest) {
            player.setVolume(if (volume > 0.01f) 0f else 1f)
        } else {
            player.togglePlayPause()
        }
    }
    val playIcon =
        if (isGuest) {
            if (isMuted) MetrolistIcons.AutoMirrored.Filled.VolumeUp else MetrolistIcons.AutoMirrored.Filled.VolumeOff
        } else if (isPlaying) {
            MetrolistIcons.Rounded.Pause
        } else {
            MetrolistIcons.Rounded.PlayArrow
        }
    val playLabel =
        stringResource(
            if (isGuest) {
                if (isMuted) Res.string.unmute else Res.string.mute
            } else if (isPlaying) {
                Res.string.pause
            } else {
                Res.string.play
            },
        )

    val scheme = MaterialTheme.colorScheme
    val containerColor =
        when {
            docked && pureBlack -> Color.Black
            docked -> scheme.surfaceContainerLow
            else -> scheme.surfaceContainer
        }
    val lineColor = scheme.outlineVariant
    PlayerSheetSurface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    if (docked) {
                        Modifier
                    } else {
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = FloatingInset)
                    },
                ).then(if (cardFocusRequester != null) Modifier.focusRequester(cardFocusRequester) else Modifier)
                .tvFocusHighlight(scale = 1f),
        color = containerColor,
        cornerRadius = if (docked) 0.dp else FloatingRadius,
        elevation = 0.dp,
        onClick = expand,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (docked) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    } else {
                        Modifier
                    },
                ),
        ) {
            // "Play videos in mini player" applies to the tablet bar too: the 48 dp artwork becomes the video.
            val showVideo =
                shouldShowMusicVideoSurface(
                    isVideoModeActive = isVideoModeActive,
                    isVideoViewerVisible = isVideoViewerVisible,
                    hasVideo = playbackResource?.hasVideo == true,
                    presentationAllowed = Settings.shared.playVideosInMiniPlayer && active,
                )
            val songZone: @Composable (Modifier) -> Unit = { zoneModifier ->
                TabletBarSongZone(
                    videoPlayer = if (showVideo) player.videoPlayer else null,
                    showVideo = showVideo,
                    song = song,
                    playbackResource = playbackResource,
                    isLive = isLive,
                    active = active,
                    showLike = fullLayout,
                    onLike = {
                        hapticManager.toggle(!SongLikeState.isLiked(song.id))
                        SongLikeState.toggleLike(song)
                    },
                    modifier =
                        zoneModifier
                            .then(songZoneGestures)
                            .graphicsLayer { translationX = dragOffset.value.x },
                )
            }
            if (fullLayout) {
                // Full layout: 40 dp controls without the 48 dp minimum-touch inflation, so the controls row and the
                // seek row fit the reserved height (rememberTabletPlayerBarHeight) and never overlap each other.
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides 0.dp,
                    LocalBarControlSize provides TabletBarFullControlSize,
                ) {
                    TabletBarFullLayout(
                        player = player,
                        song = song,
                        isPlaying = isPlaying,
                        isLoading = isPlaybackLoading && active,
                        isLive = isLive,
                        isGuest = isGuest,
                        active = active,
                        volume = volume,
                        canSkipNext = canSkipNext && !isGuest,
                        canSkipPrevious = canSkipPrevious && !isGuest,
                        playIcon = playIcon,
                        playLabel = playLabel,
                        onTogglePlay = togglePlay,
                        onExpand = expand,
                        onOpenEqualizer = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onOpenEqualizer()
                        },
                        onOpenLyrics = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onOpenLyrics()
                        },
                        onOpenQueue = {
                            hapticManager.perform(HapticEvent.PRESS)
                            onOpenQueue()
                        },
                        onPress = { hapticManager.perform(HapticEvent.PRESS) },
                        songZone = songZone,
                    )
                }
                if (docked) {
                    // Docked full bar: 1 dp line on the top edge (mockup `.pbar` border-top).
                    Spacer(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(lineColor),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    songZone(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BarIconButton(
                            icon = MetrolistIcons.Rounded.SkipPrevious,
                            label = stringResource(Res.string.previous),
                            enabled = canSkipPrevious && !isGuest,
                            onClick = {
                                hapticManager.perform(HapticEvent.PRESS)
                                player.skipToPrevious()
                            },
                        )
                        TabletBarPlayButton(
                            icon = playIcon,
                            label = playLabel,
                            isLoading = isPlaybackLoading && active,
                            onClick = togglePlay,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        BarIconButton(
                            icon = MetrolistIcons.Rounded.SkipNext,
                            label = stringResource(Res.string.next),
                            enabled = canSkipNext && !isGuest,
                            onClick = {
                                hapticManager.perform(HapticEvent.PRESS)
                                player.skipToNext()
                            },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BarIconButton(
                            icon = MetrolistIcons.AutoMirrored.Rounded.QueueMusic,
                            label = stringResource(Res.string.mm_player_queue_open),
                            onClick = {
                                hapticManager.perform(HapticEvent.PRESS)
                                onOpenQueue()
                            },
                        )
                        BarIconButton(
                            icon = MetrolistIcons.Outlined.KeyboardArrowUp,
                            label = stringResource(Res.string.tablet_bar_open_player),
                            onClick = expand,
                        )
                    }
                }
                if (Settings.shared.showExpressiveMiniPlayerProgress) {
                    TabletBarTopLine(
                        player = player,
                        isLive = isLive,
                        active = active,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun TabletBarFullLayout(
    player: PlayerConnection,
    song: SongItem,
    isPlaying: Boolean,
    isLoading: Boolean,
    isLive: Boolean,
    isGuest: Boolean,
    active: Boolean,
    volume: Float,
    canSkipNext: Boolean,
    canSkipPrevious: Boolean,
    playIcon: ImageVector,
    playLabel: String,
    onTogglePlay: () -> Unit,
    onExpand: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onPress: () -> Unit,
    songZone: @Composable (Modifier) -> Unit,
) {
    val shuffled by player.shuffleModeEnabled.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = LocalBrand.current.accentText
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 12.dp, top = TabletBarFullVerticalPadding, bottom = TabletBarFullVerticalPadding),
    ) {
        // Grid 1fr / <= 480 / 1fr: the centre shrinks first on narrow windows so the sides keep their minimum.
        val centreWidth = (maxWidth - SideGap * 2 - SideMinWidth * 2).coerceIn(CentreMinWidth, CentreMaxWidth)
        val sideWidth = (maxWidth - centreWidth - SideGap * 2) / 2
        val showVolume = sideWidth >= VolumeMinSideWidth
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SideGap),
        ) {
            songZone(Modifier.weight(1f))
            Column(
                modifier = Modifier.width(centreWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BarIconButton(
                        icon = MetrolistIcons.Rounded.Shuffle,
                        label = stringResource(Res.string.shuffle),
                        enabled = !isGuest,
                        tint = if (shuffled) accent else ink.copy(alpha = 0.76f),
                        onClick = {
                            onPress()
                            player.toggleShuffle()
                        },
                    )
                    BarIconButton(
                        icon = MetrolistIcons.Rounded.SkipPrevious,
                        label = stringResource(Res.string.previous),
                        enabled = canSkipPrevious,
                        onClick = {
                            onPress()
                            player.skipToPrevious()
                        },
                    )
                    TabletBarPlayButton(icon = playIcon, label = playLabel, isLoading = isLoading, onClick = onTogglePlay)
                    BarIconButton(
                        icon = MetrolistIcons.Rounded.SkipNext,
                        label = stringResource(Res.string.next),
                        enabled = canSkipNext,
                        onClick = {
                            onPress()
                            player.skipToNext()
                        },
                    )
                    BarIconButton(
                        icon = if (repeatMode == Player.REPEAT_MODE_ONE) MetrolistIcons.Rounded.RepeatOne else MetrolistIcons.Rounded.Repeat,
                        label =
                            stringResource(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> Res.string.repeat_mode_one
                                    Player.REPEAT_MODE_ALL -> Res.string.repeat_mode_all
                                    else -> Res.string.repeat_mode_off
                                },
                            ),
                        enabled = !isGuest,
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) accent else ink.copy(alpha = 0.76f),
                        onClick = {
                            onPress()
                            player.cycleRepeatMode()
                        },
                    )
                }
                if (Settings.shared.showExpressiveMiniPlayerProgress) {
                    TabletBarSeekRow(
                        player = player,
                        songId = song.id,
                        isPlaying = isPlaying,
                        isLive = isLive,
                        active = active,
                        enabled = !isGuest,
                    )
                }
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    onClick = onOpenEqualizer,
                    showBrandDot = profileName != null,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showVolume) {
                    val volumeLabel = stringResource(Res.string.volume)
                    Slider(
                        value = volume,
                        onValueChange = { player.setVolume(it) },
                        enabled = !isGuest,
                        modifier = Modifier.width(112.dp).semantics { contentDescription = volumeLabel },
                    )
                }
                BarIconButton(
                    icon = MetrolistIcons.Outlined.Lyrics,
                    label = stringResource(Res.string.lyrics),
                    onClick = onOpenLyrics,
                )
                BarIconButton(
                    icon = MetrolistIcons.AutoMirrored.Rounded.QueueMusic,
                    label = stringResource(Res.string.mm_player_queue_open),
                    onClick = onOpenQueue,
                )
                BarIconButton(
                    icon = MetrolistIcons.Outlined.KeyboardArrowUp,
                    label = stringResource(Res.string.tablet_bar_open_player),
                    onClick = onExpand,
                )
            }
        }
    }
}

/** Art 48 r8 + title 14/600 + meta line (source and quality badges, artist), and the heart in the full layout. */
@Composable
private fun TabletBarSongZone(
    videoPlayer: Player?,
    showVideo: Boolean,
    song: SongItem,
    playbackResource: PlaybackResource?,
    isLive: Boolean,
    active: Boolean,
    showLike: Boolean,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val marquee = rememberTrackMarquee(song.id, active, 10_000L)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(ArtworkSize)
                    .mmPlayerMiniArtworkSlot()
                    .clip(RoundedCornerShape(MmPlayerMiniArtworkRadius)),
        ) {
            if (showVideo) {
                MusicVideoSurface(
                    player = videoPlayer,
                    artworkUrl = song.previewThumbnailUrl,
                    fillContainer = true,
                    showControls = false,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    url = song.previewThumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = marquee,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isLive) {
                    LiveBadge(compact = true)
                } else {
                    SourceQualityBadges(songId = song.id, playbackResource = playbackResource)
                }
                Text(
                    text = song.artists.joinToString { it.displayName },
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.56f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (showLike) {
            val liked = SongLikeState.isLiked(song.id)
            BarIconButton(
                icon = if (liked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                label = stringResource(if (liked) Res.string.unlike else Res.string.action_like),
                tint = if (liked) LocalBrand.current.accentText else ink.copy(alpha = 0.76f),
                onClick = onLike,
            )
        }
    }
}

/**
 * Control size inside the bar: 44 dp icon buttons and the 48 r16 play button by default (compact layout);
 * the full layout provides [TabletBarFullControlSize] for every control and a 40 r14 play button.
 */
private val LocalBarControlSize = staticCompositionLocalOf<Dp?> { null }

/** Play/pause on the brand gradient: 48 r16, or 40 r14 in the full layout. */
@Composable
private fun TabletBarPlayButton(
    icon: ImageVector,
    label: String,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val brand = LocalBrand.current
    val loadingLabel = stringResource(Res.string.loading)
    val controlSize = LocalBarControlSize.current
    Box(
        modifier =
            modifier
                .size(controlSize ?: PlayButtonSize)
                .clip(RoundedCornerShape(if (controlSize != null) FullPlayButtonRadius else PlayButtonRadius))
                .background(brand.gradient)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (isLoading) loadingLabel else label },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(if (controlSize != null) 20.dp else 22.dp),
                color = brand.onGradient,
                strokeWidth = 3.dp,
                trackColor = Color.Transparent,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = brand.onGradient,
                modifier = Modifier.size(if (controlSize != null) 22.dp else 24.dp),
            )
        }
    }
}

@Composable
private fun BarIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
) {
    val controlSize = LocalBarControlSize.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(controlSize ?: BarIconButtonSize)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(if (controlSize != null) FullIconSize else 22.dp),
        )
    }
}

/** time · seekable progress · time. Uses the shared playback slider, so the user's slider style and thickness apply. */
@UnstableApi
@Composable
private fun TabletBarSeekRow(
    player: PlayerConnection,
    songId: String,
    isPlaying: Boolean,
    isLive: Boolean,
    active: Boolean,
    enabled: Boolean,
) {
    val position: State<Long> =
        if (active) player.currentPosition.collectAsState() else remember { mutableStateOf(player.currentPosition.value) }
    val duration by player.duration.collectAsState()
    val buffered by player.bufferedProgress.collectAsState()
    var draggedFraction by remember(songId) { mutableStateOf<Float?>(null) }
    var pendingSeek by rememberPendingSeekPosition(
        songId = songId,
        durationMs = duration,
        enabled = enabled && !isLive && duration > 0L,
        position = { position.value },
    )
    val hapticManager = rememberMetrolistHaptics()
    val sliderStyle = Settings.shared.sliderStyle
    val brand = LocalBrand.current
    // Seconds only, so the time labels recompose once per second instead of on every position sample.
    val elapsedSecond by remember(position, duration, songId, enabled, isLive) {
        derivedStateOf {
            val ms = draggedFraction?.let { (it * duration).toLong() } ?: pendingSeek?.targetMs ?: position.value
            ms / 1_000L
        }
    }
    val timeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f)
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val positionLabel = stringResource(Res.string.cd_playback_position)
    val thickness = Settings.shared.sliderTrackThicknessDp
    val rowHeight =
        with(LocalDensity.current) {
            tabletSeekRowHeight(tabletSeekTrackHeight(sliderStyle, thickness), lineHeightDp(MaterialTheme.typography.labelSmall))
        }
    Row(
        // Exactly the height rememberTabletPlayerBarHeight reserved. The M3 slider lays itself out taller (touch
        // target); clipToBounds keeps its drawing and its touch area inside the row so it never covers the controls.
        modifier = Modifier.fillMaxWidth().height(rowHeight).clipToBounds(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(formatDuration(elapsedSecond * 1_000L), color = timeColor, style = timeStyle, maxLines = 1)
        PlaybackProgressSlider(
            value = 0f,
            valueProvider = {
                if (isLive) {
                    1f
                } else {
                    draggedFraction
                        ?: if (duration > 0) (pendingSeek?.targetMs ?: position.value).toFloat() / duration else 0f
                }
            },
            onValueChange = {
                if (!isLive) {
                    pendingSeek = null
                    draggedFraction = it
                }
            },
            onValueChangeFinished = {
                if (!isLive) {
                    draggedFraction?.let {
                        hapticManager.perform(HapticEvent.CONFIRM)
                        val targetMs = (it.coerceIn(0f, 1f) * duration).toLong()
                        pendingSeek = PendingSeekPosition(targetMs, player.currentPosition.value)
                        player.seekTo(targetMs)
                    }
                    draggedFraction = null
                }
            },
            isPlaying = isPlaying,
            enabled = enabled && !isLive && duration > 0,
            showThumb = !isLive,
            animate = active,
            bufferedFraction = if (isLive) 1f else buffered,
            style = sliderStyle,
            // Same rule as the full player: the default style gets the MinMusic 4 dp gradient track; every other
            // style keeps the user's chosen shape and thickness and only swaps in the brand gradient.
            track =
                if (!isLive && sliderStyle == SliderStyle.DEFAULT) {
                    { state ->
                        MmPlayerProgressTrack(
                            progress = { state.value },
                            bufferedFraction = { buffered },
                        )
                    }
                } else {
                    null
                },
            activeGradientColors = if (isLive) null else brand.gradientColors,
            modifier = Modifier.weight(1f).semantics { contentDescription = positionLabel },
        )
        if (isLive) {
            LiveBadge(compact = true)
        } else {
            Text(formatDuration(duration), color = timeColor, style = timeStyle, maxLines = 1)
        }
    }
}

/** Compact layout: 2 dp gradient progress line on the top edge (not seekable). Position is read only while drawing. */
@UnstableApi
@Composable
private fun TabletBarTopLine(
    player: PlayerConnection,
    isLive: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val position: State<Long> =
        if (active) player.currentPosition.collectAsState() else remember { mutableStateOf(player.currentPosition.value) }
    val duration = player.duration.collectAsState()
    val colors = LocalBrand.current.gradientColors
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .drawWithCache {
                    val brush = Brush.horizontalGradient(colors, startX = 0f, endX = size.width)
                    onDrawBehind {
                        drawRect(trackColor)
                        val total = duration.value
                        val fraction =
                            when {
                                isLive -> 1f
                                total > 0L -> (position.value.toFloat() / total).coerceIn(0f, 1f)
                                else -> 0f
                            }
                        val width = size.width * fraction
                        if (width > 0f) {
                            val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                            drawRect(brush, Offset(left, 0f), Size(width, size.height))
                        }
                    }
                },
    )
}
