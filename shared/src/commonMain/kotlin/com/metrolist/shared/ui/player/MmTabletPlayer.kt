/*
 * MinMusic full player on tablets (tablet-layout-spec section 6).
 * Portrait (section 6.2): the phone block plus the "Lyrics" and "Up next" cards side by side under the chips.
 * Landscape (section 6.1): the player block in a left column, tabs (Lyrics / Up next / Related) on the right.
 */

package com.metrolist.shared.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album_art
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.mm_lyrics_unsynced_hint
import com.metrolist.shared.generated.resources.mm_player_lyrics_peek_empty
import com.metrolist.shared.generated.resources.mm_player_lyrics_peek_hint
import com.metrolist.shared.generated.resources.mm_player_queue_open
import com.metrolist.shared.generated.resources.mm_player_queue_song_count
import com.metrolist.shared.generated.resources.mm_player_queue_up_next
import com.metrolist.shared.generated.resources.more_lyrics_options
import com.metrolist.shared.generated.resources.tablet_player_related
import com.metrolist.shared.generated.resources.tablet_player_related_empty
import com.metrolist.shared.generated.resources.tablet_player_tap_to_open
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.CrossfadeArtwork
import com.metrolist.shared.ui.components.SourceQualityBadges
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.components.lyrics.rememberCaptionLyricsPositionState
import com.metrolist.shared.ui.components.minmusic.MmPlayerFullArtworkRadius
import com.metrolist.shared.ui.components.minmusic.MmPlayerTransportRow
import com.metrolist.shared.ui.components.rememberTrackMarquee
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.theme.MinMotion
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Player tab shared by the portrait cards and the landscape tabs (spec section 8: card <-> tab mapping). */
internal const val MmTabletTabLyrics = 0
internal const val MmTabletTabUpNext = 1
internal const val MmTabletTabRelated = 2

/** Portrait cards: gap and inset from spec section 6.2; below [MmTabletCardsMinHeight] the phone peek card returns. */
internal val MmTabletCardsGap = 16.dp
internal val MmTabletCardsInset = 48.dp
internal val MmTabletCardsMinHeight = 200.dp
internal val MmTabletCardsTopGap = 8.dp
internal val MmTabletCardsBottomGap = 16.dp

// Portrait column above the artwork (top padding + top bar) and the block between artwork and chips
// (title, artist, badges, progress, transport). An estimate: the artwork slot absorbs any difference.
private val MmTabletPortraitTopChrome = 16.dp + 56.dp
private val MmTabletPortraitBlockHeight = 264.dp

private const val INK3_ALPHA = 0.56f
private const val INK2_ALPHA = 0.76f
private const val PAST_LINE_ALPHA = 0.72f

private val MmTabletCardShape = RoundedCornerShape(20.dp)
private val MmTabletQueueRowHeight = 56.dp
private val MmTabletLyricLineHeight = 36.dp

/** Full-player artwork cap by window height class (spec section 3.2). */
internal fun mmTabletArtworkCap(windowHeight: Dp): Dp = if (windowHeight >= 900.dp) 440.dp else 384.dp

/** Height left for the portrait cards under the chips, or 0 when it is below [MmTabletCardsMinHeight]. */
internal fun mmTabletPortraitCardsHeight(
    windowHeight: Dp,
    statusBarHeight: Dp,
    navigationBarHeight: Dp,
    artworkSize: Dp,
): Dp {
    val remaining =
        windowHeight - statusBarHeight - MmTabletPortraitTopChrome - artworkSize - MmTabletPortraitBlockHeight -
            MmPlayerChipRowHeight - MmTabletCardsTopGap - MmTabletCardsBottomGap - navigationBarHeight - 8.dp
    return if (remaining >= MmTabletCardsMinHeight) remaining else 0.dp
}

/** "LỜI BÀI HÁT" and "TIẾP THEO" side by side; both open the same layers as the phone. */
@Composable
internal fun MmTabletPlayerCards(
    viewModel: PlayerViewModel,
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    isCasting: Boolean,
    isBuffering: Boolean,
    isLyricsLoading: Boolean,
    upNext: List<PlayerViewModel.QueueItemUiModel>,
    queueSize: Int,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MmTabletCardsGap)) {
        MmTabletLyricsCard(
            viewModel = viewModel,
            lyrics = lyrics,
            songId = songId,
            timingOffsetMs = timingOffsetMs,
            isPlaying = isPlaying,
            isCasting = isCasting,
            isBuffering = isBuffering,
            isLoading = isLyricsLoading,
            onOpen = onOpenLyrics,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        MmTabletUpNextCard(
            upNext = upNext,
            queueSize = queueSize,
            onOpen = onOpenQueue,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun MmTabletCard(
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    onSwipeUp: (() -> Unit)? = null,
    contentPadding: Modifier = Modifier.padding(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val latestOnSwipeUp by rememberUpdatedState(onSwipeUp)
    Column(
        modifier =
            modifier
                .clip(MmTabletCardShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .then(
                    if (onSwipeUp != null) {
                        // Like the phone's lyrics peek: a swipe up opens the lyrics instead of dragging the queue.
                        Modifier.pointerInput(Unit) {
                            val threshold = 32.dp.toPx()
                            var total = 0f
                            detectVerticalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = { if (total < -threshold) latestOnSwipeUp?.invoke() },
                                onDragCancel = { total = 0f },
                            ) { change, dragAmount ->
                                total += dragAmount
                                change.consume()
                            }
                        }
                    } else {
                        Modifier
                    },
                ).clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
                .then(contentPadding),
        content = content,
    )
}

@Composable
private fun MmTabletCardHeader(
    title: String,
    hint: String,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = ink.copy(alpha = INK3_ALPHA),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = INK3_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MmTabletLyricsCard(
    viewModel: PlayerViewModel,
    lyrics: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    isCasting: Boolean,
    isBuffering: Boolean,
    isLoading: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.lyrics)
    val lines = remember(lyrics) { lyrics.filter { it.text.isNotBlank() } }
    val timed = lines.isNotEmpty() && LyricsUtils.hasPlaybackTiming(lines)
    MmTabletCard(onClick = onOpen, onClickLabel = title, onSwipeUp = onOpen, modifier = modifier) {
        MmTabletCardHeader(
            title = title,
            hint =
                stringResource(
                    if (lines.isNotEmpty() && !timed) Res.string.mm_lyrics_unsynced_hint else Res.string.tablet_player_tap_to_open,
                ),
        )
        Spacer(Modifier.height(12.dp))
        if (timed) {
            MmTabletTimedLyricLines(
                viewModel = viewModel,
                lines = lines,
                songId = songId,
                timingOffsetMs = timingOffsetMs,
                isPlaying = isPlaying,
                isCasting = isCasting,
                isBuffering = isBuffering,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            val placeholder = stringResource(if (isLoading) Res.string.loading else Res.string.mm_player_lyrics_peek_empty)
            val texts = remember(lines, placeholder) { lines.map { it.text }.ifEmpty { listOf(placeholder) } }
            MmTabletLyricLines(texts = texts, currentIndex = 0, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Observes only the current line index, not the playback clock, like the phone's peek card. */
@Composable
private fun MmTabletTimedLyricLines(
    viewModel: PlayerViewModel,
    lines: List<LyricsEntry>,
    songId: String?,
    timingOffsetMs: Int,
    isPlaying: Boolean,
    isCasting: Boolean,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
) {
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
    val texts = remember(lines) { lines.map { it.text } }
    MmTabletLyricLines(texts = texts, currentIndex = index, modifier = modifier)
}

/** 3 to 5 lines: one past line (72 %), the current line in ink, the next lines in ink3. */
@Composable
private fun MmTabletLyricLines(
    texts: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val count = (maxHeight / MmTabletLyricLineHeight).toInt().coerceIn(1, 5)
        val start =
            if (currentIndex < 0) {
                0
            } else if (count >= 3) {
                (currentIndex - 1).coerceAtLeast(0)
            } else {
                currentIndex
            }
        Column {
            for (i in start until minOf(texts.size, start + count)) {
                val color =
                    when {
                        i == currentIndex -> ink
                        i < currentIndex -> ink.copy(alpha = INK3_ALPHA * PAST_LINE_ALPHA)
                        else -> ink.copy(alpha = INK3_ALPHA)
                    }
                Text(
                    text = texts[i],
                    style = MaterialTheme.typography.titleLarge,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(MmTabletLyricLineHeight),
                )
            }
        }
    }
}

@Composable
private fun MmTabletUpNextCard(
    upNext: List<PlayerViewModel.QueueItemUiModel>,
    queueSize: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    // Not consuming drags: a swipe up drags the queue sheet that hosts this card, as on the phone handle.
    MmTabletCard(
        onClick = onOpen,
        onClickLabel = stringResource(Res.string.mm_player_queue_open),
        modifier = modifier,
        contentPadding = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp)
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ink.copy(alpha = INK3_ALPHA * 0.6f)),
        )
        MmTabletCardHeader(
            title = stringResource(Res.string.mm_player_queue_up_next),
            hint = stringResource(Res.string.mm_player_lyrics_peek_hint),
        )
        Spacer(Modifier.height(8.dp))
        if (upNext.isEmpty()) {
            Text(
                text = pluralStringResource(Res.plurals.mm_player_queue_song_count, queueSize, queueSize),
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = INK3_ALPHA),
                maxLines = 1,
            )
        } else {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val rows = (maxHeight / MmTabletQueueRowHeight).toInt().coerceIn(1, 3)
                Column {
                    upNext.take(rows).forEach { item ->
                        MmTabletQueueRow(item = item)
                    }
                }
            }
        }
    }
}

/** A compact queue row (art 40, title, artists), shared by the up-next card and the related tab. */
@Composable
private fun MmTabletQueueRow(
    item: PlayerViewModel.QueueItemUiModel,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier.fillMaxWidth().height(MmTabletQueueRowHeight),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    url = item.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = MetrolistIcons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = ink.copy(alpha = INK3_ALPHA),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artists,
                style = MaterialTheme.typography.labelSmall,
                color = ink.copy(alpha = INK3_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Segmented tabs (design system "seg"): bg container, the selected segment on s2 with ink 600. */
@Composable
private fun MmTabletSegmentedTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(scheme.background)
                .padding(4.dp)
                .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f),
                animationSpec = MinMotion.fast(),
                label = "mmTabletTab",
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(container)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = if (isSelected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) scheme.onSurface else scheme.onSurface.copy(alpha = INK2_ALPHA),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Suggested tracks the queue already carries (the queue sheet's "Suggested" section); tap plays one. */
@Composable
private fun MmTabletRelatedList(
    uiState: PlayerViewModel.PlayerUiState,
    listState: LazyListState,
    enabled: Boolean,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val related =
        remember(uiState.queueItems, uiState.queueEntryKeys) {
            uiState.queueItems.mapIndexedNotNull { index, item ->
                if (item.isSuggested) uiState.queueEntryKeys.getOrNull(index)?.let { key -> key to item } else null
            }
        }
    if (related.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.tablet_player_related_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = INK2_ALPHA),
            )
        }
        return
    }
    LazyColumn(state = listState, modifier = modifier) {
        items(items = related, key = { it.first }, contentType = { "mmTabletRelated" }) { (entryKey, item) ->
            MmTabletQueueRow(
                item = item,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled) { onPlay(entryKey) }
                        .padding(horizontal = 8.dp),
            )
        }
    }
}

/**
 * Landscape tablet full player (spec section 6.1): top row, then the shared player block centred in a left
 * column of clamp(440, 42 % of width, 560) and the tabbed right pane.
 */
@Composable
internal fun MmTabletLandscapePlayerContent(
    viewModel: PlayerViewModel,
    uiState: PlayerViewModel.PlayerUiState,
    currentSong: PlayerViewModel.SongUiModel?,
    playbackResource: PlaybackResource?,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    queueSession: QueueSession,
    relatedListState: LazyListState,
    artworkOverlay: @Composable (Modifier) -> Unit,
    crossfadeNextArtworkUrl: String?,
    artworkCrossfadeProgress: () -> Float,
    showVideoSurface: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    isPlaying: Boolean,
    isPlaybackLoading: Boolean,
    duration: Long,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onShareSong: () -> Unit,
    textColor: Color,
    castState: CastState,
    onOpenCast: (() -> Unit)?,
    onOpenEqualizer: () -> Unit,
    sleepTimerState: SleepTimerState,
    onShowSleepTimer: () -> Unit,
    onShowPlayerMenu: () -> Unit,
    onShowLyricsMenu: () -> Unit,
    onClose: () -> Unit,
    showVideoButton: Boolean,
    isVideoModeActive: Boolean,
    isVideoModeResolving: Boolean,
    onToggleVideoMode: () -> Unit,
    isListenTogetherGuest: Boolean,
    artworkSwipeEnabled: Boolean,
    onArtworkSeek: (Long) -> Unit,
    seekFeedback: SeekFeedback?,
    seekFeedbackVisible: Boolean,
    playPauseFocusRequester: FocusRequester?,
    useRoundedRectPlayButton: Boolean,
    showLyricsOffsetEditor: Boolean,
    onLyricsOffsetChange: (Int) -> Unit,
    onDoneLyricsOffset: () -> Unit,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
    onNavigateToListenTogether: () -> Unit,
) {
    val haptics = rememberMetrolistHaptics()
    val platform = LocalPlayerPlatform.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val leftColumnWidth = (maxWidth * 0.42f).coerceIn(440.dp, 560.dp)
        val blockWidth = 400.dp
        // min(block width, H - top row - margins - block below the art), rounded down to 8, capped by height class.
        val fitted = maxHeight - 56.dp - 24.dp - 310.dp
        val artworkSize =
            (((minOf(blockWidth, fitted, mmTabletArtworkCap(maxHeight + 24.dp)).value / 8f).toInt() * 8).dp)
                .coerceAtLeast(160.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            MmPlayerTopBarSection(
                queueTitle = uiState.queueTitle,
                onCollapse = {
                    haptics.perform(HapticEvent.PRESS)
                    onClose()
                },
                onMore = {
                    haptics.perform(HapticEvent.PRESS)
                    onShowPlayerMenu()
                },
                showVideoButton = showVideoButton,
                isVideoModeActive = isVideoModeActive,
                isVideoModeResolving = isVideoModeResolving,
                onToggleVideoMode = {
                    haptics.perform(HapticEvent.PRESS)
                    onToggleVideoMode()
                },
                contentColor = textColor,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    modifier = Modifier.width(leftColumnWidth).fillMaxHeight().padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.width(blockWidth),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(artworkSize)
                                    .artworkSwipe(
                                        enabled = artworkSwipeEnabled,
                                        sensitivity = Settings.shared.swipeSensitivity,
                                        onPrevious = viewModel::previous,
                                        onNext = viewModel::next,
                                    ).artworkSeekGestures(
                                        seekEnabled = !isListenTogetherGuest,
                                        stepMs = Settings.shared.seekDurationSeconds * 1000L,
                                        onTap = null,
                                        tapLabel = null,
                                        progressive = Settings.shared.seekExtraSeconds,
                                        onSeek = onArtworkSeek,
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (showVideoSurface) {
                                platform.MusicVideoSurface(
                                    modifier = Modifier.size(artworkSize),
                                    cornerRadius = MmPlayerFullArtworkRadius,
                                    fillContainer = true,
                                    showControls = true,
                                    useSurfaceView = false,
                                    onFullscreenClick = onToggleFullscreen,
                                    onPiPClick = { platform.enterPictureInPicture(isPlaying = isPlaying, hasVideo = true) },
                                )
                            } else if (currentSong?.displayThumbnail != null) {
                                Box(Modifier.fillMaxSize().clip(MmPlayerArtworkShape)) {
                                    CrossfadeArtwork(
                                        animate = LocalPlayerContentActive.current,
                                        currentUrl = currentSong.displayThumbnail,
                                        nextUrl = crossfadeNextArtworkUrl,
                                        progress = artworkCrossfadeProgress,
                                        contentDescription = stringResource(Res.string.album_art),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                    artworkOverlay(Modifier.fillMaxSize())
                                }
                            }
                            SeekFeedbackOverlay(feedback = seekFeedback, visible = seekFeedbackVisible)
                        }
                        Spacer(Modifier.height(20.dp))
                        MmTabletTitleRow(
                            currentSong = currentSong,
                            playbackResource = playbackResource,
                            isLiked = isLiked,
                            onToggleLike = {
                                haptics.toggle(!isLiked)
                                onToggleLike()
                            },
                            onShareSong = {
                                haptics.perform(HapticEvent.PRESS)
                                onShareSong()
                            },
                            textColor = textColor,
                            onNavigateToArtist = onNavigateToArtist,
                            onNavigateToAlbum = onNavigateToAlbum,
                        )
                        Spacer(Modifier.height(12.dp))
                        PlayerProgressSlider(
                            viewModel = viewModel,
                            duration = duration,
                            isPlaying = isPlaying,
                            textBackgroundColor = textColor,
                            canSeek = !isListenTogetherGuest,
                            isCasting = castState.isConnected,
                            songId = currentSong?.id,
                            isLive = uiState.isLive || playbackResource?.isLive == true,
                            mmStyle = true,
                        )
                        if (!isListenTogetherGuest) {
                            Spacer(Modifier.height(8.dp))
                            MmPlayerTransportRow(
                                isPlaying = isPlaying,
                                isLoading = isPlaybackLoading,
                                isShuffleOn = uiState.isShuffleOn,
                                repeatMode = uiState.repeatMode,
                                onToggleShuffle = {
                                    haptics.toggle(!uiState.isShuffleOn)
                                    viewModel.toggleShuffle()
                                },
                                onToggleRepeat = {
                                    haptics.perform(HapticEvent.PRESS)
                                    viewModel.toggleRepeat()
                                },
                                onSkipPrevious = {
                                    haptics.perform(HapticEvent.PRESS)
                                    viewModel.previous()
                                },
                                onTogglePlay = {
                                    haptics.toggle(!isPlaying)
                                    viewModel.togglePlayPause()
                                },
                                onSkipNext = {
                                    haptics.perform(HapticEvent.PRESS)
                                    viewModel.next()
                                },
                                useRoundedRectPlayButton = useRoundedRectPlayButton,
                                contentColor = textColor,
                                playPauseFocusRequester = playPauseFocusRequester,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        MmPlayerChipRow(
                            isLyricsVisible = false,
                            onLyricsVisibleChange = {},
                            castState = castState,
                            onOpenCast = onOpenCast,
                            onOpenEqualizer = onOpenEqualizer,
                            sleepTimerState = sleepTimerState,
                            onShowSleepTimer = onShowSleepTimer,
                            isListenTogetherGuest = isListenTogetherGuest,
                        )
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MmTabletSegmentedTabs(
                            labels =
                                listOf(
                                    stringResource(Res.string.lyrics),
                                    stringResource(Res.string.mm_player_queue_up_next),
                                    stringResource(Res.string.tablet_player_related),
                                ),
                            selected = selectedTab,
                            onSelect = { tab ->
                                haptics.perform(HapticEvent.PRESS)
                                onSelectTab(tab)
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        if (selectedTab == MmTabletTabLyrics) {
                            IconButton(onClick = onShowLyricsMenu) {
                                Icon(
                                    imageVector = MetrolistIcons.Default.MoreVert,
                                    contentDescription = stringResource(Res.string.more_lyrics_options),
                                    tint = textColor,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Crossfade(
                        targetState = selectedTab,
                        animationSpec = MinMotion.standardEnter(),
                        label = "mmTabletPane",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { tab ->
                        when (tab) {
                            MmTabletTabLyrics -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    LyricsViewWithPosition(
                                        viewModel = viewModel,
                                        lyrics = uiState.lyrics,
                                        songwriters = uiState.lyricsSongwriters,
                                        agents = uiState.lyricsAgents,
                                        provider = uiState.lyricsProvider,
                                        timingOffsetMs = uiState.lyricsOffsetMs,
                                        onSeek = viewModel::seekToPosition,
                                        isLoading = uiState.isLyricsLoading,
                                        textColor = textColor,
                                        isCasting = castState.isConnected,
                                        isBuffering = castState.isBuffering,
                                        modifier = Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth(),
                                    )
                                    AnimatedVisibility(visible = showLyricsOffsetEditor) {
                                        LyricsOffsetEditor(
                                            offsetMs = uiState.lyricsOffsetMs,
                                            onOffsetChange = onLyricsOffsetChange,
                                            onDone = onDoneLyricsOffset,
                                            contentColor = textColor,
                                        )
                                    }
                                }
                            }

                            MmTabletTabUpNext -> {
                                QueueScreen(
                                    session = queueSession,
                                    viewModel = viewModel,
                                    onClose = { onSelectTab(MmTabletTabLyrics) },
                                    onShowSleepTimer = onShowSleepTimer,
                                    onNavigateToAlbum = onNavigateToAlbum,
                                    onNavigateToArtist = onNavigateToArtist,
                                    onNavigateToListenTogether = onNavigateToListenTogether,
                                    modifier = Modifier.fillMaxSize(),
                                    backHandlerEnabled = false,
                                    handleDismissalBack = false,
                                )
                            }

                            else -> {
                                MmTabletRelatedList(
                                    uiState = uiState,
                                    listState = relatedListState,
                                    enabled = !isListenTogetherGuest,
                                    onPlay = viewModel::playQueueEntry,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Title 28 + artist + badges, share and heart: the phone's title row at tablet size. */
@Composable
private fun MmTabletTitleRow(
    currentSong: PlayerViewModel.SongUiModel?,
    playbackResource: PlaybackResource?,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onShareSong: () -> Unit,
    textColor: Color,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String) -> Unit,
) {
    val titleSlidePx = with(LocalDensity.current) { MmPlayerTitleSlide.roundToPx() }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(
            targetState = currentSong,
            contentKey = { it?.id },
            transitionSpec = {
                (
                    fadeIn(MinMotion.standardEnter()) +
                        slideInVertically(MinMotion.standardEnter<IntOffset>()) { titleSlidePx }
                ).togetherWith(fadeOut(MinMotion.standardExit()))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.CenterStart,
            label = "mmTabletTitle",
            modifier = Modifier.weight(1f),
        ) { displayedSong ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = displayedSong?.title ?: stringResource(Res.string.unknown),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .clickable(enabled = displayedSong?.albumId != null) {
                                displayedSong?.albumId?.let(onNavigateToAlbum)
                            }.then(
                                displayedSong?.let { song ->
                                    rememberTrackMarquee(
                                        trackId = song.id,
                                        enabled = LocalPlayerContentActive.current,
                                        initialDelayMillis = 3000L,
                                    )
                                } ?: Modifier,
                            ),
                )
                PlayerArtistLine(
                    artists = displayedSong?.artists,
                    onNavigateToArtist = onNavigateToArtist,
                    iterations = if (LocalPlayerContentActive.current) 1 else 0,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = INK2_ALPHA),
                )
                SourceQualityBadges(songId = displayedSong?.id, playbackResource = playbackResource)
            }
        }
        MmPlayerTitleActions(
            isLiked = isLiked,
            onLikeClick = onToggleLike,
            onShareClick = onShareSong,
            contentColor = textColor,
        )
    }
}
