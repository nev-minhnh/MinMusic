@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.layouts.DesktopLayoutRules
import com.metrolist.desktop.ui.layouts.LocalDesktopLayoutMetrics
import com.metrolist.desktop.utils.SkiaCacheCleaner
import com.metrolist.desktop.utils.saveImage
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.close_fullscreen_player
import com.metrolist.shared.generated.resources.copy
import com.metrolist.shared.generated.resources.local
import com.metrolist.shared.generated.resources.lyrics
import com.metrolist.shared.generated.resources.lyrics_auto_scroll_btn
import com.metrolist.shared.generated.resources.lyrics_provided_by
import com.metrolist.shared.generated.resources.lyrics_share_filename
import com.metrolist.shared.generated.resources.lyrics_share_save_image
import com.metrolist.shared.generated.resources.mm_desk_lyrics_fullscreen
import com.metrolist.shared.generated.resources.mm_desk_lyrics_fullscreen_exit
import com.metrolist.shared.generated.resources.no_lyrics_for_this_track
import com.metrolist.shared.generated.resources.up_next
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.lyricsCuratorUrl
import com.metrolist.shared.model.lyricsProviderUrl
import com.metrolist.shared.repository.isLocalLyricsProvider
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.components.lyrics.LyricsSelectionState
import com.metrolist.shared.ui.components.lyrics.LyricsShareDialog
import com.metrolist.shared.ui.components.lyrics.LyricsView
import com.metrolist.shared.ui.components.minmusic.MmChip
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

/**
 * Lyrics fullscreen in the two-pane full player (desktop-layout-spec 3.7): the left pane hides and the lyrics
 * centre up to 960 wide in the one extra-large size the owner allowed for this mode. F11 or the pane button
 * toggles it; Esc leaves it first; opening the queue leaves it too.
 */
internal object FullPlayerLyricsMode {
    var fullscreen by mutableStateOf(false)

    fun toggle() {
        if (!fullscreen) UiState.showPersistentPlayerDrawer = false
        fullscreen = !fullscreen
    }
}

// Relative to the default 36 sp lyrics text size: 28 sp in the pane, 40 sp in lyrics fullscreen.
private const val PANE_LYRICS_SCALE = 28f / 36f
private const val FULLSCREEN_LYRICS_SCALE = 40f / 36f

/**
 * The full player's content. [twoPanes] (window >= 960 x 600): player column | tabbed lyrics / queue pane.
 * Otherwise one pane: artwork + controls, with lyrics as a full-height layer and the queue as an overlay drawer.
 */
@Composable
fun FullscreenContentLayout(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    availableHeight: Dp,
    windowWidth: Dp,
    windowHeight: Dp,
    twoPanes: Boolean,
    toggleWindowFullscreen: () -> Unit,
    closeExpandedPlayer: () -> Unit,
) {
    if (twoPanes) {
        FullPlayerTwoPanes(
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            availableHeight = availableHeight,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            toggleWindowFullscreen = toggleWindowFullscreen,
        )
    } else {
        FullPlayerOnePane(
            currentTrack = currentTrack,
            isPlaying = isPlaying,
            availableHeight = availableHeight,
            toggleWindowFullscreen = toggleWindowFullscreen,
            closeExpandedPlayer = closeExpandedPlayer,
        )
    }
}

@Composable
private fun FullPlayerTwoPanes(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    availableHeight: Dp,
    windowWidth: Dp,
    windowHeight: Dp,
    toggleWindowFullscreen: () -> Unit,
) {
    val metrics = LocalDesktopLayoutMetrics.current
    val showQueue = UiState.showPersistentPlayerDrawer
    val lyricsFullscreen = FullPlayerLyricsMode.fullscreen && !showQueue
    NavigationBackHandler(enabled = lyricsFullscreen) { FullPlayerLyricsMode.fullscreen = false }
    LaunchedEffect(showQueue) {
        if (showQueue) FullPlayerLyricsMode.fullscreen = false
    }
    // The pane shows lyrics without the lyrics drawer flag and the lyrics card is hidden, so fetch them here.
    LaunchedEffect(currentTrack?.id, Settings.shared.lyricsProviderOrder, Settings.shared.lyricsEnabledProviders) {
        currentTrack?.let { LyricsState.fetchLyrics(it) }
    }
    val leftPaneWidth = DesktopLayoutRules.fullPlayerLeftPaneWidth(windowWidth)
    val artworkSize = DesktopLayoutRules.fullPlayerArtworkSize(leftPaneWidth, windowHeight, metrics.fullPlayerArtCap)
    // Spacing tightens a little on short windows so art + controls need less scrolling.
    val scale = (availableHeight / 860.dp).coerceIn(0.7f, 1f)
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = DesktopLayoutRules.fullPlayerSidePadding(windowWidth)),
    ) {
        if (!lyricsFullscreen) {
            PlayerContentColumn(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                availableHeight = availableHeight,
                scale = scale,
                modifier = Modifier.width(leftPaneWidth).fillMaxHeight(),
                toggleWindowFullscreen = toggleWindowFullscreen,
                artworkSize = artworkSize,
                showPeekCards = false,
                verticalPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            )
            Spacer(Modifier.width(DesktopLayoutRules.fullPlayerPaneGap(windowWidth)))
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            FullPlayerSidePane(
                showQueue = showQueue,
                lyricsFullscreen = lyricsFullscreen,
                modifier =
                    Modifier
                        .then(if (lyricsFullscreen) Modifier.widthIn(max = 960.dp) else Modifier)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 32.dp),
            )
        }
    }
}

/** Right pane: "Lyrics / Up next" tabs, the lyrics-fullscreen toggle, then the lyrics (max line width 640) or the queue. */
@Composable
private fun FullPlayerSidePane(
    showQueue: Boolean,
    lyricsFullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val isSolid = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.SOLID
    val iconTint = if (isSolid) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f)
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MmChip(
                text = stringResource(Res.string.lyrics),
                selected = !showQueue,
                onClick = { UiState.showPersistentPlayerDrawer = false },
            )
            MmChip(
                text = stringResource(Res.string.up_next),
                selected = showQueue,
                onClick = { UiState.showPersistentPlayerDrawer = true },
            )
            Spacer(Modifier.weight(1f))
            if (!showQueue) {
                IconButton(
                    onClick = FullPlayerLyricsMode::toggle,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = if (lyricsFullscreen) MetrolistIcons.Default.FullscreenExit else MetrolistIcons.Default.Fullscreen,
                        contentDescription =
                            stringResource(
                                if (lyricsFullscreen) Res.string.mm_desk_lyrics_fullscreen_exit else Res.string.mm_desk_lyrics_fullscreen,
                            ),
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showQueue) {
                DesktopQueueContent(
                    onDismiss = { UiState.showPersistentPlayerDrawer = false },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.widthIn(max = if (lyricsFullscreen) 960.dp else 640.dp).fillMaxSize()) {
                    LyricsSection(
                        textSizeMultiplier = if (lyricsFullscreen) FULLSCREEN_LYRICS_SCALE else PANE_LYRICS_SCALE,
                        isVisible = !UiState.videoFullscreenObscuresPlayer,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullPlayerOnePane(
    currentTrack: SongItem?,
    isPlaying: Boolean,
    availableHeight: Dp,
    toggleWindowFullscreen: () -> Unit,
    closeExpandedPlayer: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val scale = fullscreenPlayerScale(maxWidth, availableHeight)
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScrollWithDrag(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            IconButton(
                onClick = closeExpandedPlayer,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(end = 24.dp)
                        .size(48.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Default.Close,
                    contentDescription = stringResource(Res.string.close_fullscreen_player),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            PlayerCoreContent(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                availableHeight = (availableHeight - 144.dp).coerceAtLeast(0.dp),
                scale = scale,
                toggleWindowFullscreen = toggleWindowFullscreen,
            )

            Spacer(Modifier.height(32.dp))
        }

        // One pane: the lyrics open as a full-height layer over the player; Esc or the close button hides it.
        AnimatedVisibility(
            visible = UiState.showLyricsDrawer,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
        ) {
            val isSolid = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.SOLID
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(if (isSolid) MaterialTheme.colorScheme.surfaceContainer else Color.Black.copy(alpha = 0.92f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
            ) {
                IconButton(
                    onClick = { UiState.showLyricsDrawer = false },
                    modifier =
                        Modifier
                            .align(Alignment.End)
                            .padding(top = 16.dp, end = 24.dp)
                            .size(48.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Default.Close,
                        contentDescription = stringResource(Res.string.close),
                        tint = if (isSolid) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                        LyricsSection(textSizeMultiplier = PANE_LYRICS_SCALE)
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsSection(
    textSizeMultiplier: Float = 1.2f,
    isVisible: Boolean = UiState.showLyricsDrawer && !UiState.videoFullscreenObscuresPlayer,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isSolid = Settings.shared.playerBackgroundStyle == PlayerBackgroundStyle.SOLID
    val lyricsTextColor by animateColorAsState(
        targetValue = if (isSolid) colorScheme.onSurface else Color.White,
        label = "LyricsSectionTextColor",
    )
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            DesktopLyricsContent(
                textColor = lyricsTextColor,
                textSizeMultiplier = textSizeMultiplier,
                isVisible = isVisible,
            )
        }
        AnimatedVisibility(
            visible = LyricsState.showLyricsOffsetEditor,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LyricsOffsetEditor(
                offsetMs = LyricsState.currentLyricsOffsetMs,
                onOffsetChange = { offset ->
                    LyricsState.updateLyricsOffset(offset)
                },
                onDone = { LyricsState.showLyricsOffsetEditor = false },
                contentColor = lyricsTextColor,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun DesktopLyricsContent(
    textColor: Color,
    textSizeMultiplier: Float = 1.0f,
    isVisible: Boolean = true,
) {
    val currentSongId = PlayerState.currentTrack?.id
    val isMatchingTrack = currentSongId != null && currentSongId == LyricsState.currentLyricsSongId
    val lyricsRaw = if (isMatchingTrack) LyricsState.currentLyrics ?: "" else ""
    val castState by PlayerState.castManager.state.collectAsState()
    val lyricsDoc = remember(lyricsRaw) { LyricsUtils.parseMlxDocument(lyricsRaw) }
    val enrichedLyrics = if (isMatchingTrack) LyricsState.currentLyricsEntries else emptyList()
    val lyrics =
        remember(lyricsDoc, enrichedLyrics, isMatchingTrack) {
            if (!isMatchingTrack) {
                emptyList()
            } else {
                enrichedLyrics.ifEmpty {
                    lyricsDoc?.lines ?: LyricsUtils.parseLyrics(lyricsRaw)
                }
            }
        }
    val selection = remember(currentSongId, lyrics) { LyricsSelectionState() }
    var selectedText by remember(currentSongId) { mutableStateOf<String?>(null) }
    val cancelSelection = remember(selection) { { selection.clear() } }
    DisposableEffect(selection.isActive) {
        if (selection.isActive) {
            LyricsState.cancelSelection?.takeIf { it !== cancelSelection }?.invoke()
            LyricsState.cancelSelection = cancelSelection
        }
        onDispose { if (LyricsState.cancelSelection === cancelSelection) LyricsState.cancelSelection = null }
    }
    val saveTitle = stringResource(Res.string.lyrics_share_save_image)
    val filename = stringResource(Res.string.lyrics_share_filename)
    val track = PlayerState.currentTrack
    selectedText?.let { text ->
        if (track != null) {
            LyricsShareDialog(
                text = text,
                songId = track.id,
                title = track.title,
                artist = track.artists.joinToString { it.name },
                artworkUrl = PlayerState.currentArtworkUrl,
                exportLabel = saveTitle,
                onExport = { saveImage(it, saveTitle, filename) },
                onShareText = AppState::copyToClipboard,
                onDismiss = { selectedText = null },
            )
        }
    }
    val lyricsProvidedByFmt = stringResource(Res.string.lyrics_provided_by)

    Box(modifier = Modifier.fillMaxSize()) {
        LyricsView(
            noLyricsText = stringResource(Res.string.no_lyrics_for_this_track),
            translationProgress = if (isMatchingTrack) LyricsState.translationProgress else LyricsTranslationProgress(),
            autoScrollText = stringResource(Res.string.lyrics_auto_scroll_btn),
            lyricsProvidedBy = { provider -> String.format(lyricsProvidedByFmt, provider) },
            providerUrl =
                if (isMatchingTrack) {
                    lyricsProviderUrl(LyricsState.currentLyricsProvider, lyricsDoc?.meta?.sourceId, lyricsDoc?.meta?.lyricSource)
                } else {
                    null
                },
            lyricsMeta = if (isMatchingTrack) lyricsDoc?.meta else null,
            lyricsCuratorUrl =
                if (isMatchingTrack) {
                    lyricsCuratorUrl(LyricsState.currentLyricsProvider, lyricsDoc?.meta?.lyricsCuratorId, lyricsDoc?.meta?.lyricSource)
                } else {
                    null
                },
            lyrics = lyrics,
            songwriters =
                if (isMatchingTrack) {
                    lyricsDoc?.song?.songwriters?.takeIf { it.isNotEmpty() } ?: LyricsState.currentLyricsSongwriters
                } else {
                    emptyList()
                },
            agents = if (isMatchingTrack) lyricsDoc?.agents ?: LyricsState.currentLyricsAgents else emptyMap(),
            provider =
                if (isMatchingTrack) {
                    if (isLocalLyricsProvider(LyricsState.currentLyricsProvider)) {
                        stringResource(Res.string.local)
                    } else {
                        LyricsState.currentLyricsProvider
                    }
                } else {
                    null
                },
            currentPositionFlow = PlayerState.playbackPosition,
            positionReader = PlayerState::currentMediaPositionMs,
            playbackSpeed =
                PlayerState.player.playbackSpeed
                    .collectAsState()
                    .value,
            timingOffsetMs = if (isMatchingTrack) LyricsState.currentLyricsOffsetMs else 0,
            isPlaying = PlayerState.isPlaying,
            isVisible = isVisible,
            lyricsRetimed = isMatchingTrack && LyricsState.mvLyricsRetimed,
            onSeek = { pos, source -> if (Settings.shared.lyricsClickToSeek) PlayerState.seekToPosition(pos, source) },
            isLoading = LyricsState.isLyricsLoading || (currentSongId != null && currentSongId != LyricsState.currentLyricsSongId),
            onLyricsClosed = { SkiaCacheCleaner.schedulePurge() },
            textSizeMultiplier = textSizeMultiplier,
            songId = PlayerState.currentTrack?.id,
            isCasting = castState.isConnected,
            isBuffering = castState.isBuffering,
            selectionState = selection,
            onShareSelection = { selectedText = it },
            textColor = textColor,
        )
    }
}
